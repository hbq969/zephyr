# 状态栏改进实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 状态栏重排：上下文占比移到模型名后并带颜色渐变，模型增加 maxContextTokens 字段（自动探测 + 手动回退），删除 MCP/Skill 项。

**Architecture:** 后端 ModelConfigEntity 加字段 + DDL/DML + Service 自动探测；前端 StatusBar 简化布局 + 颜色渐变计算 + store 从当前模型取上下文上限。

**Tech Stack:** Java 17 + Spring Boot + MyBatis + Vue 3 + TypeScript + OkHttp（自动探测 HTTP 调用）

---

### Task 1: Entity + DDL 加 maxContextTokens 字段

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/entity/ModelConfigEntity.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/postgresql/ModelConfigMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/mysql/ModelConfigMapper.xml`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/embedded/ModelConfigMapper.xml`

- [ ] **Step 1: Entity 加字段**

在 `ModelConfigEntity.java` 的 `private Long updatedAt;` 后添加：

```java
private Long maxContextTokens;
```

- [ ] **Step 2: 三方言 DDL 加列**

三个方言的 `createModelConfigsTable` 中的 `api_key_encrypted text,` 后添加：

```sql
max_context_tokens bigint,
```

即：
```sql
create table if not exists model_configs (
  id varchar(64) primary key,
  user_name varchar(64) not null,
  name varchar(128) not null,
  base_url varchar(512),
  api_key_encrypted text,
  max_context_tokens bigint,
  is_default smallint default 0,
  created_at bigint,
  updated_at bigint
);
```

- [ ] **Step 3: 增量 DDL（已有表迁移）**

在 `src/main/resources/zephyr-zh-CN.sql` 末尾添加：

```sql
alter table model_configs add column if not exists max_context_tokens bigint;
```

- [ ] **Step 4: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/dao/entity/ModelConfigEntity.java \
  src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/postgresql/ModelConfigMapper.xml \
  src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/mysql/ModelConfigMapper.xml \
  src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/embedded/ModelConfigMapper.xml \
  src/main/resources/zephyr-zh-CN.sql
git commit -m "feat: ModelConfig 增加 maxContextTokens 字段，DDL 三方言 + 增量迁移"
```

---

### Task 2: Common DML 更新

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/common/ModelConfigMapper.xml`

- [ ] **Step 1: select 语句加字段**

`queryByUserName` 和 `queryById` 的 select 列表末尾（`updated_at as updatedAt` 后）加：

```sql
, max_context_tokens as maxContextTokens
```

即 select 变为：
```sql
select id, user_name as userName, name, base_url as baseUrl, api_key_encrypted as apiKeyEncrypted, is_default as isDefault, created_at as createdAt, updated_at as updatedAt, max_context_tokens as maxContextTokens
```

- [ ] **Step 2: insert 语句加字段**

在 `insert` 的 values 子句中加 `max_context_tokens`：

```xml
<insert id="insert">
  insert into model_configs (id, user_name, name, base_url, api_key_encrypted, max_context_tokens, is_default, created_at, updated_at)
  values (#{id}, #{userName}, #{name}, #{baseUrl}, #{apiKeyEncrypted}, #{maxContextTokens}, #{isDefault}, #{createdAt}, #{updatedAt})
</insert>
```

- [ ] **Step 3: update 语句加字段**

在 `update` 的 set 子句中加：

```xml
<update id="update">
  update model_configs
  set name = #{name}, base_url = #{baseUrl}, api_key_encrypted = #{apiKeyEncrypted}, max_context_tokens = #{maxContextTokens}, updated_at = #{updatedAt}
  where id = #{id} and user_name = #{userName}
</update>
```

- [ ] **Step 4: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/common/ModelConfigMapper.xml
git commit -m "feat: common DML 加 maxContextTokens 字段映射"
```

---

### Task 3: Service 自动探测 maxContextTokens

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/service/impl/ModelConfigServiceImpl.java`

- [ ] **Step 1: 在 create 方法中添加自动探测逻辑**

在 `create()` 方法的 `modelConfigDao.insert(entity)` 之后、`return entity` 之前，添加自动探测：

```java
@Override
@Transactional
public ModelConfigEntity create(Map<String, String> body, String userName) {
    // ... 现有代码 ...

    modelConfigDao.insert(entity);

    // 自动探测最大上下文
    Long maxTokens = detectMaxContextTokens(entity);
    if (maxTokens != null) {
        entity.setMaxContextTokens(maxTokens);
        modelConfigDao.updateMaxContextTokens(entity.getId(), maxTokens, userName);
    } else if (body.containsKey("maxContextTokens") && !body.get("maxContextTokens").isBlank()) {
        Long manual = Long.parseLong(body.get("maxContextTokens"));
        entity.setMaxContextTokens(manual);
        modelConfigDao.updateMaxContextTokens(entity.getId(), manual, userName);
    }

    return entity;
}
```

- [ ] **Step 2: 添加 detectMaxContextTokens 私有方法**

