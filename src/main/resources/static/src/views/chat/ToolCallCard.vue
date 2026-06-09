<script lang="ts" setup>
import { Icon } from '@iconify/vue'
import type { ToolCall } from '@/types/chat'

defineProps<{ tool: ToolCall }>()
</script>

<template>
  <div class="tool-card">
    <div class="tool-header">
      <Icon icon="lucide:wrench" class="tool-icon" />
      <span>{{ tool.name }}</span>
      <span class="tool-status" :class="tool.status">
        <Icon v-if="tool.status === 'success'" icon="lucide:check-circle" style="font-size:11px" />
        <Icon v-else-if="tool.status === 'error'" icon="lucide:x-circle" style="font-size:11px" />
        <Icon v-else icon="lucide:loader-circle" style="font-size:11px" />
        {{ tool.status === 'success' ? '成功' : tool.status === 'error' ? '失败' : '运行中' }}
      </span>
    </div>
    <div v-if="tool.output" class="tool-body">{{ tool.output }}</div>
  </div>
</template>

<style scoped>
.tool-card { border: 1px solid var(--el-border-color); border-radius: 8px; margin: 8px 0; overflow: hidden; }
.tool-header { display: flex; align-items: center; gap: 8px; padding: 9px 14px; background: var(--el-fill-color); font-size: 13px; color: var(--el-text-color-regular); font-weight: 500; }
.tool-icon { color: var(--el-text-color-secondary); font-size: 15px; }
.tool-status { margin-left: auto; font-size: 11px; padding: 1px 8px; border-radius: 99px; display: flex; align-items: center; gap: 3px; }
.tool-status.success { background: rgba(93,184,114,0.12); color: var(--el-color-success); }
.tool-status.error { background: rgba(198,69,69,0.12); color: var(--el-color-danger); }
.tool-status.running { background: rgba(204,120,92,0.12); color: var(--el-color-primary); }
.tool-body { padding: 10px 14px; background: var(--el-bg-color); font-family: 'JetBrains Mono', 'SF Mono', monospace; font-size: 12px; color: var(--el-text-color-secondary); max-height: 120px; overflow-y: auto; border-top: 1px solid var(--el-border-color); white-space: pre-wrap; }
</style>
