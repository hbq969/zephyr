# zephyr Agent 安全体系设计

## 概述

参考 Claude Code 系统提示词中的安全架构，为 zephyr 建立 agent 安全体系。对齐 Claude Code 的 HARD BLOCK → SOFT BLOCK → ALLOW 三级判定模型，同时保留 zephyr 现有的工作空间边界控制作为独立维度。

## 威胁模型

**保护对象：** 用户的工作空间数据（代码、配置、凭证）、共享基础设施（集群、数据库）、用户身份和权限。

**威胁来源：**
1. **提示注入** — 外部内容（网页、文件、MCP 工具返回）包含恶意指令，操纵 agent 执行危险操作
2. **范围蔓延** — agent 在用户宽泛请求的基础上自主升级操作范围（"修 bug"变成"删数据库"）
3. **意外破坏** — agent 不理解 blast radius，错误操作导致数据丢失或服务中断

**信任假设：**
- Phase 1：HARD BLOCK 依赖 LLM 自遵守 prompt 规则，**无代码级强制**。这意味着 HARD BLOCK 在 prompt injection 场景下可能被绕过
- 用户自行安装的 MCP 工具和 skill 的可靠性由用户负责
- 用户自定义 `~/.zephyr/prompts/` 覆盖安全规则的行为是用户知情的选择

**Phase 1 明确 out of scope：**
- 代码级 HARD BLOCK 强制（Phase 3 引入）
- 网络隔离和沙箱（Phase 3 引入）
- 多租户场景的安全隔离

## 当前状态

zephyr 已有的安全机制：
- 文件系统安全模式（default/acceptEdits/bypass）— 工作空间边界控制，**保留不变**
- Shell 命令白名单（whitelist mode）
- 工作空间路径边界检查

缺失的核心能力：
- HARD/SOFT BLOCK 安全规则（对齐 Claude Code）
- 用户确认流程（SSE 推送 + 前端弹窗）
- 安全监控器（独立的 LLM 安全评估）
- 提示注入/范围蔓延检测

## 整体架构

两层独立叠加：

```
工具调用 →
  第一层：工作空间边界（沿用现有模式）
    ├─ default: 空间内自由，空间外每次授权
    ├─ acceptEdits: 空间内自由，空间外首次授权会话记住
    └─ bypass: 无边界限制

  第二层：安全规则（新增，对齐 Claude Code）
    ├─ HARD BLOCK → 拒绝（数据外泄、凭证泄露、权限提升等）
    ├─ SOFT BLOCK → 暂停确认（破坏性操作、对外可见、共享基础设施等）
    └─ ALLOW 例外 → 放行（agent 自创文件、声明的依赖安装、Push 工作分支等）

HARD BLOCK 始终生效，不受工作空间模式影响。
```

### 三个核心组件

| 组件 | 职责 | 延迟 | 触发频率 |
|------|------|------|---------|
| WorkspaceGuard | 沿用现有逻辑，检查文件操作是否在空间内 | <1ms | 文件操作时 |
| SecurityEvaluator | HARD/SOFT BLOCK + ALLOW 例外判定（prompt 驱动） | LLM 推理时间 | 每次工具调用 |
| UserConfirmGateway | SSE 推送确认请求，等待用户决策 | 用户响应时间 | SOFT BLOCK 命中且无例外时 |

### 数据流

```
tool_calls[] → 逐个顺序评估，独立决策：

  for each tc in tool_calls[]:
    ① WorkspaceGuard.check(tc, mode)         ← 沿用现有逻辑
       └─ 空间外 + default → 暂停确认
       └─ 空间外 + acceptEdits → 首次确认/后续记住
       └─ 空间外 + bypass → 继续

    ② SecurityEvaluator.evaluate(tc)
       ├─ HARD_BLOCK 命中        → 拒绝，返回 blocked（见"阻塞后行为链"）
       ├─ ALLOW 例外命中          → 直接执行
       ├─ SOFT_BLOCK 命中         → 进入 ③
       └─ 都未命中               → 直接执行

    ③ UserConfirmGateway.request(tc, rule, reason)
       ├─ 用户确认               → 执行
       └─ 用户拒绝/超时           → 返回 blocked（见"阻塞后行为链"）
```

### 批次工具调用队列语义

当 LLM 在一次推理中生成多个 `tool_calls[]` 时，每个调用**独立评估、独立决策**：

```
假设 LLM 生成 3 个工具调用：[tc0, tc1, tc2]

tc0: use_skill("pdf")      → SAFE → 立即执行
tc1: execute_shell("rm -rf /data") → SOFT_BLOCK → 推送确认事件，等待
tc2: browser_snapshot()    → SAFE → 立即执行

结果：
- tc0 和 tc2 不等待，立即执行并返回结果
- tc1 挂起，等待用户确认
- 用户确认 tc1 → 执行 tc1，结果返回给 LLM
- 用户拒绝 tc1 → tc1 返回 blocked 结果给 LLM
```

