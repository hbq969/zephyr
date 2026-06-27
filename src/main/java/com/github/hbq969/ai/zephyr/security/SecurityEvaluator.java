package com.github.hbq969.ai.zephyr.security;

import cn.hutool.core.util.ReUtil;
import com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties;
import com.github.hbq969.ai.zephyr.security.service.SecurityConfigService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.github.hbq969.ai.zephyr.constant.ZephyrConstants.*;

/**
 * Java 层安全评估器，在 LLM 自评估之外做模式匹配防御。
 * <p>
 * HARD/SOFT BLOCK 规则从 DB ({@code zephyr_security_rules}) 读取，通过 {@code SecurityConfigService.refresh()} 实时生效无需重启。
 */
@Slf4j
@Component
public class SecurityEvaluator {

    @Resource
    private ZephyrConfigProperties cfg;

    @Resource
    private AuditLogger auditLogger;

    @Resource
    private SecurityConfigService securityConfigService;

    @Resource
    private com.github.hbq969.ai.zephyr.builtintool.service.BuiltinToolService builtinToolService;

    // === 运行时编译后的 Path Prefix 缓存 ===

    private List<String> hardBlockPathPrefixes;

    // === 安全规则标识常量 ===

    public static final String RULE_WORKSPACE_BOUNDARY = "WORKSPACE_BOUNDARY";
    public static final String RULE_MODE_DEFAULT = "MODE_DEFAULT";

    /** 评估结果 */
    public enum Decision { ALLOW, CONFIRM, BLOCK }

    public record Result(Decision decision, String rule, String reason) {
        public static Result allow() { return new Result(Decision.ALLOW, "", ""); }
        public static Result confirm(String rule, String reason) { return new Result(Decision.CONFIRM, rule, reason); }
        public static Result block(String rule, String reason) { return new Result(Decision.BLOCK, rule, reason); }
    }

    public record WorkspaceBoundary(Path path) {
        public static final WorkspaceBoundary NONE = new WorkspaceBoundary(null);

        public boolean isPresent() { return path != null; }

        public boolean contains(Path target) {
            return isPresent() && target.startsWith(path);
        }

        public Path resolveTarget(String filePath) {
            Path targetPath = Path.of(filePath);
            if (!targetPath.isAbsolute()) {
                targetPath = path.resolve(targetPath);
            }
            return targetPath.normalize();
        }
    }

    @jakarta.annotation.PostConstruct
    void init() {
        hardBlockPathPrefixes = new ArrayList<>(
                cfg.getSecurity().getHardBlock().getPathPrefixes() != null
                        ? cfg.getSecurity().getHardBlock().getPathPrefixes()
                        : List.of());
        log.info("[安全] SecurityEvaluator 初始化完成 — enabled={}, debug={}, hardBlockPathPrefixes={}",
                cfg.getSecurity().isEnabled(), cfg.getSecurity().isDebug(), hardBlockPathPrefixes);
    }

    /**
     * 评估工具调用。入口处统一做角色检查（非 admin 用户拦截受控工具），
     * 然后针对 execute_shell 和文件写入类工具做安全模式匹配，
     * 其余工具调用返回 ALLOW（由 LLM 自评估负责）。
     *
     * @param mode 权限模式：default | acceptEdits | bypass
     */
    public Result evaluate(String toolName, Map<String, Object> arguments, String userName, String mode,
                           WorkspaceBoundary boundary) {
        if (!cfg.getSecurity().isEnabled()) {
            debug("[安全追踪] 安全评估已全局关闭（zephyr.security.enabled=false），放行 tool={}", toolName);
            return Result.allow();
        }

        debug("[安全追踪] === 入口 === tool={}, mode={}, user={}, boundary={}",
                toolName, mode, userName, boundary.isPresent() ? boundary.path() : "NONE");

        // 全局角色检查：所有工具统一入口
        if (builtinToolService.requiresAdmin(userName, toolName)) {
            Result r = Result.block("ROLE_CHECK", "无权限（非 admin 用户）");
            debug("[安全追踪] ROLE_CHECK -> BLOCK tool={}, user={}", toolName, userName);
            auditLogger.log("SECURITY_CHECK", toolName, r.decision().name(),
                    r.rule() + ": " + r.reason(), userName);
            return r;
        }

        Result result = switch (toolName) {
            case "execute_shell" -> evaluateShell(arguments, mode);
            case "list_processes", "kill_process" -> Result.allow();
            case "write_file", "edit_file" -> evaluateFileWrite(arguments, mode, boundary);
            default -> {
                debug("[安全追踪] tool={} 非 shell/文件类工具，走 LLM 自评估，直接放行", toolName);
                yield Result.allow();
            }
        };

        if (result.decision() != Decision.ALLOW) {
            auditLogger.log("SECURITY_CHECK", toolName, result.decision().name(),
                    result.rule() + ": " + result.reason(), userName);
        }

        debug("[安全追踪] === 结果 === tool={}, decision={}, rule={}, reason={}",
                toolName, result.decision(), result.rule(), result.reason());
        return result;
    }

