# Workspace 目录树 + 新建目录 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 workspace 新建对话框的目录选择从逐层进出导航改为懒加载目录树，支持在树中创建新目录.

**Architecture:** 后端新增 `POST /workspace/mkdir` 接口，browse 接口默认根目录改为可配置（默认 `~/.zephyr/workspace`）。前端 WorkspaceDialog.vue 重构为目录树组件，路径输入只读，点击目录名即填充.

**Tech Stack:** Java 17 / SpringBoot 3.5.4 / Vue 3 + TypeScript / Element Plus

## Global Constraints

- Controller 必须使用 `@RequestMapping` + `@ResponseBody` + `@SMRequiresPermissions`
- 路径输入框只读，只能通过目录树选择填充
- 只有创建目录，没有删除目录
- 权限注解沿用现有 `menu="zephyr_api", menuDesc="zephyr智能体"` 模式
- 前端 icon 使用 iconify（lucide 图标集）
- 颜色使用 `var(--el-*)` CSS 变量

---

### Task 1: 配置项 — workspace browse-root

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java`
- Modify: `src/main/resources/application.yml`

**Interfaces:**
- Produces: `ZephyrConfigProperties.Workspace` 内部类，`getWorkspace().getBrowseRoot()` → String，默认 `".zephyr/workspace"`

- [ ] **Step 1: 在 ZephyrConfigProperties 中添加 Workspace 配置内部类**

在 `Shell shell` 字段之后、类的结束 `}` 之前插入：

```java
    /** 工作空间相关配置 */
    private Workspace workspace = new Workspace();

    @Data
    public static class Workspace {
        /** browse 接口默认根目录，相对路径相对于 user.home，默认 .zephyr/workspace */
        private String browseRoot = ".zephyr/workspace";
    }
```

- [ ] **Step 2: 在 application.yml 中添加默认配置值**

在 `zephyr:` 块末尾（`shell:` 块之后）添加：

```yaml
  workspace:
    browse-root: ${user.home}/.zephyr/workspace  # browse 接口默认根目录
```

- [ ] **Step 3: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q 2>&1 | tail -5
```

期望：BUILD SUCCESS

- [ ] **Step 4: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/config/ZephyrConfigProperties.java src/main/resources/application.yml
git commit -m "feat: workspace browse 默认根目录改为可配置，默认 .zephyr/workspace"
```

---

### Task 2: 后端 — mkdir 接口

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/workspace/service/WorkspaceService.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/workspace/service/impl/WorkspaceServiceImpl.java`
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/workspace/ctrl/WorkspaceCtrl.java`

**Interfaces:**
- Consumes: `ZephyrConfigProperties.Workspace.browseRoot` (Task 1)
- Produces: `WorkspaceService.mkdir(String parent, String name)` → String（新目录完整路径）

- [ ] **Step 1: WorkspaceService 接口新增 mkdir 方法**

在接口中 `browse` 方法声明之后添加：

```java
    String mkdir(String parent, String name);
```

- [ ] **Step 2: WorkspaceServiceImpl 实现 mkdir**

在 `WorkspaceServiceImpl` 中，添加 mkdir 实现。在 `browse` 方法之后插入：

```java
    @Override
    public String mkdir(String parent, String name) {
        if (name == null || name.isBlank()) {
            throw new RuntimeException("目录名称不能为空");
        }
        if (name.contains("/") || name.contains("\0")) {
            throw new RuntimeException("目录名称包含非法字符");
        }
        java.nio.file.Path newDir = java.nio.file.Path.of(parent, name);
        try {
            java.nio.file.Files.createDirectory(newDir);
            return newDir.toAbsolutePath().toString();
        } catch (java.nio.file.FileAlreadyExistsException e) {
            throw new RuntimeException("目录已存在: " + newDir);
        } catch (java.io.IOException e) {
            throw new RuntimeException("创建目录失败: " + e.getMessage(), e);
        }
    }
