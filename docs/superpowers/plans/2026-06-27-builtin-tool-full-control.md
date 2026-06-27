# 内置工具全面角色管控 — 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将角色管控从仅 3 个 shell 工具扩展到所有非只读内置工具 + MCP 全局开关

**Architecture:** `BuiltinToolServiceImpl` 注入 `McpDao` 用注册式识别 MCP 工具；`SecurityEvaluator.evaluate()` 角色检查统一上提到入口；种子数据改为逐行幂等

**Tech Stack:** Java 17, Spring Boot 3.5.4, MyBatis, H2/PostgreSQL/MySQL

**Spec:** `docs/superpowers/specs/2026-06-27-builtin-tool-full-control-design.md`
**PRD:** `.omx/plans/prd-builtin-tool-full-control.md`

## Global Constraints

- 纯读工具（read/grep/glob 等）不纳入管控
- MCP 工具全局一个 `mcp_all` 开关，不逐工具控制
- admin 角色始终豁免
- 种子数据逐行 `WHERE NOT EXISTS` 幂等
- 角色检查在 `isEnabled()` 之后执行
- ROLE_CHECK 分支显式写审计日志，不绕过底部统一审计

---

### Task 1: McpDao 新增 `queryAllDistinctToolNames()` 接口方法

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/McpDao.java`

**Interfaces:**
- Produces: `List<String> queryAllDistinctToolNames()` — 返回所有 MCP 工具的 distinct tool_name，供 Task 3 的 `BuiltinToolServiceImpl` 加载 MCP 工具名集合

- [ ] **Step 1: 在 McpDao 接口末尾加方法声明**

在 `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/McpDao.java` 的 `queryToolByNameAndUser` 之后、`}` 之前插入：

```java
    List<String> queryAllDistinctToolNames();
```

插入位置：第 50 行 `queryToolByNameAndUser` 之后，第 51 行 `}` 之前。

- [ ] **Step 2: Git add + commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/McpDao.java
git commit -m "feat: McpDao 新增 queryAllDistinctToolNames 接口方法

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: common/McpMapper.xml 新增查询 SQL

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/common/McpMapper.xml`

**Interfaces:**
- Implements: `McpDao.queryAllDistinctToolNames()` (Task 1)
- Consumed by: Task 3 `BuiltinToolServiceImpl.refreshCache()`

- [ ] **Step 1: 在 `</mapper>` 前加 select 语句**

在 `src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/common/McpMapper.xml` 第 186 行 `</mapper>` 之前插入：

```xml
    <select id="queryAllDistinctToolNames" resultType="java.lang.String">
        select distinct tool_name as "toolName"
        from zephyr_mcp_tools
        where tool_name is not null
        order by tool_name
    </select>
```

- [ ] **Step 2: Git add + commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/mcp/dao/mapper/common/McpMapper.xml
git commit -m "feat: McpMapper 新增 queryAllDistinctToolNames SQL

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: BuiltinToolServiceImpl 注入 McpDao + MCP 工具识别

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/builtintool/service/impl/BuiltinToolServiceImpl.java`

**Interfaces:**
- Consumes: `McpDao.queryAllDistinctToolNames()` (Task 1-2)
- Produces: `requiresAdmin(userName, toolName)` — 新增 MCP 工具识别逻辑，调用方 (SecurityEvaluator) 无需改动

- [ ] **Step 1: 注入 McpDao + 新增 mcpToolNames 字段 + 修改 refreshCache**

修改 `src/main/java/com/github/hbq969/ai/zephyr/builtintool/service/impl/BuiltinToolServiceImpl.java`：

在第 3 行 import 区域加 `McpDao` 和 `Set` import：
```java
import java.util.Set;
import com.github.hbq969.ai.zephyr.mcp.dao.McpDao;
```

在 `@Resource private BuiltinToolDao builtinToolDao;` 之后加：
```java
    @Resource
    private McpDao mcpDao;
```

在 `requireAdminCache` 字段之后加：
```java
    private volatile Set<String> mcpToolNames = Collections.emptySet();
```

修改 `refreshCache()` 方法（第 33-52 行），在加载 requireAdminCache 之后加 MCP 工具名加载：

将这段：
```java
    @Override
    public void refreshCache() {
        try {
            List<BuiltinToolControlEntity> list = builtinToolDao.queryAll();
            if (list.isEmpty()) {
                log.warn("[内置工具管控] 配置表为空，所有工具放行");
                requireAdminCache = new ConcurrentHashMap<>();
                return;
            }
            Map<String, Boolean> map = new ConcurrentHashMap<>();
            for (BuiltinToolControlEntity e : list) {
                map.put(e.getToolName(), e.getRequireAdmin() != null && e.getRequireAdmin() == 1);
            }
            requireAdminCache = map;
            log.info("[内置工具管控] 缓存已刷新: {}", requireAdminCache);
        } catch (Exception e) {
            log.warn("[内置工具管控] 加载配置失败，所有工具放行", e);
            requireAdminCache = new ConcurrentHashMap<>();
        }
    }
