# MCP 连接生命周期管理增强

## 问题

1. **孤儿进程**：Zephyr 非正常退出（kill -9、OOM）时，STDIO 子进程（npx 等）未被 `destroyForcibly()`，残留为孤儿进程。重启 Zephyr 后懒创建新连接，同一 MCP 服务器同时跑多个进程。
2. **日志缺失**：连接创建、复用、关闭、淘汰、清理等生命周期事件均无日志，排查问题困难。
3. **connect() 未真正启动持久进程**：`McpServiceImpl.connect()` 调用 `McpClient.discoverTools()` 启动临时进程发现工具后立即 kill，状态却标记为 `connected`，实际进程未运行。页面"已连接"状态与实际进程状态不一致。
4. **启动时 DB 状态未清理**：Zephyr 重启时 `cleanupOrphanProcesses()` 只杀进程不更新 DB，导致 `mcp_server.status` 残留 `connected`/`error`。
5. **连接失败时 DB 状态未同步**：`connect()` 中工具发现成功后设为 `connected`，但后续首次工具调用时 `McpConnectionManager.createConnection()` 可能失败，DB 状态与实际不一致。

## 方案

### 1. connect() 启动持久进程

**现状**：`connect()` → `McpClient.discoverTools()` → 临时进程(启→发现→杀) → DB="connected"
首次工具调用 → `McpConnectionManager.getConnection()` → 懒创建新进程

**改为**：`connect()` → `McpConnectionManager.getConnection()` → 创建持久连接(启动进程+MCP握手)
→ `conn.listTools()` → 从持久连接发现工具 → DB="connected"

- `McpClient.discoverTools()` 不再在 `connect()` 中使用（保留用于其他场景）
- 新增 `McpConnection.listTools()` 方法，复用已有的 STDIO/HTTP 通道发送 `tools/list` 请求

**收益**：
- 点击"连接"后进程立即启动并保持运行，页面状态与真实状态一致
- 首次工具调用无需等待进程启动+握手，消除延迟
- 只有一个进程，消除"临时进程+持久进程"双进程问题

### 2. PID 文件 + 启动时清理

**PID 文件目录**：`~/.zephyr/mcp-pids/`

**文件命名**：`{userName}-{serverId}.pid`，内容为进程 PID。

**生命周期**：

| 阶段 | 操作 |
|------|------|
| 创建连接 (STDIO) | 启动子进程后，写 PID 文件到 `mcp-pids/` |
| 关闭连接 | kill 进程后，删除 PID 文件 |
| 工具调用超时 | `destroyForcibly()` 后，删除 PID 文件 |
| Zephyr 启动 | 扫描 `mcp-pids/` 目录，逐个 kill PID，删除文件，然后初始化目录 |
| Zephyr 启动 | **新增**：重置所有 `mcp_server.status` 为 `disconnected` |

**启动清理逻辑**（`McpConnectionManager.cleanupOrphanProcesses()`）：

```java
@PostConstruct
void cleanupOrphanProcesses() {
    // 1. 清理孤儿进程（已有）
    // 2. 重置所有 DB 状态为 disconnected（新增）
    mcpDao.resetAllServerStatus("disconnected");
}
```

### 3. connect() 异常处理

`connect()` 中如果 `getConnection()` 或 `listTools()` 失败：
- catch 异常后将 DB 状态设为 `error`
- 同时关闭已创建的连接（如有），清理 PID 文件

**关键决策：移除 `@Transactional`。** 如果保留 `@Transactional`，catch 块中 `updateServerStatus("error")` 在 `throw e` 后会被回滚。移除后每条 SQL 自动提交，error 状态能持久化。

### 4. 全生命周期日志

| 类 | 事件 | 日志 |
|---|---|---|
| `McpConnectionManager` | 连接创建 | `log.info("MCP 连接已建立: user={}, serverId={}, transport={}, pid={}, 当前 {} 个连接")` |
| `McpConnectionManager` | 连接复用 | `log.debug("MCP 连接复用: user={}, serverId={}")` |
| `McpConnectionManager` | 连接关闭 | `log.info("MCP 连接已关闭: user={}, serverId={}")` |
| `McpConnection` | 超时 kill | `log.warn("STDIO MCP 工具执行超时({}s)，强制关闭进程: server={}, pid={}")` |
| `McpConnection` | close | `log.info("MCP 进程已终止: server={}, pid={}")` |
| `McpConnectionManager` | LRU 淘汰 | `log.info("LRU 淘汰连接: user={}, serverId={}")` |
| `McpConnectionManager` | 空闲回收 | `log.info("空闲连接已回收: user={}, serverId={}, 空闲 {}ms")` |
| `McpConnectionManager` | 启动清理 | `log.info("清理孤儿进程: pid={}, server={}")` |
| `McpConnectionManager` | 无孤儿进程 | `log.info("无孤儿进程需要清理")` |
| `McpServiceImpl` | 连接失败 | `log.warn("MCP 连接失败，状态已置为 error: server={}")` |

### 5. PID 文件路径配置

复用 `ZephyrConfigProperties.Memory.home` 的父目录 `~/.zephyr/`，硬编码子目录 `mcp-pids`，无需新增配置项。

## connect() 新流程

```
connect(serverId, userName):              ← 注意：无 @Transactional
  1. 查 DB 获取 server 配置
  2. 权限检查（共享服务器需 admin）
  3. 关闭已有连接 removeAllConnectionsForServer(id)
  4. 解密 headers
  5. McpConnection conn = connectionManager.getConnection(userName, id)  ← 启动持久进程
  6. List<McpToolEntity> tools = conn.listTools()                          ← 从持久连接发现工具
  7. 检查工具名冲突
  8. 存工具到 DB
  9. DB status = "connected"
  catch: DB status = "error", 关闭连接, 抛出异常
```

## 影响范围

| 文件 | 改动 |
|------|------|
| `McpConnectionManager.java` | `@PostConstruct` 增加 DB 状态重置；日志增强（已完成） |
| `McpConnection.java` | 新增 `listTools()`；PID 文件写/删（已完成）；日志增强（已完成） |
| `McpServiceImpl.java` | `connect()` 重写：用 `getConnection()`+`listTools()` 替代 `McpClient.discoverTools()` |
| `McpDao.java` | 新增 `resetAllServerStatus(String status)` SQL |
| McpDao Mapper XML | 新增 `resetAllServerStatus` 的 DML |

`McpClient.java` 保留不变（`discoverTools()` 仍可用于未来场景如手动刷新工具列表）。

纯后端改动，前端接口不变。
