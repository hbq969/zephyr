<script lang="ts" setup>
import { ref, watch } from 'vue'
import { getLangData } from '@/i18n/locale'
import { msg } from '@/utils/Utils'
import { Icon } from '@iconify/vue'
import { marked } from 'marked'
import axios from '@/network'

const langData = getLangData()

const props = defineProps<{
  visible: boolean
  kbId: string
  editDoc?: any  // null = create mode
}>()

const emit = defineEmits<{
  (e: 'update:visible', v: boolean): void
  (e: 'saved'): void
}>()

const title = ref('')
const content = ref('')

marked.setOptions({ breaks: true, gfm: true })

const previewHtml = ref('')
const updatePreview = () => {
  try { previewHtml.value = marked(content.value || '') as string } catch { previewHtml.value = '' }
}

watch(() => props.visible, (v) => {
  if (v) {
    if (props.editDoc) {
      title.value = props.editDoc.fileName?.replace(/\.md$/, '') || ''
      content.value = props.editDoc.content || ''
    } else {
      title.value = ''
      content.value = ''
    }
    updatePreview()
  }
})

watch(content, updatePreview)

const saving = ref(false)
const save = async () => {
  if (!title.value.trim()) { msg('请输入文档标题', 'warning'); return }
  if (!content.value.trim()) { msg('请输入文档内容', 'warning'); return }

  const isEdit = !!props.editDoc
  const url = isEdit ? '/knowledge/doc/update-inline' : '/knowledge/doc/create-inline'
  const data: any = isEdit
    ? { id: props.editDoc.id, title: title.value.trim(), content: content.value }
    : { kbId: props.kbId, title: title.value.trim(), content: content.value }

  saving.value = true
  try {
    const res = await axios({ url, method: 'post', data })
    if (res.data.state === 'OK') { emit('update:visible', false); emit('saved') }
    else msg(res.data.errorMessage, 'warning')
  } catch (err: any) { msg(err?.response?.data?.errorMessage || '保存失败', 'error') }
  finally { saving.value = false }
}
</script>

<template>
  <el-dialog
    :model-value="visible"
    @update:model-value="emit('update:visible', $event)"
    :title="editDoc ? langData.knowledgeMgmt_editDoc : langData.knowledgeMgmt_createDoc"
    width="900px"
    destroy-on-close
    :close-on-click-modal="false"
  >
    <el-form :model="{ title }" label-position="top">
      <el-form-item :label="langData.knowledgeMgmt_docTitle">
        <el-input v-model="title" :placeholder="langData.knowledgeMgmt_docTitlePlaceholder" />
      </el-form-item>
    </el-form>

    <div class="editor-split">
      <div class="editor-pane">
        <div class="pane-label">{{ langData.knowledgeMgmt_docContent }}</div>
        <el-input
          v-model="content"
          type="textarea"
          :rows="20"
          :placeholder="langData.formInputPlaceholder"
          class="editor-textarea"
        />
      </div>
      <div class="preview-pane">
        <div class="pane-label">{{ langData.knowledgeMgmt_preview }}</div>
        <div class="preview-content" v-html="previewHtml" />
      </div>
    </div>

    <template #footer>
      <el-button @click="emit('update:visible', false)">{{ langData.btnCancel }}</el-button>
      <el-button type="primary" @click="save" :loading="saving">{{ langData.btnSave }}</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.editor-split { display: flex; gap: 12px; margin-top: 8px; }
.editor-pane, .preview-pane { flex: 1; min-width: 0; }
.pane-label { font-size: 13px; font-weight: 500; color: var(--el-text-color-secondary); margin-bottom: 6px; }
.editor-textarea :deep(.el-textarea__inner) {
  font-family: 'SF Mono', 'Consolas', monospace; font-size: 13px; line-height: 1.6;
  height: 420px; resize: none;
}
.preview-content {
  border: 1px solid var(--el-border-color); border-radius: 6px; padding: 14px;
  height: 420px; overflow-y: auto; font-size: 14px; line-height: 1.7;
  color: var(--el-text-color-primary);
}
.preview-content :deep(h1) { font-size: 22px; margin: 0 0 12px; }
.preview-content :deep(h2) { font-size: 17px; margin: 16px 0 8px; }
.preview-content :deep(p) { margin: 0 0 8px; }
.preview-content :deep(code) { background: var(--el-fill-color-light); padding: 2px 6px; border-radius: 4px; font-size: 13px; }
.preview-content :deep(pre) { background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 6px; overflow-x: auto; }
.preview-content :deep(pre code) { background: none; padding: 0; color: inherit; }
.preview-content :deep(ul), .preview-content :deep(ol) { padding-left: 20px; margin: 0 0 8px; }
.preview-content :deep(blockquote) { border-left: 3px solid var(--el-color-primary); padding-left: 12px; color: var(--el-text-color-secondary); margin: 0 0 8px; }
</style>
