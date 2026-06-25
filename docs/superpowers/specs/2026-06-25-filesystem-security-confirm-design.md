# 文件系统安全确认弹窗集成设计

> **Ralplan 审核状态：APPROVE**
> Architect: ITERATE → Critic: APPROVE
> 审核日期：2026-06-25

## 问题

`fileSystemSecurity` prompt 规则（`modes/*.md`）告诉 LLM workspace 外访问需用户授权，但：
- LLM 无法主动触发 `waitForUserConfirm` 弹窗
- `SecurityEvaluator.evaluateFileWrite()` 不检查 workspace 边界
- 即使用户在消息中指定了 workspace 外的路径，也没有二次确认机制

而 `securityRules` 那条线（shell 命令）已经有完整的代码拦截 + 弹窗确认闭环。

## 目标

`fileSystemSecurity` 规则触发确认时，走和 `securityRules` 一样的 `waitForUserConfirm` 弹窗确认流程。所有安全判断集中在 `SecurityEvaluator` 中。

## 方案

### 核心原则

**LLM 只管调工具，安全判断全交给 SecurityEvaluator。**

用户在消息中指定 workspace 外路径 → LLM 直接调 `write_file`/`edit_file` → `SecurityEvaluator` 拦截 → 弹窗二次确认 → 用户亲眼确认后执行。

### 设计决策（ADR）

- **Decision**: 在 `SecurityEvaluator.evaluate()` 中新增 `WorkspaceBoundary` 值对象参数，在 `evaluateFileWrite()` 中做 workspace 边界检查
- **Drivers**: DB 重复查询性能、单一职责、异常安全降级
- **Alternatives considered**:
  - B: SecurityEvaluator 注入 DAO 自己查（职责膨胀 + N 次 DB 查询）
  - C: DAO 查询 + 缓存（引入缓存复杂度、失效问题）
- **Why A**: dispatchTools 循环外一次性解析 workspace 路径，传入 evaluate() 为纯值对象。SecurityEvaluator 保持纯评估器角色，零额外失败模式
- **Consequences**: dispatchTools 新增 `resolveWorkspaceBoundary()` 辅助方法；SecurityEvaluator 新增 `WorkspaceBoundary` 内部类型
- **Follow-ups**: write_file/edit_file 的执行依赖外部 MCP filesystem server（如 anthropic 的 filesystem MCP server），需在文档中说明

---

### 改动 1：SecurityEvaluator — 新增 WorkspaceBoundary + 边界检查

#### 1.1 新增 WorkspaceBoundary 值对象

```java
// SecurityEvaluator.java 内部
public record WorkspaceBoundary(Path path) {
    public static final WorkspaceBoundary NONE = new WorkspaceBoundary(null);

    public boolean isPresent() { return path != null; }

    public boolean contains(Path target) {
        return isPresent() && target.startsWith(path);  // isPresent() 防止 NPE
    }
}
```

零依赖的纯值对象，可独立单元测试。

#### 1.2 evaluate() 签名变更

```java
// 旧
public Result evaluate(String toolName, Map<String, Object> arguments, String userName, String mode)

// 新
public Result evaluate(String toolName, Map<String, Object> arguments, String userName, String mode,
                       WorkspaceBoundary boundary)
```

SecurityEvaluator 不注入任何新依赖（ChatDao/WorkspaceDao），保持纯评估器。

#### 1.3 evaluateFileWrite() 新增 workspace 边界检查

检查顺序：HARD BLOCK → bypass → **boundary check** → default/acceptEdits

```java
private Result evaluateFileWrite(Map<String, Object> arguments, String mode, WorkspaceBoundary boundary) {
    String filePath = arguments.getOrDefault("file_path", "").toString();
    if (filePath.isEmpty()) {
        filePath = arguments.getOrDefault("filePath", "").toString();
    }

    // HARD BLOCK：修改安全文件（不变）
    ...
    // HARD BLOCK：修改 application.yml（不变）
    ...

    // bypass 模式：跳过后续所有检查
    if ("bypass".equalsIgnoreCase(mode)) {
        return Result.allow();
    }

    // === 新增：workspace 边界检查 ===
    if (boundary.isPresent()) {
        Path targetPath = Path.of(filePath);
        if (!targetPath.isAbsolute()) {
            targetPath = boundary.path().resolve(targetPath);
        }
        targetPath = targetPath.normalize();
        try { targetPath = targetPath.toRealPath(); } catch (IOException ignored) {}

        if (!boundary.contains(targetPath)) {
            return Result.confirm("WORKSPACE_BOUNDARY",
                "目标路径 " + filePath + " 不在工作空间 " + boundary.path() + " 内");
        }
        // 在 workspace 内 → 继续
    }

    // default 模式：所有 workspace 内文件编辑需确认
    if (!"acceptEdits".equalsIgnoreCase(mode)) {
        return Result.confirm("MODE_DEFAULT", "Default 模式下文件写入需要用户确认");
    }

    // acceptEdits 模式：workspace 内文件编辑自动放行
    return Result.allow();
}
```

