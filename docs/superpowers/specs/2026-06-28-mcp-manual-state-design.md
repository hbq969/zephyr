# MCP 手工状态字段：重启自动重连

## 问题

程序重启后 MCP 服务器全部断开，需用户逐一手工重新连接。`reconnectOnStartup()` 被设计为启动时重连，但 `cleanupOrphanProcesses()` 先执行 `resetAllServerStatus("disconnected")` 清掉了所有 `status`，导致 `reconnectOnStartup()` 永远查不到需要重连的服务器。

## 根因

`status` 字段承担了两项职责，启动清理时全部丢失：

| 职责 | 当前存放位置 | 启动行为 |
|------|-------------|---------|
| 运行时连接状态（进程是否存活） | `status` | 重置为 `disconnected` |
| 用户意图（希望保持连接） | 无独立记录 | 丢失 |

## 方案

新增 `manual_state` 字段记录用户手工操作意图，与 `status` 职责分离。

### 字段定义

| 字段 | 类型 | 默认值 | 职责 | 谁更新 | 启动时重置？ |
|------|------|--------|------|--------|-------------|
| `status` | varchar(16) | `disconnected` | 运行时连接状态 | 连接/断开/错误事件、启动清理 | 是 |
| `manual_state` | varchar(16) | `none` | 用户手工操作意图 | **仅**页面手工连接/断开 | 否 |

`manual_state` 取值：`none`（从未手工操作）、`connected`（手工连接）、`disconnected`（手工断开）。

### 行为矩阵

| 场景 | `manual_state` | 启动行为 |
|------|---------------|---------|
| 新建服务器 | `none` | 不重连 |
| 用户在页面点"连接"成功 | → `connected` | 自动重连 |
| 用户在页面点"断开" | → `disconnected` | 不重连 |
| 启动自动重连成功 | 保持 `connected` | 下次继续 |
| 启动自动重连失败 | 保持 `connected` | 下次继续尝试 |

### 涉及文件

#### 1. DDL（三方言各 1 处）

`createMcpServersTable` 加列：
```sql
manual_state varchar(16) default 'none'
```

#### 2. 增量迁移 SQL

```sql
ALTER TABLE zephyr_mcp_servers ADD COLUMN manual_state varchar(16) default 'none';
```

#### 3. 实体类

`McpServerEntity.java` — 加 `private String manualState = "none";`

#### 4. Mapper XML common

- 所有 `select` 语句加 `manual_state as manualState`
- `insertServer` 加 `manual_state`
- 新增 `queryManualConnectedServers`：`where manual_state = 'connected'`

#### 5. Mapper XML 方言（三文件）

DDL 加列（见第 1 条）。

#### 6. DAO

`McpDao.java` — 新增 `queryManualConnectedServers()`，新增 `updateManualState()`。

#### 7. 启动清理

`McpConnectionManager.cleanupOrphanProcesses()`：
- `resetAllServerStatus("disconnected")` 保持不动（只清 `status`，不动 `manual_state`）

#### 8. 启动重连

`McpServiceImpl.reconnectOnStartup()`：
- 改为调用 `queryManualConnectedServers()`（查 `manual_state = 'connected'`）

#### 9. 手工连接

`McpServiceImpl.connect0()`：
- 连接成功后同时更新 `manual_state = 'connected'`

#### 10. 手工断开

`McpServiceImpl.disconnect()`：
- 断开时更新 `manual_state = 'disconnected'`