```

改为：
```java
    @Override
    public void refreshCache() {
        try {
            List<BuiltinToolControlEntity> list = builtinToolDao.queryAll();
            if (list.isEmpty()) {
                log.warn("[内置工具管控] 配置表为空，所有工具放行");
                requireAdminCache = new ConcurrentHashMap<>();
            } else {
                Map<String, Boolean> map = new ConcurrentHashMap<>();
                for (BuiltinToolControlEntity e : list) {
                    map.put(e.getToolName(), e.getRequireAdmin() != null && e.getRequireAdmin() == 1);
                }
                requireAdminCache = map;
            }
        } catch (Exception e) {
            log.warn("[内置工具管控] 加载配置失败，所有工具放行", e);
            requireAdminCache = new ConcurrentHashMap<>();
        }

        // 加载 MCP 工具名集合（用于 mcp_all 全局开关匹配）
        try {
            List<String> names = mcpDao.queryAllDistinctToolNames();
            mcpToolNames = new java.util.HashSet<>(names);
            log.info("[内置工具管控] MCP 工具名集合已加载: {} 个", mcpToolNames.size());
        } catch (Exception e) {
            log.warn("[内置工具管控] 加载 MCP 工具名失败，MCP 管控暂时失效", e);
            mcpToolNames = Collections.emptySet();
        }

        log.info("[内置工具管控] 缓存已刷新: controls={}, mcpToolCount={}", requireAdminCache, mcpToolNames.size());
    }
```

- [ ] **Step 2: 修改 requiresAdmin 方法，加 MCP 工具识别**

将 `requiresAdmin` 方法（第 54-68 行）改为：

```java
    @Override
    public boolean requiresAdmin(String userName, String toolName) {
        UserInfo ui = UserContext.getNoCheck();
        if (ui != null && ui.isAdmin()) {
            log.debug("[内置工具管控] admin 用户豁免: tool={}, user={}", toolName, userName);
            return false;
        }

        // MCP 工具统一走 mcp_all 开关
        String lookupKey = mcpToolNames.contains(toolName) ? "mcp_all" : toolName;
        Boolean v = requireAdminCache.get(lookupKey);
        boolean blocked = v != null && v;
        if (blocked) {
            log.info("[内置工具管控] 非 admin 用户被拦截: tool={}, lookupKey={}, user={}, roles={}",
                    toolName, lookupKey, userName,
                    ui == null ? "[]" : ui.getRoleNames());
        }
        return blocked;
    }
```

- [ ] **Step 3: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 4: Git add + commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/builtintool/service/impl/BuiltinToolServiceImpl.java
git commit -m "feat: BuiltinToolService 注入 McpDao 实现 MCP 工具全局角色管控

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: SecurityEvaluator 角色检查统一上提 + 审计日志

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/security/SecurityEvaluator.java`

**Interfaces:**
- Consumes: `BuiltinToolService.requiresAdmin(userName, toolName)` (Task 3)
- 行为：非 admin 用户调用受控工具 → BLOCK("ROLE_CHECK")，审计日志记录

- [ ] **Step 1: 替换 evaluate() 方法体**

将 `evaluate()` 方法中第 90-125 行：

```java
    public Result evaluate(String toolName, Map<String, Object> arguments, String userName, String mode,
                           WorkspaceBoundary boundary) {
        if (!cfg.getSecurity().isEnabled()) {
            return Result.allow();
        }

        Result result = switch (toolName) {
            case "execute_shell" -> {
                if (builtinToolService.requiresAdmin(userName, "execute_shell")) {
                    yield Result.block("ROLE_CHECK", "命令未执行（无权限）");
                }
                yield evaluateShell(arguments, mode, boundary);
            }
            case "list_processes" -> {
                if (builtinToolService.requiresAdmin(userName, "list_processes")) {
                    yield Result.block("ROLE_CHECK", "命令未执行（无权限）");
                }
                yield Result.allow();
            }
            case "kill_process" -> {
                if (builtinToolService.requiresAdmin(userName, "kill_process")) {
                    yield Result.block("ROLE_CHECK", "命令未执行（无权限）");
                }
                yield Result.allow();
            }
            case "write_file", "edit_file" -> evaluateFileWrite(arguments, mode, boundary);
            default -> Result.allow();
        };

        if (result.decision() != Decision.ALLOW) {
            auditLogger.log("SECURITY_CHECK", toolName, result.decision().name(),
                    result.rule() + ": " + result.reason(), userName);
        }

        return result;
    }
```

