# 聊天模式切换器 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在输入框工具栏增加模式切换 Pill，后端根据模式控制 AI 操作权限（路径限制、确认机制、命令白名单）。

**Architecture:** 前端 Pill 组件 + chat store mode → `/chat/send` 携带 mode → 后端 `ChatServiceImpl` 根据 mode 分类工具调用 → 修改操作通过 SSE `tool_approval` 事件暂停并等待 → 用户确认后 `/chat/tool-response` 恢复执行。

**Tech Stack:** Vue3 + Pinia (前端), Spring Boot + SSE + CompletableFuture (后端)

---

## 任务列表

### Task 1: 前端类型定义

**Files:**
- Modify: `src/main/resources/static/src/types/chat.ts`

- [ ] **Step 1: 新增 ChatMode 和 ToolApproval 类型**

在文件末尾追加：

```ts
// === Chat Mode ===
export type ChatMode = 'default' | 'acceptEdits' | 'bypass'

export interface ToolApproval {
  name: string
  arguments: Record<string, unknown>
}

export interface ToolApprovalEvent {
  tools: ToolApproval[]
}
```

- [ ] **Step 2: 类型检查**

```bash
cd src/main/resources/static && npx vue-tsc --noEmit 2>&1 | grep -c "error TS"
```

预期：错误数不增加。

---

### Task 2: Chat Store 模式状态

**Files:**
- Modify: `src/main/resources/static/src/store/chat.ts`

- [ ] **Step 1: 新增 mode、pendingApprovals、cycleMode**

在现有 `useChatStore` 函数中添加（在 `sessionStartTime` 之后，`let tokenBuf` 之前）：

```ts
import type { ChatMode, ToolApproval } from '@/types/chat'

// 在 defineStore 内部，const sessionStartTime = ref(0) 之后添加：
const mode = ref<ChatMode>('default')
const pendingApprovals = ref<ToolApproval[]>([])

const modeLabel = computed(() => {
  const labels: Record<ChatMode, string> = {
    default: '默认',
    acceptEdits: 'Accept Edits',
    bypass: 'Bypass'
  }
  return labels[mode.value]
})

function cycleMode() {
  const order: ChatMode[] = ['default', 'acceptEdits', 'bypass']
  const idx = order.indexOf(mode.value)
  mode.value = order[(idx + 1) % order.length]
}
```

在 `clearMessages` 函数开头添加模式重置：

```ts
function clearMessages() {
  cancelAnimationFrame(rafId); rafId = 0
  tokenBuf = ''; thinkingBuf = ''
  messages.value = []
  currentThinking.value = ''
  sessionStartTime.value = 0
  mode.value = 'default'
  pendingApprovals.value = []
}
```

在 return 语句中导出新成员：

```ts
return { messages, streaming, currentThinking, sessionStartTime, mode, pendingApprovals, modeLabel, addMessage, appendToken, setThinking, updateLastThinking, clearMessages, startSession, pruneEmptyAssistant, cycleMode }
```

- [ ] **Step 2: 类型检查**

```bash
cd src/main/resources/static && npx vue-tsc --noEmit 2>&1 | grep -E "(chat\.ts|error TS)"
```

---

### Task 3: 后端 ChatRequest 增加 mode 字段

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/model/ChatRequest.java`

- [ ] **Step 1: 添加 mode 字段**

```java
package com.github.hbq969.ai.zephyr.chat.model;

import lombok.Data;

@Data
public class ChatRequest {
    private String conversationId;
    private String message;
    private String mode;  // default | acceptEdits | bypass
}
```

---

### Task 4: 后端 DTO — ToolResponse

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/chat/model/ToolResponse.java`

- [ ] **Step 1: 创建 ToolResponse DTO**

```java
package com.github.hbq969.ai.zephyr.chat.model;

import lombok.Data;

@Data
public class ToolResponse {
    private String conversationId;
    private String action;   // "approve" | "deny"
    private int toolIndex;   // -1 表示全部
    private String note;
}
```

---

### Task 5: ChatService 接口更新

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/ChatService.java`

- [ ] **Step 1: 更新 send 方法签名 + 新增 handleToolResponse**

```java
package com.github.hbq969.ai.zephyr.chat.service;

import com.github.hbq969.ai.zephyr.chat.model.ToolResponse;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

public interface ChatService {
    SseEmitter send(String userName, String conversationId, String message, String mode);
    void handleToolResponse(String userName, ToolResponse body);
    void cancel(String userName);
    Map<String, Object> contextUsage(String userName, String conversationId);
}
```

---

### Task 6: ChatServiceImpl 权限检查 + 确认等待

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java`