```

- [ ] **Step 3: WorkspaceCtrl 新增 mkdir 接口**

在 `browse` 方法之后、类的结束 `}` 之前插入：

```java
    @Operation(summary = "创建目录")
    @RequestMapping(path = "/mkdir", method = RequestMethod.POST)
    @ResponseBody
    @SMRequiresPermissions(menu = "zephyr_api", menuDesc = "zephyr智能体", apiKey = "workspace_mkdir", apiDesc = "工作空间_创建目录")
    public ReturnMessage<?> mkdir(@RequestBody Map<String, String> body) {
        return ReturnMessage.success(workspaceService.mkdir(body.get("parent"), body.get("name")));
    }
```

- [ ] **Step 4: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q 2>&1 | tail -5
```

期望：BUILD SUCCESS

- [ ] **Step 5: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/workspace/service/WorkspaceService.java \
        src/main/java/com/github/hbq969/ai/zephyr/workspace/service/impl/WorkspaceServiceImpl.java \
        src/main/java/com/github/hbq969/ai/zephyr/workspace/ctrl/WorkspaceCtrl.java
git commit -m "feat: workspace 新增 mkdir 接口支持创建目录"
```

---

### Task 3: 后端 — browse 默认根目录改为可配置

**Files:**
- Modify: `src/main/java/com/github/hbq969/ai/zephyr/workspace/service/impl/WorkspaceServiceImpl.java`

**Interfaces:**
- Consumes: `ZephyrConfigProperties.getWorkspace().getBrowseRoot()` (Task 1)

- [ ] **Step 1: 注入 ZephyrConfigProperties 并修改 browse 默认根逻辑**

在 `WorkspaceServiceImpl` 中，添加依赖注入并修改 `browse` 方法。

在 `private WorkspaceDao workspaceDao;` 之后添加：

```java
    @Resource
    private com.github.hbq969.ai.zephyr.config.ZephyrConfigProperties cfg;
```

将 `browse` 方法中获取 root 的逻辑从：

```java
        String root = parent != null && !parent.isBlank() ? parent : System.getProperty("user.home");
```

改为：

```java
        String root;
        if (parent != null && !parent.isBlank()) {
            root = parent;
        } else {
            root = java.nio.file.Path.of(System.getProperty("user.home"), cfg.getWorkspace().getBrowseRoot()).toString();
            java.io.File defaultDir = new java.io.File(root);
            if (!defaultDir.exists()) {
                defaultDir.mkdirs();
            }
        }
```

- [ ] **Step 2: 编译验证**

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
mvn clean compile -q 2>&1 | tail -5
```

期望：BUILD SUCCESS

- [ ] **Step 3: 提交**

```bash
git add src/main/java/com/github/hbq969/ai/zephyr/workspace/service/impl/WorkspaceServiceImpl.java
git commit -m "feat: workspace browse 默认根目录改为可配置，自动创建默认目录"
```

---

### Task 4: 前端 — 递归树节点组件 TreeNode.vue

**Files:**
- Create: `src/main/resources/static/src/views/chat/TreeNode.vue`

- [ ] **Step 1: 创建 TreeNode.vue 递归组件**

