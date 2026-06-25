# 安全规则外置 + HARD BLOCK 规则扩充

## 概述

将 `SecurityEvaluator` 中写死在代码里的 `HARD_BLOCK_SHELL_PATTERNS`、`HARD_BLOCK_PATH_PREFIXES`、`SOFT_BLOCK_SHELL_PATTERNS` 外置到 `application.yml` 配置，同时扩充 HARD BLOCK 规则覆盖（不可逆系统毁灭 + 安全绕过两大类）。

## 动机

- 当前规则写死在 `static final` 列表中，修改需要重新编译部署
- 不同部署环境可能需要不同的安全策略（如生产环境更严格）
- 原 7 条 HARD BLOCK 规则仅覆盖信息外泄，缺失系统毁灭、权限提升、持久化后门等关键类别
- 借助 Spring 配置刷新机制（`/dn3i1vx4/refresh`），可实现规则热更新

## 配置结构

### `ZephyrConfigProperties.Security` 新增嵌套类

```java
@Data
public static class Security {
    // ... 现有字段保持不变 ...

    private HardBlock hardBlock = new HardBlock();
    private SoftBlock softBlock = new SoftBlock();

    @Data
    public static class HardBlock {
        /** HARD BLOCK shell 正则列表，命中任一即禁止执行 */
        private List<String> shellPatterns = new ArrayList<>();
        /** HARD BLOCK 路径前缀列表，命中任一即禁止文件写入 */
        private List<String> pathPrefixes = new ArrayList<>();
    }

    @Data
    public static class SoftBlock {
        /** SOFT BLOCK shell 正则列表，命中任一即需用户确认 */
        private List<String> shellPatterns = new ArrayList<>();
    }
}
```

### `application.yml` 格式

```yaml
zephyr:
  security:
    hard-block:
      shell-patterns:
        - "rm\\s+(-[a-zA-Z]*[rf][a-zA-Z]*\\s+)+/(\\*|$|\\s)"
        - "dd\\s+.*of=/dev/(sd|hd|nvme|xvd|vd|mmcblk|dm-|loop)"
        # ... 更多
      path-prefixes:
        - "prompts/security/"
        - "prompts/modes/"
        - "prompts/tools/"
    soft-block:
      shell-patterns:
        - "\\brm\\s+(-[a-zA-Z]*[rf][a-zA-Z]*\\s+)+"
        - "git\\s+push\\s+.*(?:--force|--force-with-lease)"
        # ... 更多
```

### SecurityEvaluator 改动

- 移除三个 `static final` 列表
- `@PostConstruct` 中从 `cfg.getSecurity().getHardBlock()` / `getSoftBlock()` 读取模式列表，编译为 `List<Pattern>` 缓存
- 配置列表为空时 fallback 到代码内置默认列表（保留当前规则作为出厂默认值）
- 评估逻辑不变（HARD → bypass 放行 → SOFT → default 只读放行）

## HARD BLOCK 规则清单（6 类 25 条）

### 1. 系统/数据毁灭

| # | 场景 | 正则 |
|---|------|------|
| 1 | 递归强制删除根目录 | `rm\s+(-[a-zA-Z]*[rf][a-zA-Z]*\s+)+/(\*\|$\|\s)` |
| 2 | dd 覆写磁盘设备 | `dd\s+.*of=/dev/(sd\|hd\|nvme\|xvd\|vd\|mmcblk\|dm-\|loop)` |
| 3 | mkfs 格式化 | `mkfs\.\S*\s+/dev/` |
| 4 | fork 炸弹 | `:\(\)\s*\{.*:\|&\s*\}\|fork\s+bomb` |
| 5 | shred 安全擦除系统路径 | `shred\s+.*/(dev\|etc\|boot\|bin\|sbin\|lib\|usr\|var\|home\|root\|opt\|sys\|proc)/` |

### 2. 权限提升

| # | 场景 | 正则 |
|---|------|------|
| 6 | 修改 sudoers | `(?:/etc/sudoers\|/etc/sudoers\.d/)` |
| 7 | 添加用户到特权组 | `(?:usermod\|gpasswd\|adduser\|useradd).*\b(?:sudo\|wheel\|admin\|root)\b` |
| 8 | 设置 SUID/SGID 位 | `chmod\s+(?:.*[ug]\+s\|[4-7]\d{3})` |

### 3. 安全控制绕过

| # | 场景 | 正则 |
|---|------|------|
| 9 | 清空/删除防火墙规则 | `(?:iptables\s+-(?:F\|X)\b\|nft\s+flush\s+ruleset\|ufw\s+disable)` |
| 10 | 停止/禁用安全服务 | `systemctl\s+(?:stop\|disable\|mask)\s+(?:selinux\|apparmor\|auditd\|firewalld\|iptables\|ufw\|fail2ban\|clamav\|crowdstrike)` |
| 11 | 关闭 SELinux | `setenforce\s+0` |
| 12 | 修改 PAM 配置 | `/etc/pam\.d/` |
| 13 | 修改 SSH 服务配置 | `/etc/ssh/(?:sshd_config\|ssh_config)` |
| 14 | 绕过验证（原有保留） | `.*--no-verify.*` |
| 15 | 不安全模式（原有保留） | `.*--insecure.*` |
| 16 | PowerShell 执行策略绕过（原有） | `.*Set-ExecutionPolicy\s+Bypass.*` |

### 4. 后门/持久化