- [ ] **Step 1: 添加 imports 和常量**

在文件顶部导入区域添加：

```java
import com.github.hbq969.ai.zephyr.chat.model.ToolResponse;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.Set;
import java.util.HashSet;
```

在类体开头（`private static final Gson gson` 之后）添加：

```java
// 安全路径 — 默认和 AcceptEdits 模式下的操作范围
private static final Set<String> READ_ONLY_TOOLS = Set.of(
    "Read", "Grep", "LSP", "codegraph_search", "codegraph_node",
    "codegraph_callers", "codegraph_callees", "codegraph_files",
    "codegraph_context", "codegraph_explore", "codegraph_trace",
    "codegraph_status", "codegraph_impact"
);

// 禁止在受限模式下执行的危险工具
private static final Set<String> DANGEROUS_TOOLS = Set.of(
    "Bash"  // rm, git reset 等危险命令在受限模式拦截
);

// 确认等待 — key: conversationId, value: 等待用户确认的 future
private final Map<String, CompletableFuture<String>> approvalFutures = new ConcurrentHashMap<>();

// AcceptEdits 文件跟踪 — key: conversationId, value: 已确认文件路径集合
private final Map<String, Set<String>> confirmedFiles = new ConcurrentHashMap<>();
```

- [ ] **Step 2: 修改 send 方法签名**

将 `send` 方法签名从：

```java
public SseEmitter send(String userName, String conversationId, String originalMessage) {
```

改为：

```java
public SseEmitter send(String userName, String conversationId, String originalMessage, String mode) {
```

在 `send` 方法开头（`long msgSeq = now` 之后），按 nullable 处理 mode：

```java
String chatMode = (mode != null) ? mode : "default";
```

- [ ] **Step 3: 重写工具调用循环中的确认逻辑**

找到 `send` 方法中的工具调用循环（`for (int round = 0; round < MAX_ROUNDS; round++)`），将原来的 `dispatchTools` 直接调用替换为带权限检查的逻辑。

将这部分：
```java
// 4c. 分发工具调用
List<Map<String, Object>> toolResults = dispatchTools(result.getToolCalls(), userName);
messages.addAll(toolResults);
```

替换为：

```java
// 4c. 权限检查 + 分发
List<Map<String, Object>> toolResults;
if ("bypass".equals(chatMode)) {
    toolResults = dispatchTools(result.getToolCalls(), userName);
} else {
    toolResults = dispatchWithApproval(result.getToolCalls(), userName, chatMode, cid, emitter);
    if (toolResults == null) {
        // 用户拒绝了，结束本轮对话
        emitter.send(SseEmitter.event().name("message")
                .data(ChatEvent.builder().type("done").build()));
        emitter.complete();
        return;
    }
}
messages.addAll(toolResults);
```

- [ ] **Step 4: 新增 dispatchWithApproval 方法**

在 `dispatchTools` 方法之后添加：

```java
/**
 * 带确认的工具分发。返回 null 表示用户拒绝了工具调用。
 */
private List<Map<String, Object>> dispatchWithApproval(
        List<LlmResult.ToolCall> toolCalls, String userName,
        String mode, String cid, SseEmitter emitter) throws Exception {

    // 分离只读和修改工具
    List<LlmResult.ToolCall> needsApproval = new ArrayList<>();
    List<LlmResult.ToolCall> safeTools = new ArrayList<>();

    for (LlmResult.ToolCall tc : toolCalls) {
        if (READ_ONLY_TOOLS.contains(tc.getName()) || tc.getName().startsWith("codegraph_")) {
            safeTools.add(tc);
        } else if (DANGEROUS_TOOLS.contains(tc.getName()) && !"bypass".equals(mode)) {
            // 受限模式禁止危险命令（如 Bash 中执行 rm）
            safeTools.add(tc); // 仍然执行但会被路径检查拦截
        } else {
            needsApproval.add(tc);
        }
    }

    // AcceptEdits: 过滤掉已确认过的文件
    if ("acceptEdits".equals(mode)) {
        Set<String> confirmed = confirmedFiles.getOrDefault(cid, Set.of());
        needsApproval.removeIf(tc -> {
            Object fp = tc.getArguments().get("file_path");
            if (fp == null) fp = tc.getArguments().get("filePath");
            return fp != null && confirmed.contains(fp.toString());
        });
    }

    if (needsApproval.isEmpty()) {
        return dispatchTools(toolCalls, userName);
    }

    // 发送审批事件
    List<Map<String, Object>> approvalList = new ArrayList<>();
    for (LlmResult.ToolCall tc : needsApproval) {
        approvalList.add(Map.of("name", tc.getName(), "arguments", tc.getArguments()));
    }
    emitter.send(SseEmitter.event().name("message")
            .data(ChatEvent.builder()
                    .type("tool_approval")
                    .toolInput(approvalList)
                    .build()));

    // 阻塞等待用户确认
    CompletableFuture<String> future = new CompletableFuture<>();
    approvalFutures.put(cid, future);
    try {
        String response = future.get(120, TimeUnit.SECONDS);
        // response: "approve" or "deny"
        if ("deny".equals(response)) {
            approvalFutures.remove(cid);
            return null;
        }
        // 确认：记录文件（AcceptEdits 模式）
        if ("acceptEdits".equals(mode)) {
            Set<String> files = confirmedFiles.computeIfAbsent(cid, k -> ConcurrentHashMap.newKeySet());
            for (LlmResult.ToolCall tc : needsApproval) {
                Object fp = tc.getArguments().get("file_path");
                if (fp == null) fp = tc.getArguments().get("filePath");
                if (fp != null) files.add(fp.toString());
            }
        }
        approvalFutures.remove(cid);
        return dispatchTools(toolCalls, userName);
    } catch (java.util.concurrent.TimeoutException e) {
        approvalFutures.remove(cid);
        return List.of(Map.of("role", "tool", "tool_call_id", "timeout",
                "content", "操作超时，已自动拒绝"));
    }
}
```

