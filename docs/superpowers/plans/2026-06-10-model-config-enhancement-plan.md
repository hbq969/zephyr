# 模型配置增强 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 为模型配置增加拉取模型名称、模型参数配置、API Key 掩码显示修复、请求超时参数四项功能。

**架构：** 后端新增 `/fetch-models` 接口调用 provider API 拉取模型列表，`model_configs` 表加 `params TEXT` 列存储 JSON 参数，前端 ModelSettings.vue 重构表单为双区块布局（基本配置 + 模型参数），修复编辑时 API Key 丢失问题。

**技术栈：** SpringBoot 3.5.4 + MyBatis + Vue3 + TypeScript + Element Plus

---

## Task 1: 后端 — ModelConfigEntity 加 params 字段

**文件：**
- 修改: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/entity/ModelConfigEntity.java`

- [ ] **Step 1: Entity 加 params 属性**

```java
@Data
public class ModelConfigEntity {
    private String id;
    private String userName;
    private String name;
    private String baseUrl;
    private String apiKeyEncrypted;
    private Integer isDefault;
    private Long createdAt;
    private Long updatedAt;
    private Long maxContextTokens;
    private String params;
}
```

## Task 2: 后端 — Mapper XML 三方言 DDL 加 params 列

**文件：**
- 修改: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/embedded/ModelConfigMapper.xml`
- 修改: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/mysql/ModelConfigMapper.xml`
- 修改: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/postgresql/ModelConfigMapper.xml`

- [ ] **Step 1: embedded DDL 加 params 列**

在 `<update id="createModelConfigsTable">` 的 `updated_at bigint` 后面加一行：
```xml
      params text,
```
注意保持缩进一致。

- [ ] **Step 2: mysql DDL 加 params 列**（同上）

- [ ] **Step 3: postgresql DDL 加 params 列**（同上）

- [ ] **Step 4: 编译验证**

```bash
mvn compile -DskipTests
```

## Task 3: 后端 — common Mapper XML 加 params 字段

**文件：**
- 修改: `src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/common/ModelConfigMapper.xml`

- [ ] **Step 1: select 语句加 params**

修改 `queryByUserName`：
```xml
<select id="queryByUserName" resultType="com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity">
  select id, user_name as userName, name, base_url as baseUrl, api_key_encrypted as apiKeyEncrypted, is_default as isDefault, created_at as createdAt, updated_at as updatedAt, max_context_tokens as maxContextTokens, params
  from model_configs
  where user_name = #{userName}
  order by created_at desc
</select>
```

- [ ] **Step 2: queryById 加 params**

```xml
<select id="queryById" resultType="com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity">
  select id, user_name as userName, name, base_url as baseUrl, api_key_encrypted as apiKeyEncrypted, is_default as isDefault, created_at as createdAt, updated_at as updatedAt, max_context_tokens as maxContextTokens, params
  from model_configs where id = #{id}
</select>
```

- [ ] **Step 3: insert 加 params**

```xml
<insert id="insert">
  insert into model_configs (id, user_name, name, base_url, api_key_encrypted, max_context_tokens, params, is_default, created_at, updated_at)
  values (#{id}, #{userName}, #{name}, #{baseUrl}, #{apiKeyEncrypted}, #{maxContextTokens}, #{params}, #{isDefault}, #{createdAt}, #{updatedAt})
</insert>
```

- [ ] **Step 4: update 加 params，同时修复 api_key_encrypted 空值覆盖问题**

```xml
<update id="update">
  update model_configs
  set name = #{name}, base_url = #{baseUrl}, updated_at = #{updatedAt}
  <if test="apiKeyEncrypted != null and apiKeyEncrypted != ''">, api_key_encrypted = #{apiKeyEncrypted}</if>
  <if test="maxContextTokens != null">, max_context_tokens = #{maxContextTokens}</if>
  <if test="params != null">, params = #{params}</if>
  where id = #{id} and user_name = #{userName}
</update>
```

> 原来的 update 无条件 set `api_key_encrypted = #{apiKeyEncrypted}`，当用户留空 API Key 编辑时会覆盖为 null。改为 `<if>` 条件判断后，只有用户明确输入新 Key 才更新。

- [ ] **Step 5: 编译验证**

```bash
mvn compile -DskipTests
```

## Task 4: 后端 — SQL 增量脚本加 ALTER TABLE

