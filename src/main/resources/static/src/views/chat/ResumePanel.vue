<script lang="ts" setup>
import { useConversationsStore } from '@/store/conversations'
import { Icon } from '@iconify/vue'

const convStore = useConversationsStore()

defineProps<{ visible: boolean }>()
const emit = defineEmits<{ close: [] }>()

function select(id: string) {
  convStore.selectConversation(id)
  emit('close')
}
</script>

<template>
  <teleport to="body">
    <div v-if="visible" class="panel-overlay" @click.self="emit('close')">
      <div class="slash-panel">
        <div class="panel-header">
          <span>恢复对话</span>
          <el-button circle size="small" @click="emit('close')">
            <Icon icon="lucide:x" />
          </el-button>
        </div>
        <div class="panel-body">
          <div v-for="c in convStore.conversations" :key="c.id" class="panel-item" @click="select(c.id)">
            <div>
              <div class="item-title">{{ c.title }}</div>
              <div class="item-meta">{{ c.messageCount || 0 }} 条消息</div>
            </div>
          </div>
          <div v-if="convStore.conversations.length === 0" class="panel-empty">暂无历史对话</div>
        </div>
      </div>
    </div>
  </teleport>
</template>

<style scoped>
.panel-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.3); z-index: 300; display: flex; align-items: center; justify-content: center; }
.slash-panel { background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 12px; width: 480px; max-height: 420px; display: flex; flex-direction: column; }
.panel-header { display: flex; justify-content: space-between; align-items: center; padding: 14px 16px; border-bottom: 1px solid var(--el-border-color-light); font-weight: 600; font-size: 15px; }
.panel-body { overflow-y: auto; flex: 1; }
.panel-item { padding: 10px 16px; cursor: pointer; transition: background 0.1s; }
.panel-item:hover { background: var(--el-fill-color-light); }
.item-title { font-weight: 500; color: var(--el-text-color-primary); }
.item-meta { font-size: 12px; color: var(--el-text-color-placeholder); margin-top: 2px; }
.panel-empty { padding: 40px; text-align: center; color: var(--el-text-color-placeholder); }

html.dark .slash-panel { background: var(--el-bg-color); }
html.dark .panel-item:hover { background: var(--el-fill-color-light); }
</style>
