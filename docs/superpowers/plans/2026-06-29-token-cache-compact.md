# Token 缓存与上下文压缩 — 实施计划

> **For agentic workers:** 使用 superpowers:subagent-driven-development 或 superpowers:executing-plans 执行此计划。步骤用 checkbox (`- [ ]`) 追踪。

**目标：** 实现 zephyr 对话的自动上下文压缩，接近 token 阈值时调用 LLM 总结旧消息释放空间，并支持手动 `/compact` 命令和 Anthropic 协议的 `cache_control` 缓存标记。

**架构：** 新增 `TokenCacheService` 接口 + `TokenCacheServiceImpl` 实现负责 token 估算、压缩判断、压缩执行。`LlmClient` 内 `if/else` 按 `model.protocol` 注入 cache_control 标记。`ChatServiceImpl.runLlmLoop` 前置压缩检查。

**技术栈：** JDK 17, SpringBoot 3.5.4, MyBatis, Gson, Hutool, Vue3 + TS + Element-plus

## 全局约束

- 向后兼容：`maxHistoryMessages=200` 继续工作，不改旧逻辑
- `ModelConfigEntity.maxContextTokens` 已存在，直接使用
- 项目 10/10 业务服务全部 Interface+Impl，TokenCacheService 必须遵循
- CacheMarkStrategy 不做接口，在 LlmClient 内 `if/else` 内联
- CompactResult 作为 TokenCacheService 接口的静态内部类
- 压缩不可破坏 DB 中的对话历史，只改内存消息列表
- 前/后端共用 `tokenEstimationRatio = 0.3` 配置项

---

### Task 1: ModelConfigEntity 增加 protocol 字段

**文件：**
- 修改：`src/main/java/com/github/hbq969/ai/zephyr/config/dao/entity/ModelConfigEntity.java`
- 修改：`src/main/resources/zephyr-zh-CN.sql`
- 修改：`src/main/resources/zephyr-en-US.sql`
- 修改：`src/main/resources/zephyr-ja-JP.sql`
- 修改：`src/main/resources/schema.sql`
- 修改：`src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/common/ModelConfigMapper.xml`
- 修改：`src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/embedded/ModelConfigMapper.xml`
- 修改：`src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/mysql/ModelConfigMapper.xml`
- 修改：`src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/postgresql/ModelConfigMapper.xml`
- 修改：`src/main/resources/static/src/types/chat.ts`
- 修改：`src/main/resources/static/src/store/settings.ts`
- 修改：`src/main/resources/static/src/views/settings/ModelSettings.vue`

**接口：**
- 产出：`ModelConfigEntity.protocol` 字段，默认值 `"openai"`
- 产出：前端 `ModelConfig` 接口新增 `protocol?: string`

- [ ] **Step 1: ModelConfigEntity 添加 protocol 字段**

```java
// src/main/java/.../config/dao/entity/ModelConfigEntity.java
@Data
public class ModelConfigEntity {
    private String id;
    private String userName;
    private String name;
    private String baseUrl;
    private String apiKeyEncrypted;
    private Integer isDefault;
    private Long createdAt;
    private Long updatedAt;
    private Long maxContextTokens;
    private String params;
    private String modelType;
    private String scope = "user";
    private Integer dimensions;
    private String protocol = "openai";  // 新增
}
```

- [ ] **Step 2: SQL 迁移 — 增量 ALTER TABLE**

在三个语言 SQL 文件末尾添加：

```sql
-- zephyr-zh-CN.sql / zephyr-en-US.sql / zephyr-ja-JP.sql 各加一段
ALTER TABLE zephyr_model_configs ADD COLUMN IF NOT EXISTS protocol VARCHAR(32) DEFAULT 'openai';
```

- [ ] **Step 3: SQL迁移 — 更新 schema.sql**

`src/main/resources/schema.sql`，在 `CREATE TABLE zephyr_model_configs` 的列定义中加入：

```sql
protocol VARCHAR(32) DEFAULT 'openai',
```

（若 schema.sql 当前无 CREATE TABLE，跳过。实际建表在三方言 Mapper XML 的 `createTable` 中）

- [ ] **Step 4: 更新三方言 DDL（embedded/mysql/postgresql）**

三个 Mapper XML 的 `createTable` 块（`<update id="createModelConfigTable">`）中添加列：