**文件：**
- 修改: `src/main/resources/zephyr-zh-CN.sql`
- 修改: `src/main/resources/zephyr-en-US.sql`
- 修改: `src/main/resources/zephyr-ja-JP.sql`

- [ ] **Step 1: 三语言 SQL 各加一行**

在文件末尾追加：
```sql
alter table model_configs add column if not exists params text;
```

## Task 5: 后端 — Service 新增 fetchModels 方法

**文件：**
- 修改: `src/main/java/com/github/hbq969/ai/zephyr/config/service/ModelConfigService.java`
- 修改: `src/main/java/com/github/hbq969/ai/zephyr/config/service/impl/ModelConfigServiceImpl.java`

- [ ] **Step 1: Service 接口加方法声明**

在 `ModelConfigService.java` 加：
```java
List<Map<String, Object>> fetchModels(Map<String, String> body);
```

- [ ] **Step 2: ServiceImpl 实现 fetchModels**

在 `ModelConfigServiceImpl.java` 加方法：
```java
@Override
public List<Map<String, Object>> fetchModels(Map<String, String> body) {
    String baseUrl = body.get("baseUrl");
    String apiKey = body.get("apiKey");
    if (baseUrl == null || baseUrl.isBlank() || apiKey == null || apiKey.isBlank()) {
        return List.of();
    }
    try {
        String url = baseUrl.replaceAll("/$", "") + "/v1/models";
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
        if (!resp.isSuccessful()) { resp.close(); return List.of(); }
        String respBody = resp.body() != null ? resp.body().string() : "";
        resp.close();
        com.google.gson.JsonObject json = new com.google.gson.Gson().fromJson(respBody, com.google.gson.JsonObject.class);
        List<Map<String, Object>> models = new java.util.ArrayList<>();
        if (json.has("data")) {
            for (var item : json.getAsJsonArray("data")) {
                var obj = item.getAsJsonObject();
                if (obj.has("id")) {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("id", obj.get("id").getAsString());
                    models.add(m);
                }
            }
        }
        return models;
    } catch (Exception e) {
        log.info("拉取模型列表失败: {}", e.getMessage());
        return List.of();
    }
}
```

- [ ] **Step 3: 编译验证**

```bash
mvn compile -DskipTests
```

## Task 6: 后端 — Controller 新增 /fetch-模型 接口，create/update 透传 params

**文件：**
- 修改: `src/main/java/com/github/hbq969/ai/zephyr/config/ctrl/ModelConfigCtrl.java`
- 修改: `src/main/java/com/github/hbq969/ai/zephyr/config/service/impl/ModelConfigServiceImpl.java`

- [ ] **Step 1: Controller 加 fetchModels 端点**

在 `ModelConfigCtrl.java` 加：
```java
@Operation(summary = "拉取模型列表")
@RequestMapping(path = "/fetch-models", method = RequestMethod.POST)
@ResponseBody
@SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "modelConfig_fetchModels", apiDesc = "模型配置_拉取模型列表")
public ReturnMessage<?> fetchModels(@RequestBody Map<String, String> body) {
    List<Map<String, Object>> models = modelConfigService.fetchModels(body);
    return ReturnMessage.success(models);
}
```

- [ ] **Step 2: ServiceImpl create 方法加 params 处理**

在 `create()` 方法中 `entity.setUpdatedAt(...)` 之前加：
```java
String params = body.get("params");
entity.setParams(params != null && !params.isBlank() ? params : null);
```

- [ ] **Step 3: ServiceImpl update 方法加 params 处理**

在 `update()` 方法中 `entity.setUpdatedAt(...)` 之前加：
```java
String params = body.get("params");
entity.setParams(params != null ? params : null);
```

- [ ] **Step 4: 编译验证**

```bash
mvn compile -DskipTests
```

## Task 7: 前端 — 类型定义加 params 和 fetchModels

**文件：**
- 修改: `src/main/resources/static/src/types/chat.ts`
- 修改: `src/main/resources/static/src/store/settings.ts`

- [ ] **Step 1: ModelConfig 类型加 params**

```typescript
export interface ModelConfig {
  id?: string
  name: string
  baseUrl?: string
  apiKey?: string
  isDefault: boolean
  maxContextTokens?: number
  params?: string
}
```

- [ ] **Step 2: store loadModels 加 params 映射**

修改 `settings.ts` 中 `loadModels()` 的 map：
```typescript
const list: ModelConfig[] = res.data.body.map((m: any) => ({
  id: m.id,
  name: m.name,
  baseUrl: m.baseUrl,
  isDefault: m.isDefault === 1,
  apiKey: m.apiKeyEncrypted,
  maxContextTokens: m.maxContextTokens,
  params: m.params
}))
```

