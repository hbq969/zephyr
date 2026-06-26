# Shell 安全配置管理 — 设计文档

## 问题定义

当前 `zephyr.shell.allowed-commands`、`zephyr.security.default-allow-commands`、`zephyr.security.hard-block.shell-patterns`、`zephyr.security.soft-block.shell-patterns` 四个安全配置项都写在 `application.yml` 中，通过 `@PostConstruct` 一次性加载到内存。修改配置需要编辑 YAML 文件并重启服务，用户无法在界面上管理。

**目标：** 四个配置项迁移到数据库表，提供管理页面，修改后即时刷新内存缓存（无需重启）。

## 关键设计决策

### 存储：纯 DB，YAML 不保留默认值

用户选择方案 A — YAML 中完全移除这些配置字段，启动时从 DB 加载。首次启动时 DB 无数据则使用空默认值（空列表）。管理员通过管理页面配置初始值。

### 架构：新 SecurityConfigService 统一管理内存缓存

不在 ChatServiceImpl / SecurityEvaluator 中各自维护 `@PostConstruct`，而是新建 `SecurityConfigService`：

- 启动时从 DB 加载全部配置到不可变快照（`volatile ConfigSnapshot`），读操作无锁
- 写操作（增/删/改后 refresh）使用 `synchronized` 防并发，一次性替换整个快照引用
- ChatServiceImpl、SecurityEvaluator 在方法调用时现场从 snapshot 读取，不缓存字段引用
- Pattern 编译也在 SecurityConfigService 完成，SecurityEvaluator 直接拿编译好的 `List<Pattern>` 用

```text
┌──────────────────────┐
│ SecurityConfigCtrl   │  REST API
└────────┬─────────────┘
         │ CRUD → 写完调 refresh()
┌────────▼─────────────┐
│ SecurityConfigService │  volatile ConfigSnapshot
│  - shellAllowedCmds   │  (Set<String>)
│  - defaultAllowCmds   │  (Set<String>)
│  - hardBlockPatterns  │  (List<Pattern>)
│  - softBlockPatterns  │  (List<Pattern>)
└────────┬─────────────┘
         │ DAO
┌────────▼─────────────┐
│ 4 张 zephyr_* 表     │  DB 存储
└──────────────────────┘

消费者:
  ChatServiceImpl──→ 每次调用时 snapshot.getShellAllowedCommands()
  SecurityEvaluator─→ 每次调用时 snapshot.getHardBlockPatterns() 等
```

### 前端：单页面 4 Tab

路径 `/settings/security`，一个页面内用 Tab 切换四个配置区域。命令类 Tab 数据结构相同可复用组件。

### 数据模型

| 表名 | 字段 | 用途 |
|------|------|------|
| `zephyr_shell_allowed_cmds` | id, command_name, description, created_at, updated_at | whitelist 模式允许的命令 |
| `zephyr_security_default_allow_cmds` | id, command_name, description, created_at, updated_at | default 模式免确认命令 |
| `zephyr_security_hard_block_rules` | id, pattern, description, created_at, updated_at | 硬阻断正则 |
| `zephyr_security_soft_block_rules` | id, pattern, description, created_at, updated_at | 软阻断正则 |

### API 设计

```
GET    /zephyr-ui/security/{type}/list
POST   /zephyr-ui/security/{type}/add
POST   /zephyr-ui/security/{type}/delete
POST   /zephyr-ui/security/{type}/update    (仅规则表使用)
```

type: `shell-allowed` | `default-allow` | `hard-block` | `soft-block`

### 改造影响

| 文件 | 改动 |
|------|------|
| `ZephyrConfigProperties.java` | 仅删除 4 个字段：`Shell.allowedCommands`、`Security.defaultAllowCommands`、`HardBlock.shellPatterns`、`SoftBlock.shellPatterns`。Shell 其他字段（mode 等）和 Security 其他字段（enabled、audit 等）保留不动 |
| `application.yml` | 删除对应 YAML key |
| `SecurityEvaluator.java` | 删除 init()/initReadOnlyCommands()/initPatterns()，改为注入 SecurityConfigService |
| `ChatServiceImpl.java` | 删除 initShellWhitelist()，改为注入 SecurityConfigService |
| 新增 | SecurityConfigCtrl, SecurityConfigService, SecurityConfigDao, 4 Entity, Mapper XML × 4 |
| SQL 新增 | zephyr-zh-CN.sql, zephyr-en-US.sql, zephyr-ja-JP.sql 添加初始数据 INSERT |
| 前端新增 | SecuritySettings.vue, 路由注册, settings store 扩展 |

### 前端 tab 结构

| Tab 名称 | 数据 | UI 元素 |
|----------|------|---------|
| 命令白名单 | shell_allowed_cmds | 命令名 + 描述 + 操作(增/删) |
| 默认允许命令 | security_default_allow_cmds | 同上 |
| 硬阻断规则 | security_hard_block_rules | 正则模式 + 描述 + 操作(增/删/改) |
| 软阻断规则 | security_soft_block_rules | 同上 |

### SQL 初始化脚本

将当前 `application.yml` 中的默认配置转为 INSERT 语句，写入 `zephyr-zh-CN.sql`、`zephyr-en-US.sql`、`zephyr-ja-JP.sql` 三个文件。这样新建环境首次启动时自动填充初始数据，无需手动逐条添加。

**来源映射：**

| YAML 路径 | 目标表 |
|-----------|--------|
| `zephyr.shell.allowed-commands`（逗号分隔） | `zephyr_shell_allowed_cmds` |
| `zephyr.security.default-allow-commands`（逗号分隔） | `zephyr_security_default_allow_cmds` |
| `zephyr.security.hard-block.shell-patterns`（List） | `zephyr_security_hard_block_rules` |
| `zephyr.security.soft-block.shell-patterns`（List） | `zephyr_security_soft_block_rules` |

**处理规则：**
- 命令类配置：逗号分隔后逐条生成 INSERT，description 从 `application.yml` 注释提取
- 规则类配置：每条 pattern 生成一条 INSERT，description 从 YAML 行内注释 `# ...` 提取
- 三语言 SQL 内容相同（描述暂用中文），使用 `INSERT INTO ... SELECT ... WHERE NOT EXISTS (SELECT 1 FROM ... WHERE ...)` 保证幂等（H2 兼容）
