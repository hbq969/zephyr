# Tag 标签命令输入实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将聊天输入框从 textarea 改为 contenteditable div，MCP/Skill 工具命令以 tag 标签插入，Ctrl+Enter 发送。

**Architecture:** 前端 InputArea.vue 用 contenteditable div 替代 textarea，tag 为 `contenteditable="false"` span 元素。序列化时遍历 DOM 提取纯文本。后端只改 ContextBuilder.java 的 ROLE_PROMPT。

**Tech Stack:** Vue 3 + TypeScript + contenteditable API / Spring Boot + Java 17

---

### Task 1: 更新 ContextBuilder ROLE_PROMPT

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/ContextBuilder.java:45-63`

- [ ] **Step 1: 替换 ROLE_PROMPT 中的"命令约定"部分**

找到 `ROLE_PROMPT` 常量（约第 45 行），将现有的：

```java
            ## 命令约定
            当用户消息以 `/` 开头时，这是一个工具调用快捷命令，你必须调用对应工具：
            - `/工具名`（如 `/browser_navigate`）→ 直接调用同名 MCP 工具，不要用文字回复
            - `/技能名`（如 `/frontend-design`）→ 调用 use_skill(skill_name="技能名") 加载该技能
            - `/记忆名` → 调用 use_memory(memory_name="记忆名") 查看该记忆
            收到 `/` 命令后必须调用工具，禁止只回复文字而不调用工具。
```

替换为：

```java
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

注意保持原有的缩进（12 个空格缩进，与 ROLE_PROMPT text block 对齐）。

- [ ] **Step 2: 编译验证**

```bash
cd /Users/hbq/Codes/me/github/zephyr && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/ContextBuilder.java
git commit -m "feat: 更新系统提示词，支持 MCP/、Skill/ 前缀格式的命令约定"
```

---

### Task 2: textarea 替换为 contenteditable div

**Files:**
- Modify: `src/main/resources/static/src/views/chat/InputArea.vue`

- [ ] **Step 1: 替换模板中的 textarea 为 contenteditable div**

找到模板中的 `<textarea>`（约第 128-136 行），替换为：

```html
<div
  ref="inputRef"
  class="input-textarea"
  contenteditable="true"
  @keydown="onKeydown"
  @input="onInput"
  data-placeholder="Ctrl+Enter 发送 · Enter 换行"
></div>
```

- [ ] **Step 2: 移除 v-model，改用 DOM 操作**

删除 `const text = ref('')`（第 10 行）。

替换 `onInput` 函数（原第 34-37 行）：

```typescript
function onInput() {
  const el = inputRef.value
  if (el) {
    // 如果内容为空，清空内部 HTML 以保持 placeholder 显示
    if (el.innerText.trim() === '') {
      el.innerHTML = ''
    }
  }
}
```

`onInput` 中移除 auto-height 逻辑（contenteditable 自然扩展高度）。

- [ ] **Step 3: 添加 placeholder CSS**

在当前 `<style scoped>` 块中的 `.input-textarea` 样式后添加 `:empty:before` 伪元素：

在 `.input-textarea::placeholder` 样式（第 250 行）之后，替换为：

```css
.input-textarea[data-placeholder]:empty:before {
  content: attr(data-placeholder);
  color: var(--el-text-color-placeholder);
  pointer-events: none;
}
```

同时删除原有的 `.input-textarea::placeholder` 样式块。

- [ ] **Step 4: 类型检查**

```bash
cd src/main/resources/static && npx vue-tsc --noEmit 2>&1 | head -30
```

Expected: No errors related to InputArea.vue

- [ ] **Step 5: 提交**

```bash
git add src/main/resources/static/src/views/chat/InputArea.vue
git commit -m "feat: 输入框从 textarea 切换为 contenteditable div"
```

---

### Task 3: 实现 tag DOM 创建

**Files:**
- Modify: `src/main/resources/static/src/views/chat/InputArea.vue`

- [ ] **Step 1: 给能力列表数据添加类型标记**

当前 `insertCommand` 接收的是纯字符串（如 `/toolName`）。需要改为传递类型+名称。

找到 `mcpGroups` 中 tool 的点击处理（约第 168 行）：

```html
<div v-for="t in g.tools" :key="t.name" class="pick-option sub-option" @click="insertCommand('/' + t.name)">
```

改为：

```html
<div v-for="t in g.tools" :key="t.name" class="pick-option sub-option" @click="insertTag('mcp', t.name)">
```

找到 skillList 的点击处理（约第 178 行）：

```html
<div v-for="s in skillList" :key="s.name" class="pick-option sub-option" @click="insertCommand('/' + s.name)">
```

改为：

```html
<div v-for="s in skillList" :key="s.name" class="pick-option sub-option" @click="insertTag('skill', s.name)">
```

- [ ] **Step 2: 实现 insertTag 函数**

在 `insertCommand` 函数之后添加 `insertTag` 函数：