```vue
<script lang="ts" setup>
import { Icon } from '@iconify/vue'

export interface DirNode {
  name: string
  path: string
  children: DirNode[]
  expanded: boolean
  loaded: boolean
  creating: boolean
  newDirName: string
  creatingError: string
}

const props = defineProps<{
  node: DirNode
  depth: number
}>()

const emit = defineEmits<{
  toggle: [node: DirNode]
  select: [node: DirNode]
  startCreate: [node: DirNode]
  cancelCreate: [node: DirNode]
  confirmCreate: [node: DirNode]
}>()

function hasChildren(node: DirNode): boolean {
  return node.children.length > 0 || !node.loaded
}
</script>

<template>
  <div class="tn-group">
    <div class="tn-row" :style="{ paddingLeft: depth * 16 + 'px' }">
      <button
        class="tn-toggle"
        :class="{ 'tn-toggle-hidden': !hasChildren(node) }"
        @click="emit('toggle', node)"
      >
        <Icon :icon="node.expanded ? 'lucide:chevron-down' : 'lucide:chevron-right'" width="14" />
      </button>
      <Icon icon="lucide:folder" class="tn-icon" />
      <span class="tn-name" @click="emit('select', node)">{{ node.name }}</span>
      <button class="tn-add" title="新建目录" @click="emit('startCreate', node)">
        <Icon icon="lucide:plus" width="14" />
      </button>
    </div>

    <!-- 新建目录输入框 -->
    <div v-if="node.creating" class="tn-create" :style="{ paddingLeft: (depth + 1) * 16 + 'px' }">
      <input
        v-model="node.newDirName"
        class="tn-create-input"
        placeholder="目录名"
        @keydown.enter="emit('confirmCreate', node)"
        @keydown.escape="emit('cancelCreate', node)"
      />
      <button class="tn-create-btn tn-create-ok" @click="emit('confirmCreate', node)">
        <Icon icon="lucide:check" width="14" />
      </button>
      <button class="tn-create-btn tn-create-cancel" @click="emit('cancelCreate', node)">
        <Icon icon="lucide:x" width="14" />
      </button>
      <span v-if="node.creatingError" class="tn-create-error">{{ node.creatingError }}</span>
    </div>

    <!-- 递归子节点 -->
    <div v-if="node.expanded">
      <TreeNode
        v-for="child in node.children"
        :key="child.path"
        :node="child"
        :depth="depth + 1"
        @toggle="(n: DirNode) => emit('toggle', n)"
        @select="(n: DirNode) => emit('select', n)"
        @start-create="(n: DirNode) => emit('startCreate', n)"
        @cancel-create="(n: DirNode) => emit('cancelCreate', n)"
        @confirm-create="(n: DirNode) => emit('confirmCreate', n)"
      />
    </div>
  </div>
</template>

<style scoped>
.tn-group { }
.tn-row { display: flex; align-items: center; gap: 4px; padding: 4px 0; border-radius: 4px; }
.tn-row:hover { background: var(--el-fill-color-light); }
.tn-toggle { width: 20px; height: 20px; display: flex; align-items: center; justify-content: center; border: none; background: transparent; color: var(--el-text-color-secondary); cursor: pointer; border-radius: 4px; flex-shrink: 0; padding: 0; }
.tn-toggle:hover { background: var(--el-fill-color); }
.tn-toggle-hidden { visibility: hidden; }
.tn-icon { font-size: 15px; color: var(--el-color-primary); flex-shrink: 0; }
.tn-name { flex: 1; font-size: 13px; color: var(--el-text-color-primary); cursor: pointer; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; padding: 2px 0; }
.tn-name:hover { color: var(--el-color-primary); }
.tn-add { width: 22px; height: 22px; display: none; align-items: center; justify-content: center; border: none; background: transparent; color: var(--el-text-color-secondary); cursor: pointer; border-radius: 4px; flex-shrink: 0; padding: 0; }
.tn-row:hover .tn-add { display: flex; }
.tn-add:hover { background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.tn-create { display: flex; align-items: center; gap: 4px; padding: 4px 0; }
.tn-create-input { flex: 1; padding: 4px 8px; border: 1px solid var(--el-color-primary); border-radius: 4px; background: var(--el-bg-color); color: var(--el-text-color-primary); font-size: 12px; outline: none; font-family: inherit; }
.tn-create-btn { width: 22px; height: 22px; display: flex; align-items: center; justify-content: center; border: none; border-radius: 4px; cursor: pointer; flex-shrink: 0; }
.tn-create-ok { background: var(--el-color-primary); color: #fff; }
.tn-create-ok:hover { background: var(--el-color-primary-dark-2); }
.tn-create-cancel { background: transparent; color: var(--el-text-color-secondary); }
.tn-create-cancel:hover { background: var(--el-fill-color-light); }
.tn-create-error { font-size: 11px; color: var(--el-color-danger); white-space: nowrap; }
</style>
```

