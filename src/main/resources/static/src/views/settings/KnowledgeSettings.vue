<script lang="ts" setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { getLangData } from '@/i18n/locale'
import { useSettingsStore } from '@/store/settings'
import { msg } from '@/utils/Utils'
import { Icon } from '@iconify/vue'
import axios from '@/network'

const router = useRouter()
const langData = getLangData()
const store = useSettingsStore()
const serverScope = ref<'user' | 'shared'>('user')
const activeTab = ref('shared')
const sharedBases = computed(() => store.knowledgeBases.filter((kb: any) => kb.scope === 'shared'))
const userBases = computed(() => store.knowledgeBases.filter((kb: any) => kb.scope !== 'shared'))
const dialogVisible = ref(false)
const dialogTitle = ref('')
const editingId = ref('')
const form = reactive({ name: '', description: '', embedModelId: '', graphEnabled: false })
const embedModels = ref<any[]>([])

const fetchEmbedModels = () => {
  axios({ url: '/model-config/list', method: 'get', params: { modelType: 'embedding' } })
    .then(res => { if (res.data.state === 'OK') embedModels.value = res.data.body })
}

const openCreate = () => {
  dialogTitle.value = langData.knowledgeMgmt_createKb
  editingId.value = ''
  form.name = ''; form.description = ''; form.embedModelId = ''; form.graphEnabled = false; serverScope.value = 'user'
  fetchEmbedModels()
  dialogVisible.value = true
}

const openEdit = (kb: any) => {
  dialogTitle.value = langData.knowledgeMgmt_editKb
  editingId.value = kb.id
  form.name = kb.name; form.description = kb.description || ''; form.embedModelId = kb.embedModelId || ''
  form.graphEnabled = !!kb.graphEnabled; serverScope.value = kb.scope || 'user'
  fetchEmbedModels()
  dialogVisible.value = true
}

const saveKb = async () => {
  if (!form.name.trim()) { msg(langData.memoryMgmt_nameRequired, 'warning'); return }
  const url = editingId.value ? '/knowledge/kb/update' : '/knowledge/kb/create'
  const data: any = { name: form.name.trim(), description: form.description.trim(), embedModelId: form.embedModelId, graphEnabled: form.graphEnabled, scope: serverScope.value }
  if (editingId.value) data.id = editingId.value
  try {
    const res = await axios({ url, method: 'post', data })
    if (res.data.state === 'OK') { dialogVisible.value = false; store.loadKnowledgeBases() }
    else msg(res.data.errorMessage, 'warning')
  } catch (err: any) { msg(err?.response?.data?.errorMessage || '保存失败', 'error') }
}

const deleteKb = (kb: any) => {
  ElMessageBox.confirm(
    langData.confirmDelete + ' ' + kb.name,
    langData.knowledgeMgmt_deleteKb,
    { confirmButtonText: langData.btnDelete, cancelButtonText: langData.btnCancel, type: 'warning' }
  ).then(() => {
    axios({ url: '/knowledge/kb/delete', method: 'post', data: { id: kb.id } })
      .then(res => { if (res.data.state === 'OK') store.loadKnowledgeBases() })
      .catch(err => msg(err?.response?.data?.errorMessage || '删除失败', 'error'))
  }).catch(() => {})
}

async function toggleScope(id: string, newScope: string) {
  await store.toggleKbScope(id, newScope)
}

function goDocs(kbId: string) {
  router.push('/settings/knowledge/' + kbId + '/docs')
}

function fmtTime(ts: number) {
  return ts ? new Date(ts * 1000).toISOString().slice(0, 10) : '-'
}

onMounted(() => { store.loadKnowledgeBases() })
</script>

