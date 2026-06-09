<script lang="ts" setup>
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Icon } from '@iconify/vue'
import axios from '@/network'
import type { MemoryItem } from '@/types/chat'

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ close: [] }>()
const router = useRouter()

const memories = ref<MemoryItem[]>([])

watch(() => props.visible, (v) => {
  if (v) {
    axios({ url: '/memory/list', method: 'get' }).then(res => {
      if (res.data.state === 'OK') memories.value = res.data.body
    }).catch(() => {})
  }
})

function goToMemory(name: string) {
  emit('close')
  router.push(`/settings/memory/edit?name=${encodeURIComponent(name)}`)
}
</script>

<template>
  <teleport to="body">
    <div v-if="visible" class="panel-overlay" @click.self="emit('close')">
      <div class="slash-panel">
        <div class="panel-header">
          <span>用户记忆</span>
          <el-button circle size="small" @click="emit('close')"><Icon icon="lucide:x" /></el-button>
        </div>
        <div class="panel-body">
          <div v-for="m in memories" :key="m.name" class="panel-item" @click="goToMemory(m.name)">
            <div>
              <div class="item-title">{{ m.name }}</div>
              <div class="item-meta">{{ m.description }}</div>
            </div>
            <span class="type-badge" :class="m.type">{{ m.type }}</span>
          </div>
          <div v-if="memories.length === 0" class="panel-empty">暂无用户记忆</div>
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
.panel-item { display: flex; align-items: center; justify-content: space-between; padding: 10px 16px; cursor: pointer; transition: background 0.1s; }
.panel-item:hover { background: var(--el-fill-color-light); }
.item-title { font-weight: 500; color: var(--el-text-color-primary); }
.item-meta { font-size: 12px; color: var(--el-text-color-placeholder); margin-top: 2px; }
.panel-empty { padding: 40px; text-align: center; color: var(--el-text-color-placeholder); }
.type-badge { font-size: 11px; padding: 2px 8px; border-radius: 4px; flex-shrink: 0; text-transform: capitalize; }
.type-badge.user { background: rgba(204,120,92,0.12); color: var(--el-color-primary); }
.type-badge.project { background: var(--el-color-info-light-9); color: var(--el-color-info); }
</style>
