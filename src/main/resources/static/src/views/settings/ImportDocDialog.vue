<script lang="ts" setup>
import { ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { msg } from '@/utils/Utils'
import { Icon } from '@iconify/vue'
import { marked } from 'marked'
import axios from '@/network'

const props = defineProps<{ visible: boolean; kbId: string }>()

const emit = defineEmits<{
  (e: 'update:visible', v: boolean): void
  (e: 'imported'): void
}>()

marked.setOptions({ breaks: true, gfm: true })

const step = ref(1)
const uploading = ref(false)
const confirming = ref(false)
const uploadFile = ref<File | null>(null)

const docId = ref('')
const fileName = ref('')
const headings = ref<{ level: number; text: string }[]>([])
const markdownPreview = ref('')
const markdownEdited = ref('')

const previewHtml = ref('')
const updatePreview = () => { try { previewHtml.value = marked(markdownEdited.value || '') as string } catch { previewHtml.value = '' } }
watch(markdownEdited, updatePreview)

const selectedHeadingLevel = ref(0)

const headingLevelCounts = () => {
  const counts: Record<number, number> = {}
  for (const h of headings.value) counts[h.level] = (counts[h.level] || 0) + 1
  return Object.entries(counts).sort((a, b) => Number(a[0]) - Number(b[0]))
}

const allowedExts = ['md', 'docx', 'pdf']


const onFileChange = (f: any) => {
  const ext = (f.name || '').split('.').pop()?.toLowerCase() || ''
  if (!allowedExts.includes(ext)) { msg('仅支持 md、docx、pdf 格式文件', 'warning'); return }
  uploadFile.value = f.raw
  handleUpload()
}

const handleUpload = async () => {
  if (!uploadFile.value) return
  uploading.value = true
  try {
    const fd = new FormData(); fd.append('file', uploadFile.value); fd.append('kbId', props.kbId)
    const res = await axios({ url: '/knowledge/doc/upload', method: 'post', data: fd, headers: { 'Content-Type': 'multipart/form-data' } })
    if (res.data.state === 'OK') {
      const b = res.data.body
      docId.value = b.docId; fileName.value = b.fileName
      headings.value = b.headings || []
      markdownPreview.value = b.markdownPreview || ''; markdownEdited.value = b.markdownPreview || ''
      step.value = 2
    } else msg(res.data.errorMessage, 'warning')
  } catch (err: any) { msg(err?.response?.data?.errorMessage || '上传失败', 'error') }
  finally { uploading.value = false }
}

const downloadMd = () => {
  window.open(`/zephyr-ui/knowledge/doc/${docId.value}/markdown/download?kbId=${props.kbId}`, '_blank')
}

const confirmImport = async () => {
  confirming.value = true
  try {
    const body: any = { docId: docId.value, kbId: props.kbId, headingLevel: selectedHeadingLevel.value }
    if (markdownEdited.value !== markdownPreview.value) body.markdownContent = markdownEdited.value
    const res = await axios({ url: '/knowledge/doc/confirm-import', method: 'post', data: body })
    if (res.data.state === 'OK') { msg('导入已开始', 'success'); emit('update:visible', false); emit('imported') }
    else msg(res.data.errorMessage, 'warning')
  } catch (err: any) { msg(err?.response?.data?.errorMessage || '导入失败', 'error') }
  finally { confirming.value = false }
}

const reset = () => {
  step.value = 1; uploadFile.value = null; docId.value = ''; fileName.value = ''
  headings.value = []; markdownPreview.value = ''; markdownEdited.value = ''; selectedHeadingLevel.value = 0
}
watch(() => props.visible, (v) => { if (v) reset() })

const handleClose = (done: () => void) => {
  if (step.value >= 2) {
    ElMessageBox.confirm(
      '关闭将丢弃本次导入的数据，是否继续？',
      '提示',
      { confirmButtonText: '确定关闭', cancelButtonText: '继续操作', type: 'warning' }
    ).then(() => {
      const id = docId.value
      done()
      reset()
      axios({ url: '/knowledge/doc/delete', method: 'post', data: { id } })
        .finally(() => emit('imported'))
    }).catch(() => {})
  } else {
    done()
  }
}
</script>

<template>
  <el-dialog :model-value="visible" @update:model-value="emit('update:visible', $event)"
    title="导入文档" width="860px" destroy-on-close :close-on-click-modal="false"
    :before-close="handleClose">

    <div class="steps-indicator">
      <div class="step" :class="{ active: step === 1, done: step > 1 }">
        <span class="step-num">1</span><span class="step-label">选择文件</span>
      </div>
      <div class="step-connector" :class="{ done: step > 1 }" />
      <div class="step" :class="{ active: step === 2, done: step > 2 }">
        <span class="step-num">2</span><span class="step-label">预览修正</span>
      </div>
      <div class="step-connector" :class="{ done: step > 2 }" />
      <div class="step" :class="{ active: step === 3 }">
        <span class="step-num">3</span><span class="step-label">切分配置</span>
      </div>
    </div>

    <div v-if="step === 1" class="step-body">
      <el-upload drag :auto-upload="false" :show-file-list="true" :limit="1"
        :accept="'.md,.docx,.pdf'" :on-change="onFileChange" :on-remove="() => { uploadFile = null }">
        <Icon icon="lucide:upload" :width="40" style="color:var(--el-text-color-secondary);margin-bottom:8px" />
        <div style="font-size:14px;color:var(--el-text-color-primary)">拖拽或点击上传文档</div>
        <div style="font-size:12px;color:var(--el-text-color-secondary);margin-top:4px">支持 .md、.docx、.pdf 格式</div>
      </el-upload>
    </div>

    <div v-if="step === 2" class="step-body">
      <div class="editor-split">
        <div class="editor-pane">
          <div class="pane-label">Markdown（可编辑）</div>
          <el-input v-model="markdownEdited" type="textarea" :rows="20" class="editor-textarea" />
        </div>
        <div class="preview-pane">
          <div class="pane-label">预览</div>
          <div class="preview-content" v-html="previewHtml" />
        </div>
      </div>
      <div class="step-actions">
        <el-button @click="downloadMd"><Icon icon="lucide:download" style="margin-right:4px" />下载 .md</el-button>
        <div style="flex:1" />
        <el-button type="primary" @click="step = 3">下一步</el-button>
      </div>
    </div>

    <div v-if="step === 3" class="step-body">
      <div v-if="headings.length === 0" class="no-headings">
        <Icon icon="lucide:info" width="20" style="color:var(--el-text-color-secondary)" />
        <span>未检测到标题，将按段落切分</span>
      </div>
      <div v-else class="heading-selector">
        <div class="selector-label">请选择 chunk 切分的标题层级：</div>
        <el-radio-group v-model="selectedHeadingLevel" class="heading-radio-group">
          <el-radio :value="0" size="large" class="heading-radio-item">
            <span class="radio-main">无标题（按段落切分）</span>
          </el-radio>
          <el-radio v-for="[level, count] in headingLevelCounts()" :key="level"
            :value="Number(level)" size="large" class="heading-radio-item">
            <span class="radio-main">{{ '#'.repeat(Number(level)) }} 标题</span>
            <span class="radio-sub">（{{ count }} 个）</span>
          </el-radio>
        </el-radio-group>
      </div>
      <div class="step-actions">
        <el-button @click="step = 2">上一步</el-button>
        <div style="flex:1" />
        <el-button type="primary" @click="confirmImport" :loading="confirming">确认导入</el-button>
      </div>
    </div>
  </el-dialog>
</template>

<style scoped>
.steps-indicator { display: flex; align-items: center; justify-content: center; margin-bottom: 24px; }
.step { display: flex; align-items: center; gap: 6px; }
.step-num { width: 26px; height: 26px; border-radius: 50%; display: flex; align-items: center; justify-content: center; font-size: 13px; font-weight: 600; background: var(--el-fill-color-light); color: var(--el-text-color-placeholder); }
.step.active .step-num { background: var(--el-color-primary); color: #fff; }
.step.done .step-num { background: var(--el-color-primary-light-3); color: #fff; }
.step-label { font-size: 13px; color: var(--el-text-color-placeholder); }
.step.active .step-label { color: var(--el-color-primary); font-weight: 500; }
.step.done .step-label { color: var(--el-text-color-secondary); }
.step-connector { width: 40px; height: 2px; background: var(--el-fill-color-light); margin: 0 8px; }
.step-connector.done { background: var(--el-color-primary-light-3); }

.step-body { min-height: 200px; }

.editor-split { display: flex; gap: 12px; }
.editor-pane, .preview-pane { flex: 1; min-width: 0; }
.pane-label { font-size: 13px; font-weight: 500; color: var(--el-text-color-secondary); margin-bottom: 6px; }
.editor-textarea :deep(.el-textarea__inner) { font-family: 'SF Mono', 'Consolas', monospace; font-size: 13px; line-height: 1.6; height: 380px; resize: none; }
.preview-content { border: 1px solid var(--el-border-color); border-radius: 6px; padding: 14px; height: 380px; overflow-y: auto; font-size: 14px; line-height: 1.7; color: var(--el-text-color-primary); }
.preview-content :deep(h1) { font-size: 22px; margin: 0 0 12px; }
.preview-content :deep(h2) { font-size: 17px; margin: 16px 0 8px; }
.preview-content :deep(p) { margin: 0 0 8px; }
.preview-content :deep(code) { background: var(--el-fill-color-light); padding: 2px 6px; border-radius: 4px; }
.preview-content :deep(pre) { background: #1e1e1e; color: #d4d4d4; padding: 12px; border-radius: 6px; overflow-x: auto; }
.preview-content :deep(pre code) { background: none; padding: 0; color: inherit; }

.step-actions { display: flex; align-items: center; margin-top: 16px; }

.no-headings { display: flex; align-items: center; justify-content: center; gap: 8px; padding: 40px 0; color: var(--el-text-color-secondary); font-size: 14px; }

.heading-selector { padding: 16px 0; }
.selector-label { font-size: 14px; color: var(--el-text-color-primary); margin-bottom: 16px; font-weight: 500; }
.heading-radio-group { display: flex; flex-direction: column; gap: 10px; }
.radio-main { font-size: 14px; color: var(--el-text-color-primary); font-family: 'SF Mono', 'Consolas', monospace; }
.radio-sub { font-size: 12px; color: var(--el-text-color-secondary); }
</style>
