# Token 缓存与上下文压缩

## 1. 目标

zephyr 当前对话上下文管理仅靠硬截断（`maxHistoryMessages=200`，超出直接丢弃旧消息），存在两个问题：

1. **上下文丢失** — 截断的旧消息中可能包含关键信息（文件路径、中间结论、用户偏好）
2. **无缓存标记** — 不利用 API 层的 prompt caching 特性，每次请求都是全量 cache write，API 费用高

参考 Claude-Code 的多层上下文管理架构，实现：

- **上下文自动压缩** — 接近 token 阈值时调用 LLM 总结旧消息，保留信息的同时释放空间
- **手动压缩命令** — `/compact` 斜杠命令
- **API 层缓存标记** — 检测到支持 Anthropic 协议格式的模型时注入 `cache_control`

## 2. 架构

### 2.1 新增文件

| 文件 | 职责 |
|------|------|
| `chat/service/TokenCacheService.java` | 接口：token 估算、压缩判断、压缩执行 |
| `chat/service/impl/TokenCacheServiceImpl.java` | 默认实现 |
| `chat/service/CacheMarkStrategy.java` | 接口：注入 cache 标记 |
| `chat/service/impl/AnthropicCacheMarkStrategy.java` | Anthropic 协议格式实现 |
| `chat/service/impl/NoopCacheMarkStrategy.java` | 不支持的模型兜底 |
| `chat/model/CompactResult.java` | 压缩结果 DTO |
| `config/compact/summarize.md` | 压缩摘要 prompt 模板 |

### 2.2 改动文件

| 文件 | 改动内容 |
|------|---------|
| `config/ZephyrConfigProperties.java` | 新增 `Compact` 配置类 |
| `config/dao/entity/ModelConfigEntity.java` | 新增 `protocol` 字段 |
| `chat/client/LlmClient.java` | 请求体构建时调用 `CacheMarkStrategy.injectCacheControl()` |
| `chat/service/impl/ChatServiceImpl.java` | `runLlmLoop` 中加压缩检查、`/compact` 命令 |
| `chat/service/ContextBuilder.java` | `buildMessages` 支持 compact boundary 消息 |
| `chat/model/ChatEvent.java` | 新增 `preTokens`、`postTokens`、`usage` 字段 |
| 前端 `MessageBubble.vue` | 渲染 compact 事件 |
| 前端 `StatusBar.vue` | 实时 token 用量 |
| 前端 `ChatView.vue` | 维护 tokenState |
| 前端 `InputArea.vue` | `/compact` 命令补全 |

## 3. 接口设计

### 3.1 TokenCacheService

```java
public interface TokenCacheService {
    /** 估算文本 token 数（字符数 / 4） */
    int estimateTokens(String text);
    /** 估算消息列表 token 数 */
    int estimateTokens(List<Map<String, Object>> messages);
    /** 判断是否需要压缩 */
    boolean shouldCompact(List<Map<String, Object>> messages, ModelConfigEntity model);
    /** 执行压缩，返回组装好的新消息列表 */
    CompactResult compact(List<Map<String, Object>> messages, ModelConfigEntity model,
                           LlmClient llmClient) throws IOException;
}
```

### 3.2 CacheMarkStrategy

```java
public interface CacheMarkStrategy {
    /** 是否匹配此模型 */
    boolean supports(ModelConfigEntity model);
    /** 向请求体注入 cache 标记 */
    void injectCacheControl(JsonObject requestBody);
}
```

策略选择逻辑：Spring 注入所有 `CacheMarkStrategy` Bean → 遍历找 `supports() == true` 的 → 没有则用 `NoopCacheMarkStrategy`。

`AnthropicCacheMarkStrategy.supports()` 判断 `model.protocol == "anthropic"`。

## 4. 数据模型

### 4.1 CompactResult

```java
@Data
@Builder
public class CompactResult {
    private String summary;                              // 摘要文本
    private int preCompactTokens;                        // 压缩前 token 数
    private int postCompactTokens;                       // 压缩后 token 数
    private List<Map<String, Object>> messages;          // 组装好的新消息列表
}
```

### 4.2 ModelConfigEntity 新增字段

```sql
ALTER TABLE t_model_config ADD COLUMN protocol VARCHAR(32) DEFAULT 'openai';
```

| 字段 | 默认值 | 说明 |
|------|--------|------|
| `protocol` | `openai` | `openai` 或 `anthropic` |

> `maxContextTokens` 已在 `ModelConfigEntity` 中存在，无需新增。

前端模型配置表单加两个字段：协议下拉框、上下文窗口数字输入。

### 4.3 配置类（ZephyrConfigProperties.Compact）

```java
@Data
public static class Compact {
    /** 自动压缩时保留的最近完整轮次数 */
    private int keepRecentRounds = 20;
    /** 自动压缩触发余量（token），当前 token > 窗口 - 余量 时触发 */
    private int autoCompactBuffer = 10_000;
    /** 是否启用自动压缩 */
    private boolean autoEnabled = true;
}
```

## 5. 核心流程

### 5.1 压缩判断（shouldCompact）

```
currentTokens = estimateTokens(messages)
threshold = model.maxContextTokens - cfg.compact.autoCompactBuffer
currentTokens > threshold → 需要压缩
```

### 5.2 压缩执行（compact）