#### 1.4 边界情况

| 情况 | 行为 |
|------|------|
| `boundary` 为 `NONE`（conversationId null 或 无 workspace） | 跳过边界检查 |
| workspace 路径不存在（目录被外部删除） | `Files.exists()` 为 false → 用 `normalize().toAbsolutePath()` + warn 日志 |
| `toRealPath()` 抛 IOException | 降级用 `normalize()` 结果 |
| 目标路径包含 `../` | `normalize()` 消除后比较 |
| 目标路径是相对路径 | `boundary.path().resolve()` 解析 |
| 目标路径是绝对路径 | 直接 normalize |

---

### 改动 2：ChatServiceImpl — dispatchTools() 循环外解析 workspace

#### 2.1 新增 resolveWorkspaceBoundary() 辅助方法

```java
private WorkspaceBoundary resolveWorkspaceBoundary(String conversationId) {
    if (conversationId == null) return WorkspaceBoundary.NONE;
    try {
        ConversationEntity conv = chatDao.queryConversationById(conversationId);
        if (conv == null || conv.getWorkspaceId() == null) return WorkspaceBoundary.NONE;
        WorkspaceEntity ws = workspaceDao.queryById(conv.getWorkspaceId());
        if (ws == null || ws.getPath() == null) return WorkspaceBoundary.NONE;
        Path wsPath = Path.of(ws.getPath());
        if (!Files.exists(wsPath)) {
            log.warn("[安全] workspace 路径不存在，使用规范化形式: {}", wsPath);
            return new WorkspaceBoundary(wsPath.normalize().toAbsolutePath());
        }
        return new WorkspaceBoundary(wsPath.toRealPath());
    } catch (IOException e) {
        log.warn("[安全] 解析 workspace 路径失败 conv={}: {}", conversationId, e.getMessage());
        return WorkspaceBoundary.NONE;
    }
}
```

#### 2.2 dispatchTools() 调用点

```java
// 循环外一次性解析
WorkspaceBoundary boundary = resolveWorkspaceBoundary(conversationId);

for (LlmResult.ToolCall tc : toolCalls) {
    SecurityEvaluator.Result secResult = securityEvaluator.evaluate(
        tc.getName(), tc.getArguments(), userName, mode, boundary);
    // ...
}
```

---

### 改动 3：Prompt 模板更新

#### default.md

在文件系统安全规则末尾增加：

```markdown
- **系统自动确认**：调用 write_file / edit_file 时，SecurityEvaluator 自动检查 workspace 边界。
  即使用户在消息中指定了 workspace 外的路径，也直接调用工具，系统会自动弹出确认窗口。
  不要在文字中反复询问用户"可以吗"。
```

#### accept-edits.md

同上。

#### bypass.md

不需要改。

---

### 不改的部分

| 项目 | 原因 |
|------|------|
| 前端 `ConfirmDialog.vue` | `confirm_action` 事件已支持展示任意 toolName/rule/ruleDetail |
| Shell 命令评估 | 不受影响 |
| bypass 模式 | 跳过所有检查，行为不变 |
| SecurityEvaluator 依赖 | 不注入 ChatDao/WorkspaceDao，保持纯评估器 |

### 前置依赖

`write_file` / `edit_file` 工具依赖外部 MCP filesystem server（如 `@anthropic/mcp-server-filesystem`）提供。如果未配置该 MCP server，工具调用会走 `default` case 的 `executeMcpTool()` 并返回工具未找到错误。安全评估层不关心工具如何执行，只负责拦截。

### 影响范围汇总

| 文件 | 改动类型 |
|------|----------|
| `security/SecurityEvaluator.java` | 新增 WorkspaceBoundary 值对象，evaluate() 签名加 boundary 参数，evaluateFileWrite() 加工 workspace 边界检查。**不注入新依赖** |
| `chat/service/impl/ChatServiceImpl.java` | 新增 resolveWorkspaceBoundary() 辅助方法，dispatchTools() 循环外解析 workspace 并在 evaluate() 调用时传入 |
| `prompts/modes/default.md` | 文件系统安全规则增加"系统自动确认"说明 |
| `prompts/modes/accept-edits.md` | 同上 |

### 验证方式

1. 启动后端，在 default 模式下发送消息 "请在 /tmp/test.txt 写入 hello"
2. 确认前端弹出 `ConfirmDialog`，显示 rule=`WORKSPACE_BOUNDARY`，toolName=`write_file`
3. 点击"允许本次"→ 文件写入成功
4. 点击"拒绝"→ 工具返回"操作已被用户拒绝"
5. 在 acceptEdits 模式下，workspace 内写入 → 不弹窗
6. 在 acceptEdits 模式下，workspace 外写入 → 弹窗确认
7. 在 bypass 模式下 → 不弹窗