```typescript
function insertTag(type: 'mcp' | 'skill', name: string) {
  const el = inputRef.value
  if (!el) return

  el.focus()

  const sel = window.getSelection()
  if (!sel) return

  // 如果选区不在 contenteditable 内，把光标放到末尾
  if (sel.rangeCount === 0 || !el.contains(sel.anchorNode)) {
    const range = document.createRange()
    range.selectNodeContents(el)
    range.collapse(false)
    sel.removeAllRanges()
    sel.addRange(range)
  }

  const prefix = type === 'mcp' ? 'MCP' : 'Skill'
  const tag = document.createElement('span')
  tag.contentEditable = 'false'
  tag.className = `cmd-tag cmd-tag--${type}`
  tag.setAttribute('data-type', type)
  tag.setAttribute('data-name', name)
  tag.innerHTML = `<span class="cmd-tag__prefix">${prefix}</span><span class="cmd-tag__sep">/</span><span class="cmd-tag__name">${name}</span>`

  const range = sel.getRangeAt(0)
  range.deleteContents()
  range.insertNode(tag)

  // 在 tag 后面放一个空格，光标移过去
  const space = document.createTextNode(' ')
  range.setStartAfter(tag)
  range.collapse(true)
  tag.after(space)
  range.setStartAfter(space)
  range.collapse(true)
  sel.removeAllRanges()
  sel.addRange(range)

  el.dispatchEvent(new Event('input', { bubbles: true }))
  closeAll()
}
```

- [ ] **Step 3: 处理 Backspace 删除 tag**

在 `onKeydown` 函数开始处（`const el = inputRef.value` 之后）添加 Backspace 处理：

```typescript
function onKeydown(e: KeyboardEvent) {
  // Backspace 删除 tag：光标在文本节点开头且前一个兄弟是 tag
  if (e.key === 'Backspace') {
    const sel = window.getSelection()
    if (sel && sel.rangeCount > 0) {
      const range = sel.getRangeAt(0)
      if (range.collapsed && range.startOffset === 0) {
        const prev = range.startContainer.previousSibling
        if (prev && prev.nodeType === Node.ELEMENT_NODE && (prev as Element).classList.contains('cmd-tag')) {
          e.preventDefault()
          prev.remove()
          el.dispatchEvent(new Event('input', { bubbles: true }))
          return
        }
      }
    }
  }

  // ... 现有 Enter 处理保持不变（后面 Task 5 会改成 Ctrl+Enter）
}
```

- [ ] **Step 4: 类型检查**

```bash
cd src/main/resources/static && npx vue-tsc --noEmit 2>&1 | head -30
```

Expected: No errors

- [ ] **Step 5: 提交**

```bash
git add src/main/resources/static/src/views/chat/InputArea.vue
git commit -m "feat: 实现 MCP/Skill 工具命令的 tag DOM 创建和 Backspace 删除"
```

---

### Task 4: 实现 tag 序列化（doSend）

**Files:**
- Modify: `src/main/resources/static/src/views/chat/InputArea.vue`

- [ ] **Step 1: 重写 doSend 函数**

替换现有的 `doSend` 函数（约第 43-49 行）：

```typescript
function doSend() {
  const el = inputRef.value
  if (!el) return

  // 遍历 contenteditable 子节点，提取纯文本
  const parts: string[] = []
  for (const child of Array.from(el.childNodes)) {
    if (child.nodeType === Node.TEXT_NODE) {
      parts.push(child.textContent || '')
    } else if (child.nodeType === Node.ELEMENT_NODE) {
      const elem = child as Element
      if (elem.classList.contains('cmd-tag')) {
        const type = elem.getAttribute('data-type')
        const name = elem.getAttribute('data-name')
        if (type && name) {
          const prefix = type === 'mcp' ? 'MCP' : 'Skill'
          parts.push(prefix + '/' + name)
        }
      } else if (elem.tagName === 'BR') {
        parts.push('\n')
      } else {
        parts.push(elem.textContent || '')
      }
    }
  }

  const msg = parts.join('').trim()
  if (!msg) return

  emit('send', msg)

  // 清空输入框
  el.innerHTML = ''
  el.dispatchEvent(new Event('input', { bubbles: true }))
}
```

- [ ] **Step 2: 类型检查**

```bash
cd src/main/resources/static && npx vue-tsc --noEmit 2>&1 | head -30
```

