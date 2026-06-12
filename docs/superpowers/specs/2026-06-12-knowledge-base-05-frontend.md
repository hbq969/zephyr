# Spec 05: 前端页面

## 目标

实现知识库管理的前端页面，以及在聊天页集成知识库勾选。

## 依赖

- Spec 02（API 就绪）

## 页面

### 1. 知识库列表页 `settings/KnowledgeSettings.vue`

**路由**：`/settings/knowledge`

**布局**：
- 顶部：标题"知识库管理" + 返回按钮 + "创建知识库"按钮
- 卡片网格：每张卡片显示名称、描述、文档数、Embedding 模型名、更新时间
- 卡片悬浮操作：编辑、删除
- 点击卡片 → 跳转文档列表页 `/settings/knowledge/:kbId/docs`

**创建/编辑对话框**：
- 名称（el-input）
- 描述（el-input）
- Embedding 模型（el-select，只显示 modelType=embedding 的模型）
- 保存

### 2. 文档列表页 `settings/KnowledgeDocs.vue`

**路由**：`/settings/knowledge/:kbId/docs`

**布局**：
- 顶部：知识库名称 + 返回按钮 + "上传文档"按钮
- 文档表格/列表：
  - 文件名、类型 tag、大小、chunk 数、状态 tag（processing/ready/error）、上传时间
  - 操作：删除、重新解析（换模型后）
- 分页

**上传文档**：
- el-dialog 或 drawer
- el-upload（支持 pdf/md/txt/html/docx 等）
- 上传后列表自动刷新，新文档初始状态 processing，轮询或手动刷新看结果

### 3. 聊天页知识库选择

在聊天页输入框上方或 SettingsPanel 中增加：

- "知识库"行，显示已勾选的知识库 chip/tag
- 点击展开一个 dropdown/panel，列出所有可用知识库（checkbox）
- 勾选/取消时调 API 同步到后端
- 切换对话时自动恢复该对话的勾选状态

具体布局和交互用 `frontend-design` skill 在实现阶段设计。

## 路由注册

```typescript
// router/index.ts
{ path: '/settings/knowledge', component: KnowledgeSettings }
{ path: '/settings/knowledge/:kbId/docs', component: KnowledgeDocs }
```

## 设计规范

- 配色使用 Element Plus 主题色变量（`var(--el-color-primary)` 等）
- 图标优先 Element Plus icons，找不到用 iconify（lucide 集）
- 暗黑模式适配
- 操作按钮：新增类用 `el-button circle` + tooltip，表格操作用 link 按钮

## 验证

- 启动前端调试服务 + 后端，浏览器打开知识库管理页
- 创建知识库 → 上传文档 → 查看文档状态变为 ready
- 聊天页勾选知识库 → 发消息测试检索
