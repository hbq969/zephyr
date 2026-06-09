<script lang="ts" setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { Icon } from '@iconify/vue'

interface Command {
  cmd: string
  desc: string
  badge?: string
  group: string
}

const props = defineProps<{
  visible: boolean
  models: Array<{ name: string; id?: string }>
  mcpCount: number
  skillCount: number
  memoryCount: number
}>()

const emit = defineEmits<{
  close: []
  select: [cmd: string]
}>()

const search = ref('')
const activeIdx = ref(0)

const allCommands: Command[] = [
  { cmd: '/model', desc: '切换对话模型', group: '模型' },
  { cmd: '/mcp', desc: 'MCP 工具列表', badge: props.mcpCount > 0 ? `${props.mcpCount} 个` : '', group: '能力' },
  { cmd: '/skills', desc: '可用技能', badge: props.skillCount > 0 ? `${props.skillCount} 个` : '', group: '能力' },
  { cmd: '/memory', desc: '用户记忆', badge: props.memoryCount > 0 ? `${props.memoryCount} 条` : '', group: '能力' },
  { cmd: '/resume', desc: '恢复之前的对话', group: '会话' },
  { cmd: '/context', desc: '上下文占比', group: '会话' },
  { cmd: '/clear', desc: '清空当前对话', group: '操作' },
  { cmd: '/help', desc: '查看帮助', group: '操作' },
]

const filtered = computed(() => {
  const q = search.value.toLowerCase()
  if (!q) return allCommands
  return allCommands.filter(c => c.cmd.includes(q) || c.desc.includes(q))
})

const groups = computed(() => {
  const map = new Map<string, Command[]>()
  for (const c of filtered.value) {
    const arr = map.get(c.group) || []
    arr.push(c)
    map.set(c.group, arr)
  }
  return Array.from(map.entries())
})

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'ArrowDown') { e.preventDefault(); activeIdx.value = Math.min(activeIdx.value + 1, filtered.value.length - 1) }
  else if (e.key === 'ArrowUp') { e.preventDefault(); activeIdx.value = Math.max(activeIdx.value - 1, 0) }
  else if (e.key === 'Enter') { e.preventDefault(); const f = filtered.value[activeIdx.value]; if (f) emit('select', f.cmd) }
  else if (e.key === 'Escape') { emit('close') }
}

onMounted(() => { window.addEventListener('keydown', onKeydown, true) })
onUnmounted(() => { window.removeEventListener('keydown', onKeydown, true) })
</script>

<template>
  <div v-if="visible" class="slash-menu">
    <div class="slash-search">
      <Icon icon="lucide:search" class="search-icon" />
      <input v-model="search" placeholder="搜索命令..." autofocus />
    </div>
    <div v-for="[group, cmds] in groups" :key="group" class="slash-group">
      <div class="slash-group-label">{{ group }}</div>
      <div v-for="(c, i) in cmds" :key="c.cmd" class="slash-item" :class="{ active: allCommands.indexOf(c) === activeIdx }" @click="emit('select', c.cmd)">
        <span class="cmd-text">{{ c.cmd }}</span>
        <span class="desc-text">{{ c.desc }}</span>
        <span class="badge-text" v-if="c.badge">{{ c.badge }}</span>
        <span v-else class="arrow-icon">›</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.slash-menu {
  position: absolute; bottom: calc(100% + 8px); left: 0; right: 0;
  background: var(--el-bg-color); border: 1px solid var(--el-border-color);
  border-radius: 12px; box-shadow: 0 8px 32px rgba(0,0,0,0.12);
  overflow: hidden; z-index: 200; max-height: 360px; overflow-y: auto;
}
.slash-search { display: flex; align-items: center; gap: 8px; padding: 10px 14px; border-bottom: 1px solid var(--el-border-color-light); }
.slash-search input { flex: 1; border: none; background: transparent; color: var(--el-text-color-primary); font-size: 14px; outline: none; }
.search-icon { color: var(--el-text-color-placeholder); font-size: 16px; flex-shrink: 0; }
.slash-group { padding: 4px 0; }
.slash-group + .slash-group { border-top: 1px solid var(--el-border-color-light); }
.slash-group-label { font-size: 11px; color: var(--el-text-color-placeholder); padding: 6px 14px 2px; text-transform: uppercase; letter-spacing: 0.5px; }
.slash-item { display: flex; align-items: center; gap: 8px; padding: 8px 14px; cursor: pointer; transition: background 0.1s; font-size: 14px; }
.slash-item:hover, .slash-item.active { background: var(--el-fill-color-light); }
.cmd-text { font-weight: 600; color: var(--el-color-primary); min-width: 70px; }
.desc-text { color: var(--el-text-color-secondary); flex: 1; }
.badge-text { font-size: 12px; background: var(--el-fill-color); color: var(--el-text-color-secondary); padding: 1px 6px; border-radius: 4px; }
.arrow-icon { color: var(--el-text-color-placeholder); font-size: 14px; flex-shrink: 0; }

html.dark .slash-menu { background: var(--el-bg-color); border-color: var(--el-border-color); }
html.dark .slash-item:hover, html.dark .slash-item.active { background: var(--el-fill-color-light); }
</style>
