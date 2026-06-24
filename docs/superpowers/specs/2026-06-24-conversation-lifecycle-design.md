# 会话生命周期管理设计

## 问题定义

zephyr 会话（conversation）退出时，关联的后台资源（SSE 连接、LLM 调用、异步线程、MCP 工具调用）不会被清理，导致资源泄漏。

### 会话退出场景

| 场景 | 触发方 | 说明 |
|------|--------|------|
| `/clear` | 用户 | 清空当前会话上下文 |
| 删除会话 | 用户 | 用户在 UI 中删除会话 |
| 刷新页面 | 用户 | 关闭/刷新标签页，SSE 断开 |
| 会话超时 | 后端 | 15 分钟无活动，定时任务扫描清理 |
| 进程强杀 | 系统 | `kill -9`、OOM、系统关机——进程级清理，代码无法干预 |

### 目标

- 会话退出时**强制取消**所有关联后台资源
- 保证 DB 数据一致，查询历史消息不出错
- 所有超时统一为 15 分钟，可配置

---

## 架构方案：集中式 SessionManager

新增 `ConversationSessionManager`，用 `ConcurrentHashMap` 管理所有活跃会话的内存注册表。

### 数据结构

```java
@Component
public class ConversationSessionManager {

    private final ConcurrentHashMap<String, ConversationSession> sessions = new ConcurrentHashMap<>();

    public static class ConversationSession {
        String conversationId;
        String userName;
        SseEmitter emitter;
        Future<?> asyncTask;      // send() 的异步线程
        Call llmCall;              // OkHttp 请求引用
        long lastActivityTime;     // 秒级 Unix 时间戳
    }
}
```

### 资源清理范围

| 资源 | 类型 | 清理方式 |
|------|------|---------|
| SSE 连接 | SseEmitter | `emitter.complete()` |
| LLM 调用 | OkHttp Call | `call.cancel()` |
| 异步线程 | `Future<?>` | `future.cancel(true)`（中断） |
| MCP 工具调用 | `CompletableFuture` + 子进程 | 中断 `callTool` 线程 + 杀工具调用产生的子进程（见下方） |

### MCP 工具调用子进程清理

LLM 通过 MCP bash server 启动的命令（如 `npm run dev`）以 MCP 服务器进程的子进程形式存在。`callTool()` 同步阻塞等待响应时，子进程可能一直运行。

**MCP 服务器进程不杀**（userName:serverId 共享），只杀**本次工具调用新 fork 的子进程**。

`McpConnection` 新增中断/追踪能力：

```java
// McpConnection 新增字段
private volatile Thread currentCallThread;  // 当前执行 callTool 的线程
private volatile Set<Long> toolChildPids;   // 本次工具调用产生的子进程 PID

// 调用 tool 前后对 MCP 进程 descendants() 做快照 diff
String callToolStdio(String toolName, JsonObject arguments) throws Exception {
    Set<Long> before = process.descendants().map(ProcessHandle::pid).collect(toSet());
    currentCallThread = Thread.currentThread();
    try {
        return _callToolStdio(toolName, arguments);
    } finally {
        Set<Long> after = process.descendants().map(ProcessHandle::pid).collect(toSet());
        after.removeAll(before);
        toolChildPids = after;
        currentCallThread = null;
    }
}

// SessionManager.cleanup() 中调用，强制杀本次工具调用的子进程
void cancelCurrentTool() {
    Thread t = currentCallThread;
    if (t != null) t.interrupt();
    if (toolChildPids != null) {
        toolChildPids.forEach(pid ->
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly));
    }
}
```

`readMsg()` 改为感知中断：`future.get(timeout, SECONDS)` + 循环检查 `Thread.interrupted()`。

### 生命周期流程

```
会话创建（send/首次消息） → register(session)
    ↓
会话活跃中 → touch(conversationId)  // 每次 SSE 推送时更新时间戳
    ↓
触发退出 → cleanup(conversationId):
  1. llmCall.cancel()              — 取消 OkHttp 请求
  2. asyncTask.cancel(true)         — 中断异步线程
  3. mcpConnection.cancelCurrentTool() — 中断 callTool + 杀工具子进程
  4. emitter.complete()             — 关闭 SSE
  5. remove from sessions           — 清出注册表
    ↓
cleanup 后：
  - /clear / 删除：继续现有 DB 清理逻辑
  - SSE 超时 / 断连：不删 DB 数据，仅释放内存资源
```