```java
private Long detectMaxContextTokens(ModelConfigEntity entity) {
    if (entity.getBaseUrl() == null || entity.getBaseUrl().isBlank()) return null;
    String apiKey = entity.getApiKeyEncrypted();
    if (apiKey == null || apiKey.isBlank()) return null;
    try {
        String url = entity.getBaseUrl().replaceAll("/$", "") + "/v1/models";
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build();
        okhttp3.Request req = new okhttp3.Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .get()
                .build();
        okhttp3.Response resp = client.newCall(req).execute();
        if (!resp.isSuccessful()) return null;
        String body = resp.body() != null ? resp.body().string() : "";
        com.google.gson.JsonObject json = com.google.gson.Gson().fromJson(body, com.google.gson.JsonObject.class);
        if (json.has("data")) {
            for (var item : json.getAsJsonArray("data")) {
                var obj = item.getAsJsonObject();
                String id = obj.has("id") ? obj.get("id").getAsString() : "";
                if (id.equals(entity.getName()) || id.contains(entity.getName())) {
                    if (obj.has("context_window")) return obj.get("context_window").getAsLong();
                    if (obj.has("max_context_length")) return obj.get("max_context_length").getAsLong();
                    if (obj.has("max_input_tokens")) return obj.get("max_input_tokens").getAsLong();
                }
            }
        }
    } catch (Exception e) {
        log.info("自动探测模型 {} 上下文大小失败: {}", entity.getName(), e.getMessage());
    }
    return null;
}
```

- [ ] **Step 3: 在 DAO 中添加 updateMaxContextTokens 方法**

修改 `src/main/java/com/github/hbq969/ai/zephyr/config/dao/ModelConfigDao.java`：

```java
@Update("update model_configs set max_context_tokens = #{maxTokens} where id = #{id} and user_name = #{userName}")
void updateMaxContextTokens(@Param("id") String id, @Param("maxTokens") Long maxTokens, @Param("userName") String userName);
```

- [ ] **Step 4: update 方法也支持 maxContextTokens**

```java
@Override
@Transactional
public void update(Map<String, String> body, String userName) {
    ModelConfigEntity entity = new ModelConfigEntity();
    entity.setId(body.get("id"));
    entity.setUserName(userName);
    entity.setName(body.get("name"));
    entity.setBaseUrl(body.getOrDefault("baseUrl", ""));
    String apiKey = body.get("apiKey");
    if (apiKey != null && !apiKey.isEmpty()) {
        entity.setApiKeyEncrypted(encryptApiKey(apiKey));
    }
    if (body.containsKey("maxContextTokens") && !body.get("maxContextTokens").isBlank()) {
        entity.setMaxContextTokens(Long.parseLong(body.get("maxContextTokens")));
    }
    entity.setUpdatedAt(System.currentTimeMillis() / 1000);
    modelConfigDao.update(entity);
}
```

- [ ] **Step 5: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home && mvn compile -q
```

Expected: BUILD SUCCESS

- [ ] **Step 6: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/service/impl/ModelConfigServiceImpl.java \
  src/main/java/com/github/hbq969/ai/zephyr/config/dao/ModelConfigDao.java
git commit -m "feat: 创建模型时自动探测 maxContextTokens，支持手动回退"
```

---

### Task 4: 前端 store 支持 maxContextTokens + 上下文实时获取

**Files:**
- Modify: `src/main/resources/static/src/store/settings.ts`
- Modify: `src/main/resources/static/src/types/chat.ts`

- [ ] **Step 1: 类型定义**

在 `src/main/resources/static/src/types/chat.ts` 的 ModelConfig 接口加字段：

```typescript
export interface ModelConfig {
  id?: string
  name: string
  baseUrl?: string
  isDefault: boolean
  apiKey?: string
  maxContextTokens?: number
}
```

- [ ] **Step 2: store loadModels 映射 maxContextTokens**

在 `settings.ts` 的 `loadModels()` 中的 map 加：

```typescript
maxContextTokens: m.maxContextTokens,
```

即：
```typescript
const list: ModelConfig[] = res.data.body.map((m: any) => ({
  id: m.id,
  name: m.name,
  baseUrl: m.baseUrl,
  isDefault: m.isDefault === 1,
  apiKey: m.apiKeyEncrypted,
  maxContextTokens: m.maxContextTokens,
}))
```

- [ ] **Step 3: 添加 contextTotal 计算属性**

替换现有的硬编码 `contextTotal`，改为从当前模型取值：

```typescript
const contextTotal = computed(() => {
  const def = models.value.find(m => m.name === currentModel.value)
  return def?.maxContextTokens || 131072
})
```

`contextTotal` 从 `ref(131072)` 改为 computed。

`contextPercent` computed 保持不变（已依赖 contextTotal）。

- [ ] **Step 4: 提交**

```bash
git add src/main/resources/static/src/store/settings.ts src/main/resources/static/src/types/chat.ts
git commit -m "feat: store 从当前模型取 maxContextTokens 作为上下文上限"
```

