<script lang="ts" setup>
import { onMounted, ref, watch } from 'vue'
import { useConversationsStore } from '@/store/conversations'
import { useChatStore } from '@/store/chat'
import { useSettingsStore } from '@/store/settings'
import ChatSidebar from './ChatSidebar.vue'
import ChatArea from './ChatArea.vue'
import InputArea from './InputArea.vue'
import StatusBar from './StatusBar.vue'
import CommandPalette from './CommandPalette.vue'
import SettingsPanel from './SettingsPanel.vue'
import { Icon } from '@iconify/vue'
import { getLangData } from '@/i18n/locale'
import axios from '@/network'

const convStore = useConversationsStore()
const chatStore = useChatStore()
const settingsStore = useSettingsStore()
const showSettings = ref(false)
const langData = getLangData()
let abortController: AbortController | null = null
let msgIdCounter = 0
function nextMsgId() { return 'm' + (++msgIdCounter) }

function refreshConversationList() {
  axios({ url: '/conversations/list', method: 'get' })
    .then(res => {
      if (res.data.state === 'OK') convStore.setConversations(res.data.body)
    })
}

function newChat() {
  chatStore.clearMessages()
  convStore.currentId = null
}

function onSend(text: string) {
  // 本地命令拦截 — 不发送到后端
  if (text === '/clear') {
    chatStore.clearMessages()
    convStore.currentId = null
    return
  }
  if (text === '/context') {
    axios({ url: '/chat/context-usage', method: 'get', params: { conversationId: convStore.currentId } })
      .then(res => {
        if (res.data.state === 'OK') {
          const info = res.data.body
          chatStore.addMessage({ id: nextMsgId(), role: 'system', content: langData.context_usageInfo + JSON.stringify(info, null, 2), timestamp: Date.now() / 1000 })
        }
      })
    return
  }
  if (text === '/help') {
    chatStore.addMessage({ id: nextMsgId(), role: 'system', content: langData.context_helpTitle, timestamp: Date.now() / 1000 })
    return
  }

  chatStore.startSession()
  if (abortController) abortController.abort()
  abortController = new AbortController()

  chatStore.addMessage({ id: nextMsgId(), role: 'user', content: text, timestamp: Date.now() / 1000 })
  chatStore.addMessage({ id: nextMsgId(), role: 'assistant', content: '', timestamp: Date.now() / 1000 })
  chatStore.streaming = true

  let lastPos = 0
  axios({
    url: `/chat/send`,
    method: 'post',
    data: { conversationId: convStore.currentId, message: text },
    responseType: 'text',
    signal: abortController.signal,
    onDownloadProgress(evt: any) {
      const raw = evt.event?.target?.responseText || evt.currentTarget?.responseText || ''
      const newData = raw.substring(lastPos)
      lastPos = raw.length
      const lines = newData.split('\n')
      for (const line of lines) {
        if (!line.startsWith('data:')) continue
        try {
          const event = JSON.parse(line.substring(5).trim())
          if (event.type === 'token') {
            chatStore.appendToken(event.content)
          } else if (event.type === 'thinking') {
            chatStore.updateLastThinking(event.content)
          } else if (event.type === 'meta') {
            convStore.currentId = event.content
            refreshConversationList()
          } else if (event.type === 'clear') {
            chatStore.clearMessages()
            convStore.currentId = null
            chatStore.streaming = false
          } else if (event.type === 'done') {
            chatStore.pruneEmptyAssistant()
            refreshConversationList()
            settingsStore.loadContextUsage(convStore.currentId)
            chatStore.streaming = false
          } else if (event.type === 'error') {
            chatStore.appendToken('\n\n' + langData.context_errorPrefix + (event.content || langData.context_requestFailed))
            chatStore.streaming = false
          }
        } catch (_) {}
      }
    }
  }).catch((err: any) => {
    if (err?.code !== 'ERR_CANCELED' && err?.name !== 'AbortError' && err?.name !== 'CanceledError') {
      chatStore.appendToken('\n\n' + langData.context_requestFailed)
    }
    chatStore.streaming = false
  })
}

function onStop() {
  if (abortController) { abortController.abort(); abortController = null }
  axios({ url: '/chat/cancel', method: 'post' }).catch(() => {})
  chatStore.streaming = false
}

function restoreConversation(id: string) {
  axios({ url: `/conversations/${id}/messages`, method: 'get' })
    .then(res => {
      if (res.data.state === 'OK') {
        const msgs = (res.data.body || []).map((m: any) => ({
          id: m.id,
          role: m.role,
          content: m.content || '',
          thinking: m.thinking || '',
          toolCalls: m.toolCalls || [],
          timestamp: m.timestamp
        }))
        // 合并连续 assistant 消息的 thinking 和 content 到最后一条
        // 工具调用循环每轮产生一个 assistant 消息，但流式期间前端只用了一个 assistant 气泡
        const merged: any[] = []
        for (let i = 0; i < msgs.length; i++) {
          if (msgs[i].role === 'assistant') {
            const group: any[] = []
            while (i < msgs.length && msgs[i].role === 'assistant') {
              group.push(msgs[i])
              i++
            }
            i--
            const thinkingParts: string[] = []
            const contentParts: string[] = []
            for (const m of group) {
              if (m.thinking) thinkingParts.push(m.thinking)
              if (m.content) contentParts.push(m.content)
            }
            const last = group[group.length - 1]
            last.thinking = thinkingParts.join('\n')
            last.content = contentParts.join('\n')
            merged.push(last)
          } else {
            merged.push(msgs[i])
          }
        }
        msgs.length = 0
        msgs.push(...merged)
        chatStore.clearMessages()
        msgs.forEach((m: any) => chatStore.addMessage(m))
      }
    })
}

watch(() => convStore.currentId, (newId) => {
  if (newId && !chatStore.streaming) restoreConversation(newId)
})

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
    <ChatSidebar @open-settings="showSettings = true" @new-chat="newChat" />
    <div class="main-area">
      <div class="top-toolbar" :class="{ show: convStore.sidebarCollapsed }">
        <button class="tb-btn" @click="convStore.toggleSidebar()" :title="langData.chatSidebar_expandTooltip">
          <Icon icon="lucide:panel-left-open" />
        </button>
        <span class="tb-logo" @click="convStore.toggleSidebar()">zephyr</span>
        <span class="tb-divider"></span>
        <button class="tb-btn" :title="langData.chatSidebar_newChat" @click="newChat">
          <Icon icon="lucide:square-pen" />
        </button>
        <button class="tb-btn" :title="langData.chatSidebar_searchConv">
          <Icon icon="lucide:search" />
        </button>
        <button class="tb-btn" :title="langData.chatSidebar_historyConv">
          <Icon icon="lucide:history" />
        </button>
      </div>
      <ChatArea />
      <CommandPalette />
      <InputArea @send="onSend" @stop="onStop" />
      <StatusBar />
    </div>
    <SettingsPanel :visible="showSettings" @close="showSettings = false" />
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
