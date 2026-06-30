# Changelog

本项目遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)，格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/)。

## [1.3.4] - 2026-06-30

### 修复

- **MCP 重启后孤儿进程残留**：`initStdio()` 覆盖 PID 文件前先杀旧文件中的残留进程，`cleanupOrphanProcesses()` 用 `isAlive()` + 3s 循环等待替代 200ms 盲等，防止 `npm exec` 进程未及时死亡导致 PID 被覆盖后成为追踪不到的孤儿

## [1.3.3] - 2026-06-28

### 新增

- **MCP reconnect_on_startup**：MCP 服务器配置新增 `reconnect_on_startup` 字段，Zephyr 启动时自动重连标记为 reconnect 的 MCP 服务器
- **应用名称可配置**：`whoami` 接口返回 `appName`，AI 助手标签和身份标识可通过配置动态设置

### 变更

- **移除命令面板**：聊天输入区移除命令选择面板，简化交互
- **修复 /clear 后会话恢复问题**：清空对话后重新发送消息时正确恢复会话上下文
- **工具执行气泡标题格式**：从 `xxx: yyyy` 改为 `xxx(yyyy)`，MCP 前缀改为小写 `mcp`
- **MCP 日志优化**：重复初始化跳过日志降为 debug 级别

### 修复

- **MCP 重连死循环**：`ScriptInitialDoneEvent` 重复触发导致重连死循环，新增 `AtomicBoolean` 幂等守卫确保清理和重连只执行一次

## [1.3.2] - 2026-06-27

### 新增

- **内置工具角色管控**：shell 族工具支持按角色开启/关闭（`execute_shell`/`list_processes`/`kill_process`），种子数据幂等插入 + 三语本地化
- **MCP 工具全局角色管控**：`BuiltinToolService` 通过 `McpDao.queryAllDistinctToolNames` 获取完整 MCP 工具名，结合角色配置实现精准管控
- **前端权限控制**：非 ADMIN 角色隐藏侧边栏设置入口，共享 MCP 仅可查看工具不可修改
- **安全追踪日志**：`SecurityEvaluator` 全链路追踪日志（入口 → HARD_BLOCK → SOFT_BLOCK → MODE_DEFAULT → 结果），便于排查安全判断路径
- **前端错误提示**：安全拦截时前端气泡展示具体阻断规则名称和原因
- **第 14 条软阻断规则**：新增 `>\s*\S+` 规则，检测输出重定向行为
- **工具执行拒绝状态**：被安全规则拦截的工具结果前端展示橙色盾牌+拒绝（而非绿色对勾+成功），`dispatchTools` 统一设置 `status: "rejected"`
- **主题切换优化**：主题切换按钮移至主页右上角，修复暗黑模式代码块样式
- **工具调用卡片展示优化**：`execute_shell` 展示具体命令名，`search_knowledge` 展示查询关键词，`use_skill`/`use_memory` 展示参数名，MCP 工具加 `MCP:` 前缀，长文本自动截断

### 修复

- **execute_shell 白名单拒绝误显成功**：shell 命令因不在白名单内被拒绝执行时抛 `ToolRejectedException`，前端气泡正确显示拒绝状态
- **/clear 漏杀后台进程**：清空对话时补充 `backgroundProcessManager.killByConversationId(cid)`，防止残留后台进程
- **MCP 下拉列表只展示已连接服务**：聊天输入框 MCP 下拉过滤未连接的服务，避免用户选中不可用工具
- **异步线程 UserContext 丢失**：`@Async` 方法内无法获取当前用户，修复后前端增加模型未配置提示

### 变更

- **角色检查统一上提**：各 case 中的角色检查逻辑移到 `SecurityEvaluator` 入口统一处理，减少分散判断
- **安全绕过次数调整**：`maxBypassAttempts` 从 1 调整为 2，允许在确认模式下多一次重试机会
- **shell 路径越界改为提示词约束**：移除 Shell 命令正则路径越界检查，改为 prompt 中约束 LLM 行为
- **数据库初始化解耦**：`InitialServiceImpl` 改用 `ApplicationListener<ScriptInitialDoneEvent>` 模式，`@PostConstruct` 不再操作 DB

## [1.3.1] - 2026-06-26

### 新增

- **安全规则统计接口**：`GET /security/stats`，返回四种类型规则计数，设置面板入口展示 (白/查/硬/软)
- **安全规则批量删除**：`POST /security/{type}/batch-delete`，批量删除选中规则
- **安全规则列表增强**：新增刷新按钮、序号列、多选列、前端分页（默认 10 条/页）
- **启停状态筛选**：硬阻断/软阻断 Tab 新增启用/禁用筛选下拉框
- **ADMIN 权限控制**：安全规则配置仅 ADMIN 角色可访问，非管理员入口隐藏且页面重定向
- **设置面板安全入口**：SettingsPanel 新增安全规则行，显示各类型规则计数
- **命令类规则启停控制**：安全规则支持按命令类型（shell/mcp/skill/file）独立启停
- **批量启停**：安全规则支持批量启用/禁用操作
- **启停筛选改用 radio 组**：筛选组件从 select 下拉改为 radio 组，操作更直观