<template>
  <div class="settings-page">
    <div class="page-header">
      <div>
        <button class="back-btn" @click="router.push('/chat')">
          <Icon icon="lucide:chevron-left" />
        </button>
        <h1>{{ langData.knowledgeMgmt_title }}</h1>
      </div>
      <button v-if="store.knowledgeBases.length > 0" class="btn-primary" @click="openCreate">
        <Icon icon="lucide:plus" /> {{ langData.knowledgeMgmt_createKb }}
      </button>
    </div>
    <p class="subtitle">{{ langData.knowledgeMgmt_subtitle }}</p>

    <el-tabs v-if="store.knowledgeBases.length > 0" v-model="activeTab" class="kb-tabs">
      <el-tab-pane :label="(langData.knowledgeMgmt_sharedTab || '共享知识库') + ' (' + sharedBases.length + ')'" name="shared">
        <div v-if="sharedBases.length > 0" class="card-list">
          <div v-for="kb in sharedBases" :key="kb.id" class="kb-card" @click="goDocs(kb.id)">
            <div class="card-inner">
              <div class="card-header">
                <Icon icon="lucide:library" class="card-icon" />
                <div class="card-body">
                  <div class="card-title">{{ kb.name }}</div>
                  <div class="card-desc" v-if="kb.description">{{ kb.description }}</div>
                  <div class="card-meta">
                    <span class="kb-embed-badge">{{ kb.embedModelName || langData.knowledgeMgmt_embedModel }}</span>
                    <span v-if="kb.graphEnabled" class="kb-graph-badge">图谱</span>
                    <span class="badge-scope-shared">{{ langData.knowledgeMgmt_shared || '共享' }}</span>
                    <span>{{ langData.knowledgeMgmt_docCount.replace('{count}', kb.docCount || 0) }}</span>
                    <span>{{ fmtTime(kb.updatedAt) }}</span>
                  </div>
                </div>
                <div class="card-actions" @click.stop>
                  <el-tooltip :content="langData.knowledgeMgmt_recallTest">
                    <el-button circle size="small" @click="router.push('/settings/knowledge/' + kb.id + '/recall-test')">
                      <Icon icon="lucide:search" />
                    </el-button>
                  </el-tooltip>
                  <el-tooltip v-if="kb.canManage" :content="kb.scope === 'shared' ? langData.knowledgeMgmt_unshare : langData.knowledgeMgmt_shareToAll">
                    <el-button circle size="small" @click="toggleScope(kb.id!, kb.scope === 'shared' ? 'user' : 'shared')">
                      <Icon :icon="kb.scope === 'shared' ? 'lucide:lock' : 'lucide:share-2'" />
                    </el-button>
                  </el-tooltip>
                  <el-tooltip v-if="kb.canManage" :content="langData.btnEdit">
                    <el-button circle size="small" @click="openEdit(kb)">
                      <Icon icon="lucide:edit-3" />
                    </el-button>
                  </el-tooltip>
                  <el-tooltip v-if="kb.canManage" :content="langData.btnDelete">
                    <el-button circle size="small" @click="deleteKb(kb)">
                      <Icon icon="lucide:trash-2" />
                    </el-button>
                  </el-tooltip>
                </div>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="empty-result">
          <Icon icon="lucide:inbox" class="empty-icon" />
          <p class="empty-desc">{{ langData.knowledgeMgmt_noShared || '暂无共享知识库' }}</p>
        </div>
      </el-tab-pane>
      <el-tab-pane :label="(langData.knowledgeMgmt_userTab || '我的知识库') + ' (' + userBases.length + ')'" name="user">
        <div v-if="userBases.length > 0" class="card-list">
          <div v-for="kb in userBases" :key="kb.id" class="kb-card" @click="goDocs(kb.id)">
            <div class="card-inner">
              <div class="card-header">
                <Icon icon="lucide:library" class="card-icon" />
                <div class="card-body">
                  <div class="card-title">{{ kb.name }}</div>
                  <div class="card-desc" v-if="kb.description">{{ kb.description }}</div>
                  <div class="card-meta">
                    <span class="kb-embed-badge">{{ kb.embedModelName || langData.knowledgeMgmt_embedModel }}</span>
                    <span v-if="kb.graphEnabled" class="kb-graph-badge">图谱</span>
                    <span>{{ langData.knowledgeMgmt_docCount.replace('{count}', kb.docCount || 0) }}</span>
                    <span>{{ fmtTime(kb.updatedAt) }}</span>
                  </div>
                </div>
                <div class="card-actions" @click.stop>
                  <el-tooltip :content="langData.knowledgeMgmt_recallTest">
                    <el-button circle size="small" @click="router.push('/settings/knowledge/' + kb.id + '/recall-test')">
                      <Icon icon="lucide:search" />
                    </el-button>
                  </el-tooltip>
                  <el-tooltip v-if="kb.canManage" :content="langData.knowledgeMgmt_shareToAll">
                    <el-button circle size="small" @click="toggleScope(kb.id!, 'shared')">
                      <Icon icon="lucide:share-2" />
                    </el-button>
                  </el-tooltip>
                  <el-tooltip v-if="kb.canManage" :content="langData.btnEdit">
                    <el-button circle size="small" @click="openEdit(kb)">
                      <Icon icon="lucide:edit-3" />
                    </el-button>
                  </el-tooltip>
                  <el-tooltip v-if="kb.canManage" :content="langData.btnDelete">
                    <el-button circle size="small" @click="deleteKb(kb)">
                      <Icon icon="lucide:trash-2" />
                    </el-button>
                  </el-tooltip>
                </div>
              </div>
            </div>
          </div>
        </div>
        <div v-else class="empty-result">
          <Icon icon="lucide:inbox" class="empty-icon" />
          <p class="empty-desc">{{ langData.knowledgeMgmt_noUser || '暂无个人知识库' }}</p>
        </div>
      </el-tab-pane>
    </el-tabs>

    <div v-if="store.knowledgeBases.length === 0" class="empty-state">
      <Icon icon="lucide:library" width="48" style="color: var(--el-text-color-placeholder)" />
      <h3 class="empty-title">{{ langData.knowledgeMgmt_noKb }}</h3>
      <p class="empty-desc">{{ langData.knowledgeMgmt_noKbHint }}</p>
      <button class="btn-primary" @click="openCreate">
        <Icon icon="lucide:plus" /> {{ langData.knowledgeMgmt_createKb }}
      </button>
    </div>

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="480px" destroy-on-close>
      <el-form :model="form" label-position="top">
        <el-form-item :label="langData.knowledgeMgmt_nameLabel" required>
          <el-input v-model="form.name" :placeholder="langData.formInputPlaceholder" />
        </el-form-item>
        <el-form-item :label="langData.knowledgeMgmt_descLabel">
          <el-input v-model="form.description" type="textarea" :rows="3" :placeholder="langData.formInputPlaceholder" />
        </el-form-item>
        <el-form-item :label="langData.knowledgeMgmt_embedModel">
          <el-select v-model="form.embedModelId" style="width:100%" :placeholder="langData.formSelectPlaceholder">
            <el-option v-for="m in embedModels" :key="m.id" :label="m.name" :value="m.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="store.isAdmin" :label="langData.knowledgeMgmt_scope || '范围'">
          <div class="transport-toggle">
            <button :class="{ active: serverScope === 'user' }" @click="serverScope = 'user'">{{ langData.knowledgeMgmt_personal || '个人' }}</button>
            <button :class="{ active: serverScope === 'shared' }" @click="serverScope = 'shared'">{{ langData.knowledgeMgmt_shared || '共享' }}</button>
          </div>
        </el-form-item>
        <el-form-item :label="langData.knowledgeMgmt_graphLabel || '图谱增强'">
          <div style="display:flex;align-items:center;gap:8px;">
            <el-switch v-model="form.graphEnabled" />
            <span style="font-size:12px;color:var(--el-text-color-secondary);">{{ langData.knowledgeMgmt_graphHint || '额外构建实体关系图谱，提升多跳推理和全局理解能力' }}</span>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ langData.btnCancel }}</el-button>
        <el-button type="primary" @click="saveKb">{{ langData.btnSave }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.settings-page { max-width: 780px; margin: 0 auto; padding: 48px 24px 96px; }

.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.page-header > div:first-child { display: flex; align-items: center; gap: 12px; }
.back-btn {
  width: 32px; height: 32px; border-radius: 50%;
  border: 1px solid var(--el-border-color); background: var(--el-bg-color);
  cursor: pointer; display: flex; align-items: center; justify-content: center;
  color: var(--el-text-color-secondary);
}
.back-btn:hover { background: var(--el-fill-color-light); }
h1 { font-family: Georgia, 'Times New Roman', serif; font-size: 36px; font-weight: 400; color: var(--el-text-color-primary); letter-spacing: -0.5px; margin: 0; }
.subtitle { font-size: 15px; color: var(--el-text-color-secondary); margin: 0 0 36px 44px; }

.empty-state { text-align: center; padding: 80px 24px; }
.empty-title { font-family: Georgia, serif; font-size: 22px; color: var(--el-text-color-primary); margin: 16px 0 8px; }
.empty-desc { font-size: 14px; color: var(--el-text-color-secondary); max-width: 420px; margin: 0 auto 24px; }

.btn-primary { display: inline-flex; align-items: center; gap: 6px; padding: 10px 18px; border-radius: 8px; border: none; background: var(--el-color-primary); color: #fff; font-size: 14px; font-weight: 500; cursor: pointer; font-family: inherit; transition: background 150ms; }
.btn-primary:hover { background: var(--el-color-primary-light-3); }

.card-list { display: flex; flex-direction: column; gap: 1px; background: var(--el-border-color); border-radius: 12px; overflow: hidden; }

.kb-card { background: var(--el-bg-color); cursor: pointer; transition: background 0.15s; }
.kb-card:hover { background: var(--el-fill-color-light); }
.card-inner { padding: 14px 16px; }
.card-header { display: flex; align-items: flex-start; gap: 10px; }
.card-icon { color: var(--el-color-primary); font-size: 20px; flex-shrink: 0; margin-top: 2px; }
.card-body { flex: 1; min-width: 0; }
.card-title { font-size: 14px; font-weight: 500; color: var(--el-text-color-primary); }
.card-desc { font-size: 13px; color: var(--el-text-color-secondary); margin-top: 2px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.card-meta { display: flex; align-items: center; gap: 8px; margin-top: 6px; font-size: 12px; color: var(--el-text-color-placeholder); }
.kb-embed-badge { display: inline-block; padding: 2px 8px; border-radius: 9999px; font-size: 11px; font-weight: 500; background: var(--el-color-primary-light-9); color: var(--el-color-primary); }
.kb-graph-badge { display: inline-block; padding: 2px 8px; border-radius: 9999px; font-size: 11px; font-weight: 500; background: rgba(204,120,92,0.12); color: var(--el-color-primary); }

.card-actions { display: flex; align-items: center; gap: 4px; flex-shrink: 0; }

/* dark mode */
html.dark .kb-card { background: var(--el-bg-color); }
html.dark .kb-card:hover { background: var(--el-fill-color); }
html.dark .back-btn:hover { background: var(--el-fill-color); }

.kb-tabs { margin-top: 0; }
.badge-scope-shared { display: inline-block; padding: 2px 8px; border-radius: 9999px; font-size: 11px; font-weight: 500; background: rgba(204,120,92,0.12); color: var(--el-color-primary); }
.empty-result { text-align: center; padding: 64px 24px; }
.empty-result .empty-icon { font-size: 40px; color: var(--el-text-color-placeholder); }
.empty-result .empty-desc { font-size: 13px; color: var(--el-text-color-secondary); }

.transport-toggle { display: flex; border: 1px solid var(--el-border-color); border-radius: 6px; overflow: hidden; }
.transport-toggle button { flex: 1; padding: 8px 12px; border: none; background: var(--el-bg-color); color: var(--el-text-color-secondary); font-size: 13px; cursor: pointer; font-family: inherit; transition: background 0.15s; }
.transport-toggle button.active { background: var(--el-color-primary); color: #fff; font-weight: 500; }

/* dark mode */
html.dark .empty-result .empty-icon { color: var(--el-text-color-placeholder); }
html.dark .empty-result .empty-desc { color: var(--el-text-color-secondary); }
</style>
