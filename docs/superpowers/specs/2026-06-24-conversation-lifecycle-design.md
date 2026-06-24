# 会话生命周期管理设计

## 问题定义

zephyr 会话（conversation）退出时，关联的后台资源（SSE 连接、LLM 调用、异步线程、MCP 工具调用子进程）不会被清理，导致资源泄漏。

### 会话退出场景

| 场景 | 触发方 | 说明 |
|------|--------|------|
| `/clear` | 用户 | 清空当前会话上下文 |
| 删除会话 | 用户 | 用户在 UI 中删除会话 |
| 刷新页面 | 用户 | 关闭/刷新标签页，SSE 断开 |
| 会话超时 | 后端 | 15 分钟无活动，定时任务扫描清理 |
| 进程强杀 | 系统 | `kill -9`、OOM、系统关机——进程级清理，代码无法干预 |

### 目标

- 会话退出时取消所有关联后台资源
- 保证 DB 数据一致，查询历史消息不出错
- 所有超时统一为 15 分钟，可配置

---

## 架构方案：自清理任务 + 薄观察者

### 核心原则

**每个异步任务负责自己资源的清理。** 所有资源分配和释放都在同一个线程内完成，使用 `try-finally` 保证清理执行。`ConversationSessionManager` 只做观察者（注册/注销 + 超时扫描通知），**不跨线程打断**。

### 为什么不用集中式编排器

| 问题 | 说明 |
|------|------|
| 跨线程 interrupt 不可靠 | `Future.cancel(true)` 打断的线程可能卡在 OkHttp/JDBC/进程 IO 中，行为不可预测 |
| SseEmitter 多路竞争 | 清理线程、SSE 回调、异步任务 catch 块三重竞争 `emitter.complete()` |
| MCP 连接共享冲突 | `userName:serverId` 级别共享，无法按 conversationId 取消单个工具调用 |

### 数据结构

```java
@Component
public class ConversationSessionManager {

    private final ConcurrentHashMap<String, SessionHandle> sessions = new ConcurrentHashMap<>();

    public static class SessionHandle {
        String conversationId;
        String userName;
        long lastActivityTime;  // 秒级 Unix 时间戳
        volatile boolean cancelled;  // true = 通知异步任务退出
    }
}
```

`SessionHandle` 不持有 `SseEmitter`、`Future`、`Call` 引用——只存取消标志位和时间戳。

### 生命周期流程

```
// send() 方法内，同步阶段（在 HTTP 线程上）：
conversationId = resolveOrCreateCid(request)  // 若为空则预生成
handle = new SessionHandle(conversationId, userName)
sessionManager.register(handle)
emitter = new SseEmitter(timeout)             // 创建 SSE
emitter.onTimeout(() -> {                     // 回调中可通过 sessionManager.get(cid) 取到 handle
    handle = sessionManager.get(cid); handle.cancel();
})
// 启动异步任务
executor.execute(() -> {
    ↓
异步任务执行：
    try {
        while (true) {
            checkCancel(handle);        // 检查 cancelled 标志，true 则抛 CancelException
            llmCall = llmClient.chat(...)
            handle.touch()              // 更新活跃时间
            if (hasToolCalls) dispatchTools()
            checkCancel(handle);
        }
    } catch (CancelException e) {
        // 线程内自己取消，不需跨线程 interrupt
        llmCall.cancel()
    } finally {
        emitter.complete()              // 只有这一条路径调用 complete
        sessionManager.remove(conversationId)
    }
    ↓
外部触发退出 → handle.cancel()  // 只设标志位
    → 异步任务在下次 checkCancel() 时抛 CancelException → 进入 finally 清理
```

### 退出路径与触发方式

| 退出路径 | 触发方式 | 说明 |
|---------|---------|------|
| `/clear` | async 任务内 `handle.cancel()` + 删 DB | `handleSlashCommand()` 内设置 cancelled，抛 CancelException |
| 删除会话 | `ConversationServiceImpl` 设 `handle.cancel()` | 异步任务感知后退出；DB 删除在异步任务 finally 之后执行 |
| SSE 超时/断连 | `ChatCtrl` 回调中 `handle.cancel()` | 替换现有 `llmClient.cancelCall(cancelKey)` 直接取消的逻辑 |
| 定时扫描 | `sessionManager.scanExpired()` 设 `handle.cancel()` | 超时 15 分钟无活动则标记 |

### 定时扫描

```java
@Scheduled(fixedRateString = "${zephyr.chat.session-scan-interval-seconds:60000}")
void scanExpired() {
    long now = System.currentTimeMillis() / 1000;
    sessions.values().forEach(h -> {
        if (now - h.lastActivityTime > sessionIdleTimeoutSeconds && !h.cancelled) {
            h.cancel();
        }
    });
}
```