    private Result evaluateShell(Map<String, Object> arguments, String mode) {
        if (arguments == null || !arguments.containsKey("command")) {
            return Result.allow();
        }
        Object cmdObj = arguments.get("command");
        if (cmdObj == null) return Result.allow();
        String command = cmdObj.toString().trim();

        SecurityConfigService.ConfigSnapshot snap = securityConfigService.getSnapshot();

        debug("[安全追踪] evaluateShell cmd=`{}`, mode={}, hardBlock={}, softBlock={}, defaultAllow={}",
                command, mode,
                snap.hardBlockPatterns().size(), snap.softBlockPatterns().size(), snap.defaultAllowCommands().size());

        // 1. HARD BLOCK 检查（所有模式强制执行）
        for (Pattern p : snap.hardBlockPatterns()) {
            if (ReUtil.contains(p, command)) {
                log.info("[安全] HARD_BLOCK 触发拒绝 — 命令: {}, 命中规则: {}", command, p.pattern());
                return Result.block("HARD_BLOCK", "命令匹配安全红线规则，禁止执行");
            }
        }
        debug("[安全追踪] HARD_BLOCK({}条) -> 全部通过", snap.hardBlockPatterns().size());

        // 2. bypass 模式：放行
        if ("bypass".equalsIgnoreCase(mode)) {
            debug("[安全追踪] bypass 模式 -> 跳过 SOFT_BLOCK 和 MODE_DEFAULT，直接放行");
            return Result.allow();
        }

        // 3. SOFT BLOCK 检查
        debug("[安全追踪] SOFT_BLOCK 检查开始（{}条规则）...", snap.softBlockPatterns().size());
        for (Pattern p : snap.softBlockPatterns()) {
            boolean matched = ReUtil.contains(p, command);
            debug("[安全追踪]   SOFT_BLOCK 规则 `{}` -> {}", p.pattern(), matched ? "命中" : "未命中");
            if (!matched) continue;

            // acceptEdits 模式：shell 重定向操作视为文件编辑，免批
            if ("acceptEdits".equalsIgnoreCase(mode) && isRedirectPattern(p.pattern())) {
                debug("[安全追踪]   -> acceptEdits 重定向豁免，跳过");
                continue;
            }
            log.info("[安全] SOFT_BLOCK 触发确认 — 命令: {}, 命中规则: {}", command, p.pattern());
            return Result.confirm("SOFT_BLOCK", "该命令具有破坏性，需要用户确认");
        }
        debug("[安全追踪] SOFT_BLOCK({}条) -> 全部通过", snap.softBlockPatterns().size());

        // 4. default 模式：只读命令放行，其余需确认
        if (!"acceptEdits".equalsIgnoreCase(mode) && !"bypass".equalsIgnoreCase(mode)) {
            debug("[安全追踪] MODE_DEFAULT 检查开始...");
            if (command.contains("&&") || command.contains("||")
                    || command.contains(";") || command.contains("\n")) {
                log.info("[安全] MODE_DEFAULT 触发确认 — 复合命令: {}", command);
                return Result.confirm("MODE_DEFAULT", "复合命令需用户确认");
            }
            String cmdName = command.split("\\s+", 2)[0];
            int lastSlash = cmdName.lastIndexOf('/');
            if (lastSlash >= 0) {
                cmdName = cmdName.substring(lastSlash + 1);
            }
            boolean inAllowList = snap.defaultAllowCommands().contains(cmdName);
            debug("[安全追踪] MODE_DEFAULT cmdName={}, defaultAllow 列表({}条)中{}",
                    cmdName, snap.defaultAllowCommands().size(), inAllowList ? "存在 -> 放行" : "不存在 -> 需确认");
            if (!inAllowList) {
                log.info("[安全] MODE_DEFAULT 触发确认 — 非只读命令: {} (主命令: {})", command, cmdName);
                return Result.confirm("MODE_DEFAULT", "Default 模式下 shell 命令需要用户确认");
            }
        }

        return Result.allow();
    }