```xml
<!-- embedded/ModelConfigMapper.xml -->
<update id="createModelConfigTable">
    create table if not exists zephyr_model_configs (
        ...
        protocol varchar(32) default 'openai',
        ...
    )
</update>
```

`mysql/ModelConfigMapper.xml` 和 `postgresql/ModelConfigMapper.xml` 同样处理。

- [ ] **Step 5: 更新 DML（insert/select）透传 protocol**

`ModelConfigMapper.xml` (common) 中 insert 和 select 语句添加 `protocol` 列：

```xml
<!-- insertModelConfig -->
insert into zephyr_model_configs (id, user_name, name, base_url, api_key_encrypted, is_default, max_context_tokens, params, model_type, scope, dimensions, protocol, created_at, updated_at)
values (#{id}, #{userName}, #{name}, #{baseUrl}, #{apiKeyEncrypted}, #{isDefault}, #{maxContextTokens}, #{params}, #{modelType}, #{scope}, #{dimensions}, #{protocol}, #{createdAt}, #{updatedAt})

<!-- queryByUserName / queryById / queryShared 的 select 列中加入 -->
... protocol AS "protocol", ...
```

- [ ] **Step 6: 前端 types/chat.ts 增加 protocol 字段**

```typescript
// src/main/resources/static/src/types/chat.ts, ModelConfig 接口中加:
export interface ModelConfig {
  // ... existing fields
  protocol?: string  // 'openai' | 'anthropic'
}
```

- [ ] **Step 7: 前端 settings store 透传 protocol**

`src/main/resources/static/src/store/settings.ts` 的 `loadModels` 方法中，映射时添加：

```typescript
protocol: m.protocol || 'openai',
```

- [ ] **Step 8: 前端 ModelSettings.vue 表单增加协议下拉框**

在模型编辑表单中添加：

```html
<el-form-item :label="langData.settings_modelProtocol || '协议'">
  <el-select v-model="form.protocol">
    <el-option label="OpenAI" value="openai" />
    <el-option label="Anthropic" value="anthropic" />
  </el-select>
</el-form-item>
```

表单 `form` 初始化时加入 `protocol: 'openai'`。

- [ ] **Step 9: 编译验证**

```bash
cd src/main/resources/static && npm run type-check
```

```bash
mvn clean compile -q
```

- [ ] **Step 10: 提交**

```bash
git add -A
git commit -m "feat: ModelConfigEntity 增加 protocol 字段支持协议类型选择"
```

---

### Task 2: ZephyrConfigProperties 增加 Compact 配置类

**文件：**
- 修改：`src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java`

**接口：**
- 产出：`cfg.getChat().getCompact().isAutoEnabled()` 等 getter

- [ ] **Step 1: 在 Chat 内部类中添加 Compact 配置**

```java
// ZephyrConfigProperties.java，在 Chat 内部类的 Sse 类后面加：
@Data
public static class Compact {
    /** 自动压缩时保留的最近完整轮次数 */
    private int keepRecentRounds = 20;
    /** 自动压缩触发余量（token），当前 token > 窗口 - 余量时触发 */
    private int autoCompactBuffer = 10_000;
    /** 是否启用自动压缩 */
    private boolean autoEnabled = true;
}
```

在 `Chat` 类中新增字段：

```java
private Compact compact = new Compact();
```

- [ ] **Step 2: 编译验证**

```bash
mvn clean compile -q
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java
git commit -m "feat: ZephyrConfigProperties 增加 Compact 配置类"
```

---

### Task 3: TokenCacheService 接口 + 实现

**文件：**
- 新增：`src/main/java/com/github/hbq969/ai/zephyr/chat/service/TokenCacheService.java`
- 新增：`src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/TokenCacheServiceImpl.java`
- 新增：`src/main/resources/prompts/compact/summarize.md`
- 修改：`src/main/java/com/github/hbq969/ai/zephyr/constant/ZephyrConstants.java`

**接口：**
- 产出：`TokenCacheService` 接口（`estimateTokens`, `shouldCompact`, `compact`）
- 产出：`TokenCacheService.CompactResult` 静态内部类（`summary`, `preCompactTokens`, `postCompactTokens`, `compactMessages`）
- 产出：熔断异常 `CompactCircuitBrokenException`

- [ ] **Step 1: ZephyrConstants 添加常量**

```java
// ZephyrConstants.java，在 SSE_EVENT_CONFIRM_ACTION 下面加：
/** SSE event 名称：上下文压缩 */
public static final String SSE_EVENT_COMPACT = "compact";
```