| # | 场景 | 正则 |
|---|------|------|
| 17 | 写入 SSH authorized_keys | `(?:>\|>>\|tee\s+(?:-a\s+)?)\S*authorized_keys` |
| 18 | 写入 shell RC 文件 | `(?:>\|>>\|tee\s+(?:-a\s+)?)\S*(?:\.bashrc\|\.zshrc\|\.profile\|\.bash_profile\|/etc/profile)` |
| 19 | 写入系统二进制目录 | `(?:>\|>>\|cp\|mv\|install)\s+.*/(?:usr/)?(?:s?bin\|lib(?:64)?)/` |
| 20 | 修改 crontab | `crontab\s+-\S` |

### 5. 基础设施破坏

| # | 场景 | 正则 |
|---|------|------|
| 21 | kubectl 删除关键资源 | `kubectl\s+delete\s+(?:namespace\|ns\b\|pods?\|deployment\|deploy\b\|statefulset\|daemonset\|secret\|configmap\|service\b\|svc\b\|ingress\|ing\b\|pvc\|pv\b)` |
| 22 | docker 特权容器挂载宿主机 | `docker\s+(?:run\|create).*--privileged.*(?:-v\|--volume)\s+/:` |
| 23 | docker 清理所有未使用资源 | `docker\s+system\s+prune` |

### 6. 数据外泄 + 反弹 Shell

| # | 场景 | 正则 |
|---|------|------|
| 24 | 敏感文件经网络外传（扩展原有） | `(?:cat\|head\|tail\|read\|strings\|xxd\|od\|hexdump).*(?:\.env\|credentials\|private.?key\|secret\|token\|password\|id_rsa\|id_ed25519\|id_ecdsa).*(?:\|\|>\|curl\|http\|nc\s\|socat\s\|ssh\s\|scp\s\|rsync\s)` |
| 25 | nc 反弹 shell | `nc\s+.*-[ec]\s+(?:/bin/\|/usr/bin/)?(?:bash\|sh\|zsh\|dash\|python\|perl\|ruby)` |

## SOFT BLOCK 规则调整

### 变更点

- `chmod 777` 从 HARD BLOCK 下沉到 SOFT BLOCK（有合法调试场景，不算不可逆毁灭）
- 新增 4 类：`git branch -D`、`docker-compose down -v`、`helm delete`、`terraform destroy`

### 完整列表（13 条）

| # | 场景 | 正则 |
|---|------|------|
| 1 | rm -rf 变体 | `\brm\s+(-[a-zA-Z]*[rf][a-zA-Z]*\s+)+` |
| 2 | git push --force | `git\s+push\s+.*(?:--force\|--force-with-lease)` |
| 3 | git reset --hard / clean | `git\s+(?:reset\s+--hard\|clean\s+-fdx)` |
| 4 | git branch -D（新增） | `git\s+branch\s+-D` |
| 5 | curl/wget 远程执行 | `(?:curl\|wget).*(?:\|\|>\|bash\|sh\|python\|eval\|exec)` |
| 6 | DROP/TRUNCATE/DELETE | `(?:DROP\s+(?:TABLE\|DATABASE)\|TRUNCATE\|DELETE\s+FROM)` |
| 7 | kubectl delete（通用） | `kubectl\s+delete` |
| 8 | docker rm/stop/kill | `docker\s+(?:rm\|stop\|kill)` |
| 9 | kill -9 / pkill | `(?:kill\s+-9\|pkill)` |
| 10 | 重定向覆写 | `>\s*\S+` |
| 11 | chmod 777（从 HARD 下沉） | `chmod\s+777` |
| 12 | docker-compose down -v（新增） | `docker-compose\s+down.*-v` |
| 13 | helm delete（新增） | `helm\s+(?:delete\|uninstall)` |

## fallback 机制

当 `application.yml` 中 `hard-block.shell-patterns` 或 `soft-block.shell-patterns` 为空列表时，`SecurityEvaluator` 使用代码内置的默认列表。默认列表内容与当前 `main` 分支一致（即现有 7 条 HARD + 9 条 SOFT），确保向后兼容——存量部署升级后行为不变。

## 改动文件

| 文件 | 改动 |
|------|------|
| `ZephyrConfigProperties.java` | `Security` 中新增 `HardBlock`、`SoftBlock` 嵌套类 |
| `application.yml` | `security` 节点下补充 `hard-block`、`soft-block` 配置 |
| `SecurityEvaluator.java` | 移除 `static final` 列表，改为从配置读取 + fallback 默认值 |
| 无需改 | `AuditLogger`、评估逻辑、调用方 |

## 验收标准

| 编号 | 标准 | 验证方法 |
|------|------|---------|
| V-1 | 配置中的正则被正确编译并用于匹配 | 用 `rm -rf /` 测试，命中 HARD BLOCK |
| V-2 | 配置为空时 fallback 到默认规则 | 将 `shell-patterns` 设为空列表 `[]`，验证 `rm -rf /` 仍命中 SOFT_BLOCK（默认规则无此条）→ 用 `--no-verify` 验证命中 HARD_BLOCK |
| V-3 | YAML 热更新生效 | 修改规则后调用 `/dn3i1vx4/refresh`，验证新规则生效 |
| V-4 | 新增规则 `rm -rf /` 命中 HARD_BLOCK | 发送 `rm -rf / --no-preserve-root`，验证返回 BLOCK |
| V-5 | `chmod 777` 命中 SOFT_BLOCK 非 HARD | 发送 `chmod 777 /tmp/test`，验证返回 CONFIRM 而非 BLOCK |
| V-6 | 路径前缀配置正确拦截 | 写入 `prompts/security/xxx.md` 命中 HARD_BLOCK |
