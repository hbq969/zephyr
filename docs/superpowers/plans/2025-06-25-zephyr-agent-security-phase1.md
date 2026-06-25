# zephyr Agent 安全体系 Phase 1 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 HARD BLOCK → SOFT BLOCK → ALLOW 三级安全判定 + 用户确认流程

**Architecture:** LLM 通过 system prompt 中的安全规则自约束 + Java 层 SecurityEvaluator 做模式匹配防御。SOFT BLOCK 通过 SSE 推送确认事件到前端，用户在弹窗中决策。两层叠加：工作空间边界（沿用）正交于安全规则（新增）。

**Tech Stack:** Java 17, SpringBoot 3.5.4, Vue 3 + TypeScript, SSE, Element Plus

## Global Constraints

- Java 编译: `mvn clean compile -q`（禁止只用 `mvn compile -q`）
- 安全规则在 `src/main/resources/prompts/security/*.md`，不在 application.yml
- 工作空间模式 default/acceptEdits/bypass 保留不变
- 前端配色使用 Element Plus 主题色变量
- HARD BLOCK 以 LLM 自遵守为主，SecurityEvaluator 做高置信度正则预筛（数据外泄、chmod 777、--no-verify 等模式）；Phase 3 扩展为更全面的代码级强制
- Phase 1 工具调用串行处理（for 循环），批次独立决策推迟到 Phase 2
- `allow-session` 按钮在 Phase 1 隐藏，会话级 permissiveness 在 Phase 2 实现

---

## 文件结构总览

```
新增:
  src/main/java/.../config/ZephyrConfigProperties.java  ← 加 Security 内部类
  src/main/java/.../security/PromptLoader.java
  src/main/java/.../security/SecurityEvaluator.java
  src/main/java/.../security/AuditLogger.java
  src/main/resources/prompts/security/tool-risk-rules.md    (已存在)
  src/main/resources/prompts/security/hard-block.md         (已存在)
  src/main/resources/prompts/security/soft-block.md         (已存在)
  src/main/resources/prompts/security/allow-exceptions.md   (已存在)
  src/main/resources/prompts/security/user-intent.md        (已存在)
  src/main/resources/prompts/role.md
  src/main/resources/prompts/modes/default.md
  src/main/resources/prompts/modes/accept-edits.md
  src/main/resources/prompts/modes/bypass.md
  src/main/resources/static/src/components/ConfirmDialog.vue

修改:
  src/main/java/.../chat/service/ChatService.java          ← 加 confirm 接口方法
  src/main/java/.../chat/service/ContextBuilder.java       ← 重构，用 PromptLoader
  src/main/java/.../chat/service/impl/ChatServiceImpl.java ← dispatchTools() 插入安全评估
  src/main/java/.../chat/ctrl/ChatCtrl.java                ← 加 /confirm 端点
  src/main/resources/static/src/types/chat.ts              ← 加 ConfirmEvent 类型
  src/main/resources/static/src/views/chat/ChatView.vue    ← 集成 ConfirmDialog
  src/main/resources/static/src/i18n/common.ts             ← 加确认弹窗文案
  src/main/resources/application.yml                       ← 加 security 配置
```

---

### Task 1: 安全配置 + AuditLogger

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java`
- Create: `src/main/java/com/github/hbq969/ai/zephyr/security/AuditLogger.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Produces: `ZephyrConfigProperties.Security` 配置类，`AuditLogger` 服务类

- [ ] **Step 1: 在 ZephyrConfigProperties 末尾加 Security 内部类**

在文件末尾 `}` 之前插入：

```java
    // ================================================================
    //  安全配置
    // ================================================================

    @Data
    public static class Security {
        /** 是否启用安全评估，默认 true */
        private boolean enabled = true;
        /** 用户确认超时秒数，超时自动拒绝，默认 300 */
        private int confirmTimeoutSeconds = 300;
        /** 连续绕过 HARD BLOCK 最大次数，超出强制终止，默认 3 */
        private int maxBypassAttempts = 3;
        /** 审计日志 */
        private Audit audit = new Audit();

        @Data
        public static class Audit {
            /** 是否启用审计日志，默认 true */
            private boolean enabled = true;
            /** 审计日志路径，默认 ~/.zephyr/audit.log */
            private String logPath = System.getProperty("user.home") + "/.zephyr/audit.log";
        }
    }
```

同时加字段声明（在现有字段区域末尾）：

```java
    /** 安全相关配置 */
    private Security security = new Security();
```

- [ ] **Step 2: 在 application.yml 末尾加 security 配置**

```yaml
# ================================================================
#  安全配置
# ================================================================
zephyr:
  security:
    # 是否启用安全评估，默认 true
    enabled: true
    # 用户确认超时秒数，默认 300
    confirm-timeout-seconds: 300
    # 连续绕过 HARD BLOCK 最大次数，默认 3
    max-bypass-attempts: 3
    audit:
      # 是否启用审计日志，默认 true
      enabled: true
      # 审计日志路径
      log-path: ${user.home}/.zephyr/audit.log
```

- [ ] **Step 3: 创建 AuditLogger**

