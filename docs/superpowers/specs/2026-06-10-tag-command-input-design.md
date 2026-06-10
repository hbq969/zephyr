# Tag 标签命令输入设计

## 动机

当前输入框使用原生 `<textarea>`，MCP 工具和 Skill 技能命令以纯文本（如 `/toolName`）插入，与普通文字混淆不易区分。目标：工具命令以 tag 标签形式展示，带能力类型前缀（如 `MCP/toolName`），配色来自 DESIGN.md 设计系统。

## 命令行为三分法

| 类型 | 触发方式 | 输入框表现 | 发送格式 | 后端处理 |
|------|---------|-----------|---------|---------|
| MCP 工具 | 能力菜单点选 | 插入 `[MCP/toolName]` tag，可加文字后发送 | 序列化文本 `MCP/toolName` | LLM 识别 → dispatchTools() |
| Skill 技能 | 能力菜单点选 | 插入 `[Skill/skillName]` tag，可加文字后发送 | 序列化文本 `Skill/skillName` | LLM 识别 → dispatchTools() |
| 会话/操作 | 对应菜单点选 | **不插入**，选中即发送 | `/clear`、`/help` 等 | handleSlashCommand() 直接处理 |

## 前端：InputArea.vue

### 输入机制

将 `<textarea>` 替换为 `<div contenteditable="true">`，原生支持内嵌 HTML 元素。placeholder 改为 `"Ctrl+Enter 发送 · Enter 换行"`。

### Tag 元素

tag 为 `<span contenteditable="false">`，不可编辑、不可聚焦。

**DOM 结构：**
```html
<span contenteditable="false" data-type="mcp" data-name="getTenantRiskProfile"
      class="cmd-tag cmd-tag--mcp">
  <span class="cmd-tag__prefix">MCP</span>
  <span class="cmd-tag__sep">/</span>
  <span class="cmd-tag__name">getTenantRiskProfile</span>
</span>
```

**配色（来源 DESIGN.md）：**

| 元素 | MCP | Skill |
|------|-----|-------|
| 前缀文字 | accent-teal #5db8a6 | accent-amber #e8a55a |
| tag 底色 | surface-card #efe9de | surface-card #efe9de |
| 分隔符 / | muted-soft #8e8b82 | muted-soft #8e8b82 |
| 工具/技能名 | ink #141413 | ink #141413 |
| 边框 | hairline #e6dfd8 | hairline #e6dfd8 |
| 圆角 | rounded.sm 6px | rounded.sm 6px |

### 行为规格

| 操作 | 行为 |
|------|------|
| 从菜单点击插入 | 创建 tag 节点，插入光标位置，光标移到 tag 后 |
| Backspace（光标在 tag 后） | 删除整颗 tag |
| Delete（光标在 tag 前） | 删除整颗 tag |
| 左右方向键 | 正常移动，tag 为原子单位（浏览器默认 contenteditable=false 行为） |
| Ctrl+Enter | 发送消息 |
| Enter | 换行（插入 `<br>`） |
| 序列化（发送时） | 遍历 DOM 子节点，tag → `数据类型/工具名`，文本节点保持原样，拼接为纯文本发送 |

### 按键处理

```typescript
function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); doSend() }
  // Enter without modifier → default behavior (newline in contenteditable)
}
```

### 会话/操作命令

`insertCommand()` 对会话/操作类命令（`/clear`、`/help`、`/context`）不插入到输入框，而是直接 emit 事件给父组件 `ChatView`，`ChatView` 将其作为普通消息发送：

```typescript
// 会话/操作命令：直接发送，不走输入框
function insertCommand(cmd: string) {
  if (cmd.startsWith('/')) {
    emit('send', cmd)  // 直接发送
    closeAll()
    return
  }
  // MCP/Skill：创建 tag 节点插入光标位置
  createTagElement(type, name)
  closeAll()
}
```

### 暗黑模式

```css
html.dark .cmd-tag {
  background-color: var(--el-fill-color-light);
  border-color: var(--el-border-color);
}
html.dark .cmd-tag__name {
  color: var(--el-text-color-primary);
}
```

## 后端：ContextBuilder.java

### ROLE_PROMPT 更新

在现有 ROLE_PROMPT 中"命令约定"部分增加新前缀格式（保留旧 `/` 格式作为手动输入兼容）：

```
## 命令约定
当用户消息中以下列格式引用工具或技能时，必须调用对应工具，禁止只回复文字而不调用工具：

### 前缀格式（tag 插入）
- `MCP/工具名` → 调用同名 MCP 工具
- `Skill/技能名` → 调用 use_skill(skill_name="技能名")
- `Memory/记忆名` → 调用 use_memory(memory_name="记忆名")

### 斜杠格式（手动输入，兼容保留）
- `/工具名`（如 `/browser_navigate`）→ 调用同名 MCP 工具
- `/技能名`（如 `/frontend-design`）→ 调用 use_skill(skill_name="技能名") 加载该技能
- `/记忆名` → 调用 use_memory(memory_name="记忆名") 查看该记忆
```

### ChatServiceImpl.java

**不改。** handleSlashCommand() 保持原样，继续处理 `/clear`、`/help`、`/context` 等会话/操作命令。

## 不改的文件

- `ChatView.vue`、`ChatSidebar.vue`、`MessageBubble.vue`、`CommandPalette.vue` 等所有其他聊天组件
- `ChatServiceImpl.java`（handleSlashCommand 不变）
- MCP/Skill 的 DAO/Service/Controller 层
- 下拉菜单的加载逻辑（`loadMcpTools()`、`loadSkills()`）

## 测试验证

1. **Tag 插入**：从 MCP 菜单点选工具 → 输入框出现 `MCP/toolName` tag → 继续输入文字 → 发送 → curl 确认后端收到序列化文本
2. **Tag 删除**：插入 tag → 光标在 tag 后按 Backspace → tag 整体删除
3. **Tag 暗黑模式**：切换暗黑主题 → tag 配色正确切换
4. **会话命令直接执行**：从会话菜单点选 `/context` → 直接发送消息，不走 tag 流程 → 后端返回上下文占比信息
5. **LLM 工具识别**：发送含 `MCP/browser_navigate` 的消息 → LLM 正确调用 browser_navigate 工具
