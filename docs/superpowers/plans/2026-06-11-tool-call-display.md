# 工具调用实时展示 — 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 聊天页面实时展示模型工具调用过程，类似思考气泡的折叠卡片，标题显示工具名+历时，可展开查看输入/输出

**Architecture:** 后端在 LLM 流式返回中检测到 tool_calls 时立即推送 SSE 事件给前端，工具执行完成后推送 tool_result 事件。前端 store 收到事件后更新最后一条 assistant 消息的 toolCalls 数组，ToolCallCard 组件增加计时和折叠交互，始终渲染（不再限于 streaming 状态）。

**Tech Stack:** Java 17 + Spring Boot SSE + Vue 3 + TypeScript + Pinia

---

### 文件清单

| 操作 | 文件 | 说明 |
|------|------|------|
| 修改 | `LlmClient.java:155-178` | tool_calls 流式推送 SSE |
| 修改 | `ChatServiceImpl.java:139-141` | 工具执行后推送 tool_result SSE |
| 修改 | `ChatView.vue:122-150` | 新增 tool_call/tool_result 事件处理 |
| 修改 | `chat.ts` | 新增 upsertToolCall 方法 |
| 修改 | `ToolCallCard.vue` | 重构：计时器、折叠、始终渲染 |
| 修改 | `MessageBubble.vue:134-136` | 移除 `v-if="streaming"` 限制 |

---

### Task 1: LlmClient 流式推送 tool_call SSE 事件

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/client/LlmClient.java:155-178`

- [ ] **Step 1: 在 tool_calls 处理逻辑中新增 SSE 推送**

在 `LlmClient.java` 的 tool_calls 处理循环（第 158-177 行）中，当解析到 function name 时，立即发送 `tool_call` SSE 事件。需要用一个 Set 记录本轮已推送的 tool name 防止重复。

修改第 155-178 行，将原来的 tool_calls 累积逻辑改为同时推送 SSE：

```java
// tool calls
if (delta.has("tool_calls") && !delta.get("tool_calls").isJsonNull()) {
    JsonArray tcArray = delta.getAsJsonArray("tool_calls");
    Set<String> emittedNames = new HashSet<>();
    for (int i = 0; i < tcArray.size(); i++) {
        JsonObject tc = tcArray.get(i).getAsJsonObject();
        int idx = tc.has("index") ? tc.get("index").getAsInt() : 0;
        while (accumulatedToolCalls.size() <= idx) {
            accumulatedToolCalls.add(new JsonObject());
        }
        JsonObject accumulated = accumulatedToolCalls.get(idx).getAsJsonObject();

        if (tc.has("id")) accumulated.addProperty("id", tc.get("id").getAsString());
        if (tc.has("function")) {
            JsonObject func = tc.getAsJsonObject("function");
            if (!accumulated.has("function")) accumulated.add("function", new JsonObject());
            JsonObject accFunc = accumulated.getAsJsonObject("function");
            if (func.has("name")) {
                String name = func.get("name").getAsString();
                accFunc.addProperty("name", name);
                // 首次解析到 name 时推送 tool_call 事件
                if (emittedNames.add(String.valueOf(idx) + ":" + name)) {
                    try {
                        emitter.send(SseEmitter.event().name("message")
                                .data(ChatEvent.builder()
                                        .type("tool_call")
                                        .toolName(name)
                                        .build()));
                    } catch (IOException e) {
                        log.warn("推送 tool_call 事件失败: {}", e.getMessage());
                    }
                }
            }
            if (func.has("arguments")) {
                String args = accFunc.has("arguments") ? accFunc.get("arguments").getAsString() : "";
                accFunc.addProperty("arguments", args + func.get("arguments").getAsString());
            }
        }
    }
}
```

需要新增 import：
```java
import java.util.HashSet;
import java.util.Set;
```

- [ ] **Step 2: 编译验证**

```bash
cd /Users/hbq/Codes/me/github/zephyr
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn compile -DskipTests
```

预期：BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/client/LlmClient.java
git commit -m "feat: LLM 流式过程中实时推送 tool_call SSE 事件"
```

---

### Task 2: ChatServiceImpl 工具执行后推送 tool_result SSE 事件

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java:139-141`

- [ ] **Step 1: 在工具执行结果返回后发送 tool_result 事件**

在 `send()` 方法的工具调用循环中，`dispatchTools` 返回 `toolResults` 后（第 140 行后），遍历 toolCalls 和 toolResults 发送 SSE 事件。找到第 140-141 行附近的代码：

```java
List<Map<String, Object>> toolResults = dispatchTools(result.getToolCalls(), userName);
messages.addAll(toolResults);
```

在 `messages.addAll(toolResults)` 之前插入 SSE 推送逻辑：

```java
List<Map<String, Object>> toolResults = dispatchTools(result.getToolCalls(), userName);