---

## DB 一致性保证

异步任务在 `finally` 块中退出，`checkCancel()` 在安全点（LLM 调用前后、工具分派前后）抛出，保证：

- `cancel()` 后 LLM 流被 cancel → `IOException("Canceled")` → 不会写入新的半截消息
- async 任务持有的 `emitter.complete()` 在 finally 中调用，仅一次

| 中断时的状态 | DB 中的情况 | 影响 |
|-------------|------------|------|
| assistant 消息已入库，tool_call 还在执行 | content 为空，toolCallsJson 有值 | `ContextBuilder.sanitizeToolCalls()` 已有处理 |
| tool 消息已入库，assistant 被中断 | 孤立的 tool 消息 | 不影响历史展示 |
| 用户消息已入库，assistant 还没开始 | 只有一条 user 消息 | 前端展示无异常 |

---

## 与现有代码的集成点

### 1. ConversationSessionManager（新增）

- `register(conversationId, userName)` → `SessionHandle`
- `remove(conversationId)`
- `get(conversationId)` → 供外部设取消标志
- `scanExpired()` — `@Scheduled` 定时扫描
- `shutdown()` — `@PreDestroy` 关闭 executor

### 2. ChatServiceImpl.send()

- 异步任务开始：`SessionHandle handle = sessionManager.register(cid, userName)`
- 工具调用循环内：每轮迭代前 `handle.checkCancel()`
- 每次 SSE send 后：`handle.touch()`
- 正常结束 / `finally`：`emitter.complete()` + `sessionManager.remove(cid)`
- `/clear` 处理：`handle.cancel()` → `throw CancelException`（让 finally 统一清理）
- Executor 从 `Executors.newCachedThreadPool()` 改为注入的 `ThreadPoolExecutor`（由 SessionManager 管理生命周期）

### 3. LlmClient

- `activeCalls` key 从 `userName` 改为 `userName:conversationId`
- cancel 时 `call.cancel()` 由异步任务自己在 catch(CancelException) 中调用，不再跨线程

### 4. ConversationServiceImpl.delete()

- `handle = sessionManager.get(conversationId)` → 如有则 `handle.cancel()`
- 不等待异步任务结束（它会在 finally 中自清理）
- DB 删除可立即执行（与异步任务的 finally 不相干——finally 只做资源释放，不做 DB 操作）

### 5. ChatCtrl

- SSE `onTimeout` / `onError` 回调中：`sessionManager.get(cid).cancel()`，不再直接调 `llmClient.cancelCall()`

---

## 不改动的范围

- MCP 连接生命周期：由现有 `McpConnectionManager.cleanupIdle()` 独立管理
- McpConnection：不修改。工具调用取消由 async 任务 catch CancelException 后自然退出循环来处理
- MCP 服务器进程：userName:serverId 共享，不随会话退出而销毁
- Chroma 嵌入进程：应用级，不关联会话
- 消息历史持久化：保持现有逻辑

---

## 配置项

在 `ZephyrConfigProperties.Chat` 下新增：

```java
@Data
public static class Chat {
    /** SSE 连接超时毫秒，默认 900000（15 分钟） */
    private long sseTimeoutMillis = 900_000L;

    /** 会话空闲超时秒数，默认 900（15 分钟） */
    private int sessionIdleTimeoutSeconds = 900;

    /** 会话超时扫描间隔秒数，默认 60 */
    private int sessionScanIntervalSeconds = 60;
}
```

> 现有 `zephyr.chat.sse.timeout-millis` 改为直接挂在 `zephyr.chat.sse-timeout-millis` 下，默认值 5 分钟 → 15 分钟。

配置对应 `application.yml`：

```yaml
zephyr:
  chat:
    sse-timeout-millis: 900000
    session-idle-timeout-seconds: 900
    session-scan-interval-seconds: 60
```

---

## 设计决策记录

| 决策 | 理由 | 后果 |
|------|------|------|
| 异步任务自清理，不用集中编排器 | 避免跨线程 interrupt 不可靠、emitter 多路竞争、MCP 连接共享冲突 | SessionManager 职责仅限于注册/注销/超时扫描 |
| 合作式取消（标志位），不强制 interrupt | 资源分配和释放同线程，无线程间竞争 | 取消延迟最多为一个 LLM 调用周期 |
| 不按 conversationId 取消 MCP 工具调用 | MCP 连接共享，无法精确定位具体会话的工具调用 | MCP 工具调用随 async 任务退出自然结束；MCP 连接闲置由现有 `cleanupIdle()` 处理 |
| 删除会话时不等异步任务结束 | 等待会导致 HTTP 请求超时 | DB 删除先行，异步任务 finally 只做资源释放不做 DB 操作 |
