<script lang="ts" setup>
import { ref } from 'vue'
import { Icon } from '@iconify/vue'

defineProps<{ content: string }>()
const open = ref(true)
</script>

<template>
  <div class="thinking-block" :class="{ open }">
    <div class="thinking-header" @click="open = !open">
      <Icon icon="svg-spinners:3-dots-scale" class="thinking-icon" />
      <span>推理过程</span>
      <Icon :icon="open ? 'lucide:chevron-up' : 'lucide:chevron-down'" class="chevron" />
    </div>
    <div v-if="content" class="thinking-body">{{ content }}</div>
  </div>
</template>

<style scoped>
.thinking-block { border: 1px solid var(--el-border-color); border-radius: 8px; margin: 8px 0; overflow: hidden; }
.thinking-header { display: flex; align-items: center; gap: 8px; padding: 10px 14px; background: var(--el-fill-color-light); cursor: pointer; font-size: 13px; color: var(--el-text-color-secondary); user-select: none; transition: background 0.15s; }
.thinking-header:hover { background: var(--el-fill-color); }
.thinking-icon { color: var(--el-color-primary); font-size: 16px; animation: pulse 1.5s ease-in-out infinite; }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.4; } }
.chevron { margin-left: auto; transition: transform 0.2s; font-size: 14px; }
.thinking-block.open .chevron { transform: rotate(180deg); }
.thinking-body { display: none; padding: 12px 14px; font-size: 13px; color: var(--el-text-color-secondary); background: var(--el-fill-color-light); border-top: 1px solid var(--el-border-color); line-height: 1.6; max-height: 200px; overflow-y: auto; }
.thinking-block.open .thinking-body { display: block; }
</style>