- [ ] **Step 3: store 加 fetchModels 方法**

在 `detectCtxRaw` 方法后面加：
```typescript
async function fetchModels(baseUrl: string, apiKey: string) {
  const res = await axios({ url: '/model-config/fetch-models', method: 'post', data: { baseUrl, apiKey } })
  if (res.data.state === 'OK' && Array.isArray(res.data.body)) {
    return res.data.body as { id: string }[]
  }
  return []
}
```

- [ ] **Step 4: store 导出加 fetchModels**

在 return 块中 `detectCtxRaw` 后面加 `fetchModels`

- [ ] **Step 5: 修改 addModelRemote 和 updateModelRemote 传 params**

```typescript
async function addModelRemote(name: string, baseUrl: string, apiKey: string, maxContextTokens: string, params: string) {
  const res = await axios({ url: '/model-config/create', method: 'post', data: { name, baseUrl, apiKey, maxContextTokens, params } })
  if (res.data.state === 'OK') await loadModels()
}

async function updateModelRemote(id: string, name: string, baseUrl: string, apiKey: string, maxContextTokens: string, params: string) {
  await axios({ url: '/model-config/update', method: 'post', data: { id, name, baseUrl, apiKey, maxContextTokens, params } })
  await loadModels()
}
```

## Task 8: 前端 — i18n 加新 key

**文件：**
- 修改: `src/main/resources/static/src/i18n/locale.ts`

- [ ] **Step 1: zh-CN 加键**

在 `modelConfig_contextLabel` 后加：
```typescript
"modelConfig_fetchModels": "拉取模型列表",
"modelConfig_fetchFail": "拉取失败，请手动输入",
"modelConfig_keySet": "已设置 API Key，无需修改可留空",
"modelConfig_params": "模型参数",
"modelConfig_addParam": "添加参数",
"modelConfig_paramName": "参数名",
"modelConfig_paramValue": "参数值",
```

- [ ] **Step 2: en-US 加键**

```typescript
"modelConfig_fetchModels": "Fetch model list",
"modelConfig_fetchFail": "Fetch failed, please input manually",
"modelConfig_keySet": "API Key is set, leave blank to keep unchanged",
"modelConfig_params": "Model Parameters",
"modelConfig_addParam": "Add parameter",
"modelConfig_paramName": "Param name",
"modelConfig_paramValue": "Param value",
```

- [ ] **Step 3: ja-JP 加键**

```typescript
"modelConfig_fetchModels": "モデル一覧を取得",
"modelConfig_fetchFail": "取得失敗、手動入力してください",
"modelConfig_keySet": "API Key設定済み、変更不要なら空欄で",
"modelConfig_params": "モデルパラメータ",
"modelConfig_addParam": "パラメータ追加",
"modelConfig_paramName": "パラメータ名",
"modelConfig_paramValue": "パラメータ値",
```

## Task 9: 前端 — ModelSettings.vue 全面改造

**文件：**
- 修改: `src/main/resources/static/src/views/settings/ModelSettings.vue`

**改动要点：**
1. 新增拉取模型按钮及下拉选择交互
2. 表单拆为"基本配置"和"模型参数"两个独立区块
3. 模型名称移到 API Key 下方
4. 7 个预设参数 + 自定义参数支持增删
5. 编辑时 API Key 显示掩码

> 由于该文件改动较大（约 200+ 行），用完整替换方式写入新内容。

- [ ] **Step 1: 完整替换 ModelSettings.vue**

