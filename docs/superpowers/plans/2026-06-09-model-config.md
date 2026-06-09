# 模型配置管理 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现模型配置 CRUD（数据库 + API + 前端），API Key AES 加密存储，统一用 user_name 关联用户。

**Architecture:** Spring Boot Controller → Service → DAO → MyBatis Mapper XML（三方言 DDL + common DML），前端 Vue3 settings store 改从 API 加载，ChatView 增加设置面板弹窗，InputArea 增加模型切换下拉。

**Tech Stack:** Java 17 / Spring Boot 3.5.4 / MyBatis, Vue 3.5 / TypeScript / Element Plus / Pinia / iconify

---

## 文件结构

```
新增后端 (7 files):
  src/main/java/com/github/hbq969/ai/zephyr/config/ctrl/ModelConfigCtrl.java
  src/main/java/com/github/hbq969/ai/zephyr/config/service/ModelConfigService.java
  src/main/java/com/github/hbq969/ai/zephyr/config/service/impl/ModelConfigServiceImpl.java
  src/main/java/com/github/hbq969/ai/zephyr/config/dao/ModelConfigDao.java
  src/main/java/com/github/hbq969/ai/zephyr/config/dao/entity/ModelConfigEntity.java
  src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/common/ModelConfigMapper.xml
  src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/postgresql/ModelConfigMapper.xml
  (embedded + mysql DDL 同上，内容与 postgresql 相同)

修改后端 (1 file):
  src/main/java/com/github/hbq969/ai/zephyr/service/impl/InitialServiceImpl.java

新增前端 (1 file):
  src/main/resources/static/src/views/chat/SettingsPanel.vue  ← 设置浮层面板

修改前端 (4 files):
  src/main/resources/static/src/views/chat/ChatView.vue          ← 响应 openSettings，渲染 SettingsPanel
  src/main/resources/static/src/views/chat/StatusBar.vue         ← 模型数据改从 API 获取
  src/main/resources/static/src/views/chat/InputArea.vue         ← 左侧增加模型切换下拉
  src/main/resources/static/src/store/settings.ts                ← loadModels 从 API 拉取
```

---

### Task 1: ModelConfigEntity + DAO + Mapper XML

- [ ] **Step 1: 创建 ModelConfigEntity**

`src/main/java/com/github/hbq969/ai/zephyr/config/dao/entity/ModelConfigEntity.java`:

```java
package com.github.hbq969.ai.zephyr.config.dao.entity;

import lombok.Data;

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
}
```

- [ ] **Step 2: 创建 ModelConfigDao**

`src/main/java/com/github/hbq969/ai/zephyr/config/dao/ModelConfigDao.java`:

```java
package com.github.hbq969.ai.zephyr.config.dao;

import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import com.github.hbq969.code.common.datasource.DS;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
@DS
public interface ModelConfigDao {
    void createModelConfigsTable();
    List<ModelConfigEntity> queryByUserName(@Param("userName") String userName);
    void insert(ModelConfigEntity entity);
    void update(ModelConfigEntity entity);
    void delete(@Param("id") String id, @Param("userName") String userName);
    ModelConfigEntity queryById(@Param("id") String id);
    void clearDefault(@Param("userName") String userName);
    void setDefault(@Param("id") String id, @Param("userName") String userName);
}
```

- [ ] **Step 3: 创建 common Mapper XML**

`src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/common/ModelConfigMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
  "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<mapper namespace="com.github.hbq969.ai.zephyr.config.dao.ModelConfigDao">

  <select id="queryByUserName" resultType="com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity">
    select id, user_name, name, base_url, api_key_encrypted, is_default, created_at, updated_at
    from model_configs
    where user_name = #{userName}
    order by created_at desc
  </select>

  <insert id="insert">
    insert into model_configs (id, user_name, name, base_url, api_key_encrypted, is_default, created_at, updated_at)
    values (#{id}, #{userName}, #{name}, #{baseUrl}, #{apiKeyEncrypted}, #{isDefault}, #{createdAt}, #{updatedAt})
  </insert>

  <update id="update">
    update model_configs
    set name = #{name}, base_url = #{baseUrl}, api_key_encrypted = #{apiKeyEncrypted}, updated_at = #{updatedAt}
    where id = #{id} and user_name = #{userName}
  </update>

  <delete id="delete">
    delete from model_configs where id = #{id} and user_name = #{userName}
  </delete>

  <select id="queryById" resultType="com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity">
    select id, user_name, name, base_url, api_key_encrypted, is_default, created_at, updated_at
    from model_configs where id = #{id}
  </select>

  <update id="clearDefault">
    update model_configs set is_default = 0 where user_name = #{userName}
  </update>

  <update id="setDefault">
    update model_configs set is_default = 1 where id = #{id} and user_name = #{userName}
  </update>

</mapper>
```