- [ ] **Step 2: 写 summarize.md prompt 模板**

```markdown
你是一个对话摘要器。请将以下对话历史总结为简洁的摘要，提取关键信息：

1. 用户做了什么操作或问了什么问题
2. AI 给出了什么关键回答或执行了什么操作
3. 产生的任何重要结论、文件路径、数据或决策

要求：
- 用中文输出
- 保留具体的文件路径、命令、数字等关键细节
- 不要遗漏任何技术决策
- 长度控制在 500 字以内
```

保存到 `src/main/resources/prompts/compact/summarize.md`。

- [ ] **Step 3: 写 TokenCacheService 接口**

```java
package com.github.hbq969.ai.zephyr.chat.service;

import com.github.hbq969.ai.zephyr.chat.client.LlmClient;
import com.github.hbq969.ai.zephyr.chat.service.ConversationSessionManager;
import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public interface TokenCacheService {

    /** 估算文本 token 数（charCount × tokenEstimationRatio） */
    int estimateTokens(String text);

    /** 估算消息列表 token 数 */
    int estimateTokens(List<Map<String, Object>> messages);

    /** 判断是否需要压缩：当前 token > 模型窗口 - 余量 */
    boolean shouldCompact(List<Map<String, Object>> messages, ModelConfigEntity model);

    /** 执行压缩，返回组装好的新消息列表。失败返回 null */
    CompactResult compact(List<Map<String, Object>> messages, ModelConfigEntity model,
                           LlmClient llmClient, String cid, SseEmitter emitter,
                           ConversationSessionManager.SessionHandle handle) throws IOException;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    class CompactResult {
        private String summary;
        private int preCompactTokens;
        private int postCompactTokens;
        private List<Map<String, Object>> compactMessages;
    }
}
```

- [ ] **Step 4: 写 TokenCacheServiceImpl 实现**