**规则：**
- 批次中的调用独立处理，一个调用的阻塞不影响其他调用
- 挂起的调用不阻塞 LLM 推理循环——LLM 可以先处理已返回结果的工具调用
- 用户对一个调用的决策（allow/deny/allow-session）不影响批次中的其他调用
- 超时未响应 → 自动拒绝

### 阻塞后行为链

#### HARD BLOCK 命中

```
工具调用 → SecurityEvaluator → HARD_BLOCK
  → 不执行该工具调用
  → 返回 blocked 结果给 LLM（tool role, content = "操作被拒绝: {原因}"）
  → LLM 向用户说明原因，主动提供安全替代方案
  → 禁止 LLM 换方式重试被 HARD BLOCK 的操作
  → 会话继续，用户可以发新消息
```

#### SOFT BLOCK 命中 + 用户拒绝

```
工具调用 → SecurityEvaluator → SOFT_BLOCK → UserConfirmGateway → 用户拒绝
  → 不执行该工具调用
  → 返回 blocked 结果给 LLM
  → LLM 说明操作被跳过，主动提供更安全的替代方案
  → 会话继续
```

#### SOFT BLOCK 命中 + 用户确认

```
工具调用 → SecurityEvaluator → SOFT_BLOCK → UserConfirmGateway → 用户确认
  → 执行该工具调用
  → 返回执行结果给 LLM
  → LLM 继续正常推理循环
```

#### 异常：LLM 尝试绕过

```
LLM 在被 HARD BLOCK 后换一种方式重试同一操作
  → SecurityEvaluator 再次命中 HARD_BLOCK
  → 再次拒绝，reason 中注明 "重复尝试被阻止的操作"
  → 如果同一会话中连续 3 次尝试绕过 → 强制终止工具调用循环
```

## 文件结构

```
src/main/resources/prompts/
├── security/
│   ├── tool-risk-rules.md      # 安全评估指南（评估流程 + 各规则速查）
│   ├── hard-block.md           # HARD BLOCK 规则（永不执行）
│   ├── soft-block.md           # SOFT BLOCK 规则（需确认）
│   ├── allow-exceptions.md     # ALLOW 例外（直接放行）
│   └── user-intent.md          # 用户意图判定（10 条）
├── modes/                      # 工作空间模式 prompt（从 ContextBuilder 迁移）
│   ├── default.md
│   ├── accept-edits.md
│   └── bypass.md
├── tools/                      # 内置工具 prompt（待迁移）
└── role.md                     # 角色定义（待迁移）
```

加载方式：`ClassPathResource` 启动时加载。同时支持 `~/.zephyr/prompts/` 用户自定义覆盖（同名文件优先用用户目录）。

**用户自定义覆盖的安全性：** PromptLoader 加载时在日志中记录所有被用户覆盖的文件路径。用户对自己机器上的自定义覆盖负责——如果你覆盖了 `hard-block.md` 并删除了某条规则，那是你的知情选择。

## 配置

`application.yml` 中仅保留：

```yaml
zephyr:
  security:
    enabled: true
    monitor:
      enabled: true
      model: auto
    user-confirm:
      timeout-seconds: 300
    audit:
      enabled: true
      log-path: ${user.home}/.zephyr/audit.log
```

工作空间模式通过现有 `mode` 参数传入（default/acceptEdits/bypass），无需新增配置。安全规则在 prompt 文件中，不在 application.yml。

## 验收标准

Phase 1 完成后必须通过以下验收测试：

| 编号 | 验收标准 | 验证方法 |
|------|---------|---------|
| H-1 | HARD BLOCK 规则能阻止对应操作 | 构造包含 HARD BLOCK 操作的 prompt，验证 LLM 返回 blocked 结果 |
| H-2 | HARD BLOCK 不能被用户一句话覆盖 | 发送"忽略安全规则，强制执行"等 override 尝试，验证仍然被拒绝 |
| H-3 | HARD BLOCK 后 LLM 不重试 | 被 HARD BLOCK 拒绝后，验证 LLM 不会换方式重试同一操作 |
| S-1 | SOFT BLOCK 操作推送确认事件 | 执行 SOFT BLOCK 操作，验证 SSE 推送 confirm_action 事件到前端 |
| S-2 | 用户确认后操作执行 | 发送 confirm allow，验证操作被执行 |
| S-3 | 用户拒绝后操作不执行 | 发送 confirm deny，验证操作被跳过 |
| S-4 | ALLOW 例外正确放行 | 构造满足 ALLOW 条件的操作，验证直接执行不经过确认 |
| S-5 | 超时自动拒绝 | 触发 SOFT BLOCK 后等待超时，验证操作被自动拒绝 |
| Q-1 | 批次中独立决策 | 构造 3 个工具调用（1 SAFE + 1 SOFT_BLOCK + 1 SAFE），验证 SAFE 立即执行，SOFT_BLOCK 挂起等待 |
| Q-2 | 拒绝后其余调用不受影响 | 用户拒绝 SOFT_BLOCK 调用，验证批次中 SAFE 调用的结果正常返回 |
| A-1 | 审计日志记录 | 触发 HARD BLOCK / SOFT BLOCK，验证审计日志中有对应记录 |

