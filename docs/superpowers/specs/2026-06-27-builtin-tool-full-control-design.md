# 内置工具全面角色管控

**日期：** 2026-06-27
**状态：** 设计完成

## 背景

当前只有 `execute_shell`、`list_processes`、`kill_process` 三个 shell 族工具有角色管控。其余工具缺少角色层面的准入控制，非 admin 用户可直接调用。

## 目标

将角色管控覆盖到除纯读类工具（`read`、`grep`、`glob` 等）之外的全部工具：

- 文件写入类：`write_file`、`edit_file`
- Zephyr 内部工具：`use_skill`、`use_memory`、`search_knowledge`
- MCP 外部工具：全局开关 `mcp_all`

## 设计

### 数据模型

`zephyr_builtin_tool_controls` 表结构不变，扩充种子数据。

**新增行（`require_admin=1` 表示非 admin 不可用）：**

| tool_name | description | require_admin |
|-----------|-------------|:---:|
| `write_file` | 写入/创建文件，支持覆盖和追加模式 | 1 |
| `edit_file` | 精确字符串替换编辑文件 | 1 |
| `use_skill` | 调用自定义技能模块，扩展 Agent 能力 | 0 |
| `use_memory` | 读写持久化记忆，跨会话保留上下文 | 0 |
| `search_knowledge` | 在知识库中语义检索相关文档片段 | 0 |
| `mcp_all` | MCP 外部工具全局开关（控制所有 MCP 工具） | 1 |

### BuiltinToolServiceImpl.requiresAdmin 改造

新增 MCP 工具识别：toolName 以 `mcp__` 开头时，查缓存的 key 改为 `mcp_all`：

```
requiresAdmin(userName, toolName):
  if admin → false (豁免)

  lookupKey = toolName.startsWith("mcp__") ? "mcp_all" : toolName
  v = requireAdminCache.get(lookupKey)

  blocked = v != null && v
  if blocked → log + return true
  return false
```

### SecurityEvaluator 改造

把原来分散在各 case 中的角色检查上提至 `evaluate()` 方法入口，统一执行：

```
evaluate(toolName, arguments, userName, mode, boundary):
  // 1. 全局角色检查（所有工具统一入口）
  if requiresAdmin(userName, toolName) → BLOCK("ROLE_CHECK")

  // 2. 具体安全策略
  switch toolName:
    execute_shell → evaluateShell(...)
    list_processes, kill_process → ALLOW
    write_file, edit_file → evaluateFileWrite(...)
    default → ALLOW
```

要点：
- `execute_shell` 角色检查从 case 内移除，上提后仍保留 shell 安全策略评估（HARD_BLOCK / SOFT_BLOCK / workspace boundary）
- `write_file` / `edit_file` 新增角色拦截，已有的文件安全策略不变
- `default` 分支（MCP 工具等）受全局角色检查约束，由 `mcp_all` 行控制

### 种子数据

三语 SQL 文件各扩充 6 行 INSERT，`WHERE NOT EXISTS` 保证幂等。

### 前端

无需改动。`ToolControlSettings.vue` 从 `/builtin-tool/list` 拉取全表，新条目自动出现。`mcp_all` 混在表格中展示。

## 涉及文件

| 文件 | 改动 |
|------|------|
| `BuiltinToolServiceImpl.java` | `requiresAdmin` 方法增加 MCP 工具前缀判断 |
| `SecurityEvaluator.java` | 角色检查从 3 个 case 上提到入口统一处理 |
| `zephyr-zh-CN.sql` | 6 行种子数据 |
| `zephyr-en-US.sql` | 6 行种子数据 |
| `zephyr-ja-JP.sql` | 6 行种子数据 |
