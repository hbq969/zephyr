<script lang="ts" setup>
import { ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import axios from '@/network'
import type { McpServer } from '@/types/chat'

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ close: [] }>()

const servers = ref<McpServer[]>([])

watch(() => props.visible, (v) => {
  if (v) {
    axios({ url: '/mcp/server/list', method: 'get' }).then(res => {
      if (res.data.state === 'OK') servers.value = res.data.body
    }).catch(() => {})
  }
})
</script>

<template>
  <teleport to="body">
    <div v-if="visible" class="panel-overlay" @click.self="emit('close')">
      <div class="slash-panel">
        <div class="panel-header">
          <span>MCP 工具列表</span>
          <el-button circle size="small" @click="emit('close')"><Icon icon="lucide:x" /></el-button>
        </div>
        <div class="panel-body">
          <div v-for="s in servers" :key="s.id" class="panel-item">
            <div>
              <div class="item-title">{{ s.name }}</div>
              <div class="item-meta">{{ s.transport }} · {{ s.status }}</div>
            </div>
            <span class="status-dot" :class="s.status === 'connected' ? 'online' : 'offline'"></span>
          </div>
          <div v-if="servers.length === 0" class="panel-empty">暂无 MCP 服务器</div>
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
.panel-item { display: flex; align-items: center; justify-content: space-between; padding: 10px 16px; }
.item-title { font-weight: 500; }
.item-meta { font-size: 12px; color: var(--el-text-color-placeholder); margin-top: 2px; }
.panel-empty { padding: 40px; text-align: center; color: var(--el-text-color-placeholder); }
.status-dot { width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.status-dot.online { background: var(--el-color-success); }
.status-dot.offline { background: var(--el-color-danger); }
</style>