```java
package com.github.hbq969.ai.zephyr.chat.service.impl;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.ROLE_SYSTEM;
import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.ROLE_USER;

import cn.hutool.cache.Cache;
import cn.hutool.cache.impl.TimedCache;
import com.github.hbq969.ai.zephyr.chat.client.LlmClient;
import com.github.hbq969.ai.zephyr.chat.service.ConversationSessionManager;
import com.github.hbq969.ai.zephyr.chat.service.TokenCacheService;
import com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties;
import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import com.github.hbq969.ai.zephyr.security.PromptLoader;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.*;

@Slf4j
@Service
public class TokenCacheServiceImpl implements TokenCacheService {

    @Resource
    private ZephyrConfigProperties cfg;
    @Resource
    private PromptLoader promptLoader;

    // 熔断计数器：conversationId → 连续压缩次数（TTL 1 小时）
    private final Cache<String, Integer> circuitBreaker = new TimedCache<>(3600_000L);

    // 递归保护
    private static final ThreadLocal<Boolean> COMPACTING = ThreadLocal.withInitial(() -> false);

    @Override
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() * cfg.getChat().getContext().getTokenEstimationRatio());
    }

    @Override
    public int estimateTokens(List<Map<String, Object>> messages) {
        int total = 0;
        for (Map<String, Object> msg : messages) {
            String role = (String) msg.get("role");
            String content = (String) msg.get("content");
            if (content != null) total += estimateTokens(content);
            if ("assistant".equals(role) && msg.get("tool_calls") != null) {
                // 粗略算 tool_calls JSON 的 token 数
                total += estimateTokens(msg.get("tool_calls").toString());
            }
        }
        return total;
    }

    @Override
    public boolean shouldCompact(List<Map<String, Object>> messages, ModelConfigEntity model) {
        if (COMPACTING.get()) return false; // 递归保护
        long maxCtx = model.getMaxContextTokens() != null ? model.getMaxContextTokens() : 200_000L;
        int threshold = (int) (maxCtx - cfg.getChat().getCompact().getAutoCompactBuffer());
        int current = estimateTokens(messages);
        return current > threshold;
    }

    @Override
    public CompactResult compact(List<Map<String, Object>> messages, ModelConfigEntity model,
                                  LlmClient llmClient, String cid, SseEmitter emitter,
                                  ConversationSessionManager.SessionHandle handle) throws IOException {
        COMPACTING.set(true);
        try {
            int preTokens = estimateTokens(messages);

            // 1. 分离消息
            int keepRounds = cfg.getChat().getCompact().getKeepRecentRounds();
            List<Map<String, Object>> oldMessages = new ArrayList<>();
            List<Map<String, Object>> keptMessages = new ArrayList<>();

            // system prompt 始终保留
            if (!messages.isEmpty() && ROLE_SYSTEM.equals(messages.get(0).get("role"))) {
                keptMessages.add(messages.get(0));
            }

            // 从后往前数 keepRounds 轮（user-assistant 配对算一轮）
            int roundCount = 0;
            int cutIdx = messages.size();
            for (int i = messages.size() - 1; i >= 1; i--) { // i=0 是 system prompt
                Map<String, Object> msg = messages.get(i);
                String role = (String) msg.get("role");
                if (ROLE_USER.equals(role)) roundCount++;
                if (roundCount >= keepRounds) {
                    cutIdx = i;
                    break;
                }
            }

            // 待总结：system 之后、cutIdx 之前
            for (int i = 1; i < cutIdx; i++) {
                oldMessages.add(messages.get(i));
            }
            // 保留：cutIdx 之后
            for (int i = cutIdx; i < messages.size(); i++) {
                keptMessages.add(messages.get(i));
            }

            if (oldMessages.isEmpty()) {
                // 轮次太少，无需压缩
                return null;
            }

            // 2. 构建摘要请求
            String summaryPrompt = promptLoader.load("compact/summarize.md");
            List<Map<String, Object>> summaryMessages = new ArrayList<>();
            summaryMessages.add(Map.of("role", ROLE_SYSTEM, "content", summaryPrompt));
            summaryMessages.addAll(oldMessages);
            summaryMessages.add(Map.of("role", ROLE_USER, "content", "请按照要求总结以上对话"));

            // 3. 调 LLM 获取摘要（无 tools）
            LlmClient.LlmResult summaryResult = llmClient.chat(model, summaryMessages, List.of(),
                    emitter, cid + "-compact", handle);

            String summary = summaryResult.getContent();
            if (summary == null || summary.isBlank()) {
                log.warn("[压缩] cid={} 摘要为空", cid);
                recordCircuitBreaker(cid, preTokens);
                return null;
            }

            // 4. 组装新消息列表
            List<Map<String, Object>> compactMessages = new ArrayList<>();
            compactMessages.add(keptMessages.get(0)); // system prompt
            compactMessages.add(Map.of("role", ROLE_SYSTEM, "content",
                    "## 历史对话摘要\n\n" + summary + "\n\n---"));
            for (int i = 1; i < keptMessages.size(); i++) {
                compactMessages.add(keptMessages.get(i));
            }

            int postTokens = estimateTokens(compactMessages);
            log.info("[压缩] cid={} preTokens={} postTokens={} summaryLen={}",
                    cid, preTokens, postTokens, summary.length());

            // 检查效果，更新熔断
            if (postTokens >= preTokens * 0.8) {
                recordCircuitBreaker(cid, preTokens);
            } else {
                circuitBreaker.remove(cid);
            }

            return CompactResult.builder()
                    .summary(summary)
                    .preCompactTokens(preTokens)
                    .postCompactTokens(postTokens)
                    .compactMessages(compactMessages)
                    .build();
        } catch (CompactCircuitBrokenException e) {
            throw e; // 熔断异常直接抛出，不重复计数
        } catch (Exception e) {
            log.warn("[压缩] cid={} LLM 调用失败: {}", cid, e.getMessage());
            recordCircuitBreaker(cid, estimateTokens(messages));
            return null;
        } finally {
            COMPACTING.remove();
        }
    }

    // 记录一次无效压缩；>=3 次抛出熔断异常
    private void recordCircuitBreaker(String cid, int preTokens) {
        Integer count = circuitBreaker.get(cid);
        int next = (count == null ? 0 : count) + 1;
        circuitBreaker.put(cid, next);
        if (next >= 3) {
            throw new CompactCircuitBrokenException(cid);
        }
    }

    /** 熔断异常：连续压缩无效，暂停自动压缩 */
    public static class CompactCircuitBrokenException extends RuntimeException {
        public CompactCircuitBrokenException(String cid) {
            super("会话 " + cid + " 连续压缩 3 次无效，已暂停自动压缩");
        }
    }
}
```

- [ ] **Step 5: 编译验证**