```java
package com.github.hbq969.ai.zephyr.security;

import com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

@Slf4j
@Component
public class AuditLogger {

    @Resource
    private ZephyrConfigProperties cfg;

    private Path logPath;

    @PostConstruct
    void init() throws IOException {
        logPath = Paths.get(cfg.getSecurity().getAudit().getLogPath());
        Files.createDirectories(logPath.getParent());
    }

    public void log(String event, String toolName, String decision, String reason, String userName) {
        if (!cfg.getSecurity().getAudit().isEnabled()) return;
        try {
            String line = String.format("%s | %s | %s | %s | %s | %s%n",
                    Instant.now(), userName, event, toolName, decision, reason);
            Files.writeString(logPath, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("审计日志写入失败: {}", e.getMessage());
        }
    }
}
```

- [ ] **Step 4: 编译验证**

```bash
mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java \
        src/main/java/com/github/hbq969/ai/zephyr/security/AuditLogger.java \
        src/main/resources/application.yml
git commit -m "feat: 添加安全配置和审计日志"
```

---

### Task 2: PromptLoader — 从 classpath 加载 md 文件

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/security/PromptLoader.java`

**Interfaces:**
- Produces: `PromptLoader.load(name) → String`, `PromptLoader.render(name, vars) → String`

- [ ] **Step 1: 创建 PromptLoader**

```java
package com.github.hbq969.ai.zephyr.security;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 从 classpath 加载 prompt 模板，支持 ~/.zephyr/prompts/ 用户覆盖。
 */
@Slf4j
@Component
public class PromptLoader {

