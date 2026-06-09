<script lang="ts" setup>
import { onMounted } from 'vue'
import { useSettingsStore } from '@/store/settings'
import { Icon } from '@iconify/vue'

const settingsStore = useSettingsStore()

onMounted(() => { settingsStore.loadModels(); settingsStore.loadMcpToolCount() })
</script>

<template>
  <div class="status-bar">
    <div class="status-item">
      <Icon icon="lucide:bot" class="s-icon" />
      <span class="s-label">模型</span>
      <span>{{ settingsStore.models.length ? settingsStore.currentModel : '无' }}</span>
    </div>

    <div class="status-item">
      <Icon icon="lucide:plug" class="s-icon" />
      <span class="s-label">MCP</span>
      <span>{{ settingsStore.mcpToolCount > 0 ? settingsStore.mcpToolCount + ' 个' : '无' }}</span>
    </div>

    <div class="status-item">
      <Icon icon="lucide:puzzle" class="s-icon" />
      <span class="s-label">Skills</span>
      <span>{{ settingsStore.skills.filter(s => s.enabled).length }} 个</span>
    </div>

    <span class="spacer"></span>

    <div class="ctx-group">
      <span class="s-label">上下文</span>
      <div class="ctx-bar-wrap">
        <div class="ctx-bar">
          <div class="ctx-fill" :class="settingsStore.contextPercent > 80 ? 'warn' : 'ok'" :style="{ width: settingsStore.contextPercent + '%' }"></div>
        </div>
        <span class="ctx-text">{{ (settingsStore.contextUsed / 1024).toFixed(1) }}K / {{ (settingsStore.contextTotal / 1024).toFixed(0) }}K</span>
        <span class="ctx-text ctx-pct">{{ settingsStore.contextPercent }}%</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.status-bar { padding: 8px 16px; display: flex; align-items: center; gap: 2px; font-size: 12px; color: var(--el-text-color-placeholder); border-top: 1px solid var(--el-border-color); background: var(--el-bg-color); }
.status-item { display: flex; align-items: center; gap: 5px; padding: 4px 10px; border-radius: 6px; white-space: nowrap; color: var(--el-text-color-secondary); }
.s-icon { font-size: 14px; flex-shrink: 0; }
.s-label { font-size: 11px; color: var(--el-text-color-placeholder); margin-right: 2px; }
.spacer { flex: 1; }
.ctx-group { display: flex; align-items: center; gap: 8px; margin-left: auto; }
.ctx-bar-wrap { display: flex; align-items: center; gap: 6px; }
.ctx-bar { width: 100px; height: 5px; border-radius: 3px; background: var(--el-border-color); overflow: hidden; }
.ctx-fill { height: 100%; border-radius: 3px; transition: width 0.3s; }
.ctx-fill.ok { background: var(--el-color-primary); }
.ctx-fill.warn { background: var(--el-color-danger); }
.ctx-text { font-size: 11px; color: var(--el-text-color-placeholder); white-space: nowrap; }
.ctx-pct { color: var(--el-text-color-secondary); }
</style>
