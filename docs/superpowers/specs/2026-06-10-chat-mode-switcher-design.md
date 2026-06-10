# 聊天模式切换器

## 概述

在输入框工具栏左侧（模型切换器之前）增加模式切换 Pill，控制 AI 的操作权限层级。模式与当前对话绑定，新对话默认重置。

## 三种模式

| 维度 | 默认 | Accept Edits | Bypass |
|------|------|-------------|--------|
| 路径限制 | 安全路径（如 `/private/tmp`） | 同默认 | 全文件系统 |
| 修改确认 | 每次需确认 | 同文件确认一次，之后自动 | 无需确认 |
| 命令执行 | 受限（白名单或禁止） | 同默认 | 全部 |
| 工具调用 | 受限（只读安全工具） | 同默认 | 全部 |

**模式切换即时生效**，切换后在 StatusBar 短暂提示。`/send` 请求中携带当前 `mode`，后端据此决定每一步操作的权限策略。

## 视觉设计

### Pill 组件

- 形状：`border-radius: 9999px`
- 字号：11px，font-weight 500（Bypass 时 600）
- 圆点直径：6px，间距 3px，内边距 `3px 10px`

| 模式 | 亮色背景 | 亮色文字 | 圆点色 | 暗色背景 | 暗色文字 |
|------|----------|----------|--------|----------|----------|
| 默认 | transparent | `#141413` | `#8e8b82` | transparent | `#a09d96` |
| Accept Edits | `rgba(232,165,90,0.12)` | `#141413` | `#e8a55a` | `rgba(232,165,90,0.14)` | `#faf9f5` |
| Bypass | `rgba(198,69,45,0.16)` | `#c64545` | `#c64545` | `rgba(198,69,45,0.22)` | `#e07373` |

颜色均来自 DESIGN.md token。

### 位置

```
[模式 Pill] [模型 ▾] [能力 ▾] [会话 ▾] [操作 ▾]            [附件] [发送]
```

## 交互设计

- **点击 Pill**：循环切换 默认 → Accept Edits → Bypass → 默认
- **Shift+Tab**：仅输入框聚焦时生效，阻止默认焦点跳转，等效点击
- **hover**：tooltip 描述当前模式含义
- **切换后**：StatusBar 短暂提示（如"已切换至 Bypass 模式"）

## 后端权限控制

### 层级模型

```
Bypass         — 无限制，所有操作直接执行
Accept Edits   — 路径限制 + 同文件单次确认 + 命令/工具受限
默认            — 路径限制 + 每次确认 + 命令/工具受限
```

### 安全路径定义

```java
// 默认 & AcceptEdits 模式下的允许路径
private static final List<String> DEFAULT_ALLOWED_PATHS = List.of(
    System.getProperty("java.io.tmpdir"),
    projectWorkspace  // 项目工作目录
);
```

### 工具分类

| 分类 | 工具示例 | 默认/AcceptEdits | Bypass |
|------|---------|-----------------|--------|
| 只读 | Read, Grep, codegraph_*, LSP | 直接执行 | 直接执行 |
| 写文件 | Write, Edit | 需确认 | 直接执行 |
| 命令 | Bash | 需确认 + 路径白名单 | 直接执行 |
| 删除 | rm, git reset | 禁止 | 允许 |
| 浏览器 | browser_* | 需确认 | 直接执行 |

### SSE 确认流程

```
LLM 返回 tool_calls
  │
  ├─ 全部只读 → 直接执行（所有模式均如此）
  │
  ├─ 包含修改操作：
  │   ├─ Bypass → 直接执行
  │   ├─ 默认 → 遍历 toolCalls：
  │   │   ├─ 只读 → 直接执行
  │   │   └─ 修改 → 发送 tool_approval SSE 事件，阻塞等待
  │   └─ AcceptEdits → 遍历 toolCalls：
  │       ├─ 只读 → 直接执行
  │       └─ 修改 → 检查文件路径是否已确认过
  │           ├─ 已确认 → 直接执行
  │           └─ 未确认 → 发送 tool_approval，阻塞等待
```

SSE `tool_approval` 事件格式：

```json
{
  "type": "tool_approval",
  "content": {
    "tools": [
      {"name": "Write", "arguments": {"file_path": "/tmp/test.txt", "content": "..."}},
      {"name": "Bash", "arguments": {"command": "npm install"}}
    ]
  }
}
```

### 确认响应接口

`POST /chat/tool-response`：

```java
{
  "conversationId": "xxx",
  "action": "approve" | "deny",
  "toolIndex": 0,           // 指定拒绝哪个（-1 表示全部）
  "note": "可以，继续"        // 用户输入的确认信息
}
```

### AcceptEdits 文件跟踪

```java
// 每个对话维护已确认文件集合
private final Map<String, Set<String>> confirmedFiles = new ConcurrentHashMap<>();

// 确认后记录：confirmedFiles.computeIfAbsent(cid, k -> new HashSet<>()).add(filePath)
// 新对话时 clear
```

## 前端状态管理

### chat store 新增

```ts
type ChatMode = 'default' | 'acceptEdits' | 'bypass'

const mode = ref<ChatMode>('default')
const pendingApprovals = ref<ToolApproval[]>([])  // 待确认工具列表

function cycleMode() {
  const order: ChatMode[] = ['default', 'acceptEdits', 'bypass']
  const idx = order.indexOf(mode.value)
  mode.value = order[(idx + 1) % order.length]
}
```

### 发送消息时携带 mode

```ts
// ChatView.vue onSend
axios({
  url: '/chat/send',
  method: 'post',
  data: {
    conversationId: convStore.currentId,
    message: text,
    mode: chatStore.mode
  }
})
```

### 确认信息在聊天区展示

前端收到 `tool_approval` 事件后，在聊天区渲染内联确认卡片（使用现有 `ToolCallCard` 组件风格），显示待确认的工具名称和参数。用户直接在输入框输入确认/拒绝文本，前端判断意图后调用 `/chat/tool-response`。

## 需修改的文件

### 前端（5 个文件）

| 文件 | 变更 |
|------|------|
| `src/types/chat.ts` | 新增 `ChatMode`、`ToolApproval` 类型 |
| `src/store/chat.ts` | 新增 `mode`、`pendingApprovals`、`cycleMode()` |
| `src/views/chat/InputArea.vue` | 新增 Pill UI + Shift+Tab + 暗黑样式 |
| `src/views/chat/ChatView.vue` | `onSend` 携带 mode；`tool_approval` SSE 事件处理 |
| `src/views/chat/ToolCallCard.vue` | 新增待确认状态（waiting 样式） |

### 后端（4 个文件）

| 文件 | 变更 |
|------|------|
| `chat/ctrl/ChatCtrl.java` | `sendMessage` + `toolResponse` 接口；接收 mode 参数 |
| `chat/service/impl/ChatServiceImpl.java` | 权限检查 + 确认等待 + AcceptEdits 文件跟踪 |
| `chat/model/ChatRequest.java` | 新增 `mode` 字段 |
| `chat/model/ChatEvent.java` | 新增 `tool_approval` 事件类型 |

## 不做

- 不弹窗（确认信息内联在聊天区）
- 不持久化到用户设置（与对话绑定）
- 不限制模式切换（随时可切）
- 不在侧边栏显示模式信息