```vue
<script lang="ts" setup>
import { ref, onMounted } from 'vue'
import { useSettingsStore } from '@/store/settings'
import { Icon } from '@iconify/vue'
import { getLangData } from '@/i18n/locale'
import { msg } from '@/utils/Utils'

const langData = getLangData()
const settingsStore = useSettingsStore()
const showForm = ref(false)
const name = ref('')
const baseUrl = ref('')
const apiKey = ref('')
const apiKeyShown = ref('')
const hasExistingKey = ref(false)
const maxCtx = ref('')
const editId = ref<string | null>(null)
const detecting = ref(false)
const detectMsg = ref('')
const fetching = ref(false)

const PRESET_PARAMS = [
  { key: 'temperature', default: '0.7', tip: '控制输出随机性，值越高回复越多样，越低越确定' },
  { key: 'top_p', default: '1.0', tip: '核采样，仅从累积概率达 top_p 的 token 中采样' },
  { key: 'max_tokens', default: '4096', tip: '模型单次回复的最大 token 数' },
  { key: 'frequency_penalty', default: '0', tip: '降低模型重复相同词的倾向，正值减少重复' },
  { key: 'presence_penalty', default: '0', tip: '鼓励模型谈论新话题，正值增加话题多样性' },
  { key: 'reasoning_effort', default: 'medium', tip: '推理模型的思考深度（low / medium / high），仅部分模型支持' },
  { key: 'request_timeout', default: '120', tip: '请求 LLM API 的超时等待时间（秒）' }
]
const params = ref<{ key: string; value: string; tip: string | null; isPreset: boolean }[]>([])
const showAddParam = ref(false)
const newParamKey = ref('')
const newParamVal = ref('')

function initParams(loadedParams?: Record<string, any>) {
  params.value = PRESET_PARAMS.map(p => ({ key: p.key, value: loadedParams?.[p.key] != null ? String(loadedParams[p.key]) : p.default, tip: p.tip, isPreset: true }))
  if (loadedParams) {
    for (const [k, v] of Object.entries(loadedParams)) {
      if (!PRESET_PARAMS.find(p => p.key === k)) {
        params.value.push({ key: k, value: String(v), tip: null, isPreset: false })
      }
    }
  }
}

function buildParamsJson(): string {
  const obj: Record<string, any> = {}
  for (const p of params.value) {
    if (p.value === '') continue
    const num = Number(p.value)
    obj[p.key] = isNaN(num) ? p.value : num
  }
  return Object.keys(obj).length > 0 ? JSON.stringify(obj) : ''
}

function parseParamsJson(raw?: string): Record<string, any> | undefined {
  if (!raw) return undefined
  try { return JSON.parse(raw) } catch { return undefined }
}

onMounted(() => { settingsStore.loadModels() })

async function add() {
  if (!name.value.trim()) return
  const paramsJson = buildParamsJson()
  if (editId.value) {
    await settingsStore.updateModelRemote(editId.value, name.value.trim(), baseUrl.value.trim(), apiKey.value, maxCtx.value, paramsJson)
  } else {
    await settingsStore.addModelRemote(name.value.trim(), baseUrl.value.trim(), apiKey.value, maxCtx.value, paramsJson)
  }
  resetForm()
}

function resetForm() {
  name.value = ''
  baseUrl.value = ''
  apiKey.value = ''
  apiKeyShown.value = ''
  hasExistingKey.value = false
  maxCtx.value = ''
  editId.value = null
  showForm.value = false
  detectMsg.value = ''
  initParams()
}

function startEdit(m: any) {
  editId.value = m.id
  name.value = m.name
  baseUrl.value = m.baseUrl || ''
  maxCtx.value = m.maxContextTokens ? String(m.maxContextTokens) : ''
  if (m.apiKey) {
    hasExistingKey.value = true
    apiKey.value = ''
    apiKeyShown.value = '••••••••'
  } else {
    hasExistingKey.value = false
    apiKey.value = ''
    apiKeyShown.value = ''
  }
  initParams(parseParamsJson(m.params))
  showForm.value = true
}

function cancelForm() { resetForm() }

function onApiKeyFocus() {
  if (hasExistingKey.value && apiKeyShown.value === '••••••••') {
    apiKeyShown.value = ''
  }
}

function onApiKeyBlur() {
  if (hasExistingKey.value && apiKeyShown.value === '') {
    apiKeyShown.value = '••••••••'
  }
}

function onApiKeyInput() { /* user typing */ }

function clearApiKey() {
  hasExistingKey.value = false
  apiKey.value = ''
  apiKeyShown.value = ''
}

function apiKeyDisplayValue(): string {
  return hasExistingKey.value ? apiKeyShown.value : apiKey.value
}

async function fetchModels() {
  if (!baseUrl.value.trim() || !apiKey.value) { msg(langData.modelConfig_fetchFail, 'warning'); return }
  fetching.value = true
  const models = await settingsStore.fetchModels(baseUrl.value.trim(), apiKey.value)
  fetching.value = false
  if (models.length > 0) {
    const sel = document.getElementById('modelSelect') as HTMLSelectElement | null
    if (sel) {
      sel.innerHTML = '<option value="">-- 选择模型 --</option>' + models.map(m => `<option value="${m.id}">${m.id}</option>`).join('')
      sel.style.display = 'block'
      const input = document.getElementById('modelNameInput') as HTMLInputElement | null
      if (input) input.style.display = 'none'
      sel.focus()
    }
  } else {
    msg(langData.modelConfig_fetchFail, 'warning')
  }
}

function onModelSelect(val: string) {
  if (val) {
    name.value = val
    const sel = document.getElementById('modelSelect') as HTMLSelectElement | null
    const input = document.getElementById('modelNameInput') as HTMLInputElement | null
    if (sel) sel.style.display = 'none'
    if (input) input.style.display = 'block'
  }
}

async function detectCtx() {
  if (!baseUrl.value.trim() || !apiKey.value) return
  detecting.value = true
  detectMsg.value = ''
  const res = await settingsStore.detectCtxRaw(name.value.trim(), baseUrl.value.trim(), apiKey.value)
  detecting.value = false
  if (res?.state === 'OK' && res.body) {
    maxCtx.value = String(res.body)
    detectMsg.value = langData.modelConfig_detectSuccess
  } else {
    detectMsg.value = langData.modelConfig_detectFail
  }
}

async function removeModel(id: string) { await settingsStore.deleteModelRemote(id) }
async function setDefault(id: string) { await settingsStore.setDefaultModelRemote(id) }

async function onSetCurrent(name: string) {
  settingsStore.setModel(name)
  const m = settingsStore.models.find(x => x.name === name)
  if (m?.id) await settingsStore.setDefaultModelRemote(m.id)
}

function addCustomParam() {
  if (!newParamKey.value.trim()) return
  params.value.push({ key: newParamKey.value.trim(), value: newParamVal.value.trim(), tip: null, isPreset: false })
  newParamKey.value = ''
  newParamVal.value = ''
  showAddParam.value = false
}

function removeParam(idx: number) { params.value.splice(idx, 1) }
</script>

<template>
  <div class="settings-page">
    <div class="page-header">
      <button class="back-btn" @click="$router.push('/chat')"><Icon icon="lucide:chevron-left" /></button>
      <h2>{{ langData.modelConfig_title }}</h2>
    </div>
    <div class="page-body">
      <!-- 模型列表 -->
      <div v-for="m in settingsStore.models" :key="m.name" class="setting-row">
        <div class="row-left">
          <Icon icon="lucide:cpu" class="row-icon" />
          <div>
            <div class="row-title">{{ m.name }}</div>
            <div v-if="m.baseUrl" class="row-sub">{{ m.baseUrl }}</div>
            <div v-if="m.maxContextTokens" class="row-sub ctx-info">{{ langData.modelConfig_contextLabel }}: {{ (m.maxContextTokens / 1024).toFixed(0) }}K</div>
          </div>
        </div>
        <div class="row-right">
          <button class="action-icon" @click="startEdit(m)" :title="langData.btnEdit"><Icon icon="lucide:pencil" /></button>
          <button class="action-icon danger" @click="m.id && removeModel(m.id)" :title="langData.btnDelete"><Icon icon="lucide:trash-2" /></button>
          <button v-if="settingsStore.currentModel !== m.name" class="set-btn" @click="onSetCurrent(m.name)">{{ langData.modelConfig_use }}</button>
          <span v-else class="current-badge">{{ langData.modelConfig_current }}</span>
        </div>
      </div>

      <!-- 表单 -->
      <div v-if="showForm" class="form-area">

        <!-- ====== 基本配置 ====== -->
        <div class="section-title">基本配置</div>
        <div class="config-block">
          <div class="field">
            <label class="field-label">Base URL</label>
            <input class="field-input" v-model="baseUrl" :placeholder="langData.modelConfig_baseUrl" />
          </div>
          <div class="field">
            <label class="field-label">API Key</label>
            <div class="input-row">
              <input
                class="field-input" type="password"
                :value="apiKeyDisplayValue()"
                :style="{ color: hasExistingKey && apiKeyShown === '••••••••' ? 'var(--el-text-color-placeholder)' : 'var(--el-text-color-primary)' }"
                @input="apiKey = ($event.target as HTMLInputElement).value; onApiKeyInput()"
                @focus="onApiKeyFocus()"
                @blur="onApiKeyBlur()"
                :placeholder="hasExistingKey ? '' : 'sk-xxxxxxxx'"
              />
              <button v-if="hasExistingKey" class="fetch-btn" @click="clearApiKey()" title="清除已保存的 Key">
                <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6 6 18M6 6l12 12"/></svg>
              </button>
            </div>
            <span v-if="hasExistingKey" class="key-hint">{{ langData.modelConfig_keySet }}</span>
          </div>
          <div class="field">
            <label class="field-label">{{ langData.modelConfig_modelName }}</label>
            <div class="input-row">
              <input class="field-input" type="text" id="modelNameInput" v-model="name" :placeholder="langData.modelConfig_modelName" />
              <select id="modelSelect" class="fetch-select" style="display:none;" @change="onModelSelect(($event.target as HTMLSelectElement).value)">
                <option value="">-- 选择模型 --</option>
              </select>
              <button class="fetch-btn" @click="fetchModels()" :title="langData.modelConfig_fetchModels">
                <Icon v-if="!fetching" icon="lucide:search" />
                <Icon v-else icon="lucide:loader" class="spin-icon" />
              </button>
            </div>
          </div>
          <div class="field">
            <label class="field-label">{{ langData.modelConfig_maxCtx }}</label>
            <div class="input-row">
              <input class="field-input" v-model="maxCtx" :placeholder="langData.modelConfig_maxCtx" />
              <button class="fetch-btn" :class="{ detecting }" @click="detectCtx" :disabled="detecting" :title="langData.modelConfig_detectCtx">
                <Icon v-if="!detecting" icon="lucide:scan-search" />
                <Icon v-else icon="lucide:loader" class="spin-icon" />
              </button>
              <span v-if="detectMsg" class="detect-msg" :class="{ fail: detectMsg === langData.modelConfig_detectFail }">{{ detectMsg }}</span>
            </div>
          </div>
        </div>

        <!-- ====== 模型参数 ====== -->
        <div class="section-title">{{ langData.modelConfig_params }}</div>
        <div class="config-block">
          <div v-for="(p, i) in params" :key="p.key + i" class="param-row">
            <div class="param-name">
              <span :class="{ 'is-custom': !p.isPreset }">{{ p.key }}</span>
              <span v-if="p.tip" class="tip-icon" :title="p.tip">?</span>
            </div>
            <input class="field-input param-value" v-model="p.value" spellcheck="false" />
            <button class="param-delete" @click="removeParam(i)" title="删除">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6 6 18M6 6l12 12"/></svg>
            </button>
          </div>

          <div v-if="showAddParam" class="add-param-row">
            <input class="field-input param-key" v-model="newParamKey" :placeholder="langData.modelConfig_paramName" />
            <input class="field-input param-val" v-model="newParamVal" :placeholder="langData.modelConfig_paramValue" />
            <button class="add-param-confirm" @click="addCustomParam()">确认</button>
            <button class="param-delete" @click="showAddParam = false; newParamKey = ''; newParamVal = ''">
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M18 6 6 18M6 6l12 12"/></svg>
            </button>
          </div>

          <button v-if="!showAddParam" class="add-param-btn" @click="showAddParam = true">
            <Icon icon="lucide:plus" />
            {{ langData.modelConfig_addParam }}
          </button>
        </div>

        <!-- 操作按钮 -->
        <div class="form-actions">
          <button class="btn btn-sec" @click="cancelForm">{{ langData.btnCancel }}</button>
          <button class="btn btn-pri" @click="add">{{ editId ? langData.btnSave : langData.btnAdd }}</button>
        </div>

      </div>
      <button v-else class="add-btn" @click="showForm = true; initParams()"><Icon icon="lucide:plus" />{{ langData.modelConfig_addModel }}</button>
    </div>
  </div>
</template>

<style scoped>
.settings-page { max-width: 680px; margin: 0 auto; padding: 24px; }
.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 24px; }
.back-btn { width: 32px; height: 32px; border-radius: 50%; border: 1px solid var(--el-border-color); background: var(--el-bg-color); cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--el-text-color-secondary); }
.back-btn:hover { background: var(--el-fill-color-light); }
h2 { font-family: Georgia, serif; font-weight: 400; font-size: 22px; letter-spacing: -0.3px; color: var(--el-text-color-primary); margin: 0; }

.setting-row { display: flex; align-items: center; justify-content: space-between; padding: 12px; border-bottom: 1px solid var(--el-border-color); }
.row-left { display: flex; align-items: center; gap: 10px; }
.row-icon { color: var(--el-text-color-secondary); font-size: 16px; }
.row-title { font-size: 14px; color: var(--el-text-color-primary); }
.row-sub { font-size: 12px; color: var(--el-text-color-placeholder); margin-top: 2px; }
.ctx-info { color: var(--el-color-success); font-weight: 500; }
.row-right { display: flex; align-items: center; gap: 6px; }
.action-icon { width: 28px; height: 28px; border-radius: 50%; border: none; background: transparent; color: var(--el-text-color-placeholder); cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 14px; transition: all 0.15s; }
.action-icon:hover { background: var(--el-fill-color-light); color: var(--el-text-color-primary); }
.action-icon.danger:hover { background: rgba(198,69,69,0.08); color: var(--el-color-danger); }
.set-btn { padding: 4px 12px; border-radius: 6px; border: 1px solid var(--el-color-primary); background: transparent; color: var(--el-color-primary); cursor: pointer; font-size: 12px; font-family: inherit; }
.set-btn:hover { background: rgba(204,120,92,0.08); }
.current-badge { font-size: 12px; padding: 3px 10px; border-radius: 99px; background: rgba(204,120,92,0.12); color: var(--el-color-primary); }
.add-btn { display: flex; align-items: center; gap: 6px; margin-top: 16px; padding: 8px 14px; border-radius: 8px; border: 1px dashed var(--el-border-color); background: transparent; cursor: pointer; font-size: 13px; color: var(--el-color-primary); font-family: inherit; width: 100%; justify-content: center; }
.add-btn:hover { background: var(--el-fill-color-light); }

/* 表单区块 */
.form-area { margin-top: 16px; }
.section-title { font-family: Georgia, serif; font-size: 18px; font-weight: 400; letter-spacing: -0.3px; color: var(--el-text-color-primary); margin: 20px 0 12px; }
.section-title:first-child { margin-top: 0; }
.config-block { background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 12px; padding: 20px; display: flex; flex-direction: column; gap: 16px; }
.field { display: flex; flex-direction: column; gap: 6px; }
.field-label { font-size: 13px; font-weight: 500; color: var(--el-text-color-secondary); text-transform: uppercase; letter-spacing: 0.5px; }
.field-input { height: 40px; padding: 0 12px; border: 1px solid var(--el-border-color); border-radius: 8px; background: var(--el-bg-color); color: var(--el-text-color-primary); font-family: inherit; font-size: 14px; outline: none; transition: border-color 0.15s; }
.field-input:focus { border-color: var(--el-color-primary); }
.input-row { display: flex; gap: 8px; align-items: center; }
.input-row .field-input { flex: 1; }
.fetch-btn { width: 36px; height: 36px; border-radius: 50%; border: 1px solid var(--el-border-color); background: var(--el-bg-color); color: var(--el-text-color-secondary); cursor: pointer; display: flex; align-items: center; justify-content: center; flex-shrink: 0; font-size: 16px; transition: all 0.15s; }
.fetch-btn:hover { color: var(--el-color-primary); border-color: var(--el-color-primary); }
.fetch-btn.fetching, .fetch-btn.detecting { color: var(--el-color-primary); pointer-events: none; }
.detect-msg { font-size: 11px; white-space: nowrap; color: var(--el-color-success); }
.detect-msg.fail { color: var(--el-color-danger); }
.fetch-select { flex: 1; height: 40px; padding: 0 12px; border: 1px solid var(--el-color-primary); border-radius: 8px; background: var(--el-bg-color); color: var(--el-text-color-primary); font-family: inherit; font-size: 14px; outline: none; appearance: none; padding-right: 36px; cursor: pointer; }
.key-hint { font-size: 12px; color: var(--el-color-success); }

/* 模型参数 */
.param-row { display: flex; align-items: center; gap: 10px; padding: 6px 0; }
.param-row:not(:last-child) { border-bottom: 1px solid var(--el-border-color-lighter); }
.param-name { width: 150px; flex-shrink: 0; display: flex; align-items: center; gap: 6px; }
.param-name span { font-size: 13px; font-weight: 500; color: var(--el-text-color-primary); font-family: "JetBrains Mono", monospace; }
.param-name .is-custom { color: var(--el-text-color-secondary); font-style: italic; }
.tip-icon { width: 16px; height: 16px; border-radius: 50%; background: var(--el-fill-color-light); color: var(--el-text-color-placeholder); cursor: help; display: inline-flex; align-items: center; justify-content: center; font-size: 9px; font-weight: 700; font-family: Georgia, serif; flex-shrink: 0; }
.param-value { height: 32px !important; font-size: 13px !important; font-family: "JetBrains Mono", monospace !important; flex: 1; }
.param-delete { width: 28px; height: 28px; border-radius: 50%; border: none; background: transparent; color: var(--el-text-color-placeholder); cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 16px; flex-shrink: 0; transition: all 0.15s; }
.param-delete:hover { background: rgba(198,69,69,0.08); color: var(--el-color-danger); }
.add-param-btn { display: flex; align-items: center; gap: 4px; padding: 6px 12px; border-radius: 6px; border: 1px dashed var(--el-border-color); background: transparent; cursor: pointer; font-size: 13px; color: var(--el-color-primary); font-family: inherit; transition: all 0.15s; margin-top: 4px; }
.add-param-btn:hover { background: rgba(204,120,92,0.06); border-color: var(--el-color-primary); }
.add-param-row { display: flex; gap: 10px; align-items: center; padding: 6px 0; border-top: 1px dashed var(--el-border-color); }
.add-param-row .param-key { width: 150px; flex-shrink: 0; height: 32px; font-size: 13px; }
.add-param-row .param-val { flex: 1; height: 32px; font-size: 13px; }
.add-param-confirm { padding: 4px 12px; border-radius: 6px; border: 1px solid var(--el-color-primary); background: var(--el-color-primary); color: #fff; font-size: 12px; font-family: inherit; cursor: pointer; flex-shrink: 0; white-space: nowrap; }

/* 底栏 */
.form-actions { display: flex; gap: 8px; justify-content: flex-end; margin-top: 16px; }
.btn { padding: 6px 16px; border-radius: 8px; border: none; cursor: pointer; font-size: 13px; font-family: inherit; font-weight: 500; }
.btn-sec { background: var(--el-fill-color); color: var(--el-text-color-primary); }
.btn-pri { background: var(--el-color-primary); color: #fff; }

.spin-icon { animation: spin 1s linear infinite; }
@keyframes spin { 0% { transform: rotate(0deg); } 100% { transform: rotate(360deg); } }
</style>
```

