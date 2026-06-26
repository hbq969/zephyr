<script lang="ts" setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useSettingsStore } from '@/store/settings'
import { Icon } from '@iconify/vue'
import { ElMessageBox } from 'element-plus'
import { getLangData } from '@/i18n/locale'
import { msg } from '@/utils/Utils'

const settingsStore = useSettingsStore()
const langData = getLangData()

const activeTab = ref('SHELL_ALLOWED')
const loading = ref(false)
const showDialog = ref(false)
const editId = ref<string | null>(null)
const dialogValue = ref('')
const dialogDesc = ref('')

const COMMAND_TABS = ['SHELL_ALLOWED', 'DEFAULT_ALLOW']
const TAB_INFO = [
  { name: 'SHELL_ALLOWED', labelKey: 'securityMgmt_tabShellAllowed' },
  { name: 'DEFAULT_ALLOW', labelKey: 'securityMgmt_tabDefaultAllow' },
  { name: 'HARD_BLOCK', labelKey: 'securityMgmt_tabHardBlock' },
  { name: 'SOFT_BLOCK', labelKey: 'securityMgmt_tabSoftBlock' },
]

const isPatternTab = computed(() => !COMMAND_TABS.includes(activeTab.value))

const rules = computed(() => settingsStore.securityRules[activeTab.value] || [])

const valueLabel = computed(() =>
  isPatternTab.value ? langData.securityMgmt_patternLabel : langData.securityMgmt_commandLabel
)

const valuePlaceholder = computed(() =>
  isPatternTab.value ? langData.securityMgmt_valuePlaceholder_pattern : langData.securityMgmt_valuePlaceholder_cmd
)

const dialogTitle = computed(() =>
  editId.value ? langData.dialogTitleEdit : langData.dialogTitleAdd
)

const headerCellStyle = () => ({
  backgroundColor: 'var(--el-fill-color-light)',
  color: 'var(--el-text-color-primary)',
  fontWeight: 600,
  whiteSpace: 'nowrap'
})

function tabLabel(tab: { name: string; labelKey: string }): string {
  return (langData as any)[tab.labelKey] || tab.name
}

onMounted(() => loadRules())

watch(activeTab, () => loadRules())

async function loadRules() {
  loading.value = true
  await settingsStore.loadSecurityRules(activeTab.value)
  loading.value = false
}

function openAdd() {
  editId.value = null
  dialogValue.value = ''
  dialogDesc.value = ''
  showDialog.value = true
}

function openEdit(rule: any) {
  editId.value = rule.id
  dialogValue.value = rule.ruleValue || ''
  dialogDesc.value = rule.description || ''
  showDialog.value = true
}

async function saveRule() {
  if (!dialogValue.value.trim()) return
  const val = dialogValue.value.trim()
  const desc = dialogDesc.value.trim()
  if (editId.value) {
    await settingsStore.updateSecurityRule(activeTab.value, editId.value, val, desc)
  } else {
    await settingsStore.addSecurityRule(activeTab.value, val, desc)
  }
  showDialog.value = false
}

function confirmDelete(rule: any) {
  ElMessageBox.confirm(
    langData.securityMgmt_confirmDeleteMsg,
    langData.confirmDelete,
    { confirmButtonText: langData.btnDelete, cancelButtonText: langData.btnCancel, type: 'warning' }
  ).then(() => doDelete(rule)).catch(() => {})
}

async function doDelete(rule: any) {
  if (!rule.id) return
  await settingsStore.deleteSecurityRule(activeTab.value, rule.id)
}

async function onToggle(rule: any, val: any) {
  if (!rule.id) return
  await settingsStore.toggleSecurityRule(activeTab.value, rule.id, val ? 1 : 0)
}
</script>