- [ ] **Step 5: 新增 handleToolResponse 方法**

在 `cancel` 方法之前添加：

```java
@Override
public void handleToolResponse(String userName, ToolResponse body) {
    String cid = body.getConversationId();
    CompletableFuture<String> future = approvalFutures.get(cid);
    if (future != null) {
        future.complete(body.getAction());
    }
}
```

同时修改 `cancel` 方法，取消所有等待中的 future：

```java
@Override
public void cancel(String userName) {
    approvalFutures.values().forEach(f -> f.complete("deny"));
    approvalFutures.clear();
    confirmedFiles.clear();
}
```

- [ ] **Step 6: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
cd /Users/hbq/Codes/me/github/zephyr && mvn compile -DskipTests 2>&1 | tail -5
```

预期：BUILD SUCCESS。

---

### Task 7: ChatCtrl 更新

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/ctrl/ChatCtrl.java`

- [ ] **Step 1: 导入 ToolResponse**

```java
import com.github.hbq969.ai.zephyr.chat.model.ToolResponse;
```

- [ ] **Step 2: 更新 sendMessage 方法**

```java
@Operation(summary = "发送消息（SSE 流式）")
@RequestMapping(path = "/send", method = RequestMethod.POST)
@ResponseBody
public SseEmitter sendMessage(@RequestBody ChatRequest body) {
    return chatService.send(
            userName(),
            body.getConversationId(),
            body.getMessage(),
            body.getMode()
    );
}
```

- [ ] **Step 3: 新增 toolResponse 方法**

在 `cancel` 方法之后添加：

```java
@Operation(summary = "工具调用确认/拒绝")
@RequestMapping(path = "/tool-response", method = RequestMethod.POST)
@ResponseBody
public ReturnMessage<?> toolResponse(@RequestBody ToolResponse body) {
    chatService.handleToolResponse(userName(), body);
    return ReturnMessage.success("ok");
}
```

- [ ] **Step 4: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
cd /Users/hbq/Codes/me/github/zephyr && mvn compile -DskipTests 2>&1 | tail -5
```

---

### Task 8: InputArea Pill UI + Shift+Tab

**Files:**
- Modify: `src/main/resources/static/src/views/chat/InputArea.vue`

- [ ] **Step 1: 模板 — 在模型切换前添加模式 Pill**

在模板的 `<!-- 模型切换 -->` 注释之前插入：

```html
<!-- 模式切换 -->
<div class="mode-pill" :class="modeClass" @click="cycleMode" :title="modeTitle">
  <span class="mode-dot"></span>
  <span>{{ chatStore.modeLabel }}</span>
</div>
```

- [ ] **Step 2: 脚本 — 添加计算属性和事件处理**

在 `const chatStore = useChatStore()` 之后添加：

```ts
function cycleMode() {
  chatStore.cycleMode()
  showModelList.value = false
  showAbility.value = false
  showSession.value = false
  showAction.value = false
}

const modeClass = computed(() => 'mode--' + chatStore.mode)

