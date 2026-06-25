# 文件系统安全确认弹窗集成设计

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

### 改动 1：SecurityEvaluator 增强

#### 1.1 evaluate() 签名变更

```java
// 旧
public Result evaluate(String toolName, Map<String, Object> arguments, String userName, String mode)

// 新
public Result evaluate(String toolName, Map<String, Object> arguments, String userName, String mode, String conversationId)
```

#### 1.2 新增依赖注入

```java
@Resource
private com.github.hbq969.ai.zephyr.chat.dao.ChatDao chatDao;

@Resource
private com.github.hbq969.ai.zephyr.workspace.dao.WorkspaceDao workspaceDao;
```

#### 1.3 evaluateFileWrite() 新增 workspace 边界检查

在 HARD BLOCK 检查之后、bypass 检查**之后**、default/acceptEdits 判断**之前**插入边界检查：

```
1. HARD BLOCK 检查（不变）
2. bypass 模式 → allow 直接返回（不变）
3. === 新增：workspace 边界检查 ===
   a. 通过 conversationId → ChatDao.queryConversationById() → workspaceId
   b. workspaceId → WorkspaceDao.queryById() → workspacePath
   c. 如果 conversationId 为 null 或无 workspace → 跳过（降级放行）
   d. 规范化目标路径：
      - 相对路径 → workspacePath.resolve(目标路径)
      - 绝对路径 → Path.of(目标路径)
   e. normalize() 消除 . 和 ..
   f. toRealPath() 解析 symlink（失败则用 normalize 结果）
   g. 检查 targetPath.startsWith(workspacePath)
      - 不在 workspace 内 → Result.confirm("WORKSPACE_BOUNDARY", "...")
      - 在 workspace 内 → 继续
4. default 模式（非 acceptEdits）→ confirm 所有文件写入（不变）
5. acceptEdits 模式 → allow（仅 workspace 内的写入能走到这里）
```

关键：boundary check 在 bypass 之后，所以 bypass 模式完全不受影响；在 acceptEdits 放行之前，所以 workspace 外写入在 acceptEdits 模式下也会弹窗确认。

伪代码：

```java
private Result evaluateFileWrite(Map<String, Object> arguments, String mode, String conversationId) {
    String filePath = arguments.getOrDefault("file_path", "").toString();
    // ... 现有 HARD BLOCK 检查不变 ...

    // bypass 模式：跳过后续所有检查
    if ("bypass".equalsIgnoreCase(mode)) {
        return Result.allow();
    }

    // === 新增：workspace 边界检查 ===
    if (conversationId != null) {
        ConversationEntity conv = chatDao.queryConversationById(conversationId);
        if (conv != null && conv.getWorkspaceId() != null) {
            WorkspaceEntity ws = workspaceDao.queryById(conv.getWorkspaceId());
            if (ws != null && ws.getPath() != null) {
                Path wsPath = Path.of(ws.getPath()).toRealPath();
                Path targetPath = Path.of(filePath);
                if (!targetPath.isAbsolute()) {
                    targetPath = wsPath.resolve(targetPath);
                }
                targetPath = targetPath.normalize();
                try { targetPath = targetPath.toRealPath(); } catch (Exception ignored) {}

                if (!targetPath.startsWith(wsPath)) {
                    return Result.confirm("WORKSPACE_BOUNDARY",
                        "目标路径 " + filePath + " 不在工作空间 " + wsPath + " 内");
                }
            }
        }
    }

    // default 模式：所有 workspace 内文件编辑也需确认
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
| `conversationId` 为 null（新会话，dispatchTools 时已创建） | 不太可能发生，但降级跳过边界检查 |
| 会话无 workspace | 跳过边界检查 |
| workspace 路径包含 symlink | `toRealPath()` 解析真实路径后再比较 |
| 目标路径包含 `../` | `normalize()` 消除后再比较 |
| 目标路径是相对路径 | `wsPath.resolve()` 为基准解析 |
| 目标路径是绝对路径 | 直接 normalize |

### 改动 2：ChatServiceImpl.dispatchTools() 传参

`dispatchTools()` 已有 `conversationId` 参数，只需在调用时多传一个：

```java
// 旧
SecurityEvaluator.Result secResult = securityEvaluator.evaluate(
    tc.getName(), tc.getArguments(), userName, mode);

// 新
SecurityEvaluator.Result secResult = securityEvaluator.evaluate(
    tc.getName(), tc.getArguments(), userName, mode, conversationId);
```

### 改动 3：Prompt 模板更新

三个模式文件各加一段说明，让 LLM 知道直接调工具即可。

#### default.md

在文件系统安全规则末尾增加：

```markdown
- **系统自动确认**：当你调用 write_file / edit_file 访问任何路径时，系统的 SecurityEvaluator 会自动检查 workspace 边界。
  即使用户在消息中指定了 workspace 外的路径，也直接调用工具，系统会自动弹出确认窗口征求用户授权。不要在文字中反复询问用户"可以吗"。
```

#### accept-edits.md

同上。

#### bypass.md

不需要改，bypass 模式跳过所有检查。

### 不改的部分

| 项目 | 原因 |
|------|------|
| 前端 `ConfirmDialog.vue` | `confirm_action` 事件已支持展示任意 toolName/rule/ruleDetail |
| Shell 命令评估 | 不受影响 |
| bypass 模式 | 跳过所有检查，行为不变 |
| MCP 工具 | 不在 SecurityEvaluator 管辖范围 |

### 影响范围汇总

| 文件 | 改动类型 |
|------|----------|
| `security/SecurityEvaluator.java` | evaluate() 签名加 conversationId，evaluateFileWrite() 加工 workspace 边界检查，新增 ChatDao/WorkspaceDao 依赖 |
| `chat/service/impl/ChatServiceImpl.java` | dispatchTools() 调用 evaluate() 时多传 conversationId |
| `prompts/modes/default.md` | 文件系统安全规则增加"系统自动确认"说明 |
| `prompts/modes/accept-edits.md` | 同上 |

### 验证方式

1. 启动后端，在 default 模式下让 LLM 尝试 `write_file` 到 `/tmp/test.txt`（绝对路径，workspace 外）
2. 确认前端弹出 `ConfirmDialog`，显示 rule=`WORKSPACE_BOUNDARY`，toolName=`write_file`
3. 点击"允许本次"→ 文件写入成功
4. 点击"拒绝"→ 工具返回"操作已被用户拒绝"
5. 在 acceptEdits 模式下，workspace 内写入 → 不弹窗
6. 在 acceptEdits 模式下，workspace 外写入 → 弹窗确认
7. 在 bypass 模式下 → 不弹窗
