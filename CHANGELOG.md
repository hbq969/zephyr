# Changelog

## v1.3.0 (2026-06-25)

### 新功能

- **安全评估框架**: 新增 SecurityEvaluator 进行代码级安全模式匹配（HARD/SOFT BLOCK），支持操作确认弹窗
- **安全规则配置外部化**: HARD/SOFT BLOCK 规则从硬编码迁移到 YAML 配置，支持 EXTEND/REPLACE 合并模式
- **安全 Prompt 外置**: prompt 模板迁移到外置 md 文件，支持 classpath 和用户目录加载
- **文件系统安全**: 文件写入操作增加 workspace 边界检查，防止路径遍历绕过
- **操作确认弹窗**: 聊天界面集成确认弹窗组件，用户可实时批准/拒绝危险操作
- **Workspace 管理**: 目录树懒加载、递归浏览、新建目录功能，browse 根目录可配置

### 优化

- **魔法值消除**: 提取 140+ 项目常量到 ZephyrConstants，消除 25 个文件中的硬编码
- **Workspace 路径缓存**: SessionHandle 加 workspacePath 字段，消除重复 DB 查询
- **代码去重**: parseCommandList 改为 public static，ChatServiceImpl 复用

### 修复

- SSE 完成路径遗漏 emitter.complete() 导致响应流永不关闭
- 操作确认弹窗内容自动换行，防止长路径溢出
- 强制终止前发送 tool_result 事件，避免前端计时器残留
- 绕过重试计数改为统计所有拒绝（HARD BLOCK + 用户拒绝）
- 消除 prompt 中 workspace 边界检查的矛盾指令
- 常量替换 bug：McpClient SSE data: 前缀误合并

## v1.2.1 (2026-06-23)

- 目录名称切换联动、默认路径填充、browse 响应完善、mkdir 父目录校验

## v1.2.0 (2026-06-20)

- MCP 管理、LLM 对话 SSE 流式、模型配置、会话持久化、记忆管理

## v1.1.2

- Bug 修复

## v1.1.1

- 初始版本