<template>
  <div class="security-page">
    <div class="page-header">
      <div>
        <button class="back-btn" @click="$router.push('/chat')">
          <Icon icon="lucide:chevron-left" />
        </button>
        <h1>{{ langData.securityMgmt_title }}</h1>
      </div>
      <button v-if="rules.length > 0" class="btn-primary" @click="openAdd">
        <Icon icon="lucide:plus" /> {{ langData.securityMgmt_addRule }}
      </button>
    </div>
    <p class="subtitle">{{ langData.securityMgmt_subtitle }}</p>

    <el-tabs v-model="activeTab">
      <el-tab-pane v-for="tab in TAB_INFO" :key="tab.name" :label="tabLabel(tab)" :name="tab.name">
        <div v-if="loading" class="loading-state">{{ langData.inputArea_loading }}</div>

        <div v-else-if="rules.length === 0" class="empty-state">
          <Icon icon="lucide:shield-off" width="48" style="color: var(--el-text-color-placeholder)" />
          <h3 class="empty-title">{{ langData.securityMgmt_noRules }}</h3>
          <button class="btn-primary" @click="openAdd">
            <Icon icon="lucide:plus" /> {{ langData.securityMgmt_addFirstRule }}
          </button>
        </div>

        <div v-else class="table-wrapper">
          <el-table :data="rules" style="width: 100%" :header-cell-style="headerCellStyle" stripe>
            <el-table-column :label="valueLabel" prop="ruleValue" min-width="200">
              <template #default="{ row }">
                <span class="mono-text">{{ row.ruleValue }}</span>
              </template>
            </el-table-column>
            <el-table-column :label="langData.tableHeaderDesc" prop="description" min-width="200">
              <template #default="{ row }">
                <span class="desc-text">{{ row.description || '-' }}</span>
              </template>
            </el-table-column>

            <el-table-column
              v-if="isPatternTab"
              :label="langData.securityMgmt_statusLabel"
              width="90"
              align="center"
            >
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
                  {{ row.enabled ? langData.securityMgmt_enabled : langData.securityMgmt_disabled }}
                </el-tag>
              </template>
            </el-table-column>

            <el-table-column :label="langData.tableHeaderOp" width="170" align="center">
              <template #default="{ row }">
                <el-switch
                  v-if="isPatternTab"
                  :model-value="!!row.enabled"
                  size="small"
                  style="margin-right: 6px"
                  @change="(val: any) => onToggle(row, val)"
                />
                <el-tooltip v-if="isPatternTab" :content="langData.btnEdit">
                  <el-button circle size="small" @click="openEdit(row)">
                    <Icon icon="lucide:pencil" />
                  </el-button>
                </el-tooltip>
                <el-tooltip :content="langData.btnDelete">
                  <el-button circle size="small" @click="confirmDelete(row)">
                    <Icon icon="lucide:trash-2" />
                  </el-button>
                </el-tooltip>
              </template>
            </el-table-column>
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="showDialog" :title="dialogTitle" width="480px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item :label="valueLabel" required>
          <el-input v-model="dialogValue" :placeholder="valuePlaceholder" />
        </el-form-item>
        <el-form-item :label="langData.tableHeaderDesc">
          <el-input v-model="dialogDesc" :placeholder="langData.securityMgmt_descPlaceholder" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showDialog = false">{{ langData.btnCancel }}</el-button>
        <el-button type="primary" @click="saveRule">{{ editId ? langData.btnSave : langData.btnAdd }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.security-page { max-width: 780px; margin: 0 auto; padding: 48px 24px 96px; }
.page-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.page-header > div:first-child { display: flex; align-items: center; gap: 12px; }
.back-btn { width: 32px; height: 32px; border-radius: 50%; border: 1px solid var(--el-border-color); background: var(--el-bg-color); cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--el-text-color-secondary); }
.back-btn:hover { background: var(--el-fill-color-light); }
h1 { font-family: Georgia, 'Times New Roman', serif; font-size: 36px; font-weight: 400; color: var(--el-text-color-primary); letter-spacing: -0.5px; margin: 0; }
.subtitle { font-size: 15px; color: var(--el-text-color-secondary); margin: 0 0 36px 44px; }

.btn-primary { display: inline-flex; align-items: center; gap: 6px; padding: 10px 18px; border-radius: 8px; border: none; background: var(--el-color-primary); color: #fff; font-size: 14px; font-weight: 500; cursor: pointer; font-family: inherit; transition: background 150ms; }
.btn-primary:hover { background: var(--el-color-primary-light-3); }

.loading-state { text-align: center; padding: 80px 24px; font-size: 14px; color: var(--el-text-color-placeholder); }

.empty-state { text-align: center; padding: 80px 24px; }
.empty-title { font-family: Georgia, serif; font-size: 22px; color: var(--el-text-color-primary); margin: 16px 0 8px; }

.table-wrapper { margin-top: 0; }

.mono-text { font-family: "JetBrains Mono", monospace; font-size: 13px; color: var(--el-text-color-primary); }
.desc-text { font-size: 13px; color: var(--el-text-color-secondary); }

:deep(.el-table) { border-radius: 8px; overflow: hidden; }

html.dark .back-btn:hover { background: var(--el-fill-color); }
</style>