改为：

```java
    public Result evaluate(String toolName, Map<String, Object> arguments, String userName, String mode,
                           WorkspaceBoundary boundary) {
        if (!cfg.getSecurity().isEnabled()) {
            return Result.allow();
        }

        // 全局角色检查：所有工具统一入口
        if (builtinToolService.requiresAdmin(userName, toolName)) {
            Result r = Result.block("ROLE_CHECK", "无权限（非 admin 用户）");
            auditLogger.log("SECURITY_CHECK", toolName, r.decision().name(),
                    r.rule() + ": " + r.reason(), userName);
            return r;
        }

        Result result = switch (toolName) {
            case "execute_shell" -> evaluateShell(arguments, mode, boundary);
            case "list_processes", "kill_process" -> Result.allow();
            case "write_file", "edit_file" -> evaluateFileWrite(arguments, mode, boundary);
            default -> Result.allow();
        };

        if (result.decision() != Decision.ALLOW) {
            auditLogger.log("SECURITY_CHECK", toolName, result.decision().name(),
                    result.rule() + ": " + result.reason(), userName);
        }

        return result;
    }
```

同时更新 JavaDoc 注释（第 84-89 行）：

```java
    /**
     * 评估工具调用。入口处统一做角色检查（非 admin 用户拦截受控工具），
     * 然后针对 execute_shell 和文件写入类工具做安全模式匹配，
     * 其余工具调用返回 ALLOW（由 LLM 自评估负责）。
     *
     * @param mode 权限模式：default | acceptEdits | bypass
     */
```

- [ ] **Step 2: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Git add + commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/security/SecurityEvaluator.java
git commit -m "refactor: 角色检查从各case上提到SecurityEvaluator入口统一处理

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 5: 种子数据 — 三语 SQL 逐行幂等 + 6 行新数据

**Files:**
- Modify: `src/main/resources/zephyr-zh-CN.sql`
- Modify: `src/main/resources/zephyr-en-US.sql`
- Modify: `src/main/resources/zephyr-ja-JP.sql`

- [ ] **Step 1: 替换 zephyr-zh-CN.sql 种子数据块**

将第 497-508 行：

```sql
-- ============================================================
-- 内置工具管控种子数据：从 InitialServiceImpl.insertSeed 迁移
-- ============================================================

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'execute_shell', '在工作空间目录执行任意 shell 命令，支持前台阻塞和后台运行', 1, 1735800000, 1735800000
UNION ALL
    SELECT 'list_processes', '列出当前用户启动的所有后台进程及其 PID', 1, 1735800000, 1735800000
UNION ALL
    SELECT 'kill_process', '根据 PID 终止指定的后台进程', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls);
```

改为：

```sql
-- ============================================================
-- 内置工具管控种子数据（逐行幂等，增量部署安全）
-- ============================================================

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'execute_shell', '在工作空间目录执行任意 shell 命令，支持前台阻塞和后台运行', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'execute_shell');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'list_processes', '列出当前用户启动的所有后台进程及其 PID', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'list_processes');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'kill_process', '根据 PID 终止指定的后台进程', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'kill_process');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'write_file', '写入/创建文件，支持覆盖和追加模式', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'write_file');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'edit_file', '精确字符串替换编辑文件', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'edit_file');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'use_skill', '调用自定义技能模块，扩展 Agent 能力', 0, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'use_skill');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'use_memory', '读写持久化记忆，跨会话保留上下文', 0, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'use_memory');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'search_knowledge', '在知识库中语义检索相关文档片段', 0, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'search_knowledge');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'mcp_all', 'MCP 外部工具全局开关（控制所有 MCP 工具的可用性）', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'mcp_all');
```

- [ ] **Step 2: 替换 zephyr-en-US.sql 种子数据块**

将第 492-500 行：

```sql
-- ============================================================

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'execute_shell', 'Execute arbitrary shell commands in workspace directory, supports foreground blocking and background running', 1, 1735800000, 1735800000
UNION ALL
    SELECT 'list_processes', 'List all background processes started by the current user and their PIDs', 1, 1735800000, 1735800000
UNION ALL
    SELECT 'kill_process', 'Terminate the specified background process by PID', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls);
```

改为：

```sql
-- ============================================================
-- Builtin Tool Control Seed Data (per-row idempotent, safe for incremental deployment)
-- ============================================================

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'execute_shell', 'Execute arbitrary shell commands in workspace directory, supports foreground blocking and background running', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'execute_shell');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'list_processes', 'List all background processes started by the current user and their PIDs', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'list_processes');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'kill_process', 'Terminate the specified background process by PID', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'kill_process');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'write_file', 'Write/create files, supports overwrite and append modes', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'write_file');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'edit_file', 'Edit files via precise string replacement', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'edit_file');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'use_skill', 'Invoke custom skill modules, extending agent capabilities', 0, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'use_skill');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'use_memory', 'Read/write persistent memory, retain context across sessions', 0, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'use_memory');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'search_knowledge', 'Semantic search document fragments in the knowledge base', 0, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'search_knowledge');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'mcp_all', 'Global MCP external tool switch (controls all MCP tools availability)', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'mcp_all');
```