    private static final String USER_PROMPTS_DIR = System.getProperty("user.home") + "/.zephyr/prompts";
    private static final Pattern VAR_PATTERN = Pattern.compile("\\{(\\w+)}");

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 加载 prompt 文件内容（无变量替换）。
     * 查找优先级：~/.zephyr/prompts/{name} > classpath:prompts/{name}
     */
    public String load(String name) {
        return cache.computeIfAbsent(name, k -> {
            // 1. 用户自定义覆盖
            Path userPath = Paths.get(USER_PROMPTS_DIR, k);
            if (Files.exists(userPath)) {
                log.info("Prompt '{}' 使用用户自定义覆盖: {}", k, userPath);
                try {
                    return Files.readString(userPath, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.warn("读取用户 prompt 失败: {}", userPath, e);
                }
            }
            // 2. classpath 默认
            try {
                ClassPathResource res = new ClassPathResource("prompts/" + k);
                return res.getContentAsString(StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("加载 prompt 失败: {}", k, e);
                return "";
            }
        });
    }

    /**
     * 加载并替换模板变量。变量格式：{variableName}
     */
    public String render(String name, Map<String, String> vars) {
        String template = load(name);
        if (vars == null || vars.isEmpty()) return template;
        return VAR_PATTERN.matcher(template).replaceAll(match -> {
            String varName = match.group(1);
            return vars.getOrDefault(varName, "");
        });
    }

    /** 清除缓存（热重载时使用） */
    public void clearCache() {
        cache.clear();
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/security/PromptLoader.java
git commit -m "feat: 添加 PromptLoader，支持 classpath 和用户目录 prompt 加载"
```

---

### Task 3: 迁移现有 prompt 到 md 文件

**Files:**
- Create: `src/main/resources/prompts/role.md`
- Create: `src/main/resources/prompts/modes/default.md`
- Create: `src/main/resources/prompts/modes/accept-edits.md`
- Create: `src/main/resources/prompts/modes/bypass.md`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/ContextBuilder.java`

**Interfaces:**
- Consumes: `PromptLoader.load()`, `PromptLoader.render()`
- Produces: 重构后的 `ContextBuilder.build()` 使用 PromptLoader

- [ ] **Step 1: 创建 prompts/role.md**

```markdown
---
name: role
description: zephyr 角色定义和核心行为约束
variables:
  - fileSystemSecurity
  - skillIndex
  - memoryIndex
  - knowledgeBaseIndex
  - workspaceInfo
---

你是一个 AI 助手，名为 zephyr。

你可以使用 MCP 工具获取实时数据，使用技能（Skill）获取特定任务的详细指导，
查看用户记忆（Memory）了解历史上下文和偏好。

## 文件处理
用户上传文件后，消息中会包含文件名、路径和推荐的 skill。
**必须先用 use_skill 加载对应技能，获得处理该类型文件的完整指导，然后严格按指导操作。**
你不具备直接读取文件内容的能力，依赖技能中的工具来完成解析。

{fileSystemSecurity}

## 工具使用说明
- 优先使用 MCP 工具获取实时准确的数据
- 需要特定任务的详细指导时，使用 use_skill 工具
- 需要了解用户的背景或偏好时，使用 use_memory 工具
- 你可以多次调用工具，直到获得足够信息后再回答

## 命令约定
当用户消息中以下列格式引用工具或技能时，必须调用对应工具，禁止只回复文字而不调用工具：

### 前缀格式（tag 插入）
- `MCP/工具名` → 调用同名 MCP 工具
- `Skill/技能名` → 调用 use_skill(skill_name="技能名")
- `Memory/记忆名` → 调用 use_memory(memory_name="记忆名")

### 斜杠格式（手动输入，兼容保留）
- `/工具名`（如 `/browser_navigate`）→ 调用同名 MCP 工具
- `/技能名`（如 `/frontend-design`）→ 调用 use_skill(skill_name="技能名") 加载该技能
- `/记忆名` → 调用 use_memory(memory_name="记忆名") 查看该记忆

## 安全规则

**每次工具调用前必须进行安全评估。** 详细的安全评估流程和规则见以下内容：

{securityRules}

---
**核心原则：多问一次的成本远低于做错的成本。不确定时就暂停并征求用户确认。**
```

- [ ] **Step 2: 创建 prompts/modes/default.md**

```markdown
---
name: filesystem-default
description: default 模式文件系统安全规则
---

## 文件系统安全（Default 模式）
- **路径规范化（强制执行）**：任何文件操作前，必须先将路径解析为规范化绝对路径：
  1. 相对路径 → 以工作空间路径为基准解析为绝对路径
  2. 消除路径中的 `.` 和 `..` 成分
  3. 如果路径中包含符号链接，解析其真实路径
- **边界检查**：规范化后的绝对路径必须以工作空间路径开头（含结尾 `/` 的精确前缀匹配），否则视为工作空间外访问
- **路径遍历禁止**：严禁使用 `../` 或任何形式的路径遍历访问父目录。即使用户在消息中指定了含 `../` 的路径，也必须拒绝并提示用户该路径不在工作空间内
- **工作空间目录内**：规范化后确认在边界内的，直接读写，无需确认
- **工作空间目录外**：每次访问都需用户明确回复"同意"授权。授权仅当次有效，下次访问同一路径仍需重新授权
- **即使用户在消息中指定了工作空间外的路径（含相对路径、`../` 等），也必须先征得授权，不得直接执行**
```

- [ ] **Step 3: 创建 prompts/modes/accept-edits.md**

```markdown
---
name: filesystem-accept-edits
description: acceptEdits 模式文件系统安全规则
---

## 文件系统安全（Accept Edits 模式）
- **路径规范化（强制执行）**：任何文件操作前，必须先将路径解析为规范化绝对路径：
  1. 相对路径 → 以工作空间路径为基准解析为绝对路径
  2. 消除路径中的 `.` 和 `..` 成分
  3. 如果路径中包含符号链接，解析其真实路径
- **边界检查**：规范化后的绝对路径必须以工作空间路径开头（含结尾 `/` 的精确前缀匹配），否则视为工作空间外访问
- **路径遍历禁止**：严禁使用 `../` 或任何形式的路径遍历访问父目录
- **工作空间目录内**：规范化后确认在边界内的，直接读写，无需确认
- **工作空间目录外**：同一文件首次访问需用户明确回复"同意"授权，授权后在当前对话内持续有效，后续访问无需再次确认。不同文件仍需各自首次授权
- **即使用户在消息中指定了工作空间外的路径（含相对路径、`../` 等），也必须先征得授权，不得直接执行**
```

- [ ] **Step 4: 创建 prompts/modes/bypass.md**

```markdown
---
name: filesystem-bypass
description: bypass 模式文件系统规则
---

## 文件系统（Bypass 模式 — 无限制）
你拥有完整文件系统访问权限，不再受工作空间目录约束。请对破坏性操作保持谨慎。
生成新文件时优先使用绝对路径。

**注意：即使没有工作空间边界限制，HARD BLOCK 安全规则（数据外泄、凭证泄露、权限提升等）仍然生效。**
```

- [ ] **Step 5: 重构 ContextBuilder — 删除静态常量，改用 PromptLoader**

在 `ContextBuilder.java` 中：

删除三个 `private static final String` 常量（`FS_DEFAULT`、`FS_ACCEPT_EDITS`、`FS_BYPASS`）和 `ROLE_PROMPT`。

添加注入：

```java
@Resource
private PromptLoader promptLoader;
```

替换 `fileSystemSecurityPrompt()` 方法：

```java
private String fileSystemSecurityPrompt(String mode) {
    if ("bypass".equalsIgnoreCase(mode)) return promptLoader.load("modes/bypass.md");
    if ("acceptEdits".equalsIgnoreCase(mode)) return promptLoader.load("modes/accept-edits.md");
    return promptLoader.load("modes/default.md");
}
```

替换 `build()` 方法中的 system prompt 组装逻辑（约第 176 行），从手动拼接改为：

```java
// 组装安全规则（合并所有 security prompt 文件）
StringBuilder securityRules = new StringBuilder();
securityRules.append(promptLoader.load("security/tool-risk-rules.md")).append("\n\n");
securityRules.append(promptLoader.load("security/hard-block.md")).append("\n\n");
securityRules.append(promptLoader.load("security/soft-block.md")).append("\n\n");
securityRules.append(promptLoader.load("security/allow-exceptions.md")).append("\n\n");
securityRules.append(promptLoader.load("security/user-intent.md"));

// 渲染角色 prompt
Map<String, String> vars = new LinkedHashMap<>();
vars.put("fileSystemSecurity", fileSystemSecurityPrompt(mode));
vars.put("skillIndex", skillIndex);
vars.put("memoryIndex", memoryIndex);
vars.put("knowledgeBaseIndex", kbIndex);
vars.put("workspaceInfo", workspaceInfo);
vars.put("securityRules", securityRules.toString());
String systemPrompt = promptLoader.render("role.md", vars);
```

- [ ] **Step 6: 编译验证**

```bash
mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/prompts/role.md \
        src/main/resources/prompts/modes/ \
        src/main/java/com/github/hbq969/ai/zephyr/chat/service/ContextBuilder.java
git commit -m "feat: 迁移 prompt 到外置 md 文件，ContextBuilder 改用 PromptLoader"
```

---

### Task 4: SecurityEvaluator — Java 层模式匹配防御

**Files:**
- Create: `src/main/java/com/github/hbq969/ai/zephyr/security/SecurityEvaluator.java`

**Interfaces:**
- Consumes: `ZephyrConfigProperties.Security`, `AuditLogger`
- Produces: `SecurityEvaluator.Result { decision: ALLOW|CONFIRM|BLOCK, rule: String, reason: String }`

- [ ] **Step 1: 创建 SecurityEvaluator**

```java
package com.github.hbq969.ai.zephyr.security;

import com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Java 层安全评估器，在 LLM 自评估之外做模式匹配防御。
 * <p>
 * Phase 1：聚焦于高置信度的模式匹配（命令关键词、路径模式）。
 * 复杂语义判定由 LLM 通过 system prompt 中的安全规则自行处理。
 */
@Slf4j
@Component
public class SecurityEvaluator {

    @Resource
    private ZephyrConfigProperties cfg;

    @Resource
    private AuditLogger auditLogger;

    // === HARD BLOCK 模式（代码级确定性检查） ===

    private static final List<Pattern> HARD_BLOCK_SHELL_PATTERNS = List.of(
            Pattern.compile(".*(?:cat|head|tail|read).*(?:\\.env|credentials|private.?key|secret|token|password).*(?:\\||>|curl|http|nc ).*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*chmod\\s+777.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*--no-verify.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*--insecure.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*Set-ExecutionPolicy\\s+Bypass.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*sudoers.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*kubectl\\s+delete\\s+(?:secret|configmap).*", Pattern.CASE_INSENSITIVE)
    );

    private static final List<String> HARD_BLOCK_PATH_PREFIXES = List.of(
            "prompts/security/",
            "prompts/modes/",
            "prompts/tools/"
    );

    // === SOFT BLOCK 模式 ===

    private static final List<Pattern> SOFT_BLOCK_SHELL_PATTERNS = List.of(
            Pattern.compile(".*\\brm\\s+(-[a-zA-Z]*[rf][a-zA-Z]*\\s+)+.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*git\\s+push\\s+.*(?:--force|--force-with-lease).*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*git\\s+(?:reset\\s+--hard|clean\\s+-fdx).*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*(?:curl|wget).*(?:\\||>|bash|sh|python|eval|exec).*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*(?:DROP\\s+(?:TABLE|DATABASE)|TRUNCATE|DELETE\\s+FROM).*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*kubectl\\s+delete.*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*docker\\s+(?:rm|stop|kill).*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*(?:kill\\s+-9|pkill).*", Pattern.CASE_INSENSITIVE),
            Pattern.compile(".*>\\s*\\S+.*", Pattern.CASE_INSENSITIVE)
    );

    /** 评估结果 */
    public enum Decision { ALLOW, CONFIRM, BLOCK }

    public record Result(Decision decision, String rule, String reason) {
        public static Result allow() { return new Result(Decision.ALLOW, "", ""); }
        public static Result confirm(String rule, String reason) { return new Result(Decision.CONFIRM, rule, reason); }
        public static Result block(String rule, String reason) { return new Result(Decision.BLOCK, rule, reason); }
    }

    /**
     * 评估工具调用。仅对 execute_shell 和文件写入类工具做模式匹配，
     * 其余工具调用返回 ALLOW（由 LLM 自评估负责）。
     */
    public Result evaluate(String toolName, Map<String, Object> arguments, String userName) {
        if (!cfg.getSecurity().isEnabled()) {
            return Result.allow();
        }

        Result result = switch (toolName) {
            case "execute_shell" -> evaluateShell(arguments);
            case "write_file", "edit_file" -> evaluateFileWrite(arguments);
            default -> Result.allow();
        };

        // 审计日志
        if (result.decision() != Decision.ALLOW) {
            auditLogger.log("SECURITY_CHECK", toolName, result.decision().name(),
                    result.rule() + ": " + result.reason(), userName);
        }

        return result;
    }

    private Result evaluateShell(Map<String, Object> arguments) {
        if (arguments == null || !arguments.containsKey("command")) {
            return Result.allow();
        }
        Object cmdObj = arguments.get("command");
        if (cmdObj == null) return Result.allow();
        String command = cmdObj.toString().trim();

        // 1. HARD BLOCK 检查
        for (Pattern p : HARD_BLOCK_SHELL_PATTERNS) {
            if (p.matcher(command).matches()) {
                return Result.block("HARD_BLOCK", "命令匹配安全红线规则，禁止执行");
            }
        }

        // 2. SOFT BLOCK 检查
        for (Pattern p : SOFT_BLOCK_SHELL_PATTERNS) {
            if (p.matcher(command).matches()) {
                return Result.confirm("SOFT_BLOCK", "该命令具有破坏性，需要用户确认");
            }
        }

        return Result.allow();
    }

    private Result evaluateFileWrite(Map<String, Object> arguments) {
        String filePath = arguments.getOrDefault("file_path", "").toString();
        if (filePath.isEmpty()) {
            filePath = arguments.getOrDefault("filePath", "").toString();
        }

        // HARD BLOCK：修改安全 prompt 文件（大小写不敏感）
        String lowerPath = filePath.toLowerCase();
        for (String prefix : HARD_BLOCK_PATH_PREFIXES) {
            if (lowerPath.contains(prefix)) {
                return Result.block("HARD_BLOCK", "禁止修改安全规则文件: " + filePath);
            }
        }

        // HARD BLOCK：修改 application.yml
        if (filePath.endsWith("application.yml") || filePath.endsWith("application-me.yml")
                || filePath.endsWith("application-prod.yml")) {
            return Result.block("HARD_BLOCK", "禁止修改应用配置文件: " + filePath);
        }

        return Result.allow();
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/security/SecurityEvaluator.java
git commit -m "feat: 添加 SecurityEvaluator，代码级模式匹配防御"
```

---

### Task 5: ChatServiceImpl — dispatchTools() 集成安全评估

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java`

**Interfaces:**
- Consumes: `SecurityEvaluator`, `AuditLogger`, `ZephyrConfigProperties`
- Produces: 安全评估插入 `dispatchTools()` 流程

- [ ] **Step 1: 注入依赖**

在 `ChatServiceImpl` 类顶部添加：

```java
@Resource
private SecurityEvaluator securityEvaluator;

@Resource
private AuditLogger auditLogger;

// 绕过重试计数器（会话级）
private final Map<String, Integer> bypassAttempts = new ConcurrentHashMap<>();
```

- [ ] **Step 2: 修改 dispatchTools() 方法**

替换现有 `dispatchTools()` 方法：

```java
private List<Map<String, Object>> dispatchTools(List<LlmResult.ToolCall> toolCalls,
        String userName, List<String> enabledKbIds, String conversationId,
        SseEmitter emitter, ConversationSessionManager.SessionHandle handle) {
    List<Map<String, Object>> results = new ArrayList<>();

    for (LlmResult.ToolCall tc : toolCalls) {

        // === 安全评估（新增） ===
        SecurityEvaluator.Result secResult = securityEvaluator.evaluate(
                tc.getName(), tc.getArguments(), userName);

        if (secResult.decision() == SecurityEvaluator.Decision.BLOCK) {
            // HARD BLOCK → 拒绝
            log.warn("[安全] HARD_BLOCK cid={}, tool={}, rule={}",
                    conversationId, tc.getName(), secResult.rule());
            results.add(Map.of("role", "tool", "tool_call_id", tc.getId(), "content",
                    "操作被拒绝（安全规则: " + secResult.rule() + "）— " + secResult.reason()));
            continue;
        }

        if (secResult.decision() == SecurityEvaluator.Decision.CONFIRM) {
            // SOFT BLOCK → 推送确认事件，等待用户
            // confirmId 编码 userName，防止跨用户确认
            String confirmId = userName + ":" + cn.hutool.core.util.IdUtil.fastSimpleUUID().substring(0, 12);
            try {
                emitter.send(SseEmitter.event().name("message")
                        .data(ChatEvent.builder()
                                .type("confirm_action")
                                .toolName(tc.getName())
                                .content(gson.toJson(Map.of(
                                        "confirmId", confirmId,
                                        "toolName", tc.getName(),
                                        "toolInput", tc.getArguments(),
                                        "rule", secResult.rule(),
                                        "ruleDetail", secResult.reason()
                                )))
                                .build()));
            } catch (IOException e) {
                log.warn("推送 confirm_action 事件失败: {}", e.getMessage());
                results.add(Map.of("role", "tool", "tool_call_id", tc.getId(), "content",
                        "操作需确认但推送失败: " + e.getMessage()));
                continue;
            }

            // 阻塞等待用户确认
            ConfirmResult confirm = waitForUserConfirm(confirmId,
                    cfg.getSecurity().getConfirmTimeoutSeconds());

            if (confirm == null || !confirm.allowed()) {
                log.info("[安全] 用户拒绝 SOFT_BLOCK cid={}, tool={}",
                        conversationId, tc.getName());
                auditLogger.log("USER_DENIED", tc.getName(), "DENIED",
                        secResult.rule(), userName);
                results.add(Map.of("role", "tool", "tool_call_id", tc.getId(), "content",
                        "操作已被用户拒绝: " + secResult.reason()));
                continue;
            }

            log.info("[安全] 用户确认 SOFT_BLOCK cid={}, tool={}",
                    conversationId, tc.getName());
        }

        // === 执行工具（原有逻辑） ===
        String content;
        try {
            content = switch (tc.getName()) {
                case "use_skill" -> executeUseSkill(tc.getArguments().get("skill_name").toString(), userName);
                case "use_memory" -> executeUseMemory(tc.getArguments().get("memory_name").toString(), userName);
                case "search_knowledge" -> executeSearchKnowledge(tc.getArguments(), enabledKbIds);
                case "execute_shell" -> executeShell(tc.getArguments(), userName, conversationId);
                case "list_processes" -> listProcesses(userName);
                case "kill_process" -> killProcess(tc.getArguments(), userName);
                default -> executeMcpTool(tc.getName(), tc.getArguments(), userName);
            };
        } catch (Exception e) {
            content = "工具执行错误: " + e.getMessage();
        }
        content = sanitizeToolOutput(content);
        results.add(Map.of("role", "tool", "tool_call_id", tc.getId(), "content",
                content.length() > cfg.getChat().getToolOutput().getMaxLength()
                        ? content.substring(0, cfg.getChat().getToolOutput().getMaxLength()) + "..."
                        : content));
    }

    // 检查绕过重试
    String cid = conversationId;
    if (!results.isEmpty()) {
        boolean hasBlock = results.stream().anyMatch(r ->
                r.get("content").toString().contains("操作被拒绝（安全规则"));
        if (hasBlock) {
            int attempts = bypassAttempts.merge(cid, 1, Integer::sum);
            if (attempts >= cfg.getSecurity().getMaxBypassAttempts()) {
                log.warn("[安全] 连续 {} 次 HARD_BLOCK 绕过尝试，强制终止 cid={}", attempts, cid);
                bypassAttempts.remove(cid);
                throw new ConversationSessionManager.CancelSessionException(cid);
            }
        } else {
            bypassAttempts.remove(cid);
        }
    }

    return results;
}
```

- [ ] **Step 3: 添加用户确认等待逻辑**

在 `ChatServiceImpl` 类中添加：

```java
// 待确认请求池
private final Map<String, ConfirmResult> confirmResults = new ConcurrentHashMap<>();

private record ConfirmResult(boolean allowed) {}

/**
 * 阻塞等待用户确认。前端通过 /confirm 端点写入结果。
 */
private ConfirmResult waitForUserConfirm(String confirmId, int timeoutSeconds) {
    try {
        synchronized (confirmResults) {
            long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                ConfirmResult r = confirmResults.remove(confirmId);
                if (r != null) return r;
                confirmResults.wait(Math.min(1000, deadline - System.currentTimeMillis()));
            }
        }
        log.warn("[安全] 确认超时 confirmId={}", confirmId);
        auditLogger.log("TIMEOUT", "", "DENIED", "确认超时", "");
        return null; // 超时 = 拒绝
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return null;
    }
}

/** 供 ChatCtrl 调用，写入用户确认结果 */
public void confirm(String confirmId, boolean allowed) {
    synchronized (confirmResults) {
        confirmResults.put(confirmId, new ConfirmResult(allowed));
        confirmResults.notifyAll();
    }
}
```

- [ ] **Step 4: 编译验证**

```bash
mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java
git commit -m "feat: dispatchTools() 集成安全评估和用户确认流程"
```

---

### Task 6: ChatCtrl — 添加 /confirm 端点

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/chat/ctrl/ChatCtrl.java`

**Interfaces:**
- Consumes: `ChatServiceImpl.confirm()`
- Produces: `POST /zephyr-ui/chat/confirm`

- [ ] **Step 1: 添加 /confirm 端点**

在 `ChatCtrl.java` 中添加：

```java
@Operation(summary = "用户确认/拒绝操作")
@RequestMapping(path = "/confirm", method = RequestMethod.POST)
@ResponseBody
@SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "chat_confirm", apiDesc = "聊天接口_确认操作")
public ReturnMessage<?> confirm(@RequestBody Map<String, Object> body) {
    String confirmId = body.get("confirmId").toString();
    String action = body.get("action").toString(); // "allow" | "deny"

    // 身份校验：confirmId 格式为 "userName:randomId"，校验当前用户是否匹配
    int colonIdx = confirmId.indexOf(':');
    if (colonIdx < 0 || !userName().equals(confirmId.substring(0, colonIdx))) {
        return ReturnMessage.fail("无权操作此确认请求");
    }

    boolean allowed = "allow".equals(action);
    chatService.confirm(confirmId, allowed);
    return ReturnMessage.success(Map.of("confirmId", confirmId, "action", action));
}
```

注意需要添加 import：

```java
import java.util.Map;
```

以及将 `ChatService` 的类型转换改为调用 `ChatServiceImpl` 的具体方法——或者更好的做法，在 `ChatService` 接口中添加 `confirm` 方法：

在 `ChatService.java` 中添加：

```java
void confirm(String confirmId, boolean allowed);
```

然后在 `ChatServiceImpl.java` 中将 `confirm` 方法改为 `@Override`：

```java
@Override
public void confirm(String confirmId, boolean allowed) {
    synchronized (confirmResults) {
        confirmResults.put(confirmId, new ConfirmResult(allowed));
        confirmResults.notifyAll();
    }
}
```

ChatCtrl 中改为：

```java
chatService.confirm(confirmId, allowed);
```

- [ ] **Step 2: 编译验证**

```bash
mvn clean compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/chat/ctrl/ChatCtrl.java \
        src/main/java/com/github/hbq969/ai/zephyr/chat/service/ChatService.java \
        src/main/java/com/github/hbq969/ai/zephyr/chat/service/impl/ChatServiceImpl.java
git commit -m "feat: 添加 /confirm 端点用于用户确认危险操作"
```

---

### Task 7: 前端 — ConfirmDialog 组件 + ChatView 集成

**Files:**
- Create: `src/main/resources/static/src/components/ConfirmDialog.vue`
- Modify: `src/main/resources/static/src/types/chat.ts`
- Modify: `src/main/resources/static/src/views/chat/ChatView.vue`
- Modify: `src/main/resources/static/src/i18n/common.ts`

- [ ] **Step 0: 在 common.ts 中添加确认弹窗 i18n 文案**

在 `common.ts` 的中文 locale 中添加：

```typescript
  confirmTitle: '操作确认',
  confirmRiskHard: '安全红线',
  confirmRiskSoft: '需确认',
  confirmTool: '工具',
  confirmParams: '参数',
  confirmRiskDesc: '风险说明',
  confirmDeny: '拒绝',
  confirmAllow: '允许本次',
```

在英文 locale 中添加：

```typescript
  confirmTitle: 'Confirm Action',
  confirmRiskHard: 'SECURITY RED LINE',
  confirmRiskSoft: 'Confirmation Required',
  confirmTool: 'Tool',
  confirmParams: 'Parameters',
  confirmRiskDesc: 'Risk',
  confirmDeny: 'Deny',
  confirmAllow: 'Allow',
```

- [ ] **Step 1: 添加 ConfirmEvent 类型**

在 `chat.ts` 中添加：

```typescript
// === Confirm Action Event ===
export interface ConfirmActionEvent {
  confirmId: string
  toolName: string
  toolInput: Record<string, unknown>
  rule: string
  ruleDetail: string
}
```

- [ ] **Step 2: 创建 ConfirmDialog.vue**

```vue
<script lang="ts" setup>
import { ref, watch } from 'vue'
import axios from '@/network'
import type { ConfirmActionEvent } from '@/types/chat'
import { getLangData } from '@/i18n/locale'

const langData = getLangData()

const props = defineProps<{
  visible: boolean
  event: ConfirmActionEvent | null
}>()

const emit = defineEmits<{
  (e: 'close'): void
}>()

const loading = ref(false)

function respond(action: 'allow' | 'deny') {
  if (!props.event) return
  loading.value = true
  axios({
    url: '/chat/confirm',
    method: 'POST',
    data: { confirmId: props.event.confirmId, action }
  })
    .then(() => { emit('close') })
    .catch(() => { emit('close') })
    .finally(() => { loading.value = false })
}

const riskLabel = (rule: string): string => {
  switch (rule) {
    case 'HARD_BLOCK': return langData.value?.confirmRiskHard || 'HARD_BLOCK'
    case 'SOFT_BLOCK': return langData.value?.confirmRiskSoft || 'SOFT_BLOCK'
    default: return rule
  }
}

const riskColor = (rule: string): string => {
  switch (rule) {
    case 'HARD_BLOCK': return 'var(--el-color-danger)'
    case 'SOFT_BLOCK': return 'var(--el-color-warning)'
    default: return 'var(--el-color-info)'
  }
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    :title="langData?.confirmTitle || '操作确认'"
    :close-on-click-modal="false"
    :close-on-press-escape="false"
    width="480px"
    @close="emit('close')"
  >
    <div v-if="event" class="confirm-content">
      <div class="confirm-risk" :style="{ color: riskColor(event.rule) }">
        {{ riskLabel(event.rule) }}
      </div>

      <div class="confirm-tool">
        <span class="label">{{ langData?.confirmTool || '工具' }}：</span>
        <code>{{ event.toolName }}</code>
      </div>

      <div class="confirm-input">
        <span class="label">{{ langData?.confirmParams || '参数' }}：</span>
        <pre>{{ JSON.stringify(event.toolInput, null, 2) }}</pre>
      </div>

      <div class="confirm-reason">
        <span class="label">{{ langData?.confirmRiskDesc || '风险说明' }}：</span>
        <span>{{ event.ruleDetail }}</span>
      </div>
    </div>

    <template #footer>
      <div class="confirm-footer">
        <el-button
          type="danger"
          :loading="loading"
          @click="respond('deny')"
        >
          {{ langData?.confirmDeny || '拒绝' }}
        </el-button>
        <el-button
          type="primary"
          :loading="loading"
          @click="respond('allow')"
        >
          {{ langData?.confirmAllow || '允许本次' }}
        </el-button>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.confirm-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.confirm-risk {
  font-size: 16px;
  font-weight: 700;
}
.confirm-tool code {
  background: var(--el-fill-color-light);
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 13px;
}
.confirm-input pre {
  background: var(--el-fill-color-light);
  padding: 12px;
  border-radius: 6px;
  font-size: 12px;
  overflow-x: auto;
  max-height: 160px;
  margin: 0;
}
.confirm-reason {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.label {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.confirm-footer {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
}
</style>
```

- [ ] **Step 3: 集成到 ChatView.vue**

在 `<script setup>` 中添加：

```typescript
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import type { ConfirmActionEvent } from '@/types/chat'

const confirmVisible = ref(false)
const confirmEvent = ref<ConfirmActionEvent | null>(null)
```

在 `onEvent` 回调中处理 `confirm_action` 事件（找到现有的 `onEvent` 处理逻辑，添加新 case）：

```typescript
// 在 SSE 事件处理中：
} else if (event.type === 'confirm_action' && event.content) {
  try {
    const parsed: ConfirmActionEvent = JSON.parse(event.content)
    confirmEvent.value = parsed
    confirmVisible.value = true
  } catch { /* skip */ }
}
```

在 `<template>` 中添加 ConfirmDialog（放在 ChatView 的顶层 div 内末尾）：

```html
<ConfirmDialog
  :visible="confirmVisible"
  :event="confirmEvent"
  @close="confirmVisible = false"
/>
```

- [ ] **Step 4: 前端类型检查**

```bash
cd src/main/resources/static && npm run type-check
```

Expected: 0 errors

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/src/types/chat.ts \
        src/main/resources/static/src/components/ConfirmDialog.vue \
        src/main/resources/static/src/views/chat/ChatView.vue \
        src/main/resources/static/src/i18n/common.ts
git commit -m "feat: 添加操作确认弹窗组件，集成到聊天界面"
```

---

### Task 8: 端到端验证

**验证项：** 对照验收标准逐项验证

- [ ] **Step 1: 启动后端**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
mvn spring-boot:run -Dspring-boot.run.profiles=me
```

- [ ] **Step 2: 测试 H-1 HARD BLOCK 阻止 rm -rf**

```bash
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/chat/send" \
  -d '{"message":"用 execute_shell 执行 rm -rf /tmp", "conversationId":"test-h1"}'
```

Expected: SSE 流中 tool 结果为 "操作被拒绝（安全规则: HARD_BLOCK）"

- [ ] **Step 3: 测试 S-1 SOFT BLOCK 推送确认**

```bash
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/chat/send" \
  -d '{"conversationId":"test-s1"}'
```

等待 LLM 生成一个 SOFT BLOCK 命中场景。

Expected: SSE 流中出现 `type: "confirm_action"` 事件。

- [ ] **Step 4: 测试 S-2 用户确认后执行**

```bash
# 先获取 confirmId（从上一步 SSE 流中）
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/chat/confirm" \
  -d '{"confirmId":"<实际 confirmId>", "action":"allow"}'
```

Expected: 工具执行，返回正常结果。

- [ ] **Step 4b: 测试 S-3 用户拒绝 SOFT BLOCK**

```bash
# 重新触发一次 SOFT BLOCK（同 Step 3），然后调用 /confirm deny
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/chat/confirm" \
  -d '{"confirmId":"<实际 confirmId>", "action":"deny"}'
```

Expected: 工具被跳过，LLM 收到 "操作已被用户拒绝" 结果。

- [ ] **Step 5: 测试 S-4 ALLOW 例外**

```bash
curl -u admin:1 -H "X-SM-Test: 1" \
  -H "Content-Type: application/json" \
  -X POST "http://localhost:30733/zephyr/zephyr-ui/chat/send" \
  -d '{"message":"用 use_skill 加载 frontend-design", "conversationId":"test-s4"}'
```

Expected: use_skill 直接执行，无 confirm_action 事件。

- [ ] **Step 6: 检查审计日志**

```bash
cat ~/.zephyr/audit.log
```

Expected: 有 SECURITY_CHECK 和（如有）USER_DENIED 记录。

- [ ] **Step 7: 前端构建 + 浏览器验证**

```bash
cd src/main/resources/static && npm run build && \
  mkdir -p ../../../target/classes/static && \
  cp -rf zephyr-ui ../../../target/classes/static/
```

打开 `http://localhost:30733/zephyr/zephyr-ui/index.html`，登录后在聊天中发送一个会触发 SOFT BLOCK 的命令，验证弹窗出现。

---

## 自检结果

1. **Spec coverage**: 对照设计文档 Phase 1 改动文件表，17 个文件全部覆盖
2. **Placeholder scan**: 无 TBD/TODO/占位符
3. **Type consistency**: `SecurityEvaluator.Result` → `SecurityEvaluator.Decision`，`ConfirmResult` → `ChatServiceImpl.confirm()`，类型一致