### 修复

- **新建对话知识库不可选**：输入区知识库下拉移除 `currentId` 前置检查，允许在未关联对话时选择知识库

## [1.3.0] - 2026-06-25

### 新增

- **安全评估框架**：新增 `SecurityEvaluator` 进行代码级安全模式匹配（HARD/SOFT BLOCK），支持操作确认弹窗
- **安全规则配置外部化**：HARD/SOFT BLOCK 规则从硬编码迁移到 `application.yml` 配置，支持 EXTEND/REPLACE 合并模式
- **安全 Prompt 外置**：prompt 模板迁移到外置 md 文件（`prompts/security/`、`prompts/modes/`），支持 classpath 和用户目录加载
- **文件系统安全**：文件写入操作增加 workspace 边界检查（路径规范化、消除 `..`、解析符号链接），防止路径遍历绕过
- **操作确认弹窗**：聊天界面集成确认弹窗组件，用户可实时批准/拒绝危险操作

### 优化

- **魔法值消除**：新建 `ZephyrConstants` 常量类（290 行），提取 140+ 跨文件常量到 15 个分类（SSE 事件、工具名、HTTP 头、API 路径、MCP 协议、文件扩展名等），消除 25 个文件中的硬编码
- **Workspace 路径缓存**：`SessionHandle` 加 `workspacePath` 字段，`send()` 入口一次解析，消除 `executeShell` 和 `resolveWorkspaceBoundary` 中的重复 DB 查询
- **代码去重**：`parseCommandList` 改为 public static，`ChatServiceImpl` 复用

### 修复

- SSE 正常完成路径遗漏 `emitter.complete()` 导致响应流永不关闭
- 操作确认弹窗内容自动换行，防止长路径溢出
- 强制终止前发送 `tool_result` 事件，避免前端计时器残留
- 绕过重试计数改为统计所有拒绝（HARD BLOCK + 用户拒绝）
- 消除 prompt 中 workspace 边界检查的矛盾指令
- 常量替换 bug：McpClient SSE `data:` 前缀误合并为带空格版本

## [1.2.1] - 2026-06-25

### 修复

- **新建对话竞态条件**：`newChat()` 主动 abort 旧 SSE fetch 并重置 streaming，`.catch()` 校验 AbortController 身份防止过期回调覆写状态
- **前端对话切换状态混乱**：侧边栏点击对话改用 sessionStorage + `location.reload()`，`onMounted` 恢复选中状态，删除/重命名后页面刷新保持一致性
- **SSE 空闲超时**：SSE 连接空闲 30 分钟自动取消，工具执行后 touch 保持活跃，读超时 5 分钟
- **MCP 连接管理**：移除 `@Scheduled cleanupIdle` 定时清理和 LRU 淘汰，连接满直接拒绝，`@PreDestroy` 统一关闭
- **硬编码配置值**：`LlmClient` 超时 300 改为从 `ZephyrConfigProperties` 读取，确保配置单一数据源

## [1.2.0] - 2026-06-24

### 新增

- **Shell 命令执行**：内置 `execute_shell`/`list_processes`/`kill_process` 三个工具，AI 可在对话中执行 shell 命令、管理后台进程
- **命令白名单**：`allowAll`/`whitelist`/`disabled` 三种安全模式，白名单模式仅允许清单内命令执行
- **白名单配置外置**：命令白名单完整默认值写入 `application.yml`，可直接在配置文件中修改
- **后台进程管理**：`BackgroundProcessManager` 用户级后台进程管理，支持配额限制、超时自动 kill、会话级隔离
- **ProcessSlot 机制**：`SessionHandle` 新增 ProcessSlot，消除 fork-register 竞争条件
- **会话生命周期管理**：空闲超时自动取消、自清理任务扫描、关键节点日志记录

### 修复

- 后台进程日志文件命名为 `$$.log` 而非 PID 日志
- 删除对话时同步清理后台进程，不允许跨会话存活
- 无活跃会话时删除/取消同样记录日志

## [1.1.2] - 2026-06-23

### 新增

