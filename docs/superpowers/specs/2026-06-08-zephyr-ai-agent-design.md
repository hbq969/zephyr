# zephyr AI Agent 设计规格

## 概述

将 yyc-ai（Express + Next.js）重写为 Spring Boot + Vue3 架构，嵌入 h-sm 平台。

## 技术栈

| 层 | 技术 |
|----|------|
| 后端 | Spring Boot 3.5.4 + JDK 17 + MyBatis |
| 前端 | Vue3 + TypeScript + Element Plus + Pinia |
| 数据库 | PostgreSQL（MyBatis 三方言 Mapper） |
| LLM | Anthropic SDK + OpenAI SDK，按模型名路由 |
| 流式 | SseEmitter + Virtual Thread |
| 构建 | Vite → `zephyr-ui/`，Maven `mvn package` |

## 模块划分

后端 `com.github.hbq969.ai.zephyr`：chat / llm / mcp / skill / memory / user / config

前端 `static/src/views/`：chat/（主界面 7 组件）、settings/（4 管理面板）

模块间通过 service 接口解耦，chat 不直接依赖 mcp/skill/memory。

## 分阶段计划

### 第一阶段：Chat 主页面 UI（前后端骨架）

**目标**：完整的聊天界面，后端 API Mock，保证前后端联通

- ChatView 主布局（侧边栏 + 对话区 + 输入区 + 状态栏）
- 消息组件（气泡、Markdown 渲染、思考块、工具调用卡片）
- 会话侧边栏（列表、新建/切换/删除）
- 命令面板（`/` 触发、16 个命令）
- 设置面板（MCP、Skill、模型、记忆管理）
- 暗黑主题（Element Plus 变量 + `html.dark` 覆盖）
- 后端骨架（Mock 数据的 REST API + SSE 端点）

### 第二阶段：核心后端

数据库建表 → SSO 认证 + 游客 → LLM 适配 + SSE 流式 → 会话/消息 CRUD

### 第三阶段：高级功能

MCP 子进程管理 → Skill 插件 → 文件记忆 → 上下文压缩 → API Keys 加密

## 关键设计决策

- **流式**：Controller 返回 SseEmitter，Service 在 Virtual Thread 中同步调用 LLM SDK，逐 token 写入
- **前端 SSE**：`fetch() + ReadableStream` 读取 SSE 流（支持 POST + 自定义 headers）
- **认证**：复用 h-sm SSO 拦截器 + 游客模式 fallback
- **LLM 路由**：模型名前缀 `deepseek*`/`claude*` → Anthropic SDK，其他 → OpenAI SDK
- **状态管理**：Pinia，chat / conversations / settings / user 各一个 store
- **API 响应**：统一 `ReturnMessage<?>` 格式

## 第一阶段 UI 详细清单

### 路由

| 路径 | 组件 | 说明 |
|------|------|------|
| `/chat` | ChatView.vue | 主聊天页（默认路由） |
| `/settings/mcp` | MCPSettings.vue | MCP 服务器管理 |
| `/settings/skills` | SkillSettings.vue | Skill 插件管理 |
| `/settings/model` | ModelSettings.vue | 模型配置 |
| `/settings/memory` | MemorySettings.vue | 记忆管理 |

### 开发顺序

1. 后端 API 骨架（ChatCtrl, ConversationCtrl, 统一返回 Mock 数据，SSE 端点）
2. ChatView 主布局 + ChatSidebar + InputArea
3. ChatArea + MessageBubble（Markdown 渲染 + 暗黑适配）
4. ThinkingBlock + ToolCallCard
5. CommandPalette（斜杠命令触发与补全）
6. StatusBar（模型切换、token 用量显示）
7. 会话管理（新建/切换/删除/列表）
8. 设置面板（4 个管理页面）
9. 暗黑主题全面适配（组件内 + teleport + 全局覆盖）