- [ ] **Step 3: 替换 zephyr-ja-JP.sql 种子数据块**

将第 492-500 行：

```sql
-- ============================================================

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'execute_shell', 'ワークスペースディレクトリで任意のシェルコマンドを実行、フォアグラウンド/バックグラウンド対応', 1, 1735800000, 1735800000
UNION ALL
    SELECT 'list_processes', '現在のユーザーが起動したすべてのバックグラウンドプロセスとそのPIDを表示', 1, 1735800000, 1735800000
UNION ALL
    SELECT 'kill_process', '指定されたPIDのバックグラウンドプロセスを終了', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls);
```

改为：

```sql
-- ============================================================
-- ビルトインツール制御シードデータ（行ごとに冪等、増分デプロイ安全）
-- ============================================================

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'execute_shell', 'ワークスペースディレクトリで任意のシェルコマンドを実行、フォアグラウンド/バックグラウンド対応', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'execute_shell');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'list_processes', '現在のユーザーが起動したすべてのバックグラウンドプロセスとそのPIDを表示', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'list_processes');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'kill_process', '指定されたPIDのバックグラウンドプロセスを終了', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'kill_process');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'write_file', 'ファイルの書き込み/作成、上書き/追記対応', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'write_file');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'edit_file', 'ファイルを正確な文字列置換で編集', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'edit_file');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'use_skill', 'カスタムスキルモジュールを呼び出し、エージェント機能を拡張', 0, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'use_skill');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'use_memory', '永続記憶の読み書き、セッション間でコンテキストを保持', 0, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'use_memory');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'search_knowledge', 'ナレッジベースで関連ドキュメント断片を意味検索', 0, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'search_knowledge');

INSERT INTO zephyr_builtin_tool_controls (tool_name, description, require_admin, created_at, updated_at)
    SELECT 'mcp_all', 'MCP 外部ツールのグローバルスイッチ（全 MCP ツールの可用性を制御）', 1, 1735800000, 1735800000
WHERE NOT EXISTS (SELECT 1 FROM zephyr_builtin_tool_controls WHERE tool_name = 'mcp_all');
```

- [ ] **Step 4: Git add + commit**

```bash
git add src/main/resources/zephyr-zh-CN.sql src/main/resources/zephyr-en-US.sql src/main/resources/zephyr-ja-JP.sql
git commit -m "feat: 内置工具管控种子数据扩充至9行，逐行幂等+三语本地化

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 6: 端到端验证

**Files:** 无代码改动，纯验证

- [ ] **Step 1: 编译项目**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 2: 构建前端并复制到 target**

```bash
cd src/main/resources/static && npm run build && mkdir -p ../../../target/classes/static && cp -rf zephyr-ui ../../../target/classes/static/
```

- [ ] **Step 3: 启动后端**

```bash
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
mvn spring-boot:run -Dspring-boot.run.profiles=me
```

- [ ] **Step 4: 验证 /builtin-tool/list 返回 9 行**

```bash
curl -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/builtin-tool/list" | python3 -m json.tool
```

Expected: `body` 数组包含 9 个元素：execute_shell, list_processes, kill_process, write_file, edit_file, use_skill, use_memory, search_knowledge, mcp_all

- [ ] **Step 5: 验证种子数据幂等（重复启动不报错）**

重启后端，再次执行 Step 4 的 curl，确认仍然返回 9 行（不报 duplicate key 错误）。

- [ ] **Step 6: 验证 MCP 工具名加载日志**

检查启动日志中是否有：
```
[内置工具管控] MCP 工具名集合已加载: X 个
```

- [ ] **Step 7: 验证非 admin 用户被拦截 write_file**

创建一个非 admin 用户、用其调用 chat/security-evaluate 接口（或直接查 SecurityEvaluator 日志），确认 `write_file` 被 ROLE_CHECK 拦截。

检查启动日志中 ROLE_CHECK 记录格式：
```
[内置工具管控] 非 admin 用户被拦截: tool=write_file, lookupKey=write_file, user=xxx, roles=[...]
```

- [ ] **Step 8: 验证纯读工具不受影响**

检查日志确认 `read`、`grep` 等不在管控缓存中的工具，`requiresAdmin` 返回 false。

- [ ] **Step 9: Git log 确认提交链完整**

```bash
git log --oneline -5
```

Expected: 包含 5 个新 commit（Task 1-5）