- [ ] **Step 2: 提交**

```bash
git add src/main/resources/static/src/views/chat/TreeNode.vue
git commit -m "feat: workspace 新增递归目录树节点组件"
```

---

### Task 5: 前端 — WorkspaceDialog 重构，接入树组件

**Files:**
- Modify: `src/main/resources/static/src/views/chat/WorkspaceDialog.vue`

**Interfaces:**
- Consumes: `TreeNode.vue` + `DirNode` interface (Task 4)

- [ ] **Step 1: 重写 WorkspaceDialog.vue**

```vue
<script lang="ts" setup>
import { ref } from 'vue'
import { useWorkspaceStore } from '@/store/workspace'
import { Icon } from '@iconify/vue'
import axios from '@/network'
import { msg } from '@/utils/Utils'
import { getLangData } from '@/i18n/locale'
import TreeNode from './TreeNode.vue'
import type { DirNode } from './TreeNode.vue'

const langData = getLangData()
const emit = defineEmits<{ close: [] }>()
const workspaceStore = useWorkspaceStore()
const name = ref('')
const path = ref('')
const saving = ref(false)

const showBrowser = ref(false)
const treeRoot = ref<{ name: string; path: string; children: DirNode[]; expanded: boolean; loaded: boolean } | null>(null)
const browserLoading = ref(false)

function openBrowser() {
  showBrowser.value = true
  loadRoot()
}

function loadRoot() {
  browserLoading.value = true
  axios({ url: '/workspace/browse', method: 'get' })
    .then(res => {
      if (res.data.state === 'OK') {
        const items = (res.data.body || [])
          .filter((d: any) => d.name !== '..')
          .map((d: any) => ({
            name: d.name,
            path: d.path,
            children: [],
            expanded: false,
            loaded: false,
            creating: false,
            newDirName: '',
            creatingError: ''
          } as DirNode))
        const rootPath = items.length > 0
          ? items[0].path.substring(0, items[0].path.lastIndexOf('/')) || '/'
          : '/'
        treeRoot.value = { name: rootPath, path: rootPath, children: items, expanded: true, loaded: true }
      }
    })
    .catch(() => {})
    .finally(() => { browserLoading.value = false })
}

function loadChildren(node: DirNode) {
  axios({ url: '/workspace/browse', method: 'get', params: { parent: node.path } })
    .then(res => {
      if (res.data.state === 'OK') {
        node.children = (res.data.body || [])
          .filter((d: any) => d.name !== '..')
          .map((d: any) => ({
            name: d.name,
            path: d.path,
            children: [],
            expanded: false,
            loaded: false,
            creating: false,
            newDirName: '',
            creatingError: ''
          } as DirNode))
        node.loaded = true
      }
    })
    .catch(() => {})
}

function onToggle(node: DirNode) {
  if (node.expanded) {
    node.expanded = false
  } else {
    if (!node.loaded) {
      loadChildren(node)
    }
    node.expanded = true
  }
}

function onSelect(node: DirNode) {
  path.value = node.path
  if (!name.value.trim()) {
    const parts = node.path.replace(/\/+$/, '').split('/')
    name.value = parts[parts.length - 1] || node.path
  }
}

function onStartCreate(node: DirNode) {
  if (!node.expanded) {
    if (!node.loaded) {
      loadChildren(node)
    }
    node.expanded = true
  }
  node.creating = true
  node.newDirName = ''
  node.creatingError = ''
}

function onCancelCreate(node: DirNode) {
  node.creating = false
  node.newDirName = ''
  node.creatingError = ''
}

function onConfirmCreate(node: DirNode) {
  const dirName = node.newDirName.trim()
  if (!dirName) {
    node.creatingError = '目录名不能为空'
    return
  }
  axios({
    url: '/workspace/mkdir',
    method: 'post',
    data: { parent: node.path, name: dirName }
  })
    .then(res => {
      if (res.data.state === 'OK') {
        node.creating = false
        node.newDirName = ''
        node.creatingError = ''
        loadChildren(node)
      } else {
        node.creatingError = res.data.errorMessage || '创建失败'
      }
    })
    .catch(err => {
      node.creatingError = err?.response?.data?.errorMessage || '创建失败'
    })
}

function onSubmit() {
  if (!path.value.trim()) { msg(langData.workspaceDialog_pathRequired, 'warning'); return }
  saving.value = true
  axios({
    url: '/workspace/create',
    method: 'post',
    data: { name: name.value.trim() || undefined, path: path.value.trim() }
  })
    .then(res => {
      if (res.data.state === 'OK') {
        workspaceStore.addWorkspace(res.data.body)
        emit('close')
      } else {
        msg(res.data.errorMessage, 'warning')
      }
    })
    .catch(err => msg(err?.response?.data?.errorMessage, 'error'))
    .finally(() => { saving.value = false })
}
</script>

<template>
  <Teleport to="body">
    <div class="ws-dialog-overlay" @click="emit('close')"></div>
    <div class="ws-dialog">
      <div class="ws-dialog-header">
        <span>{{ langData.workspaceDialog_title }}</span>
        <button class="ws-dialog-close" @click="emit('close')">
          <Icon icon="lucide:x" />
        </button>
      </div>
      <div class="ws-dialog-body">
        <label class="ws-field">
          <span>{{ langData.workspaceDialog_name }}</span>
          <input v-model="name" class="ws-input" :placeholder="langData.workspaceDialog_namePlaceholder" @keydown.enter="onSubmit" />
        </label>
        <label class="ws-field">
          <span>{{ langData.workspaceDialog_directory }}</span>
          <div class="ws-dir-row">
            <input v-model="path" class="ws-input ws-dir-input" :placeholder="langData.workspaceDialog_dirPlaceholder" readonly @click="openBrowser" />
            <button class="ws-btn ws-btn-browse" @click="openBrowser">{{ langData.workspaceDialog_browse }}</button>
          </div>
        </label>

        <!-- 目录树 -->
        <div v-if="showBrowser" class="ws-browser">
          <div class="ws-browser-head">
            <Icon icon="lucide:folder-open" class="ws-browser-icon" />
            <span class="ws-browser-path">{{ treeRoot?.path || '' }}</span>
          </div>
          <div v-if="browserLoading" class="ws-browser-loading">{{ langData.inputArea_loading }}</div>
          <div v-else class="ws-browser-tree">
            <TreeNode
              v-for="node in treeRoot?.children || []"
              :key="node.path"
              :node="node"
              :depth="0"
              @toggle="onToggle"
              @select="onSelect"
              @start-create="onStartCreate"
              @cancel-create="onCancelCreate"
              @confirm-create="onConfirmCreate"
            />
          </div>
        </div>
      </div>
      <div class="ws-dialog-footer">
        <button class="ws-btn ws-btn-cancel" @click="emit('close')">{{ langData.btnCancel }}</button>
        <button class="ws-btn ws-btn-confirm" :disabled="saving" @click="onSubmit">
          {{ saving ? langData.workspaceDialog_creating : langData.workspaceDialog_create }}
        </button>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.ws-dialog-overlay { position: fixed; inset: 0; z-index: 1000; background: rgba(0,0,0,0.2); backdrop-filter: blur(2px); }
.ws-dialog { position: fixed; top: 30%; left: 50%; transform: translate(-50%, -50%); width: 480px; max-width: 90vw; background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 12px; box-shadow: 0 12px 48px rgba(0,0,0,0.12); z-index: 1001; }
.ws-dialog-header { display: flex; align-items: center; justify-content: space-between; padding: 16px 20px 0; font-size: 16px; font-weight: 600; color: var(--el-text-color-primary); }
.ws-dialog-close { width: 30px; height: 30px; border-radius: 50%; border: none; background: transparent; color: var(--el-text-color-secondary); cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 16px; }
.ws-dialog-close:hover { background: var(--el-fill-color-light); }
.ws-dialog-body { padding: 16px 20px; display: flex; flex-direction: column; gap: 12px; }
.ws-field { display: flex; flex-direction: column; gap: 4px; }
.ws-field span { font-size: 13px; color: var(--el-text-color-secondary); }
.ws-input { width: 100%; padding: 8px 12px; border: 1px solid var(--el-border-color); border-radius: 8px; background: var(--el-bg-color); color: var(--el-text-color-primary); font-size: 14px; outline: none; font-family: inherit; box-sizing: border-box; }
.ws-input:focus { border-color: var(--el-color-primary); }
.ws-input::placeholder { color: var(--el-text-color-placeholder); }
.ws-input[readonly] { cursor: pointer; background: var(--el-fill-color-light); color: var(--el-text-color-secondary); }
.ws-dir-row { display: flex; gap: 8px; }
.ws-dir-input { flex: 1; }

.ws-browser { border: 1px solid var(--el-border-color); border-radius: 8px; overflow: hidden; max-height: 320px; display: flex; flex-direction: column; }
.ws-browser-head { display: flex; align-items: center; gap: 6px; padding: 8px 10px; border-bottom: 1px solid var(--el-border-color); background: var(--el-fill-color-light); font-size: 12px; color: var(--el-text-color-secondary); }
.ws-browser-icon { font-size: 14px; color: var(--el-color-primary); flex-shrink: 0; }
.ws-browser-path { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; direction: rtl; text-align: left; }
.ws-browser-loading { padding: 20px; text-align: center; font-size: 12px; color: var(--el-text-color-placeholder); }
.ws-browser-tree { flex: 1; overflow-y: auto; padding: 4px 8px; }
.ws-browser-tree::-webkit-scrollbar { width: 4px; }
.ws-browser-tree::-webkit-scrollbar-thumb { background: var(--el-border-color); border-radius: 2px; }

.ws-dialog-footer { display: flex; justify-content: flex-end; gap: 8px; padding: 0 20px 16px; }
.ws-btn { padding: 7px 18px; border-radius: 8px; border: 1px solid var(--el-border-color); font-size: 13px; cursor: pointer; transition: background 0.15s; }
.ws-btn-cancel { background: var(--el-bg-color); color: var(--el-text-color-regular); }
.ws-btn-cancel:hover { background: var(--el-fill-color-light); }
.ws-btn-browse { background: var(--el-bg-color); color: var(--el-text-color-regular); padding: 8px 12px; white-space: nowrap; }
.ws-btn-browse:hover { background: var(--el-fill-color-light); }
.ws-btn-confirm { background: var(--el-color-primary); color: #fff; border-color: var(--el-color-primary); }
.ws-btn-confirm:hover { background: var(--el-color-primary-dark-2); }
.ws-btn-confirm:disabled { opacity: 0.6; cursor: default; }
</style>
```

- [ ] **Step 2: 添加国际化 key**

在 `src/main/resources/static/src/i18n/locale.ts` 的 `workspaceDialog_*` key 组中追加：

```typescript
workspaceDialog_dirPlaceholder: { zh_CN: '点击选择目录', en_US: 'Click to select directory', ja_JP: 'クリックしてディレクトリを選択' },
```

- [ ] **Step 3: 构建前端验证**

```bash
cd src/main/resources/static && npm run build 2>&1 | tail -10
```

期望：构建成功，无类型错误

