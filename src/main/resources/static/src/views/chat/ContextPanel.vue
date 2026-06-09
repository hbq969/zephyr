<script lang="ts" setup>
import { ref, watch } from 'vue'
import { useConversationsStore } from '@/store/conversations'
import { Icon } from '@iconify/vue'
import axios from '@/network'

const convStore = useConversationsStore()

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ close: [] }>()

interface UsageData {
  systemPrompt: number
  history: number
  skillContent: number
  memoryContent: number
  toolDefinitions: number
  total: number
}
const usage = ref<UsageData | null>(null)

watch(() => props.visible, (v) => {
  if (v && convStore.currentId) {
    axios({ url: '/chat/context-usage', method: 'get', params: { conversationId: convStore.currentId } })
      .then(res => { if (res.data.state === 'OK') usage.value = res.data.body })
      .catch(() => {})
  }
})

const bars = [
  { key: 'systemPrompt', label: 'System Prompt', color: 'var(--el-color-primary)' },
  { key: 'history', label: '历史消息', color: 'var(--el-color-success)' },
  { key: 'skillContent', label: 'Skill 内容', color: 'var(--el-color-warning)' },
  { key: 'memoryContent', label: '记忆内容', color: 'var(--el-color-danger)' },
  { key: 'toolDefinitions', label: '工具定义', color: 'var(--el-color-info)' },
]
</script>

<template>
  <teleport to="body">
    <div v-if="visible" class="panel-overlay" @click.self="emit('close')">
      <div class="slash-panel">
        <div class="panel-header">
          <span>上下文占比</span>
          <el-button circle size="small" @click="emit('close')"><Icon icon="lucide:x" /></el-button>
        </div>
        <div class="ctx-body" v-if="usage">
          <div v-for="b in bars" :key="b.key" class="ctx-bar">
            <div class="ctx-label">
              <span>{{ b.label }}</span>
              <span>{{ (usage as any)[b.key] }} token</span>
            </div>
            <div class="ctx-track">
              <div class="ctx-fill" :style="{ width: usage.total > 0 ? ((usage as any)[b.key] / usage.total * 100) + '%' : '0%', backgroundColor: b.color }"></div>
            </div>
          </div>
          <div class="ctx-total">总计: {{ usage.total }} token</div>
        </div>
        <div v-else class="panel-empty">加载中...</div>
      </div>
    </div>
  </teleport>
</template>

<style scoped>
.panel-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.3); z-index: 300; display: flex; align-items: center; justify-content: center; }
.slash-panel { background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 12px; width: 480px; }
.panel-header { display: flex; justify-content: space-between; align-items: center; padding: 14px 16px; border-bottom: 1px solid var(--el-border-color-light); font-weight: 600; font-size: 15px; }
.panel-empty { padding: 40px; text-align: center; color: var(--el-text-color-placeholder); }
.ctx-body { padding: 14px 16px; }
.ctx-bar { margin-bottom: 10px; }
.ctx-label { display: flex; justify-content: space-between; font-size: 13px; margin-bottom: 4px; color: var(--el-text-color-secondary); }
.ctx-track { height: 6px; background: var(--el-fill-color-light); border-radius: 3px; overflow: hidden; }
.ctx-fill { height: 100%; border-radius: 3px; transition: width 0.3s; }
.ctx-total { margin-top: 12px; text-align: right; font-size: 13px; font-weight: 600; color: var(--el-text-color-primary); }
</style>