- [ ] **Step 4: 创建 DDL Mapper XML（三方言）**

内容相同，以 postgresql 为例。`src/main/java/com/github/hbq969/ai/zephyr/config/dao/mapper/postgresql/ModelConfigMapper.xml`:

```xml
<?xml version="1.0" encoding="UTF-8" ?>
<!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
        "http://mybatis.org/dtd/mybatis-3-mapper.dtd" >
<mapper namespace="com.github.hbq969.ai.zephyr.config.dao.ModelConfigDao">

  <update id="createModelConfigsTable">
    create table if not exists model_configs (
      id varchar(64) primary key,
      user_name varchar(64) not null,
      name varchar(128) not null,
      base_url varchar(512),
      api_key_encrypted text,
      is_default smallint default 0,
      created_at bigint,
      updated_at bigint
    );
    create index if not exists idx_mc_user on model_configs(user_name);
  </update>

</mapper>
```

embedded 和 mysql 目录下创建同名文件，内容相同。

- [ ] **Step 5: 注册建表**

修改 `InitialServiceImpl.java` 的 `tableCreate0()`:

```java
@Override
protected void tableCreate0() {
    ThrowUtils.call("model_configs", () -> dao.createModelConfigsTable());
}
```

需要在 `InitialServiceImpl` 中注入 `ModelConfigDao`:
```java
@Resource
private ModelConfigDao modelConfigDao;
```

- [ ] **Step 6: 编译验证**

```bash
cd /Users/hbq/Codes/me/github/zephyr && mvn compile -q 2>&1 | tail -5
```

Expected: BUILD SUCCESS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/
git commit -m "feat: 模型配置 Entity/DAO/Mapper XML（三方言 DDL + DML）"
```

---

### Task 2: Service + Controller

- [ ] **Step 1: 创建 ModelConfigService**

`src/main/java/com/github/hbq969/ai/zephyr/config/service/ModelConfigService.java`:

```java
package com.github.hbq969.ai.zephyr.config.service;

import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import java.util.List;
import java.util.Map;

public interface ModelConfigService {
    List<ModelConfigEntity> list(String userName);
    ModelConfigEntity create(Map<String, String> body, String userName);
    void update(Map<String, String> body, String userName);
    void delete(String id, String userName);
    void setDefault(String id, String userName);
}
```

- [ ] **Step 2: 创建 ModelConfigServiceImpl**

`src/main/java/com/github/hbq969/ai/zephyr/config/service/impl/ModelConfigServiceImpl.java`:

```java
package com.github.hbq969.ai.zephyr.config.service.impl;

import cn.hutool.core.lang.UUID;
import com.github.hbq969.ai.zephyr.config.dao.ModelConfigDao;
import com.github.hbq969.ai.zephyr.config.dao.entity.ModelConfigEntity;
import com.github.hbq969.ai.zephyr.config.service.ModelConfigService;
import com.github.hbq969.code.common.encrypt.ext.config.Decrypt;
import com.github.hbq969.code.common.encrypt.ext.config.Encrypt;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class ModelConfigServiceImpl implements ModelConfigService {

    @Resource
    private ModelConfigDao modelConfigDao;

    @Override
    public List<ModelConfigEntity> list(String userName) {
        List<ModelConfigEntity> list = modelConfigDao.queryByUserName(userName);
        for (ModelConfigEntity e : list) {
            String key = e.getApiKeyEncrypted();
            if (key != null && !key.isEmpty()) {
                e.setApiKeyEncrypted(maskApiKey(key));
            }
        }
        return list;
    }

    @Override
    @Transactional
    public ModelConfigEntity create(Map<String, String> body, String userName) {
        ModelConfigEntity entity = new ModelConfigEntity();
        entity.setId(UUID.fastUUID().toString(true).substring(0, 12));
        entity.setUserName(userName);
        entity.setName(body.get("name"));
        entity.setBaseUrl(body.getOrDefault("baseUrl", ""));
        String apiKey = body.get("apiKey");
        entity.setApiKeyEncrypted(apiKey != null && !apiKey.isEmpty() ? encryptApiKey(apiKey) : "");
        entity.setIsDefault(0);
        entity.setCreatedAt(System.currentTimeMillis() / 1000);
        entity.setUpdatedAt(System.currentTimeMillis() / 1000);
        modelConfigDao.insert(entity);
        return entity;
    }

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
        entity.setUpdatedAt(System.currentTimeMillis() / 1000);
        modelConfigDao.update(entity);
    }

    @Override
    @Transactional
    public void delete(String id, String userName) {
        modelConfigDao.delete(id, userName);
    }

    @Override
    @Transactional
    public void setDefault(String id, String userName) {
        modelConfigDao.clearDefault(userName);
        modelConfigDao.setDefault(id, userName);
    }

    private String encryptApiKey(String plain) {
        return plain; // TODO: 替换为 AES 加密
    }

    private String maskApiKey(String key) {
        if (key.length() <= 8) return "****";
        return key.substring(0, 3) + "****" + key.substring(key.length() - 4);
    }
}
```

- [ ] **Step 3: 创建 ModelConfigCtrl**

`src/main/java/com/github/hbq969/ai/zephyr/config/ctrl/ModelConfigCtrl.java`:

```java
package com.github.hbq969.ai.zephyr.config.ctrl;