Expected: No errors

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/static/src/views/chat/InputArea.vue
git commit -m "feat: 实现 contenteditable 内容序列化，tag 转为 MCP/Skill 前缀文本"
```

---

### Task 5: 会话/操作命令直接发送 + Ctrl+Enter

**Files:**
- Modify: `src/main/resources/static/src/views/chat/InputArea.vue`

- [ ] **Step 1: 会话/操作命令改为直接发送**

修改 `insertCommand` 函数，对以 `/` 开头的命令直接 emit 发送（不插入输入框）：

替换 `insertCommand` 函数（约第 98-113 行）：

```typescript
function insertCommand(cmd: string) {
  // 会话/操作命令：直接发送
  if (cmd.startsWith('/')) {
    emit('send', cmd)
    closeAll()
    return
  }
  // 其他命令兼容旧逻辑（理论上不会再走到这里，保留安全）
  const el = inputRef.value
  if (el) {
    el.focus()
    const text = cmd + ' '
    const sel = window.getSelection()
    if (sel && sel.rangeCount > 0) {
      const range = sel.getRangeAt(0)
      range.deleteContents()
      range.insertNode(document.createTextNode(text))
      range.collapse(false)
      sel.removeAllRanges()
      sel.addRange(range)
    }
  }
  closeAll()
}
```

- [ ] **Step 2: 将发送快捷键改为 Ctrl+Enter**

修改 `onKeydown` 中的 Enter 判断（约第 40 行）：

原代码：
```typescript
if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); doSend() }
```

改为：
```typescript
if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) { e.preventDefault(); doSend() }
```

Enter without modifier 在 contenteditable 中自然产生换行，不需要额外处理。

- [ ] **Step 3: 类型检查**

```bash
cd src/main/resources/static && npx vue-tsc --noEmit 2>&1 | head -30
```

Expected: No errors

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/static/src/views/chat/InputArea.vue
git commit -m "feat: 会话/操作命令直接发送，Ctrl+Enter 发送消息，Enter 换行"
```

---

### Task 6: 添加 tag 样式和暗黑模式

**Files:**
- Modify: `src/main/resources/static/src/views/chat/InputArea.vue`

- [ ] **Step 1: 在 scoped 样式块末尾添加 tag 亮色样式**

```css
/* tag 标签 */
.cmd-tag {
  display: inline-block;
  vertical-align: middle;
  background: #efe9de;
  border: 1px solid #e6dfd8;
  border-radius: 6px;
  padding: 1px 6px;
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  cursor: default;
  user-select: none;
  margin: 0 1px;
  line-height: 1.6;
}
.cmd-tag__prefix { font-weight: 600; }
.cmd-tag--mcp .cmd-tag__prefix { color: #5db8a6; }
.cmd-tag--skill .cmd-tag__prefix { color: #e8a55a; }
.cmd-tag__sep { color: #8e8b82; margin: 0 1px; }
.cmd-tag__name { color: #141413; }
```

- [ ] **Step 2: 在 scoped 样式块末尾添加 tag 暗黑样式**

```css
/* tag 暗黑模式 */
html.dark .cmd-tag {
  background-color: var(--el-fill-color-light);
  border-color: var(--el-border-color);
}
html.dark .cmd-tag__name { color: var(--el-text-color-primary); }
```

- [ ] **Step 3: 更新 send 按钮的 disabled 判断**

当前 `send-btn` 按钮判断 `!text.trim()`（第 222 行），但 `text` ref 已删除。改为判断 contenteditable 内容：

```html
:disabled="!chatStore.streaming && !hasContent()"
```

并在 script 中添加：

```typescript
function hasContent() {
  const el = inputRef.value
  if (!el) return false
  return el.innerText.trim().length > 0
}
```

- [ ] **Step 4: 更新 send-btn 的 has-text class 判断**

```html
:class="{ stop: chatStore.streaming, 'has-text': !chatStore.streaming && hasContent() }"
```

- [ ] **Step 5: 类型检查**

```bash
cd src/main/resources/static && npx vue-tsc --noEmit 2>&1 | head -30
```

Expected: No errors

- [ ] **Step 6: 提交**

```bash
git add src/main/resources/static/src/views/chat/InputArea.vue
git commit -m "feat: 添加 tag 标签样式和暗黑模式适配"
```

---

### Task 7: 端到端验证

- [ ] **Step 1: 构建前端**

```bash
cd src/main/resources/static && npm run build
```

Expected: BUILD SUCCESS, output in `zephyr-ui/`

- [ ] **Step 2: 复制前端产物到 target**

```bash
mkdir -p ../../../target/classes/static && cp -rf zephyr-ui ../../../target/classes/static/
```

- [ ] **Step 3: 编译后端**

```bash
cd /Users/hbq/Codes/me/github/zephyr && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: 启动后端（me 环境）**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
mvn spring-boot:run -Dspring-boot.run.profiles=me
```

- [ ] **Step 5: curl 验证 tag 序列化**

```bash
# 模拟发送含 MCP 工具的消息
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/chat/send" \
  -d '{"conversationId":"","message":"帮我查询 MCP/getTenantRiskProfile 这个接口"}'
```

Expected: SSE 流式响应，LLM 识别并调用工具

- [ ] **Step 6: curl 验证会话命令直接发送**

```bash
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/chat/send" \
  -d '{"conversationId":"","message":"/help"}'
```

Expected: 返回帮助文本（由 handleSlashCommand 处理）

- [ ] **Step 7: 浏览器验证**

打开 `http://localhost:30733/zephyr/zephyr-ui/index.html`
- [ ] 点选 MCP 工具 → 输入框出现 tag
- [ ] tag 后按 Backspace → tag 整颗删除
- [ ] Ctrl+Enter → 发送
- [ ] 点选会话命令 → 直接发送
- [ ] 切换暗黑模式 → tag 配色正确

- [ ] **Step 8: 提交（如有前端问题修复）**

```bash
git add -A && git commit -m "fix: 端到端验证后的修复"
```