```bash
mvn clean compile -q
```

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/TokenCacheService.java \
        src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/TokenCacheServiceImpl.java \
        src/main/resources/prompts/compact/summarize.md \
        src/main/java/com/github/hbq969/ai/zephyr/constant/ZephyrConstants.java
git commit -m "feat: 新增 TokenCacheService 接口+实现，支持 token 估算和上下文压缩"
```

---

### Task 4: 整合 ChatServiceImpl + ChatEvent

**文件：**
- 修改：`src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java`
- 修改：`src/main/java/com/github/hbq969/ai/zephyr/chat/model/ChatEvent.java`

**接口：**
- 消费：`TokenCacheService.estimateTokens()`, `shouldCompact()`, `compact()`, `CompactResult`
- 产出：`ChatEvent.preTokens`, `ChatEvent.postTokens`

- [ ] **Step 1: ChatEvent 新增字段**

```java
// ChatEvent.java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatEvent {
    private String type;
    private String content;
    private String toolName;
    private Object toolInput;
    private String toolOutput;
    private String toolStatus;
    private Object usage;
    private String error;
    private Integer preTokens;   // 新增
    private Integer postTokens;  // 新增
}
```

- [ ] **Step 2: ChatServiceImpl 注入 TokenCacheService，委托 estimateTokens**

```java
// ChatServiceImpl.java 添加：
@Resource
private TokenCacheService tokenCacheService;

// 将现有的 private int estimateTokens(String text) 方法改为委托：
private int estimateTokens(String text) {
    return tokenCacheService.estimateTokens(text);
}
```

- [ ] **Step 3: runLlmLoop 前置压缩检查**

在 `runLlmLoop` 的 `while (true)` 循环体首行，`handle.checkCancel()` 之后插入：

```java
// 自动压缩检查
if (cfg.getChat().getCompact().isAutoEnabled()
        && tokenCacheService.shouldCompact(messages, ctx.getModel())) {
    try {
        TokenCacheService.CompactResult cr = tokenCacheService.compact(
                messages, ctx.getModel(), llmClient, cid, emitter, handle);
        if (cr != null) {
            messages.clear();
            messages.addAll(cr.getCompactMessages());
            emitter.send(SseEmitter.event().name(SSE_EVENT_COMPACT)
                    .data(ChatEvent.builder()
                            .type(SSE_EVENT_COMPACT)
                            .preTokens(cr.getPreCompactTokens())
                            .postTokens(cr.getPostCompactTokens())
                            .build()));
        }
    } catch (TokenCacheServiceImpl.CompactCircuitBrokenException e) {
        log.warn("[压缩] cid={} 已熔断，跳过本次会话自动压缩", cid);
        emitter.send(SseEmitter.event().name(SSE_EVENT_COMPACT)
                .data(ChatEvent.builder()
                        .type(SSE_EVENT_COMPACT)
                        .content("自动压缩已暂停（连续无效）")
                        .build()));
    }
}
```

- [ ] **Step 4: /compact 斜杠命令**

`handleSlashCommand` 签名需增加 `String mode` 和 `SessionHandle handle` 两个参数：

```java
// 旧签名：
private String handleSlashCommand(String message, String userName, String cid,
                                   SseEmitter emitter, long now,
                                   ConversationSessionManager.SessionHandle handle)
// 新签名（加 String mode）：
private String handleSlashCommand(String message, String userName, String cid,
                                   SseEmitter emitter, long now, String mode,
                                   ConversationSessionManager.SessionHandle handle)
```

调用处 `processChatAsync` 中 line 169 传入 `mode` 即可。

在 `handleSlashCommand` 中添加 compact 分支：

```java
case "compact" -> {
    ContextBuilder.Context ctx = contextBuilder.build(userName, cid, mode);
    try {
        TokenCacheService.CompactResult cr = tokenCacheService.compact(
                ctx.getMessages(), ctx.getModel(), llmClient, cid, emitter, handle);
        if (cr != null) {
            emitter.send(SseEmitter.event().name(SSE_EVENT_COMPACT)
                    .data(ChatEvent.builder()
                            .type(SSE_EVENT_COMPACT)
                            .preTokens(cr.getPreCompactTokens())
                            .postTokens(cr.getPostCompactTokens())
                            .build()));
        } else {
            emitter.send(SseEmitter.event().name("message")
                    .data(ChatEvent.builder().type("error")
                            .content("压缩失败：摘要生成出错，请重试").build()));
        }
    } catch (TokenCacheServiceImpl.CompactCircuitBrokenException e) {
        emitter.send(SseEmitter.event().name("message")
                .data(ChatEvent.builder().type("error")
                        .content("压缩已熔断：" + e.getMessage()).build()));
    }
    emitter.send(SseEmitter.event().name("message")
            .data(ChatEvent.builder().type("done").build()));
    return null;
}
```

- [ ] **Step 5: /help 中加入 /compact 说明**

在 `/help` 命令输出文本中添加：

```
### 会话
- `/context` — 查看上下文占比
- `/compact` — 压缩当前对话上下文

