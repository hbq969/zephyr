# MCP 管理 设计规格

## 概述

实现 MCP（Model Context Protocol）服务器配置管理和工具管理。支持 stdio 和 HTTP 两种传输方式，工具可通过自动发现或手动添加，按用户隔离。

## 数据模型

### mcp_servers 表

| 列 | 类型 | 说明 |
|----|------|------|
| id | varchar(64) | 主键，UUID 简写 |
| user_name | varchar(64) | 用户名，数据隔离 |
| name | varchar(128) | 服务器名称 |
| transport | varchar(16) | 传输方式：stdio / http |
| command | varchar(256) | 启动命令（stdio 专用） |
| args | text | 参数，一行一个（stdio 专用） |
| env_vars | text | 环境变量，KEY=VALUE 一行一个（stdio 专用） |
| url | varchar(512) | MCP 服务器 URL（http 专用） |
| headers | text | 自定义请求头，KEY=VALUE 一行一个（http 专用） |
| status | varchar(16) | 运行时状态：connected / disconnected / error |
| created_at | bigint | Unix 秒 |
| updated_at | bigint | Unix 秒 |

### mcp_tools 表

| 列 | 类型 | 说明 |
|----|------|------|
| id | varchar(64) | 主键，UUID 简写 |
| user_name | varchar(64) | 用户名 |
| server_id | varchar(64) | 所属服务器，FK |
| tool_name | varchar(128) | 工具名称 |
| description | varchar(512) | 工具描述 |
| enabled | smallint | 是否启用（0/1），默认 1 |
| source | varchar(16) | 来源：discovered / manual |
| created_at | bigint | Unix 秒 |

## API 设计

Base path: `/zephyr-ui/mcp`

### 服务器管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/mcp/server/list` | 当前用户的服务器列表 |
| POST | `/mcp/server/create` | 新增服务器配置 |
| POST | `/mcp/server/update` | 修改服务器配置 |
| POST | `/mcp/server/delete` | 删除服务器（级联删除工具） |
| POST | `/mcp/server/connect` | 连接服务器并发现工具 |
| POST | `/mcp/server/disconnect` | 断开连接 |

### 工具管理

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/mcp/tool/list?serverId=xxx` | 某个服务器的工具列表 |
| POST | `/mcp/tool/create` | 手动添加工具 |
| POST | `/mcp/tool/update` | 修改工具（名称/描述） |
| POST | `/mcp/tool/delete` | 删除工具 |
| POST | `/mcp/tool/toggle` | 启用/禁用工具（id + enabled） |

## 包结构

```
com.github.hbq969.ai.zephyr.mcp/
├── ctrl/McpCtrl.java
├── service/McpService.java
├── service/impl/McpServiceImpl.java
├── dao/McpDao.java
├── dao/entity/McpServerEntity.java
├── dao/entity/McpToolEntity.java
└── dao/mapper/
    ├── common/McpMapper.xml
    ├── embedded/McpMapper.xml
    ├── mysql/McpMapper.xml
    └── postgresql/McpMapper.xml
```

## 开发规范（必须遵守）

- Controller 用 `@RequestMapping`，禁止 `@GetMapping/@PostMapping`
- 必须 `@Tag(name)`、`@Operation(summary)`、`@ResponseBody`
- 前端 URL 不含 `outDir` 前缀（axios baseURL 已覆盖）
- 新建表：三方言（embedded/postgresql/mysql）Mapper XML DDL + `InitialServiceImpl.tableCreate0()` 注册
- DDL 必须 `if not exists`
- 删除/修改操作校验 user_name，禁止越权访问
- 前端配色使用 DESIGN.md 方案（warm canvas / coral primary）

## 安全要求

- 所有接口校验 user_name（从 session 获取），禁止越权
- 删除服务器时级联删除其下所有工具
- 工具 toggle 操作校验 user_name + server_id 归属

## 前端设计

### 页面结构

路由 `/settings/mcp` → `MCPSettings.vue`（已存在，需重写）

### 1. 服务器列表

卡片式布局，每张卡片显示：
- 服务器图标（stdio: dark 底色, http: coral 底色）
- 服务器名称
- 传输方式 badge
- 连接状态（已连接 / 未连接 / 连接失败）
- 启动命令或 URL
- 操作按钮：编辑、连接/断开
- 点击卡片展开工具面板

### 2. 工具面板（展开后）

- 工具列表（tool_name + description + source + enabled toggle）
- 自动发现的工具标记为"自动发现"，手动添加的标记为"手动添加"
- 每行：工具名、描述、来源标记、启用/禁用开关、删除按钮
- 底部手动添加入口（tool_name + description → 添加按钮）
- 未连接时显示引导提示，但仍可手动添加

### 3. 添加/编辑弹窗

- 服务器名称输入
- 传输方式切换（stdio / HTTP 分段按钮）
- stdio 字段：command、args（textarea，一行一个）、env_vars（textarea，可选）
- HTTP 字段：url、headers（textarea，可选）
- 保存 / 取消

### 4. 空状态

无服务器时显示引导提示 + 添加按钮

### 5. 状态栏联动

StatusBar.vue 中 "MCP: N 个工具" 的数据来源改为从后端 API 获取（统计当前用户所有已启用的工具数）

### 前端文件变更清单

| 文件 | 变更 |
|------|------|
| `MCPSettings.vue` | 完全重写，按 DESIGND.md 风格实现 |
| `StatusBar.vue` | MCP 工具数改为从 API 获取 |
| `SettingsPanel.vue` | MCP 数量联动（已有入口，确认正确） |
| `store/settings.ts` | 添加 MCP 相关 actions（loadServers、loadTools、toggleTool 等） |
| `types/chat.ts` | 添加 McpServer、McpTool 类型定义 |

## 设计颜色参考（DESIGN.md）

| 用途 | 变量 | 值 |
|------|------|------|
| 页面底色 | canvas | #faf9f5 |
| 卡片底色 | surface-card | #efe9de |
| 主色调 | primary | #cc785c |
| 标题色 | ink | #141413 |
| 正文色 | body | #3d3d3a |
| 次要文字 | muted | #6c6a64 |
| 成功 | success | #5db872 |
| 错误 | error | #c64545 |
| 代码底色 | surface-dark | #181715 |
| 分隔线 | hairline | #e6dfd8 |
