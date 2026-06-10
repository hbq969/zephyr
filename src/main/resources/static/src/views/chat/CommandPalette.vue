<script lang="ts" setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { Icon } from '@iconify/vue'
import { getLangData } from '@/i18n/locale'

interface CmdItem { icon: string; name: string; desc: string; group: string }

const langData = getLangData()
const commands = computed(() => [
  { icon: 'lucide:help-circle', name: '/help', desc: langData.cmd_helpInfo, group: langData.cmd_groupGeneral },
  { icon: 'lucide:brain', name: '/memory', desc: langData.cmd_memoryMgmt, group: langData.cmd_groupGeneral },
  { icon: 'lucide:cpu', name: '/model', desc: langData.cmd_switchModel, group: langData.cmd_groupGeneral },
  { icon: 'lucide:globe', name: '/search', desc: langData.cmd_webSearch, group: langData.cmd_groupGeneral },
  { icon: 'lucide:file-plus', name: '/new', desc: langData.cmd_newSession, group: langData.cmd_groupSession },
  { icon: 'lucide:download', name: '/export', desc: langData.cmd_exportMd, group: langData.cmd_groupSession },
  { icon: 'lucide:minimize-2', name: '/compact', desc: langData.cmd_compactCtx, group: langData.cmd_groupSession },
])

const filter = ref('')
const activeIdx = ref(0)
const visible = ref(false)

const filteredCommands = computed(() => {
  if (!filter.value) return commands.value
  return commands.value.filter(c => c.name.includes(filter.value) || c.desc.includes(filter.value))
})

const grouped = computed(() => {
  const groups: Map<string, CmdItem[]> = new Map()
  for (const cmd of filteredCommands.value) {
    if (!groups.has(cmd.group)) groups.set(cmd.group, [])
    groups.get(cmd.group)!.push(cmd)
  }
  return groups
})

function onKeydown(e: KeyboardEvent) {
  if (!visible.value) return
  if (e.key === 'Escape') { close(); return }
  if (e.key === 'ArrowDown') { e.preventDefault(); activeIdx.value = Math.min(activeIdx.value + 1, filteredCommands.value.length - 1) }
  if (e.key === 'ArrowUp') { e.preventDefault(); activeIdx.value = Math.max(activeIdx.value - 1, 0) }
  if (e.key === 'Enter') { e.preventDefault(); execute(filteredCommands.value[activeIdx.value]) }
}

function open(initialFilter: string) {
  filter.value = initialFilter
  activeIdx.value = 0
  visible.value = true
}

function close() {
  visible.value = false
  filter.value = ''
}

function execute(cmd: CmdItem | undefined) {
  if (!cmd) return
  if (cmd.name === '/new') { location.reload() }
  close()
}

function isActive(idx: number) { return idx === activeIdx.value }

onMounted(() => document.addEventListener('keydown', onKeydown))
onUnmounted(() => document.removeEventListener('keydown', onKeydown))

defineExpose({ open, close, visible, onKeydown })
</script>

<template>
  <Teleport to="body">
    <div v-if="visible" class="cmd-overlay" @click="close"></div>
    <div v-if="visible" class="cmd-palette">
      <template v-for="[group, items] in grouped" :key="group">
        <div class="cmd-group-label">{{ group }}</div>
        <div
          v-for="(item, idx) in items"
          :key="item.name"
          class="cmd-item"
          :class="{ active: isActive(filteredCommands.indexOf(item)) }"
          @click="execute(item)"
        >
          <Icon :icon="item.icon" class="cmd-icon" />
          <span class="cmd-name">{{ item.name }}</span>
          <span class="cmd-desc">{{ item.desc }}</span>
        </div>
      </template>
    </div>
  </Teleport>
</template>

<style scoped>
.cmd-overlay { position: fixed; inset: 0; z-index: 999; }
.cmd-palette { position: fixed; bottom: 140px; left: 50%; transform: translateX(-50%); width: 520px; max-width: 90vw; background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 12px; overflow: hidden; box-shadow: 0 12px 40px rgba(0,0,0,0.12); z-index: 1000; }
.cmd-group-label { padding: 8px 14px 2px; font-size: 11px; color: var(--el-text-color-placeholder); text-transform: uppercase; letter-spacing: 1px; font-weight: 500; }
.cmd-item { display: flex; align-items: center; gap: 10px; padding: 9px 14px; cursor: pointer; font-size: 14px; color: var(--el-text-color-primary); transition: background 0.1s; }
.cmd-item:hover, .cmd-item.active { background: rgba(204,120,92,0.12); }
.cmd-icon { color: var(--el-text-color-secondary); font-size: 17px; width: 20px; text-align: center; flex-shrink: 0; }
.cmd-name { font-weight: 500; }
.cmd-desc { margin-left: auto; font-size: 12px; color: var(--el-text-color-placeholder); }
</style>
