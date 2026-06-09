<script lang="ts" setup>
import { ref } from 'vue'
import { useSettingsStore } from '@/store/settings'
import { Icon } from '@iconify/vue'

const settingsStore = useSettingsStore()
const showForm = ref(false)
const name = ref('')
const path = ref('')

function install() {
  if (!name.value.trim()) return
  settingsStore.addSkill({ name: name.value.trim(), source: 'custom', path: path.value.trim() || undefined, enabled: true })
  name.value = ''
  path.value = ''
  showForm.value = false
}
</script>

<template>
  <div class="settings-page">
    <div class="page-header">
      <button class="back-btn" @click="$router.push('/chat')"><Icon icon="lucide:chevron-left" /></button>
      <h2>Skill 管理</h2>
    </div>
    <div class="page-body">
      <div v-for="s in settingsStore.skills" :key="s.name" class="setting-row">
        <div class="row-left">
          <Icon icon="lucide:file-code" class="row-icon" />
          <div>
            <div class="row-title">{{ s.name }}</div>
            <span class="row-tag" :class="s.source">{{ s.source === 'builtin' ? '内置' : s.source === 'community' ? '社区' : '自定义' }}</span>
          </div>
        </div>
      </div>

      <div v-if="showForm" class="add-form">
        <input v-model="name" placeholder="Skill 名称" />
        <input v-model="path" placeholder="Git URL 或本地路径" />
        <div class="form-actions">
          <button class="btn btn-sec" @click="showForm = false">取消</button>
          <button class="btn btn-pri" @click="install">安装</button>
        </div>
      </div>
      <button v-else class="add-btn" @click="showForm = true"><Icon icon="lucide:plus" />安装 Skill</button>
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
.row-tag { font-size: 10px; padding: 1px 6px; border-radius: 99px; margin-top: 2px; display: inline-block; }
.row-tag.builtin { background: var(--el-fill-color); color: var(--el-text-color-secondary); }
.row-tag.community { background: rgba(204,120,92,0.12); color: var(--el-color-primary); }
.row-tag.custom { background: rgba(93,184,114,0.12); color: var(--el-color-success); }
.add-btn { display: flex; align-items: center; gap: 6px; margin-top: 16px; padding: 8px 14px; border-radius: 8px; border: 1px dashed var(--el-border-color); background: transparent; cursor: pointer; font-size: 13px; color: var(--el-color-primary); font-family: inherit; width: 100%; justify-content: center; }
.add-btn:hover { background: var(--el-fill-color-light); }
.add-form { margin-top: 16px; display: flex; flex-direction: column; gap: 8px; }
.add-form input { padding: 8px 12px; border-radius: 8px; border: 1px solid var(--el-border-color); background: var(--el-bg-color); font-size: 14px; color: var(--el-text-color-primary); outline: none; font-family: inherit; }
.add-form input:focus { border-color: var(--el-color-primary); }
.form-actions { display: flex; gap: 8px; justify-content: flex-end; }
.btn { padding: 6px 16px; border-radius: 8px; border: none; cursor: pointer; font-size: 13px; font-family: inherit; font-weight: 500; }
.btn-sec { background: var(--el-fill-color); color: var(--el-text-color-primary); }
.btn-pri { background: var(--el-color-primary); color: #fff; }
</style>