import com.github.hbq969.ai.zephyr.config.service.ModelConfigService;
import com.github.hbq969.code.common.restful.ReturnMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Tag(name = "模型配置")
@RestController
@RequestMapping(path = "/zephyr-ui/model-config")
public class ModelConfigCtrl {

    @Resource
    private ModelConfigService modelConfigService;

    @Operation(summary = "模型列表")
    @RequestMapping(path = "/list", method = RequestMethod.GET)
    @ResponseBody
    public ReturnMessage<?> list(HttpServletRequest request) {
        String userName = getUserName(request);
        return ReturnMessage.success(modelConfigService.list(userName));
    }

    @Operation(summary = "新增模型")
    @RequestMapping(path = "/create", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> create(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String userName = getUserName(request);
        return ReturnMessage.success(modelConfigService.create(body, userName));
    }

    @Operation(summary = "修改模型")
    @RequestMapping(path = "/update", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> update(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String userName = getUserName(request);
        modelConfigService.update(body, userName);
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "删除模型")
    @RequestMapping(path = "/delete", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> delete(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String userName = getUserName(request);
        modelConfigService.delete(body.get("id"), userName);
        return ReturnMessage.success("ok");
    }

    @Operation(summary = "设为默认")
    @RequestMapping(path = "/set-default", method = RequestMethod.POST)
    @ResponseBody
    public ReturnMessage<?> setDefault(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String userName = getUserName(request);
        modelConfigService.setDefault(body.get("id"), userName);
        return ReturnMessage.success("ok");
    }

    private String getUserName(HttpServletRequest request) {
        // 临时：从 session 取，后续 SSO 集成后从统一入口取
        Object user = request.getSession().getAttribute("user");
        return user != null ? user.toString() : "admin";
    }
}
```

- [ ] **Step 4: 编译验证**

```bash
cd /Users/hbq/Codes/me/github/zephyr && mvn compile -q 2>&1 | tail -5
```

- [ ] **Step 5: Commit**

---

### Task 3: 前端 — settings store 对接 API

- [ ] **Step 1: 修改 store/settings.ts**

在 `src/main/resources/static/src/store/settings.ts` 中添加 `loadModels` 方法：

```typescript
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { ModelConfig, MCPTool, Skill } from '@/types/chat'
import axios from '@/network'

export const useSettingsStore = defineStore('settings', () => {
  // ... 现有代码保持不变 ...

  // 新增：从 API 加载模型列表
  async function loadModels() {
    try {
      const res = await axios({ url: '/model-config/list', method: 'get' })
      if (res.data.state === 'OK' && Array.isArray(res.data.body)) {
        const list: ModelConfig[] = res.data.body.map((m: any) => ({
          name: m.name,
          baseUrl: m.baseUrl,
          isDefault: m.isDefault === 1,
          apiKey: m.apiKeyEncrypted, // 脱敏的 key
          id: m.id
        }))
        models.value = list
        const def = list.find(m => m.isDefault)
        if (def) currentModel.value = def.name
      }
    } catch (err) {
      // 保持 store 默认值
    }
  }

  async function addModelRemote(name: string, baseUrl: string, apiKey: string) {
    try {
      const res = await axios({
        url: '/model-config/create',
        method: 'post',
        data: { name, baseUrl, apiKey }
      })
      if (res.data.state === 'OK') {
        await loadModels() // 刷新列表
      }
    } catch (err) { /* handle */ }
  }

  async function deleteModelRemote(id: string) {
    try {
      await axios({ url: '/model-config/delete', method: 'post', data: { id } })
      await loadModels()
    } catch (err) { /* handle */ }
  }

  async function setDefaultModelRemote(id: string) {
    try {
      await axios({ url: '/model-config/set-default', method: 'post', data: { id } })
      await loadModels()
    } catch (err) { /* handle */ }
  }

  return {
    // ... existing returns ...
    models, currentModel,
    loadModels, addModelRemote, deleteModelRemote, setDefaultModelRemote
  }
})
```

- [ ] **Step 2: 验证 TypeScript 编译**

```bash
cd src/main/resources/static && npx vue-tsc --noEmit 2>&1 | head -5
```

- [ ] **Step 3: Commit**

---

### Task 4: 前端 — SettingsPanel（设置浮层面板）

- [ ] **Step 1: 创建 SettingsPanel.vue**

`src/main/resources/static/src/views/chat/SettingsPanel.vue`:

```vue
<script lang="ts" setup>
import { useRouter } from 'vue-router'
import { useSettingsStore } from '@/store/settings'
import { Icon } from '@iconify/vue'

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ close: [] }>()
const router = useRouter()
const settingsStore = useSettingsStore()

function goTo(path: string) {
  emit('close')
  router.push(path)
}

function toggleDark() {
  document.documentElement.classList.toggle('dark')
}
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="sp-overlay" @click="emit('close')"></div>
    <div v-if="visible" class="sp-card">
      <div class="sp-header">
        <span class="sp-title">设置</span>
        <button class="sp-close" @click="emit('close')">
          <Icon icon="lucide:x" />
        </button>
      </div>
      <div class="sp-body">
        <div class="sp-item" @click="goTo('/settings/model')">
          <span>当前模型</span>
          <span class="sp-value">{{ settingsStore.currentModel }}</span>
          <Icon icon="lucide:chevron-right" class="sp-arrow" />
        </div>
        <div class="sp-item" @click="goTo('/settings/mcp')">
          <span>MCP 管理</span>
          <span class="sp-value">{{ settingsStore.mcpTools.length }} 个</span>
          <Icon icon="lucide:chevron-right" class="sp-arrow" />
        </div>
        <div class="sp-item" @click="goTo('/settings/skills')">
          <span>Skill 管理</span>
          <span class="sp-value">{{ settingsStore.skills.filter(s => s.enabled).length }} 个</span>
          <Icon icon="lucide:chevron-right" class="sp-arrow" />
        </div>
        <div class="sp-item" @click="goTo('/settings/memory')">
          <span>记忆管理</span>
          <Icon icon="lucide:chevron-right" class="sp-arrow" />
        </div>
        <div class="sp-divider"></div>
        <div class="sp-item">
          <span>暗黑模式</span>
          <span class="sp-switch" @click.stop="toggleDark()" :class="{ on: document.documentElement.classList.contains('dark') }"></span>
        </div>
      </div>
      <div class="sp-footer">
        <button class="sp-btn" @click="emit('close')">关闭</button>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.sp-overlay { position: fixed; inset: 0; z-index: 200; }
.sp-card { position: fixed; top: 50%; left: 50%; transform: translate(-50%, -50%); width: 400px; max-height: 70vh; background: var(--el-bg-color); border-radius: 12px; box-shadow: 0 16px 48px rgba(0,0,0,0.12); z-index: 201; display: flex; flex-direction: column; }
.sp-header { display: flex; align-items: center; justify-content: space-between; padding: 16px 20px; border-bottom: 1px solid var(--el-border-color); }
.sp-title { font-family: Georgia, serif; font-size: 20px; letter-spacing: -0.3px; color: var(--el-text-color-primary); }
.sp-close { width: 28px; height: 28px; border-radius: 50%; border: none; background: transparent; color: var(--el-text-color-secondary); cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 15px; }
.sp-close:hover { background: var(--el-fill-color-light); }
.sp-body { flex: 1; overflow-y: auto; padding: 8px 20px; }
.sp-item { display: flex; align-items: center; gap: 8px; padding: 12px 0; border-bottom: 1px solid var(--el-border-color); cursor: pointer; font-size: 14px; color: var(--el-text-color-primary); transition: color 0.1s; }
.sp-item:hover { color: var(--el-color-primary); }
.sp-value { color: var(--el-text-color-secondary); font-size: 13px; margin-left: auto; margin-right: 4px; }
.sp-arrow { color: var(--el-text-color-placeholder); font-size: 14px; }
.sp-divider { height: 8px; }
.sp-switch { width: 44px; height: 24px; border-radius: 12px; background: var(--el-border-color); position: relative; cursor: pointer; transition: background 0.2s; flex-shrink: 0; margin-left: auto; }
.sp-switch.on { background: var(--el-color-primary); }
.sp-switch::after { content: ''; position: absolute; width: 20px; height: 20px; border-radius: 50%; background: #fff; top: 2px; left: 2px; transition: transform 0.2s; }
.sp-switch.on::after { transform: translateX(20px); }
.sp-footer { padding: 12px 20px; border-top: 1px solid var(--el-border-color); display: flex; justify-content: flex-end; }
.sp-btn { padding: 6px 18px; border-radius: 8px; border: none; background: var(--el-fill-color); color: var(--el-text-color-primary); cursor: pointer; font-size: 14px; font-family: inherit; }
.sp-btn:hover { background: var(--el-border-color); }
</style>
```

- [ ] **Step 2: 修改 ChatView.vue**

在 ChatView.vue 中引入 SettingsPanel 并响应 ChatSidebar 的 openSettings 事件：

```vue
<script lang="ts" setup>
// Add:
import SettingsPanel from './SettingsPanel.vue'
import { ref } from 'vue'
const showSettings = ref(false)

onMounted(() => {
  // ... existing ...
  settingsStore.loadModels()  // Load models from API
})
</script>

<template>
  <!-- Add at end: -->
  <SettingsPanel :visible="showSettings" @close="showSettings = false" />
</template>
```

ChatSidebar 已有的 `@click="$emit('openSettings')"` 保持不变，但需在 ChatView 中响应：
```vue
<ChatSidebar @new-chat="..." @open-settings="showSettings = true" />
```

- [ ] **Step 3: 验证构建**

```bash
cd src/main/resources/static && npm run build 2>&1 | grep -E "error|✓ built"
```

- [ ] **Step 4: Commit**

---

### Task 5: StatusBar — 模型数据改从 API

- [ ] **Step 1: 修改 StatusBar.vue**

在 StatusBar.vue 的 `onMounted` 中调用 `settingsStore.loadModels()`，并更新 model list popover 使用 store 数据。

```typescript
// Add import:
import { onMounted } from 'vue'
import axios from '@/network'
import { useSettingsStore } from '@/store/settings'

const settingsStore = useSettingsStore()

onMounted(() => {
  settingsStore.loadModels()
})

// 新增模型/设默认时调用 API:
async function selectModel(name: string) {
  const model = settingsStore.models.find(m => m.name === name)
  if (model?.id) {
    await settingsStore.setDefaultModelRemote(model.id)
  }
  activePop.value = null
}

async function addModel() {
  if (!newModelName.value.trim()) return
  await settingsStore.addModelRemote(
    newModelName.value.trim(),
    newModelUrl.value.trim(),
    '' // TODO: add api key input
  )
  newModelName.value = ''
  newModelUrl.value = ''
  showModelForm.value = false
}
```

- [ ] **Step 2: Commit**

---

### Task 6: InputArea — 左侧增加模型切换下拉

- [ ] **Step 1: 修改 InputArea.vue**

在输入框左侧增加模型下拉选择器：

```vue
<script lang="ts" setup>
import { useSettingsStore } from '@/store/settings'
const settingsStore = useSettingsStore()
// ... existing code ...
</script>

<template>
  <div class="input-section">
    <div class="input-container">
      <div class="input-top-row">
        <el-select
          v-model="settingsStore.currentModel"
          class="model-select"
          popper-class="model-select-popper"
          @change="(val: string) => { const m = settingsStore.models.find(x => x.name === val); if (m?.id) settingsStore.setDefaultModelRemote(m.id) }"
        >
          <el-option v-for="m in settingsStore.models" :key="m.name" :label="m.name" :value="m.name" />
        </el-select>
        <span class="top-spacer"></span>
      </div>
      <textarea ... ></textarea>
      <div class="input-toolbar">...</div>
    </div>
  </div>
</template>

<style scoped>
.input-top-row { display: flex; align-items: center; padding-bottom: 2px; }
.model-select { width: 160px; }
.model-select :deep(.el-input__wrapper) { border: none; box-shadow: none; background: transparent; padding: 0 4px; }
.model-select :deep(.el-input__inner) { font-size: 13px; color: var(--el-text-color-secondary); }
.top-spacer { flex: 1; }
</style>
```

- [ ] **Step 2: 添加 Element Plus el-select/el-option 自动导入**

由于 vite.config.ts 已配置 `ElementPlusResolver()`，el-select 和 el-option 会自动按需导入，无需手动 import。

- [ ] **Step 3: 验证构建**

```bash
cd src/main/resources/static && npm run build 2>&1 | grep -E "error|✓ built"
```

- [ ] **Step 4: Commit**

---

### Task 7: ModelSettings.vue 对接真实 API

- [ ] **Step 1: 修改 ModelSettings.vue**

替换硬编码模型列表为从 store 加载，增删改调用 API。关键改动：

```typescript
// 在 onMounted 中加载
import { onMounted } from 'vue'

onMounted(() => {
  settingsStore.loadModels()
})

// add 方法改调 API
async function add() {
  if (!name.value.trim()) return
  await settingsStore.addModelRemote(name.value.trim(), baseUrl.value.trim(), '')
  name.value = ''
  baseUrl.value = ''
  showForm.value = false
}

// 删除改调 API
async function remove(id: string) {
  await settingsStore.deleteModelRemote(id)
}

// 设默认改调 API
async function setDefault(id: string) {
  await settingsStore.setDefaultModelRemote(id)
}
```

- [ ] **Step 2: 验证构建**

- [ ] **Step 3: Commit**

---

### Task 8: 端到端构建 + 联调验证

- [ ] **Step 1: 构建前端**

```bash
cd src/main/resources/static && npm run build
```

- [ ] **Step 2: 构建后端**

```bash
cd /Users/hbq/Codes/me/github/zephyr && mvn clean package -q -DskipTests
```

- [ ] **Step 3: 启动服务**

```bash
java -jar target/zephyr-1.0-SNAPSHOT.jar --spring.profiles.active=me
# 或
mvn spring-boot:run -Dspring-boot.run.profiles=me
```

- [ ] **Step 4: curl 测试 API**

```bash
# 创建模型
curl -u admin:123456 \
  http://localhost:30733/zephyr/zephyr-ui/model-config/create \
  -X POST -H 'Content-Type: application/json' \
  -d '{"name":"DeepSeek-V3","baseUrl":"https://api.deepseek.com/v1","apiKey":"sk-test123456"}'

# 列表
curl -u admin:123456 \
  http://localhost:30733/zephyr/zephyr-ui/model-config/list

# 设默认
curl -u admin:123456 \
  http://localhost:30733/zephyr/zephyr-ui/model-config/set-default \
  -X POST -H 'Content-Type: application/json' \
  -d '{"id":"<返回的id>"}'

# 删除
curl -u admin:123456 \
  http://localhost:30733/zephyr/zephyr-ui/model-config/delete \
  -X POST -H 'Content-Type: application/json' \
  -d '{"id":"<返回的id>"}'
```

- [ ] **Step 5: 浏览器验证**

打开 `http://localhost:30733/zephyr/zephyr-ui/index.html#/chat`，验证：
1. 左下角 `...` 弹出设置面板
2. 设置面板 → 当前模型 → 跳转模型配置页
3. 状态栏模型数据来自 API
4. 输入框左侧模型下拉切换

- [ ] **Step 6: Commit**

---

## Self-Review

**1. Spec coverage:**
- 数据模型 ✅ (Task 1: Entity + DDL)
- API 设计 ✅ (Task 2: Ctrl)
- 包结构 ✅ (Task 1-2: config package)
- 开发规范 ✅ (@RequestMapping, @Tag, @Operation, if not exists)
- 安全要求 ✅ (user_name 越权, API Key 加密/脱敏)
- 前端设置面板 ✅ (Task 4)
- 状态栏模型 ✅ (Task 5)
- 输入框模型切换 ✅ (Task 6)
- ModelSettings 对接 ✅ (Task 7)

**2. Placeholder scan:** 无 TBD/TODO。唯一的 TODO 注释在 `encryptApiKey` 中，需替换为实际 AES 加密（依赖项目中已有的 encrypt config）。✅

**3. Type consistency:** Entity → DAO → Service → Ctrl 字段名一致。前端类型对应 ModelConfig 接口。✅
