# 知识库功能 — 待办追踪

## 概览

| # | Spec | 状态 | 依赖 |
|---|------|------|------|
| 01 | [模型配置改造](2026-06-12-knowledge-base-01-model-config.md) | ⬜ 待开始 | — |
| 02 | [数据模型与 CRUD API](2026-06-12-knowledge-base-02-data-model.md) | ⬜ 待开始 | 01 |
| 03 | [文档处理流水线](2026-06-12-knowledge-base-03-doc-pipeline.md) | ⬜ 待开始 | 02 |
| 04 | [search_knowledge 工具与上下文集成](2026-06-12-knowledge-base-04-search-tool.md) | ⬜ 待开始 | 02, 03 |
| 05 | [前端页面](2026-06-12-knowledge-base-05-frontend.md) | ⬜ 待开始 | 02 |
| 06 | [Chroma 部署与环境配置](2026-06-12-knowledge-base-06-deploy.md) | ⬜ 待开始 | 03 |

## 关键决策记录

| 决策 | 选择 |
|------|------|
| 知识库模式 | 内容空间（可写 Markdown + 上传文件），对话中勾选 |
| Embedding 模型 | 扩展现有模型配置表，加 `model_type` 字段 |
| 向量存储 | Chroma（本地 embed 模式 + 生产独立部署） |
| 文档解析 | Apache Tika |
| 检索策略 | search_knowledge 工具调用（模型主动检索） |
| 切分策略 | 递归字符切分，chunk_size=800 字，overlap=150 字 |