### 操作
- `/clear` — 清空当前对话
- `/help` — 查看此帮助
```

- [ ] **Step 6: 编译验证**

```bash
mvn clean compile -q
```

- [ ] **Step 7: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java \
        src/main/java/com/github/hbq969/ai/zephyr/chat/model/ChatEvent.java
git commit -m "feat: ChatServiceImpl 整合自动压缩和 /compact 命令"
```

---

### Task 5: LlmClient cache_control 注入

**文件：**
- 修改：`src/main/java/com/github/hbq969/ai/zephyr/chat/client/LlmClient.java`

- [ ] **Step 1: 在构建请求体和发送请求之间注入 cache_control**

在 `LlmClient.chat()` 方法中，`bodyJson` 构建完成后（streamOpts 之后），`RequestBody.create` 之前插入：

```java
// cache_control 注入（Anthropic 协议模型）
if ("anthropic".equals(model.getProtocol())) {
    JsonObject cacheControl = new JsonObject();
    cacheControl.addProperty("type", "ephemeral");

    // system prompt 第一块加 cache_control
    JsonArray msgs = bodyJson.getAsJsonArray("messages");
    if (msgs != null && msgs.size() > 0) {
        JsonObject sysMsg = msgs.get(0).getAsJsonObject();
        if (ROLE_SYSTEM.equals(sysMsg.get("role").getAsString())) {
            String sysContent = sysMsg.get("content").getAsString();
            JsonArray sysBlocks = new JsonArray();
            JsonObject textBlock = new JsonObject();
            textBlock.addProperty("type", "text");
            textBlock.addProperty("text", sysContent);
            textBlock.add("cache_control", cacheControl);
            sysBlocks.add(textBlock);
            sysMsg.add("content", sysBlocks);
        }

        // 最后一条消息加 cache_control
        JsonObject lastMsg = msgs.get(msgs.size() - 1).getAsJsonObject();
        JsonElement lastContent = lastMsg.get("content");
        if (lastContent != null && lastContent.isJsonPrimitive()) {
            JsonArray blocks = new JsonArray();
            JsonObject block = new JsonObject();
            block.addProperty("type", "text");
            block.addProperty("text", lastContent.getAsString());
            block.add("cache_control", cacheControl);
            blocks.add(block);
            lastMsg.add("content", blocks);
        }
    }
}
// ponytail: cache_control 标记以 OpenAI 扩展格式附加，兼容性由 API proxy 决定
// 不支持的 proxy 会忽略此字段，不影响请求正确性
```

- [ ] **Step 2: 编译验证**

```bash
mvn clean compile -q
```

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/client/LlmClient.java
git commit -m "feat: LlmClient 对 Anthropic 协议模型注入 cache_control 标记"
```

---

### Task 6: 前端 — types + compact 事件渲染 + 状态栏

**文件：**
- 修改：`src/main/resources/static/src/types/chat.ts`
- 修改：`src/main/resources/static/src/views/chat/ChatView.vue`
- 修改：`src/main/resources/static/src/views/chat/MessageBubble.vue`

- [ ] **Step 1: types/chat.ts — 类型更新**

```typescript
// ChatEvent.type 联合类型: 'compaction' → 'compact'，新增 preTokens/postTokens
export interface ChatEvent {
  type: 'token' | 'thinking' | 'tool_call' | 'tool_result' | 'usage' | 'compact' | 'done' | 'error' | 'clear' | 'confirm_action'
  content?: string
  toolName?: string
  toolInput?: Record<string, unknown>
  toolOutput?: string
  toolStatus?: string
  usage?: { inputTokens: number; outputTokens: number }
  error?: string
  preTokens?: number   // 新增
  postTokens?: number  // 新增
}