---

### Task 5: StatusBar 重排 + 颜色渐变

**Files:**
- Modify: `src/main/resources/static/src/views/chat/StatusBar.vue`

- [ ] **Step 1: 重写 StatusBar 模板和逻辑**

完整替换 StatusBar.vue：

```vue
<script lang="ts" setup>
import { computed, onMounted } from 'vue'
import { useSettingsStore } from '@/store/settings'
import { Icon } from '@iconify/vue'

const settingsStore = useSettingsStore()

onMounted(() => { settingsStore.loadModels() })

const ctxPercent = computed(() => settingsStore.contextPercent)

const ctxUsedStr = computed(() => {
  const k = settingsStore.contextUsed / 1024
  return k >= 1024 ? (k / 1024).toFixed(1) + 'M' : k.toFixed(1) + 'K'
})

const ctxTotalStr = computed(() => {
  const k = settingsStore.contextTotal / 1024
  return k >= 1024 ? (k / 1024).toFixed(0) + 'M' : (k / 1024).toFixed(0) + 'K'
})

function hslInterpolate(hex1: string, hex2: string, t: number): string {
  const [h1, s1, l1] = hexToHsl(hex1)
  const [h2, s2, l2] = hexToHsl(hex2)
  const h = h1 + (h2 - h1) * t
  const s = s1 + (s2 - s1) * t
  const l = l1 + (l2 - l1) * t
  return `hsl(${Math.round(h)}, ${Math.round(s)}%, ${Math.round(l)}%)`
}

function hexToHsl(hex: string): [number, number, number] {
  const r = parseInt(hex.slice(1, 3), 16) / 255
  const g = parseInt(hex.slice(3, 5), 16) / 255
  const b = parseInt(hex.slice(5, 7), 16) / 255
  const max = Math.max(r, g, b), min = Math.min(r, g, b)
  const d = max - min
  let h = 0
  const l = (max + min) / 2
  const s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1))
  if (d !== 0) {
    if (max === r) h = ((g - b) / d + (g < b ? 6 : 0)) * 60
    else if (max === g) h = ((b - r) / d + 2) * 60
    else h = ((r - g) / d + 4) * 60
  }
  return [h, s * 100, l * 100]
}

const ctxColor = computed(() => {
  const p = ctxPercent.value
  if (p <= 40) return '#5db872'
  if (p <= 70) return hslInterpolate('#5db872', '#e8a55a', (p - 40) / 30)
  return hslInterpolate('#e8a55a', '#c64545', Math.min((p - 70) / 30, 1))
})
</script>

<template>
  <div class="status-bar">
    <div class="status-item">
      <Icon icon="lucide:bot" class="s-icon" />
      <span>{{ settingsStore.currentModel }}</span>
    </div>

    <div class="ctx-group">
      <div class="ctx-bar">
        <div class="ctx-fill" :style="{ width: ctxPercent + '%', background: ctxColor }"></div>
      </div>
      <span class="ctx-text">{{ ctxUsedStr }} / {{ ctxTotalStr }}</span>
      <span class="ctx-pct" :style="{ color: ctxColor }">{{ ctxPercent }}%</span>
    </div>
  </div>
</template>

<style scoped>
.status-bar {
  padding: 6px 20px;
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  border-top: 1px solid var(--el-border-color);
  background: var(--el-bg-color);
}
.status-item {
  display: flex;
  align-items: center;
  gap: 5px;
  white-space: nowrap;
  color: var(--el-text-color-secondary);
}
.s-icon { font-size: 14px; flex-shrink: 0; color: var(--el-color-primary); }
.ctx-group { display: flex; align-items: center; gap: 6px; }
.ctx-bar { width: 80px; height: 5px; border-radius: 3px; background: var(--el-border-color); overflow: hidden; }
.ctx-fill { height: 100%; border-radius: 3px; transition: width 0.3s, background 0.3s; }
.ctx-text { font-size: 11px; color: var(--el-text-color-placeholder); white-space: nowrap; }
.ctx-pct { font-size: 11px; font-weight: 600; white-space: nowrap; }
</style>
```

- [ ] **Step 2: 类型检查**

```bash
cd src/main/resources/static && npx vue-tsc --noEmit 2>&1 | head -20
```

Expected: No errors from StatusBar.vue or settings.ts

- [ ] **Step 3: 提交**

```bash
git add src/main/resources/static/src/views/chat/StatusBar.vue
git commit -m "feat: 状态栏重排，占比移模型名后，颜色渐变，删除 MCP/Skill"
```

---

### Task 6: 编译验证

**Files:**
- 无

- [ ] **Step 1: 前端构建**

```bash
cd src/main/resources/static && npm run build 2>&1 | grep -E "built|error|ERROR"
```

Expected: `✓ built in X.XXs`

- [ ] **Step 2: 后端编译**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home && mvn compile -q
```

Expected: BUILD SUCCESS
