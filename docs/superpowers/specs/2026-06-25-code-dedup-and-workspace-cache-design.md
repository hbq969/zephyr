# 代码去重与 workspace 缓存优化

## 背景

安全规则外部化（`2026-06-25-security-patterns-externalize-design`）实现过程中识别了遗留问题，本次集中处理。

## 问题

### 1. parseCommandList 代码重复

`SecurityEvaluator.parseCommandList` 和 `ChatServiceImpl.initShellWhitelist` 中逗号分隔字符串 → `Set<String>` 的解析逻辑完全一致，重复了 ~8 行。

### 2. workspace 路径重复 DB 查询

`executeShell`（第 877-885 行）和 `resolveWorkspaceBoundary`（第 407-426 行）各自做了完全相同的两次 DB 查询（conversation → workspace），两次入口的查询模式一模一样。workspace 在同一会话的消息处理周期内不会变化。

### 3. 会话级 workspace 缓存

workspace 路径无人缓存，两个入口各自重复查询。

## 设计

### Part 1: 消除 parseCommandList 重复

`SecurityEvaluator.parseCommandList` 由 `private static` 改为 `public static`。`ChatServiceImpl.initShellWhitelist` 中内联解析逻辑替换为直接调用 `SecurityEvaluator.parseCommandList(raw)`。

**Tradeoff**: 不提取新工具类。`SecurityEvaluator` 作为安全相关静态工具方法的宿主是合理的，且只有两个调用点。如未来出现第三个调用点，再提取到独立 `StringUtils`。

### Part 2: 会话级 workspace 缓存

在 `SessionHandle` 中添加 `workspacePath` 字段，`send()` 中构造 `SessionHandle` 前解析 workspace 路径并传入。所有原本各自查询 workspace 的入口统一从 handle 读取。

**假设**: `send()` 参数中的 `workspaceId` 与 conversation 表中存储的 `workspaceId` 一致。前端每次消息都传当前的 `workspaceId`。不一致时以请求参数为准。

**缓存生命周期**：`send()` 是每次消息的入口点，每次创建新 `SessionHandle`。workspace 切换时前端传新 `workspaceId`，下次消息自然刷新——无需主动失效机制。

```
每次消息 → send(workspaceId) → 解析 workspacePath → register(cid, userName, workspacePath)
  → dispatchTools → resolveWorkspaceBoundary(handle.getWorkspacePath())  // 纯路径解析，无 DB
  → executeShell → handle.getWorkspacePath()
下一次消息 → 新 handle, 新路径
```

## 改动清单

### SecurityEvaluator.java

| 行 | 改动 |
|----|------|
| 164 | `parseCommandList` `private static` → `public static` |

### ChatServiceImpl.java

| 行 | 改动 |
|----|------|
| 832-843 | `initShellWhitelist` 内联解析 → 调用 `SecurityEvaluator.parseCommandList` |
| ~93 | `register(cid, userName)` → `register(cid, userName, workspacePath)`，workspacePath 在上方解析 |
| 407-426 | `resolveWorkspaceBoundary` 签名改为接收 `String workspacePath`，方法体简化为纯路径解析（`Path.of → toRealPath`），删 DB 查询 |
| 435 | `resolveWorkspaceBoundary(conversationId)` → `resolveWorkspaceBoundary(handle.getWorkspacePath())` |
| 877-885 | **删除** conversation + workspace DB 查询 |
| ~890 | `handle.getWorkspacePath()` 替代原 `workspacePath` 变量 |

### ConversationSessionManager.java

| 行 | 改动 |
|----|------|
| 32-33 | `register` 签名加 `String workspacePath` 参数 |
| 94-101 | `SessionHandle` 构造加 `workspacePath`，新增 getter |

### workspace 路径解析逻辑（ChatServiceImpl.send() 中新增）

```
workspaceId 非空 → workspaceDao.queryById(workspaceId) → getPath()
workspaceId 为空 → System.getProperty("user.home")
```

## 验证

- `mvn clean compile -q` 通过
- `executeShell` 和 `resolveWorkspaceBoundary` 中不再有 `chatDao.queryConversationById` 或 `workspaceDao.queryById`
- 文件写入操作仍能正确检测 workspace 边界（功能回归）

```bash
# 带 workspace 发送消息
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/chat/send" \
  -d '{"message":"run ls","workspaceId":"ws-001"}'

# 不带 workspaceId（fallback user.home）
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/chat/send" \
  -d '{"message":"run pwd"}'
```

## 不在本次范围

- `executeShell` 中其他性能优化
- 更粗粒度的跨消息 workspace 缓存（场景不存在，无需缓存失效）