// Message.role 增加 'compact'
export interface Message {
  id: string
  role: 'user' | 'assistant' | 'system' | 'tool' | 'compact'
  content: string
  thinking?: string
  toolCalls?: ToolCall[]
  timestamp: number
}
```

- [ ] **Step 2: ChatView.vue — compact 事件处理**

在 `onSend` 的 SSE 事件解析中添加（放在 `event.type === 'clear'` 之前）：

```typescript
} else if (event.type === 'compact') {
  if (event.content) {
    chatStore.addMessage({ id: nextMsgId(), role: 'compact', content: event.content, timestamp: Date.now() / 1000 })
  } else if (event.preTokens && event.postTokens) {
    const preK = (event.preTokens / 1024).toFixed(1)
    const postK = (event.postTokens / 1024).toFixed(1)
    chatStore.addMessage({ id: nextMsgId(), role: 'compact', content: `${langData.cmd_compactCtx}（${preK}K → ${postK}K tokens）`, timestamp: Date.now() / 1000 })
  }
  settingsStore.loadContextUsage(convStore.currentId)
}
```

- [ ] **Step 3: MessageBubble.vue — compact 消息渲染**

在 template 中 message.role 分支添加 compact 类型：

```html
<div v-else-if="message.role === 'compact'" class="compact-boundary">
  <el-divider>
    <Icon icon="lucide:folders" class="compact-icon" />
    <span class="compact-text">{{ message.content }}</span>
  </el-divider>
</div>
```

样式（scoped）：

```css
.compact-boundary { max-width: 820px; margin: 8px auto; padding: 0 24px; }
.compact-boundary :deep(.el-divider__text) { display: flex; align-items: center; gap: 0.25rem; }
.compact-icon { font-size: 14px; color: var(--el-text-color-placeholder); }
.compact-text { font-size: 12px; color: var(--el-text-color-secondary); }
```

- [ ] **Step 4: 编译验证**

```bash
cd src/main/resources/static && npm run type-check && npm run build
```

- [ ] **Step 5: 提交**

```bash
git add src/main/resources/static/src/types/chat.ts \
        src/main/resources/static/src/views/chat/ChatView.vue \
        src/main/resources/static/src/views/chat/MessageBubble.vue
git commit -m "feat: 前端 compact 事件渲染和类型更新"
```

---

### Task 7: 前端状态栏 compact 状态指示器

**文件：**
- 修改：`src/main/resources/static/src/views/chat/StatusBar.vue`
- 修改：`src/main/resources/static/src/store/settings.ts`

- [ ] **Step 1: settings store 增加 compactEnabled**

```typescript
// settings.ts 增加字段和 action
compactEnabled: true,

// 在 loadSettings 或对应的初始化方法中:
compactEnabled: true,  // 可从后端配置读取
```

- [ ] **Step 2: StatusBar.vue 增加压缩状态指示器**

在 context 进度条右侧、spacer 之前插入：

```html
<div class="ctx-group">
  <Icon icon="lucide:archive" class="s-icon" />
  <span>{{ settingsStore.compactEnabled ? langData.cmd_compactCtx + ':开' : langData.cmd_compactCtx + ':关' }}</span>
</div>
```

- [ ] **Step 3: 编译验证**

```bash
cd src/main/resources/static && npm run build
```

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/static/src/views/chat/StatusBar.vue \
        src/main/resources/static/src/store/settings.ts
git commit -m "feat: StatusBar 增加压缩状态指示器"
```

---

## 验证清单

部署后按以下步骤验证：

- [ ] 配置模型 `protocol=anthropic` → 发送消息 → 日志中请求体含 `cache_control`
- [ ] 配置模型 `protocol=openai` → 发送消息 → 请求体不含 `cache_control`
- [ ] 设置 `autoEnabled=true, autoCompactBuffer=500000`（极小余量触发）→ 发送长消息 → 触发自动压缩 SSE 事件
- [ ] 发送 `/compact` → 返回 compact 事件 + done 事件
- [ ] 连续 3 次 compact 后 token 未显著下降 → 自动压缩被熔断，日志中出现 `CompactCircuitBrokenException`
- [ ] `/help` 输出包含 `/compact` 命令
- [ ] 前端：compact 消息以分隔线样式显示，不可编辑
- [ ] `npm run type-check` 通过
- [ ] `mvn clean compile -q` 通过