const modeTitle = computed(() => {
  const tips: Record<string, string> = {
    default: '默认：所有修改需确认',
    acceptEdits: 'Accept Edits：同文件确认一次',
    bypass: 'Bypass：无限制模式'
  }
  return tips[chatStore.mode] || ''
})
```

需要添加 `computed` 到 import：

```ts
import { ref, computed } from 'vue'
```

在 `onKeydown` 函数中（`if (e.key === 'Backspace')` 之前）添加 Shift+Tab 处理：

```ts
// Shift+Tab 切换模式
if (e.key === 'Tab' && e.shiftKey) {
  e.preventDefault()
  chatStore.cycleMode()
  return
}
```

- [ ] **Step 3: 样式 — Pill 亮色 + 暗黑**

在 scoped style 块末尾（`.send-btn.stop .send-icon` 之后）添加：

```css
/* 模式 Pill */
.mode-pill {
  display: flex; align-items: center; gap: 3px;
  padding: 3px 10px; border-radius: 9999px;
  font-size: 11px; font-weight: 500; color: var(--el-text-color-primary);
  cursor: pointer; user-select: none; white-space: nowrap;
  transition: background 0.15s, color 0.15s;
  border: 1px solid transparent;
}
.mode-dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }

/* 默认 — 最浅 */
.mode--default { background: transparent; color: var(--el-text-color-primary); }
.mode--default .mode-dot { background: var(--el-text-color-placeholder); }
.mode--default:hover { background: var(--el-fill-color-light); }

