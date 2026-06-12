# Spec 01: 模型配置改造

## 目标

扩展现有 `zephyr_model_config` 表，支持区分 LLM 对话模型和 Embedding 模型。

## 后端变更

### 表结构变更

`zephyr_model_config` 加三个字段：

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `model_type` | varchar(16) | `llm` | `llm` 或 `embedding` |
| `dimensions` | int | NULL | Embedding 向量维度，llm 类型为 NULL |

现有 `is_default` 字段对 embedding 模型同样适用（设为默认 Embedding）。

### 增量 DDL

在 `zephyr-zh-CN.sql` 中加：

```sql
ALTER TABLE zephyr_model_config ADD COLUMN IF NOT EXISTS model_type varchar(16) DEFAULT 'llm';
ALTER TABLE zephyr_model_config ADD COLUMN IF NOT EXISTS dimensions int DEFAULT NULL;
```

### Mapper XML

三方言 DDL 的 `createZephyrModelConfig` 中同步加列。

### Entity

`ModelConfigEntity` 加 `modelType`、`dimensions` 字段。

### API 调整

| 接口 | 变更 |
|------|------|
| `GET /model-config/list` | 支持 `?modelType=embedding` 过滤 |
| `POST /model-config/create` | body 加 `modelType`、`dimensions` |
| `POST /model-config/update` | body 加 `modelType`、`dimensions` |
| `POST /model-config/set-default` | 如果目标模型是 embedding，取消其他 embedding 的默认，不影响 llm |

## 前端变更

### 模型配置页

- 模型列表每行加 `el-tag` 标记"对话"或"Embedding"
- 新增/编辑表单：加 `modelType` 下拉（对话模型/Embedding 模型），Embedding 类型时显示 `dimensions` 输入框
- 对话页模型选择器：只显示 `modelType=llm` 的模型

## 验证

```bash
curl -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/model-config/list?modelType=embedding"
```