## Task 10: 端到端验证

- [ ] **Step 1: 构建后端**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean package -DskipTests
cp -rf src/main/resources/*.yml target/classes/
cp -rf src/main/resources/*.xml target/classes/
cp -rf src/main/resources/*.sql target/classes/
```

- [ ] **Step 2: 构建前端**

```bash
cd src/main/resources/static
npm run build
mkdir -p ../../../target/classes/static && cp -rf zephyr-ui ../../../target/classes/static/
```

- [ ] **Step 3: 启动后端**

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=me
```

- [ ] **Step 4: curl 测试 fetch-models 接口**

```bash
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" -X POST \
  "http://localhost:30733/zephyr/zephyr-ui/model-config/fetch-models" \
  -d '{"baseUrl":"https://api.openai.com","apiKey":"sk-test"}'
```
预期：返回模型列表或空列表

- [ ] **Step 5: curl 测试 create 含 params**

```bash
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" -X POST \
  "http://localhost:30733/zephyr/zephyr-ui/model-config/create" \
  -d '{"name":"test-model","baseUrl":"https://api.test.com","apiKey":"sk-123","params":"{\"temperature\":0.5}"}'
```

- [ ] **Step 6: curl 测试 list 确认 params 回显**

```bash
curl -u admin:1 -H "X-SM-Test: 1" \
  "http://localhost:30733/zephyr/zephyr-ui/model-config/list"
```
预期：返回数据中包含 `"params":"{\"temperature\":0.5}"`

- [ ] **Step 7: curl 测试 update 留空 apiKey 不丢失**

```bash
curl -u admin:1 -H "X-SM-Test: 1" -H "Content-Type: application/json" -X POST \
  "http://localhost:30733/zephyr/zephyr-ui/model-config/update" \
  -d '{"id":"<模型ID>","name":"test-model","baseUrl":"https://api.test.com","apiKey":"","params":"{}"}'
```
再执行 list，确认 apiKeyEncrypted 不是 null

- [ ] **Step 8: 浏览器打开验证**

```bash
open http://localhost:30733/zephyr/zephyr-ui/index.html
```
验证：模型配置页面，新建/编辑表单布局正确，拉取按钮交互正常，API Key 掩码显示正常，参数增删正常。

## Task 11: 清理 mock 文件

**文件：**
- 删除: `mock-model-config.html`

- [ ] **Step 1: 删除 mock 文件**

```bash
rm /Users/hbq/Codes/me/github/zephyr/mock-model-config.html
```