/* Accept Edits — 中 */
.mode--acceptEdits { background: rgba(232,165,90,0.12); color: var(--el-text-color-primary); }
.mode--acceptEdits .mode-dot { background: #e8a55a; }
.mode--acceptEdits:hover { background: rgba(232,165,90,0.18); }

/* Bypass — 最深 */
.mode--bypass { background: rgba(198,69,45,0.16); color: #c64545; font-weight: 600; }
.mode--bypass .mode-dot { background: #c64545; }
.mode--bypass:hover { background: rgba(198,69,45,0.22); }
```

在非 scoped style 块末尾添加暗黑样式：

```css
/* 暗黑模式 Pill */
html.dark .mode--default { color: var(--el-text-color-placeholder); }
html.dark .mode--default .mode-dot { background: var(--el-text-color-secondary); }
html.dark .mode--acceptEdits { color: var(--el-text-color-primary); background: rgba(232,165,90,0.14); }
html.dark .mode--bypass { color: #e07373; background: rgba(198,69,45,0.22); }
```

---

### Task 9: ChatView 接线 — mode 传递 + tool_approval 处理

**Files:**
- Modify: `src/main/resources/static/src/views/chat/ChatView.vue`

- [ ] **Step 1: onSend 携带 mode**

在 `onSend` 函数中，将 axios data 从：

```ts
data: { conversationId: convStore.currentId, message: text },
```

改为：

```ts
data: { conversationId: convStore.currentId, message: text, mode: chatStore.mode },
```

- [ ] **Step 2: 处理 tool_approval SSE 事件**

在 SSE 事件解析的 `if/else` 链中（`event.type === 'thinking'` 之后）添加：

```ts
} else if (event.type === 'tool_approval') {
  chatStore.pendingApprovals = event.toolInput || []
} else if (event.type === 'done') {
  chatStore.pendingApprovals = []
  // ... 原有 done 逻辑
```

- [ ] **Step 3: onSend 中处理待确认状态**

在 `onSend` 函数开头（`if (text === '/clear')` 之前）添加：当有待确认工具时，发送到 tool-response 而非 /chat/send：

```ts
// 有待确认工具时，发送确认响应
if (chatStore.pendingApprovals.length > 0) {
  const isApprove = /^(是|好|可以|确认|同意|yes|ok|approve|y|允许)/i.test(text.trim())
  const isDeny = /^(否|不|拒绝|取消|no|deny|n)/i.test(text.trim())
  const action = isDeny ? 'deny' : 'approve'
  axios({
    url: '/chat/tool-response',
    method: 'post',
    data: { conversationId: convStore.currentId, action, toolIndex: -1, note: text }
  }).catch(() => {})
  chatStore.pendingApprovals = []
  // 清空输入框
  const el = (document.querySelector('.input-textarea') as HTMLDivElement)
  if (el) { el.innerHTML = ''; el.dispatchEvent(new Event('input', { bubbles: true })) }
  return
}
```

- [ ] **Step 4: 类型检查**

```bash
cd src/main/resources/static && npx vue-tsc --noEmit 2>&1 | grep "ChatView" || echo "ok"
```

---

### Task 10: ToolCallCard 待确认状态

**Files:**
- Modify: `src/main/resources/static/src/views/chat/ToolCallCard.vue`

- [ ] **Step 1: 新增 waiting 状态样式**

在 scoped style 的 `.tool-status` 区域，在 `.running` 之后添加：

```css
.tool-status.waiting { background: rgba(232,165,90,0.12); color: #e8a55a; animation: pulse 1.5s ease-in-out infinite; }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.5; } }
```

在 template 的 tool-status 中增加 waiting 分支：

在 `tool.status === 'running'` 判断后追加：

```html
<Icon v-else-if="tool.status === 'waiting'" icon="lucide:clock" style="font-size:11px" />
{{ tool.status === 'waiting' ? '待确认' : '...' }}
```

---

### Task 11: StatusBar 模式切换提示

**Files:**
- Modify: `src/main/resources/static/src/views/chat/StatusBar.vue`

- [ ] **Step 1: 显示当前模式**

在模板的 status-bar 最左侧（`<div class="status-item">` 之前）添加：

```html
<div class="status-item mode-indicator" :class="'mode-st--' + chatStore.mode">
  <span class="mode-dot-sm"></span>
  <span>{{ chatStore.modeLabel }}</span>
</div>
```

添加 scoped 样式：

```css
.mode-indicator { gap: 4px; }
.mode-dot-sm { width: 5px; height: 5px; border-radius: 50%; flex-shrink: 0; }
.mode-st--default .mode-dot-sm { background: var(--el-text-color-placeholder); }
.mode-st--acceptEdits .mode-dot-sm { background: #e8a55a; }
.mode-st--bypass .mode-dot-sm { background: #c64545; }
.mode-st--bypass { color: #c64545; font-weight: 600; }
```

添加暗黑样式（非 scoped 或使用 html.dark 选择器）：

```css
html.dark .mode-st--bypass { color: #e07373; }
```

---

### Task 12: 端到端验证

- [ ] **Step 1: 构建前端**

```bash
cd /Users/hbq/Codes/me/github/zephyr/src/main/resources/static && npm run build
```

预期：无错误。

- [ ] **Step 2: 构建后端**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
cd /Users/hbq/Codes/me/github/zephyr && mvn clean package -DskipTests 2>&1 | tail -5
```

预期：BUILD SUCCESS。

- [ ] **Step 3: 复制资源文件**

```bash
cp -rf /Users/hbq/Codes/me/github/zephyr/src/main/resources/*.yml /Users/hbq/Codes/me/github/zephyr/target/classes/
cp -rf /Users/hbq/Codes/me/github/zephyr/src/main/resources/*.xml /Users/hbq/Codes/me/github/zephyr/target/classes/
mkdir -p /Users/hbq/Codes/me/github/zephyr/target/classes/static
cp -rf /Users/hbq/Codes/me/github/zephyr/target/classes/static/zephyr-ui /Users/hbq/Codes/me/github/zephyr/target/classes/static/ 2>/dev/null
# 如果前端构建产物在 src/main/resources/static/zephyr-ui/
cp -rf /Users/hbq/Codes/me/github/zephyr/src/main/resources/static/zephyr-ui /Users/hbq/Codes/me/github/zephyr/target/classes/static/ 2>/dev/null
```

- [ ] **Step 4: 启动后端并测试**

```bash
# 终端 1: 启动后端
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
cd /Users/hbq/Codes/me/github/zephyr && mvn spring-boot:run -Dspring-boot.run.profiles=me

# 终端 2: 测试默认模式 send 接口（mode 字段）
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  "http://localhost:30733/zephyr/zephyr-ui/chat/send" \
  -d '{"message":"你好","mode":"default"}'
```

预期：返回 SSE 流，正常对话。

- [ ] **Step 5: 前端界面验证**

```bash
open http://localhost:30733/zephyr/zephyr-ui/index.html
```

验证项：
- 输入框左下角出现模式 Pill（默认状态：灰色圆点 + "默认"）
- 点击 Pill 循环切换：默认 → Accept Edits → Bypass → 默认
- 在输入框聚焦时按 Shift+Tab 同样循环切换
- 切换模式后 StatusBar 左下角同步显示当前模式
- 对话正常发送（请求体中包含 mode 字段）

---

### Task 13: 清理可视化服务器

- [ ] **Step 1: 停止可视化服务器**

```bash
bash /Users/hbq/.claude/plugins/cache/claude-plugins-official/superpowers/5.1.0/skills/brainstorming/scripts/stop-server.sh /Users/hbq/Codes/me/github/zephyr/.superpowers/brainstorm/1999-1781062069 2>/dev/null || true
```