- **MCP 连接生命周期管理**：connect 启动持久进程 + listTools 自动发现工具 + DB 状态清理，重启/重连保证状态一致
- **MCP 进程树追踪与清理**：动态查找所有子孙 PID，kill 时先杀子进程再杀父进程
- **启动自动重连**：Zephyr 启动时自动重连之前处于 connected 状态的 MCP 服务器
- **知识库混合检索**：BM25 关键词检索（文档级 IDF）+ 向量检索 RRF 融合
- **上下文窗口扩展**：RRF 融合后获取相邻 chunk 合并排序返回
- **查询增强**：embedding 前自动提取查询关键词拼接，拉近与 chunk 的语义距离
- **文本清洗管线**：TextCleaner 统一处理文本清理、低质量 chunk 过滤
- **Chroma 路径可配置**：chroma dataDir/binPath/baseUrl 通过配置文件管理

### 修复

- 删除知识库时同步清理 Chroma 向量数据和文件目录
- TikaParser 解除 100000 字符截断限制
- 启动重连跳过权限检查
- 模型管理空状态无添加按钮

## [1.1.1] - 2026-06-14

### 变更

- **LightRAG sidecar 配置外置**：所有配置项从代码默认值移至 `.env.lightrag` 环境文件
- **启动脚本**：新增 `start_lightrag.sh`，自动检查必需环境变量、创建 venv、安装依赖并启动
- **环境配置模板**：新增 `.env.lightrag.example`（可安全提交），`.env.lightrag` 加入 gitignore

## [1.1.0] - 2026-06-14

### 新增

- **知识库功能**：支持创建知识库、上传文档（PDF/Word/Markdown/TXT 等），自动解析、切分、向量化存储
- **知识库检索**：`search_knowledge` 内置工具，对话中勾选知识库后模型可主动检索相关文档片段
- **Embedding 模型支持**：模型配置支持对话模型和 Embedding 模型分类管理
- **Chroma 向量数据库集成**：本地 embed 模式 + 生产独立部署，OkHttp HTTP 客户端封装
- **文档处理流水线**：Apache Tika 文本提取 → 递归字符切分 → Embedding 向量化 → Chroma 存储
- **对话级知识库选择**：对话中可勾选/取消知识库，切换对话自动恢复勾选状态
- **模型共享**：模型配置支持 `user`/`shared` scope，私有模型仅自己可见，共享模型所有用户可见
- **MCP/Skill scope 资源隔离**：Skill 和 MCP 支持按 scope 分栏（共享/我的），安装时自动检测同名冲突

### 修复

- **SSE 流式响应偶发丢失**：axios `onDownloadProgress` 节流导致快速 LLM 响应时 assistant 消息被误删，改为 `fetch` + `ReadableStream` 行缓冲解析
- Skill 安装/同步失败时前端显示明确错误提示
- Skill 全选/取消全选按 tab 页（共享/个人）区分
- MCP 工具重名检查只查共享+当前用户，不误拦他人私有工具

## [1.0.2] - 2026-06-12

### 新增

- **Skill 用户隔离与共享机制**：支持 `user`/`shared` scope

### 变更

- 记忆管理页面去掉项目维度过滤和类型选项

## [1.0.1] - 2026-06-12

### 新增

- **聊天模式切换**：Default/Accept Edits/Bypass 三种模式，三套文件系统安全提示词
- **文件系统安全强化**：路径规范化、边界精确前缀检查、禁用路径遍历

### 修复

- 聊天页面 loading 闪屏
- 重命名输入框自动聚焦全选
- Zephyr 菜单图标更新
- 切换 workspace 时更新对话归属

### 优化

- 清除所有 Vue 文件中硬编码中文，补全三语 i18n key
- 表名统一添加 `zephyr_` 前缀

## [1.0.0] - 2026-06-12

### 新增

- **聊天对话**：SSE 流式 LLM 对话，支持 thinking blocks 折叠展示、工具调用实时可视化卡片、请求取消
- **MCP 管理**：MCP Server CRUD、工具自动发现、连接池管理、工具调用超时强制终止
- **模型配置**：模型 API 配置 CRUD（AES 加密存储）、API 可用性探测
- **Skill 管理**：Skill 导入安装、从 Claude/Codex/OpenCode 同步
- **Memory 管理**：会话记忆读写、持久化存储
- **Workspace**：工作目录管理，支持目录选择器和原生 showDirectoryPicker
- **文件上传**：聊天文件上传，chip 卡片展示，去重只保留最后一次
- **输入框**：命令菜单、模型选择器（含上下文窗口和推理能力标识）
- **会话持久化**：对话历史保存与还原，合并连续消息的 toolCalls
- **配置管理**：ZephyrConfigProperties 统一配置类
- **国际化**：中文/英文/日文三语支持

### 基础设施

- Spring Boot 3.5.4 + Vue 3 + TypeScript + Element Plus
- MyBatis 多方言 DDL（embedded/mysql/postgresql）
- H2 嵌入式数据库，表自动创建
