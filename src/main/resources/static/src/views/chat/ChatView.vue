<script lang="ts" setup>
import { onMounted, ref } from 'vue'
import { useConversationsStore } from '@/store/conversations'
import { useChatStore } from '@/store/chat'
import { useSettingsStore } from '@/store/settings'
import ChatSidebar from './ChatSidebar.vue'
import ChatArea from './ChatArea.vue'
import InputArea from './InputArea.vue'
import StatusBar from './StatusBar.vue'
import CommandPalette from './CommandPalette.vue'
import SettingsPanel from './SettingsPanel.vue'
import ResumePanel from './ResumePanel.vue'
import ContextPanel from './ContextPanel.vue'
import McpListPanel from './McpListPanel.vue'
import SkillsListPanel from './SkillsListPanel.vue'
import MemoryListPanel from './MemoryListPanel.vue'
import { Icon } from '@iconify/vue'
import axios from '@/network'

const convStore = useConversationsStore()
const chatStore = useChatStore()
const settingsStore = useSettingsStore()
const showSettings = ref(false)
const showResume = ref(false)
const showContext = ref(false)
const showMcpList = ref(false)
const showSkillsList = ref(false)
const showMemoryList = ref(false)

function onSlashCommand(cmd: string) {
  switch (cmd) {
    case 'mcp': showMcpList.value = true; break
    case 'skills': showSkillsList.value = true; break
    case 'memory': showMemoryList.value = true; break
    case 'resume': showResume.value = true; break
    case 'context': showContext.value = true; break
    case 'help': break
  }
}

onMounted(() => {
  axios({ url: '/conversations/list', method: 'get' })
    .then(res => {
      if (res.data.state === 'OK') convStore.setConversations(res.data.body)
    })
  settingsStore.loadModels()
  settingsStore.loadMcpServers()
})
</script>

<template>
  <div class="app-layout">
    <ChatSidebar @open-settings="showSettings = true" />
    <div class="main-area">
      <div class="top-toolbar" :class="{ show: convStore.sidebarCollapsed }">
        <button class="tb-btn" @click="convStore.toggleSidebar()" title="展开侧边栏">
          <Icon icon="lucide:panel-left-open" />
        </button>
        <span class="tb-logo" @click="convStore.toggleSidebar()">zephyr</span>
        <span class="tb-divider"></span>
        <button class="tb-btn" title="新对话">
          <Icon icon="lucide:square-pen" />
        </button>
        <button class="tb-btn" title="搜索会话">
          <Icon icon="lucide:search" />
        </button>
        <button class="tb-btn" title="历史会话">
          <Icon icon="lucide:history" />
        </button>
      </div>
      <ChatArea />
      <CommandPalette />
      <InputArea @slash-command="onSlashCommand" />
      <StatusBar />
    </div>
    <SettingsPanel :visible="showSettings" @close="showSettings = false" />
    <ResumePanel :visible="showResume" @close="showResume = false" />
    <ContextPanel :visible="showContext" @close="showContext = false" />
    <McpListPanel :visible="showMcpList" @close="showMcpList = false" />
    <SkillsListPanel :visible="showSkillsList" @close="showSkillsList = false" />
    <MemoryListPanel :visible="showMemoryList" @close="showMemoryList = false" />
  </div>
</template>

<style scoped>
.app-layout { display: flex; height: 100vh; }
.main-area { flex: 1; display: flex; flex-direction: column; min-width: 0; position: relative; background: var(--el-bg-color); }

.top-toolbar { display: none; align-items: center; gap: 4px; position: absolute; top: 0; left: 0; z-index: 30; padding: 10px 16px; }
.top-toolbar.show { display: flex; }
.tb-btn { width: 34px; height: 34px; border-radius: 50%; border: none; background: transparent; color: var(--el-text-color-secondary); cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 18px; flex-shrink: 0; transition: background 0.15s, color 0.15s; }
.tb-btn:hover { background: var(--el-fill-color-light); color: var(--el-text-color-primary); }
.tb-logo { font-family: Georgia, 'Times New Roman', serif; font-size: 18px; color: var(--el-text-color-primary); letter-spacing: -0.3px; margin-right: 10px; cursor: pointer; white-space: nowrap; }
.tb-divider { width: 14px; height: 1px; background: var(--el-border-color); margin: 0 6px; transform: rotate(90deg); flex-shrink: 0; }
</style>