// 推送工具执行结果
for (int i = 0; i < result.getToolCalls().size(); i++) {
    LlmResult.ToolCall tc = result.getToolCalls().get(i);
    String output = toolResults.get(i).get("content").toString();
    String status = output.startsWith("工具执行错误") ? "error" : "success";
    try {
        emitter.send(SseEmitter.event().name("message")
                .data(ChatEvent.builder()
                        .type("tool_result")
                        .toolName(tc.getName())
                        .toolOutput(output)
                        .build()));
    } catch (IOException e) {
        log.warn("推送 tool_result 事件失败: {}", e.getMessage());
    }
}

messages.addAll(toolResults);
```

注意：`ChatEvent` 没有 `status` 字段，不需要额外添加。前端可以根据 `toolOutput` 内容判断成功/失败，或者后端传递时前端默认视为 success，有 output 就是 success。

实际上更简单：`dispatchTools` 里 catch 异常后 content 是 `"工具执行错误: " + e.getMessage()`。在 tool_result 的 SSE 事件中，直接用 `toolOutput` 字段传递内容，前端根据前缀判断。或者我们直接在 status 字段中传。ChatEvent 没有 status 字段，但前端 ToolCall 接口有 status 字段。我们可以在前端 store 的 `upsertToolCall` 中根据 toolOutput 判断，如果以 `工具执行错误` 开头就是 error，否则 success。

- [ ] **Step 2: 编译验证**

```bash
cd /Users/hbq/Codes/me/github/zephyr
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn compile -DskipTests
```

预期：BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java
git commit -m "feat: 工具执行完成后推送 tool_result SSE 事件"
```

---

### Task 3: chat store 新增 upsertToolCall 方法

**Files:**
- Modify: `src/main/resources/static/src/store/chat.ts`

- [ ] **Step 1: 添加 upsertToolCall 方法**

在 `chat.ts` 的 `return` 语句之前添加方法，并在 return 中导出：

```typescript
function upsertToolCall(name: string, patch: Partial<ToolCall>) {
  flushTokens()
  const msgs = messages.value
  if (msgs.length === 0) return
  const last = msgs[msgs.length - 1]
  if (last.role !== 'assistant') return
  if (!last.toolCalls) last.toolCalls = []
  const existing = last.toolCalls.find(tc => tc.name === name)
  if (existing) {
    Object.assign(existing, patch)
  } else {
    last.toolCalls.push({
      name,
      input: patch.input || {},
      output: patch.output,
      status: patch.status || 'running',
    } as ToolCall)
  }
}
```

在 `return` 语句中添加 `upsertToolCall`：

```typescript
return { messages, streaming, currentThinking, sessionStartTime, addMessage, appendToken, setThinking, updateLastThinking, clearMessages, startSession, pruneEmptyAssistant, upsertToolCall }
```

需要在文件顶部导入 `ToolCall` 类型（已通过 `Message` 间接关联，但需确认 import）：

```typescript
import type { Message, ToolCall } from '@/types/chat'
```

- [ ] **Step 2: 类型检查验证**

```bash
cd /Users/hbq/Codes/me/github/zephyr/src/main/resources/static
npm run type-check
```