```
1. 分离消息：
   - kept（保留）：最近 N 轮完整消息（N = cfg.compact.keepRecentRounds）
   - old（待总结）：其余消息（不含 system prompt 首条）

2. 构建摘要请求：
   system = summarize.md（"你是对话摘要器，请提取关键信息..."）
   messages = old + user("请总结以上对话")
   tools = []
   thinking = disabled

3. 调 llmClient.chat() → summary

4. 组装 CompactResult：
   messages = [
     {role: "system", content: systemPrompt},
     {role: "system", content: "## 历史对话摘要\n\n" + summary + "\n\n---"},
     ...kept
   ]
```

### 5.3 自动压缩触发点

`ChatServiceImpl.runLlmLoop()` while 循环开头，每次 LLM 调用前：

```java
if (cfg.getChat().getCompact().isAutoEnabled()
        && tokenCacheService.shouldCompact(messages, ctx.getModel())) {
    CompactResult cr = tokenCacheService.compact(messages, ctx.getModel(), llmClient);
    messages.clear();
    messages.addAll(cr.getMessages());
    emitter.send(SseEmitter.event().name("message")
            .data(ChatEvent.builder().type("compact")
                    .preTokens(cr.getPreCompactTokens())
                    .postTokens(cr.getPostCompactTokens()).build()));
}
```

### 5.4 手动压缩（/compact）

```java
case "compact" -> {
    ContextBuilder.Context ctx = contextBuilder.build(userName, cid, mode);
    CompactResult cr = tokenCacheService.compact(ctx.getMessages(), ctx.getModel(), llmClient);
    emitter.send(SseEmitter.event().name("message")
            .data(ChatEvent.builder().type("compact")
                    .preTokens(cr.getPreCompactTokens())
                    .postTokens(cr.getPostCompactTokens()).build()));
    emitter.send(SseEmitter.event().name("message")
            .data(ChatEvent.builder().type("done").build()));
    return null;
}
```

### 5.5 cache_control 注入

`LlmClient.chat()` 中，构建完 `bodyJson` 后：

```java
cacheMarkStrategy.injectCacheControl(bodyJson);
```

`AnthropicCacheMarkStrategy` 注入规则：

- system prompt 的第一个 text block 加 `"cache_control": {"type": "ephemeral"}`
- messages 数组的最后一条消息的最后一个 content block 同样标记

```json
// system prompt 第一块
{"type": "text", "text": "...", "cache_control": {"type": "ephemeral"}}

// 最后一条消息最后一块
{"role": "user", "content": [..., {"type": "text", "text": "...", "cache_control": {"type": "ephemeral"}}]}
```

### 5.6 token 估算

简单字符比例：`字符数 / 4`。API 返回的 usage 数据存在 `LlmResult.usage` 中，`runLlmLoop` 已累计 `totalInputTokens`/`totalOutputTokens`。压缩事件发送时优先用 API 实际值。

## 6. UI 设计

### 6.1 compact 事件渲染

`MessageBubble.vue` 根据事件类型渲染不同内容。新增 `compact` 类型——分隔线样式：

```html
<div class="compact-boundary">
  <el-divider>
    <el-icon><Box /></el-icon>
    对话已压缩（85K → 12K tokens）
  </el-divider>
</div>
```

非对话消息，不可引用/编辑/重新生成。颜色用 `var(--el-text-color-secondary)` 低调展示。

### 6.2 StatusBar 增强

```
🔤 12.5K / 200K  |  🤖 claude-sonnet-4-6  |  📦 压缩: 开
```

左侧 token 用量进度条（`el-progress`），右侧模型名和压缩状态。`done` 事件中后端推送 usage 数据，前端累计：

```typescript
const tokenState = reactive({
  currentTokens: 0,
  contextWindow: 200000,
  cacheHitTokens: 0,
  compactEnabled: true
})
```

### 6.3 改动文件

| 文件 | 改动 |
|------|------|
| `MessageBubble.vue` | `compact` 事件类型渲染 |
| `ChatView.vue` | 维护 `tokenState`，监听 `done`/`compact` 事件更新 |
| `StatusBar.vue` | 显示 token 进度和模型信息 |
| `InputArea.vue` | `/compact` 命令补全提示 |

### 6.4 命令补全提示

`InputArea.vue` 斜杠命令列表中增加 `compact`：

```typescript
{ command: '/compact', description: '压缩当前对话上下文' }
```

## 7. 边界与约束

- **压缩只做摘要，不恢复文件/skill**：与 Claude-Code 不同，zephyr 没有"最近读取文件"的概念，skill 通过 `use_skill` 按需加载，压缩后模型需要时可重新调用
- **压缩不截断保留消息**：保留的 N 轮消息原文不动，摘要只覆盖旧消息
- **熔断**：连续压缩 3 次后 token 仍在阈值以上 → 跳过当前会话的自动压缩，新会话重置
- **递归保护**：压缩用的 summary 请求本身不算历史，不触发二次压缩
- **配置变更**：`protocol` 新增字段需同步更新三方言 DDL、Entity、前端表单、国际化

## 8. 国际化

```properties
# zh-CN
compact.title=对话已压缩
compact.desc={0}K → {1}K tokens
compact.command=/compact, 压缩当前对话上下文

# en-US
compact.title=Conversation compacted
compact.desc={0}K → {1}K tokens
compact.command=/compact, Compact current conversation context
```
