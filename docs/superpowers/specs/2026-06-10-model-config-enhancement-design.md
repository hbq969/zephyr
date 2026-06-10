# 模型配置增强

## 概述

四个改动：新增拉取模型名称功能、新增模型参数配置、新增请求超时参数、修复编辑时 API Key 掩码显示。

---

## Part A — 拉取模型名称

### 需求

用户在新增/编辑模型时，填写 baseUrl 和 apiKey 后，点击拉取按钮获取 provider 下的模型名称列表，从下拉菜单中选中后自动填入"模型名称"字段。

### 后端

**新增接口：** `POST /zephyr-ui/model-config/fetch-models`

请求体：
```json
{ "baseUrl": "https://api.openai.com", "apiKey": "sk-xxx" }
```

响应体：
```json
{ "state": "OK", "body": [{ "id": "gpt-4o" }, { "id": "gpt-4o-mini" }] }
```

**Service 实现（`ModelConfigService.fetchModels`）：**

1. 校验 baseUrl、apiKey 非空
2. 调用 `{baseUrl}/v1/models`，Authorization 头为 `Bearer {apiKey}`
3. 解析响应 JSON `data` 数组，提取每项 `id` 字段
4. 网络异常/非 200/空列表 → 返回空列表或错误提示
5. 复用现有 OkHttp 调用模式，与 `detectMaxContextTokens()` 风格一致

### 前端

- 模型名称输入框右侧增加圆形搜索按钮（`<el-button circle>`）
- 点击 → 按钮显示 loading 动画
- 请求成功 → 输入框切换为 `<el-select>`，列出模型名称，选中后填入并恢复输入框
- 请求失败 → toast 提示"拉取失败，请手动输入"
- 用户始终可手动输入（拉取为辅助功能，非强制）

---

## Part B — 模型参数配置

### 需求

在模型新增/编辑表单中，增加"模型参数"独立区块。7 个预设参数，每行含参数名、输入框（默认值）、tooltip 图标（悬浮显示说明）、删除按钮。底部支持新增自定义参数。

### 数据层

`model_configs` 表新增 `params TEXT` 列（DDL 变更）。

存储 JSON 字符串，如：
```json
{
  "temperature": 0.7,
  "top_p": 1.0,
  "max_tokens": 4096,
  "frequency_penalty": 0,
  "presence_penalty": 0,
  "reasoning_effort": "medium",
  "request_timeout": 120
}
```

### 预设参数（7 个）

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `temperature` | `0.7` | 控制输出随机性，值越高回复越多样，越低越确定 |
| `top_p` | `1.0` | 核采样，仅从累积概率达 top_p 的 token 中采样 |
| `max_tokens` | `4096` | 模型单次回复的最大 token 数 |
| `frequency_penalty` | `0` | 降低模型重复相同词的倾向，正值减少重复 |
| `presence_penalty` | `0` | 鼓励模型谈论新话题，正值增加话题多样性 |
| `reasoning_effort` | `medium` | 推理模型的思考深度（low / medium / high），仅部分模型支持 |
| `request_timeout` | `120` | 请求 LLM API 的超时等待时间（秒） |

### 后端改动

- `ModelConfigEntity` 加 `params` 字段（String）
- `create` / `update` 方法接收并持久化 `params` JSON
- `list` 返回时包含 `params` 字段
- Mapper XML：三方言 DDL 的 `createModelConfigsTable` 加 `params text` 列
- SQL 增量脚本：`ALTER TABLE model_configs ADD COLUMN IF NOT EXISTS params text`
- DML `insert` / `update` 加 `params` 字段

### 前端

- 表单中新增"模型参数"独立区块（带章节标题）
- 7 个预设参数行：参数名 monospace 字体 — 输入框 — tooltip 图标 — 删除按钮
- 底部"+ 添加参数"按钮，展开 key-value 输入行，确认后追加
- 自定义参数以斜体区分于预设参数
- 纵向布局，参数之间 hairline 分隔

---

## Part C — 编辑时 API Key 掩码显示（Bug 修复）

### 问题

编辑已有模型时，API Key 字段显示为空。用户不知道是否已设置 Key，保存时必须重新输入。

### 根因

后端 `list()` 正确返回 `apiKeyEncrypted: "abc****defg"`（mask 后），但前端 `startEdit()` 写死 `apiKey.value = ''`。

### 修复

**后端无需改动**，`update()` 已正确处理：`apiKey` 为空字符串时不覆盖原有值。

**前端修改：**

- `startEdit()` 中检测 `m.apiKeyEncrypted` 是否有值，有则设置 `hasExistingKey = true`
- API Key 输入框显示 `••••••••` 掩码文字（灰色）
- 下方显示绿色提示："已设置 API Key，无需修改可留空"
- 点击输入框自动清空掩码，用户可输入新 Key
- 离开输入框时如仍为空则恢复掩码
- 右侧 `×` 按钮清除已有 Key，允许输入新 Key

---

## 联动

- 拉取模型后，如 provider 返回了模型信息，可同时用 `detect-context` 的上下文大小结果自动填入 `max_tokens` 参数值（现有功能已支持，本次不改动 `detect-context` 接口）

---

## DDL 变更清单

| 位置 | 变更 |
|------|------|
| `embedded/createModelConfigsTable` | 加 `params text` 列 |
| `mysql/createModelConfigsTable` | 加 `params text` 列 |
| `postgresql/createModelConfigsTable` | 加 `params text` 列 |
| DML `insert` (common) | 加 `#{params}` 字段 |
| DML `update` (common) | 加 `params` 字段 |
| SQL 增量脚本 | 加 `ALTER TABLE ADD COLUMN params text` |

---

## 验证

1. `curl` 测试 `/fetch-models`，传入有效 baseUrl+apiKey，确认返回模型列表
2. `curl` 测试 create/update，传入 `params` JSON，确认持久化并正确返回
3. 前端：新建模型时填写 baseUrl+apiKey，点击拉取，下拉选择模型
4. 前端：编辑已有模型，API Key 显示掩码，不改动直接保存不丢失 Key
5. 前端：增删自定义参数，保存后再次编辑确认参数正确回显