    /** 判定 SOFT BLOCK 模式是否为输出重定向类型（在 acceptEdits 模式下应视为文件编辑免批） */
    private boolean isRedirectPattern(String rawPattern) {
        return rawPattern.contains(">") && (rawPattern.contains("\\s*\\\\S")
                || rawPattern.contains("redirect") || rawPattern.contains("overwrite"));
    }

    private Result evaluateFileWrite(Map<String, Object> arguments, String mode, WorkspaceBoundary boundary) {
        String filePath = arguments.getOrDefault("file_path", "").toString();
        if (filePath.isEmpty()) {
            filePath = arguments.getOrDefault("filePath", "").toString();
        }
        String normalizedPath = Path.of(filePath).normalize().toString();

        debug("[安全追踪] evaluateFileWrite path={}, mode={}, boundary={}",
                normalizedPath, mode, boundary.isPresent() ? boundary.path() : "NONE");

        // HARD BLOCK：修改安全 prompt 文件（大小写不敏感）
        String lowerPath = normalizedPath.toLowerCase();
        for (String prefix : hardBlockPathPrefixes) {
            if (lowerPath.contains(prefix)) {
                log.info("[安全] HARD_BLOCK 触发拒绝 — 文件: {}, 命中路径前缀: {}", filePath, prefix);
                return Result.block("HARD_BLOCK", "禁止修改安全规则文件: " + filePath);
            }
        }

        // HARD BLOCK：修改 application.yml（含路径遍历绕过检测）
        for (String configFile : APP_CONFIG_FILES) {
            if (lowerPath.contains(configFile)) {
                log.info("[安全] HARD_BLOCK 触发拒绝 — 文件: {}, 命中配置保护: {}", filePath, configFile);
                return Result.block("HARD_BLOCK", "禁止修改应用配置文件: " + filePath);
            }
        }

        // bypass 模式：放行
        if ("bypass".equalsIgnoreCase(mode)) {
            debug("[安全追踪] bypass 模式 -> 文件写入直接放行");
            return Result.allow();
        }

        if (boundary.isPresent()) {
            Path targetPath = boundary.resolveTarget(filePath);
            try {
                targetPath = targetPath.toRealPath();
            } catch (IOException ignored) {
            }

            if (!boundary.contains(targetPath)) {
                log.info("[安全] WORKSPACE_BOUNDARY 触发确认 — 目标: {} (规范化: {}), workspace: {}",
                        filePath, targetPath, boundary.path());
                return Result.confirm(RULE_WORKSPACE_BOUNDARY,
                        "目标路径 " + filePath + " 不在工作空间 " + boundary.path() + " 内");
            }
        }

        if (!"acceptEdits".equalsIgnoreCase(mode)) {
            log.info("[安全] MODE_DEFAULT 触发确认 — 文件写入: {}, mode: {}", filePath, mode);
            return Result.confirm(RULE_MODE_DEFAULT, "Default 模式下文件写入需要用户确认");
        }

        return Result.allow();
    }

    private void debug(String format, Object... args) {
        if (cfg.getSecurity().isDebug()) {
            log.info(format, args);
        }
    }
}
