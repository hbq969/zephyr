# 模型配置共享机制

## 概述

参考 SKILL 管理为模型配置增加共享机制：管理员可将模型设为共享供全员使用，普通用户可查看和使用共享模型，但不可编辑或删除。

## 数据库变更

### `zephyr_model_configs` 表加 `scope` 列

```sql
ALTER TABLE zephyr_model_configs ADD COLUMN scope VARCHAR(16) DEFAULT 'user';
```

取值 `user` | `shared`。

### 新增 `zephyr_user_model_prefs` 表

```sql
CREATE TABLE IF NOT EXISTS zephyr_user_model_prefs (
    id VARCHAR(12) PRIMARY KEY,
    user_name VARCHAR(64) NOT NULL,
    model_type VARCHAR(16) NOT NULL DEFAULT 'llm',
    model_id VARCHAR(12) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_model_prefs ON zephyr_user_model_prefs(user_name, model_type);
```

每个用户每种模型类型独立记录默认模型 ID。

## 后端改动

### Entity

`ModelConfigEntity` 加 `scope` 字段，默认 `"user"`。

### DAO — `ModelConfigDao`

| 方法 | 说明 |
|------|------|
| `queryByUserName(userName)` | 无变动，SQL 加 `AND scope='user'` |
| `queryShared()` | 新增，查 `WHERE scope='shared'` |
| `queryByType(userName, modelType)` | 无变动，SQL 加 `AND scope='user'` |
| `toggleScope(id, scope)` | 新增，更新 scope 列 |

新增 `UserModelPreferenceDao`：
- `upsert(userName, modelType, modelId)` — 插入或更新默认偏好
- `queryByUserAndType(userName, modelType)` — 查用户的默认模型 ID

### Service — `ModelConfigServiceImpl`

| 方法 | 改动 |
|------|------|
| `list(userName)` | 合并逻辑：`queryShared()` + `queryByUserName(userName)`，私有覆盖同名共享，去重 key 为 name |
| `listByType(modelType, userName)` | 同上合并逻辑，加 type 过滤 |
| `setDefault(id, userName)` | 改为写入 `UserModelPreferenceDao` 而非更新 model 表的 is_default |
| `toggleScope(id, scope)` | 新增，admin 才能调，切换共享/私有 |
| `create()`, `update()` | 无变动（scope 固定 user，不暴露给用户） |

### Controller — `ModelConfigCtrl`

新增接口：
- `POST /zephyr-ui/model-config/toggle-scope` — `{id, scope}`，admin 检查

### ContextBuilder

`build()` 中模型加载逻辑：
1. 合并私有默认（`queryByUserName`）+ 共享模型（`queryShared`）
2. 优先读 `UserModelPreferenceDao`，找到则用对应模型
3. 否则回退到用户私有模型中的默认，再没有就用第一个

## 前端改动

### types/chat.ts

```typescript
export interface ModelConfig {
  // ... 现有字段
  scope?: 'user' | 'shared'
}
```

### store/settings.ts

`loadModels()` 无需改动（后端 list 接口已合并共享模型）。
`setDefaultModelRemote()` 无需改动。

### views/settings/ModelSettings.vue

**Tab 重构：**

主 tabs 从 `llm`/`embedding`（按类型）改为 `user`/`shared`（按 scope），个人模型在上。

每个卡片显示类型标签：
- 对话模型 → `<span class="model-type-tag tag-llm">对话</span>`
- Embedding 模型 → `<span class="model-type-tag tag-embedding">Embedding</span>`

共享模型卡片在名称后加 scope badge：
- `<span class="badge badge-scope-shared">共享</span>`

**共享 toggle**：仅 admin（`store.isAdmin`）可见，每个共享模型卡片右侧显示切换按钮，点击调用 `toggleScope`。

**权限控制**：非 admin 看不到编辑/删除按钮和共享 toggle。

**类型过滤**：在每个 tab 内增加类型筛选下拉（全部/对话/Embedding），默认全部。

### views/chat/InputArea.vue

模型选择下拉拆分为共享/我的两个 section（参考知识库选择器样式）：

```
┌─────────────────────────────┐
│ 共享模型                     │
│  Claude Opus 4.7    共享    │
│  GPT-4o             共享    │
│─────── 分隔线 ──────────────│
│ 我的模型                     │
│  DeepSeek-V3        个人    │
│  Claude Sonnet 4.6  个人    │
└─────────────────────────────┘
```

新增 computed：
```typescript
const sharedModels = computed(() => chatModels.value.filter(m => m.scope === 'shared'))
const userModels = computed(() => chatModels.value.filter(m => m.scope !== 'shared'))
```

### views/settings/KnowledgeSettings.vue

无需改动（`fetchEmbedModels` 调 `/model-config/list?modelType=embedding`，后端已合并共享模型）。

## 交互规则

| 角色 | 查看共享模型 | 设共享模型为默认 | 编辑/删除共享模型 | 切换共享状态 |
|------|------------|----------------|-----------------|------------|
| admin | ✓ | ✓ | ✓ | ✓ |
| 普通用户 | ✓ | ✓ | ✗ | ✗ |

## 变更文件清单

| 层级 | 文件 | 变动类型 |
|------|------|---------|
| DB | `zephyr_model_configs` | DDL 加列 |
| DB | `zephyr_user_model_prefs` | 新建表 |
| Entity | `ModelConfigEntity.java` | 加 scope 字段 |
| Entity | `UserModelPreferenceEntity.java` | 新建 |
| DAO | `ModelConfigDao.java` | 加 queryShared/toggleScope/queryByTypeAndScope |
| DAO | `UserModelPreferenceDao.java` | 新建 |
| Mapper XML | `ModelMapper.xml`（所有方言） | DDL 加列 + 新增 SQL |
| Mapper XML | `UserModelPreferenceMapper.xml`（所有方言） | 新建 |
| Service | `ModelConfigServiceImpl.java` | 改 list/setDefault + 加 toggleScope |
| Service | `InitialServiceImpl.java` | 注册 user_model_prefs 建表 |
| Ctrl | `ModelConfigCtrl.java` | 加 toggle-scope 接口 |
| ContextBuilder | `ContextBuilder.java` | 改模型加载逻辑 |
| Types | `chat.ts` | ModelConfig 加 scope |
| Store | `settings.ts` | 无变动（后端 cover） |
| Vue | `ModelSettings.vue` | Tab 重构 + scope 相关 UI |
| Vue | `InputArea.vue` | 模型选择器分栏展示 |