### 定时扫描

```java
@Scheduled(fixedRateString = "${zephyr.chat.session-scan-interval-seconds:60000}")
void scanExpired() {
    long now = System.currentTimeMillis() / 1000;
    sessions.values().forEach(s -> {
        if (now - s.lastActivityTime > sessionIdleTimeoutSeconds) {
            cleanup(s.conversationId);
        }
    });
}
```

---

## DB 一致性保证

强制中断时，消息写入流程被 `IOException("Canceled")` 打断，不会再写后续消息。

| 中断时的状态 | DB 中的情况 | 影响 |
|-------------|------------|------|
| assistant 消息已入库，tool_call 还在执行 | content 为空，toolCallsJson 有值 | `ContextBuilder.sanitizeToolCalls()` 已有处理，构建上下文时跳过无 tool_result 的 tool_call |
| tool 消息已入库，assistant 被中断 | 孤立的 tool 消息 | 不影响历史展示 |
| 用户消息已入库，assistant 还没开始 | 只有一条 user 消息 | 前端展示无异常 |

> 结论：现有 `sanitizeToolCalls()` 机制已足够保证一致性，不需要额外中间态处理。

---

## 与现有代码的集成点

### 1. ChatServiceImpl

- register：异步线程开始时 `sessionManager.register(session)`
- touch：每次 `emitter.send()` 后 `sessionManager.touch(conversationId)`
- remove（正常结束）：`done` 事件后 `sessionManager.remove(conversationId)`
- cleanup（异常/取消/超时）：`sessionManager.cleanup(conversationId)`

### 2. LlmClient

- `activeCalls` 的 key 从 `userName` 改为 `userName:conversationId`（或新增按会话索引），以便 SessionManager 按 conversationId 取到正确的 Call

### 3. ConversationServiceImpl.delete()

- 删除前先调 `sessionManager.cleanup(conversationId)`，再执行 DB 删除

### 4. ChatServiceImpl 中的 /clear

- 先调 `sessionManager.cleanup(conversationId)`，再执行 DB 清理

### 5. ChatCtrl

- SSE `onTimeout` / `onError` / `onCompletion` 回调中调 `sessionManager.cleanup()`

### 6. McpConnection

- 新增 `currentCallThread`、`toolChildPids` 字段
- `callToolStdio()` 中用 descendants 快照 diff 追踪工具调用产生的子进程
- `readMsg()` 改为感知 `Thread.interrupted()`
- 新增 `cancelCurrentTool()` 方法：中断线程 + 杀子进程

---

## 配置项

在 `ZephyrConfigProperties.Chat` 下新增：

```java
@Data
public static class Chat {
    // ... 已有字段 ...

    /** SSE 连接超时，默认 900 秒（15 分钟） */
    private Sse sse = new Sse();

    @Data
    public static class Sse {
        /** SSE 连接超时毫秒，默认 900000（15 分钟，与会话超时对齐） */
        private long timeoutMillis = 900_000L;
    }

    /** 会话空闲超时秒数，默认 900（15 分钟），超时后自动清理资源 */
    private int sessionIdleTimeoutSeconds = 900;

    /** 会话超时扫描间隔秒数，默认 60 */
    private int sessionScanIntervalSeconds = 60;
}
```

配置对应 `application.yml`：

```yaml
zephyr:
  chat:
    sse:
      timeout-millis: 900000  # 15分钟，对齐会话超时
    session-idle-timeout-seconds: 900  # 15分钟
    session-scan-interval-seconds: 60  # 每分钟扫描
```

---

## 不改动的范围

- MCP 连接生命周期：由现有 `McpConnectionManager.cleanupIdle()` 独立管理
- MCP 服务器进程：userName:serverId 共享，不随会话退出而销毁（仅杀工具调用产生的子进程）
- Chroma 嵌入进程：应用级，不关联会话
- 消息历史持久化：保持现有逻辑
- `ChatServiceImpl` 的 `CachedThreadPool`：改为由 `ConversationSessionManager` 管理，增加 `@PreDestroy` 关闭