预期：0 errors

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/src/store/chat.ts
git commit -m "feat: chat store 新增 upsertToolCall 方法，支持流式更新工具调用状态"
```

---

### Task 4: ChatView 新增 tool_call / tool_result 事件处理

**Files:**
- Modify: `src/main/resources/static/src/views/chat/ChatView.vue:122-150`

- [ ] **Step 1: 在 SSE 事件处理中新增两个 case**

在 `onDownloadProgress` 的事件解析 switch/if-else 中，`thinking` 事件处理后（第 129 行后）新增：

```typescript
} else if (event.type === 'tool_call') {
  chatStore.upsertToolCall(event.toolName, { status: 'running' })
} else if (event.type === 'tool_result') {
  const isError = event.toolOutput && event.toolOutput.startsWith('工具执行错误')
  chatStore.upsertToolCall(event.toolName, {
    status: isError ? 'error' : 'success',
    output: event.toolOutput,
  })
```

插入位置：第 130 行 `} else if (event.type === 'meta') {` 之前。

- [ ] **Step 2: 类型检查验证**

```bash
cd /Users/hbq/Codes/me/github/zephyr/src/main/resources/static
npm run type-check
```

预期：0 errors

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/src/views/chat/ChatView.vue
git commit -m "feat: ChatView 处理 tool_call/tool_result SSE 事件"
```

---

### Task 5: ToolCallCard 重构 — 计时器、折叠、始终渲染

**Files:**
- Modify: `src/main/resources/static/src/views/chat/ToolCallCard.vue`

- [ ] **Step 1: 重写 ToolCallCard 组件**

将现有 `ToolCallCard.vue` 替换为以下内容，增加折叠交互和计时器（复用 ThinkingBlock 的 timer 模式）：

```vue
<script lang="ts" setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { Icon } from '@iconify/vue'
import type { ToolCall } from '@/types/chat'
import { getLangData } from '@/i18n/locale'

const langData = getLangData()

const props = defineProps<{
  tool: ToolCall
  animating: boolean
}>()

const collapsed = ref(true)

const startTime = ref(0)
const elapsedSeconds = ref(0)
let timerId: ReturnType<typeof setInterval> | null = null

function startTimer() {
  stopTimer()
  startTime.value = Date.now()
  elapsedSeconds.value = 0
  timerId = setInterval(() => {
    elapsedSeconds.value = Math.floor((Date.now() - startTime.value) / 1000)
  }, 1000)
}

function stopTimer() {
  if (timerId) { clearInterval(timerId); timerId = null }
}

const elapsedText = computed(() => {
  const s = elapsedSeconds.value
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  const rs = s % 60
  return `${m}m${rs}s`
})

watch(() => props.animating, (v) => { if (v) startTimer(); else stopTimer() })

onMounted(() => { if (props.animating) startTimer() })
onBeforeUnmount(stopTimer)

const inputStr = computed(() => {
  if (!props.tool.input || Object.keys(props.tool.input).length === 0) return ''
  return JSON.stringify(props.tool.input, null, 2)
})

const hasDetails = computed(() => inputStr.value || props.tool.output)
</script>

<template>
  <div class="tool-call-block" :class="{ collapsed, animating }">
    <div class="tool-header" @click="collapsed = !collapsed">
      <span class="tool-text">
        <Icon icon="lucide:wrench" class="tool-icon" />
        {{ tool.name }}
        <template v-if="animating">
          <span class="dot-anim"><i>.</i><i>.</i><i>.</i></span>
        </template>
        <span v-if="elapsedSeconds > 0" class="elapsed-time">{{ elapsedText }}</span>
      </span>
      <span class="tool-status" :class="tool.status">
        <Icon v-if="tool.status === 'success'" icon="lucide:check-circle" style="font-size:11px" />
        <Icon v-else-if="tool.status === 'error'" icon="lucide:x-circle" style="font-size:11px" />
        <Icon v-else icon="lucide:loader-circle" style="font-size:11px" />
        {{ tool.status === 'success' ? langData.toolCard_success : tool.status === 'error' ? langData.toolCard_failed : langData.toolCard_running }}
      </span>
      <Icon v-if="hasDetails" :icon="collapsed ? 'lucide:chevron-down' : 'lucide:chevron-up'" class="chevron" />
    </div>
    <!-- 折叠时显示参数预览（仅 animating 且有 input 时） -->
    <div v-if="collapsed && animating && inputStr" class="tool-preview">{{ inputStr }}</div>
    <!-- 展开详情 -->
    <div v-if="!collapsed && hasDetails" class="tool-body">
      <div v-if="inputStr" class="tool-section">
        <div class="tool-section-label">输入参数</div>
        <pre class="tool-json">{{ inputStr }}</pre>
      </div>
      <div v-if="tool.output" class="tool-section">
        <div class="tool-section-label">返回结果</div>
        <pre class="tool-json">{{ tool.output }}</pre>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tool-call-block { margin: 8px 0; }
.tool-header { display: flex; align-items: center; gap: 6px; padding: 6px 0; cursor: pointer; font-size: 13px; color: var(--el-text-color-secondary); user-select: none; }
.tool-header:hover { color: var(--el-text-color-primary); }

.tool-text { display: inline-flex; align-items: baseline; gap: 6px; }
.tool-icon { color: var(--el-text-color-secondary); font-size: 14px; flex-shrink: 0; }
.elapsed-time { font-size: 11px; color: var(--el-text-color-placeholder); font-variant-numeric: tabular-nums; flex-shrink: 0; }

.tool-status { margin-left: auto; font-size: 11px; padding: 1px 8px; border-radius: 99px; display: flex; align-items: center; gap: 3px; flex-shrink: 0; }
.tool-status.success { background: rgba(93,184,114,0.12); color: var(--el-color-success); }
.tool-status.error { background: rgba(198,69,69,0.12); color: var(--el-color-danger); }
.tool-status.running { background: rgba(204,120,92,0.12); color: var(--el-color-primary); }

.chevron { transition: transform 0.2s; font-size: 14px; color: var(--el-text-color-placeholder); flex-shrink: 0; margin-left: 4px; }

.tool-preview { padding: 4px 0 4px 22px; font-size: 12px; color: var(--el-text-color-placeholder); line-height: 1.4; border-left: 2px solid var(--el-border-color); margin-left: 7px; font-family: 'JetBrains Mono', monospace; white-space: pre-wrap; word-break: break-word; max-height: 36px; overflow: hidden; position: relative; }
.tool-preview::after { content: ''; position: absolute; bottom: 0; left: 22px; right: 0; height: 24px; background: linear-gradient(to bottom, transparent, var(--el-bg-color)); pointer-events: none; }

.tool-body { padding: 8px 0 8px 22px; border-left: 2px solid var(--el-border-color); margin-left: 7px; }
.tool-section { margin-bottom: 8px; }
.tool-section:last-child { margin-bottom: 0; }
.tool-section-label { font-size: 11px; color: var(--el-text-color-placeholder); margin-bottom: 4px; font-weight: 500; }
.tool-json { font-family: 'JetBrains Mono', 'SF Mono', monospace; font-size: 12px; color: var(--el-text-color-secondary); background: var(--el-fill-color); padding: 8px 12px; border-radius: 6px; margin: 0; white-space: pre-wrap; word-break: break-all; max-height: 200px; overflow-y: auto; }
</style>

<!-- @keyframes 放在非 scoped 块 -->
<style>
.dot-anim {
  font-size: 20px;
  line-height: 1;
  vertical-align: baseline;
  margin-left: 2px;
}
.dot-anim i {
  font-style: normal;
  font-weight: 700;
  animation: dotBounce 1.4s ease-in-out infinite;
}
.dot-anim i:nth-child(1) { color: #cc785c; animation-delay: 0s; }
.dot-anim i:nth-child(2) { color: #e8a55a; animation-delay: 0.2s; }
.dot-anim i:nth-child(3) { color: #6366f1; animation-delay: 0.4s; }
@keyframes dotBounce {
  0%, 80%, 100% { opacity: 0.15; transform: translateY(0); }
  40% { opacity: 1; transform: translateY(-2px); }
}
</style>
```

- [ ] **Step 2: 类型检查验证**

```bash
cd /Users/hbq/Codes/me/github/zephyr/src/main/resources/static
npm run type-check
```

预期：0 errors

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/src/views/chat/ToolCallCard.vue
git commit -m "feat: ToolCallCard 重构 — 增加计时器、折叠展开、始终渲染"
```

---

### Task 6: MessageBubble 移除 streaming 限制、传入 animating prop

**Files:**
- Modify: `src/main/resources/static/src/views/chat/MessageBubble.vue:134-136`

- [ ] **Step 1: 修改 ToolCallCard 渲染条件**

将第 134-136 行从：

```vue
<div v-if="streaming" v-for="tc in message.toolCalls" :key="tc.name" class="mb-2">
  <ToolCallCard :tool="tc" />
</div>
```

改为：

```vue
<div v-for="tc in message.toolCalls" :key="tc.name" class="mb-2">
  <ToolCallCard :tool="tc" :animating="streaming" />
</div>
```

- [ ] **Step 2: 类型检查验证**

```bash
cd /Users/hbq/Codes/me/github/zephyr/src/main/resources/static
npm run type-check
```

预期：0 errors

- [ ] **Step 3: Commit**

```bash
git add src/main/resources/static/src/views/chat/MessageBubble.vue
git commit -m "feat: toolCalls 始终显示，不再限于 streaming 状态"
```

---

### Task 7: 集成测试验证

- [ ] **Step 1: 构建前端**

```bash
cd /Users/hbq/Codes/me/github/zephyr/src/main/resources/static
npm run build
```

预期：构建成功，无类型错误

- [ ] **Step 2: 启动后端验证编译**

```bash
cd /Users/hbq/Codes/me/github/zephyr
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn compile -DskipTests
```

预期：BUILD SUCCESS

- [ ] **Step 3: 端到端验证**（需要后端运行 + 前端构建产物）

```bash
# 启动后端（me 环境）
mvn spring-boot:run -Dspring-boot.run.profiles=me

# 构建前端并复制到 target
cd src/main/resources/static && npm run build && mkdir -p ../../../target/classes/static && cp -rf zephyr-ui ../../../target/classes/static/

# 发送测试请求（需要可用的 MCP 工具）
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/chat/send" \
  -d '{"message": "请使用 browser_snapshot 查看页面"}'
```

验证点：
1. 工具调用卡片出现在推理内容下方
2. 进行中状态显示"执行中"标签 + 实时计时 + 动画点
3. 完成后显示"成功/失败"标签 + 总耗时
4. 点击可折叠/展开查看输入参数和返回结果
5. 刷新页面后卡片仍然可见