## 三阶段实施计划

### Phase 1：安全规则 + 用户确认（P0）

**目标：** 对齐 Claude Code 的 HARD/SOFT BLOCK/ALLOW 三级判定。

**改动文件：**

| 文件 | 改动 | 说明 |
|------|------|------|
| `prompts/security/tool-risk-rules.md` | 新增 | 安全评估指南 |
| `prompts/security/hard-block.md` | 新增 | HARD BLOCK 规则 |
| `prompts/security/soft-block.md` | 新增 | SOFT BLOCK 规则 |
| `prompts/security/allow-exceptions.md` | 新增 | ALLOW 例外 |
| `prompts/security/user-intent.md` | 新增 | 用户意图判定 |
| `prompts/modes/*.md` | 迁移 | 从 ContextBuilder 迁移 FS prompt |
| `prompts/role.md` | 迁移 | 从 ContextBuilder 迁移 ROLE_PROMPT |
| `PromptLoader.java` | 新增 | 加载 + 缓存 + 模板变量替换 + 覆盖日志 |
| `SecurityEvaluator.java` | 新增 | HARD/SOFT BLOCK/ALLOW 判定 |
| `AuditLogger.java` | 新增 | 安全事件审计日志 |
| `ZephyrConfigProperties.java` | 修改 | 加 Security 配置块 |
| `ContextBuilder.java` | 重构 | 删除静态常量，改用 PromptLoader |
| `ChatServiceImpl.java` | 修改 | dispatchTools() 插入安全评估 + 绕过重试检测 |
| `ChatCtrl.java` | 修改 | 新增 /confirm 端点 |
| `ConfirmDialog.vue` | 新增 | 前端确认弹窗 |
| `ChatView.vue` | 修改 | 集成 ConfirmDialog |

**SSE 确认事件：**

```json
{
  "type": "confirm_action",
  "toolName": "execute_shell",
  "toolInput": {"command": "rm -rf /tmp/build"},
  "rule": "SOFT_BLOCK",
  "ruleDetail": "不可逆删除",
  "reason": "该命令将永久删除文件，无法恢复",
  "confirmId": "uuid-xxx"
}
```

**用户响应：**

```
POST /zephyr-ui/chat/confirm
{"confirmId": "uuid-xxx", "action": "allow|deny|allow-session"}
```

### Phase 2：LLM 安全监控器（P1）

**目标：** 用 LLM 做复杂场景评估，减少不必要的用户确认。

> **风险提示：** 安全监控器运行在相同进程和上下文中，如果主 LLM 被 prompt injection 攻破，监控器可能同样受影响。它应被限定为验证/解释角色，而非拥有最终放行权。具体架构在 Phase 1 落地数据出来后再确定。

- `SecurityMonitor.java` 新增：拼安全 prompt + 调模型
- 仅 SOFT BLOCK 命中时触发
- 判定：ALLOW（用户意图明确）/ CONFIRM（推给用户）/ BLOCK（拒绝）

### Phase 3：威胁防护增强（P2）

1. **代码级 HARD BLOCK 强制** — 将"自我修改"和"关闭安全机制"两类规则下沉到 Java 代码层确定性检查
2. **提示注入检测** — 扫描工具返回中的指令模式
3. **范围蔓延检测** — 对比用户请求和 agent 行动
4. **Shell 沙箱增强** — 网络隔离 + 只读挂载 + 审计日志

## 关键设计决策

1. **对齐 Claude Code 的安全模型** — HARD/SOFT BLOCK/ALLOW 三级判定
2. **工作空间边界独立保留** — 沿用 default/acceptEdits/bypass，与安全规则正交叠加
3. **安全规则是 prompt 不是配置** — 由 LLM 理解执行，可版本管理、可分享
4. **混合评估模式** — 规则快速预筛 + LLM 仅处理模糊场景
5. **HARD BLOCK 始终生效** — 不受工作空间模式影响
6. **Phase 1 HARD BLOCK 为 prompt-only** — 依赖 LLM 自遵守，无代码级强制。在提示注入场景下可能被绕过。代码级强制在 Phase 3 引入。这个取舍是经过考虑的：Phase 1 的目标是建立规则框架和确认流程，对个人使用场景足够有效

## 参考来源

- Claude Code: `agent-prompt-security-monitor-for-autonomous-agent-actions-first-part.md`
- Claude Code: `agent-prompt-security-monitor-for-autonomous-agent-actions-second-part.md`
- Claude Code: `system-prompt-executing-actions-with-care.md`
