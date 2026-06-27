# 内置工具全面角色管控

**日期：** 2026-06-27
**状态：** 已审核（Architect — 已修复 → 待 Critic）

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

### BuiltinToolServiceImpl 改造

#### MCP 工具识别（关键修复）

Architect 审核发现：MCP 工具名是原始名称（如 `codegraph_search`、`browser_navigate`），没有 `mcp__` 前缀。用前缀方案 `mcp_all` 永远不命中。

**修复：** 使用注册式识别。在 `BuiltinToolServiceImpl` 中注入 `McpDao`，`refreshCache()` 时额外加载 MCP 工具名到 `Set<String> mcpToolNames`。

```
refreshCache():
  requireAdminCache = load from zephyr_builtin_tool_controls
  mcpToolNames    = load from zephyr_mcp_tools (distinct tool_name)

requiresAdmin(userName, toolName):
  if admin → false (豁免)

  // MCP 工具 ? 查 mcp_all : 查具体工具名
  lookupKey = mcpToolNames.contains(toolName) ? "mcp_all" : toolName
  v = requireAdminCache.get(lookupKey)

  blocked = v != null && v
  if blocked → log + return true
  return false
```

- 不在 cache 也不在 mcpToolNames 的工具（如 `read`）→ `get()` 返回 null → 不拦截 ✅
- 新增 MCP 工具自动被识别，无需改 BuiltinToolService ✅

#### 新增依赖

`BuiltinToolServiceImpl` 新增注入 `McpDao`。McpDao 新增一个查询方法：

```java
// McpDao 新增
List<String> queryAllDistinctToolNames();
```

同名 Mapper XML 里加一条 `SELECT DISTINCT tool_name FROM zephyr_mcp_tools`。

### SecurityEvaluator 改造

角色检查从各 case 上提到 `evaluate()` 入口：

```
evaluate(toolName, arguments, userName, mode, boundary):
  if !isEnabled() → ALLOW   // 安全整体关闭时角色检查也跳过

  // 1. 全局角色检查（所有工具统一入口，在安全策略之前）
  if requiresAdmin(userName, toolName) → BLOCK("ROLE_CHECK")

  // 2. 具体安全策略
  switch toolName:
    execute_shell → evaluateShell(...)
    list_processes, kill_process → ALLOW
    write_file, edit_file → evaluateFileWrite(...)
    default → ALLOW
```

从三个 case（execute_shell、list_processes、kill_process）中移除内联 `requiresAdmin` 调用。

### 种子数据幂等性修复

当前 `WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls)` 是表级检查，表有数据后新增行永不生效。改为逐行检查：

```sql
INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'write_file', '写入/创建文件，支持覆盖和追加模式', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'write_file');
```

原有 3 行种子数据也一并改为逐行模式。

### 前端

无需改动。`ToolControlSettings.vue` 从 `/builtin-tool/list` 拉取全表，新条目自动出现。

## 涉及文件

| 文件 | 改动 |
|------|------|
| `BuiltinToolServiceImpl.java` | 注入 `McpDao`，`refreshCache` 加载 MCP 工具名集合，`requiresAdmin` 用集合判断 |
| `McpDao.java` | 新增 `queryAllDistinctToolNames()` |
| `McpDao` Mapper XML（4 方言） | 新增 `queryAllDistinctToolNames` SQL |
| `SecurityEvaluator.java` | 角色检查从 3 个 case 上移到入口统一处理 |
| `zephyr-zh-CN.sql` | 6 行种子数据（逐行幂等），原有 3 行改为逐行 |
| `zephyr-en-US.sql` | 同上 |
| `zephyr-ja-JP.sql` | 同上 |
