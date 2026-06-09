<script lang="ts" setup>
import { ref, watch, nextTick } from 'vue'
import { useChatStore } from '@/store/chat'
import { useConversationsStore } from '@/store/conversations'
import MessageBubble from './MessageBubble.vue'

const chatStore = useChatStore()
const convStore = useConversationsStore()
const areaRef = ref<HTMLElement>()

function scrollToBottom() {
  nextTick(() => {
    if (areaRef.value) areaRef.value.scrollTop = areaRef.value.scrollHeight
  })
}

watch(
  () => {
    const msgs = chatStore.messages
    if (msgs.length === 0) return ''
    const last = msgs[msgs.length - 1]
    return `${msgs.length}-${last.content.length}-${(last.thinking || '').length}`
  },
  scrollToBottom
)
</script>

<template>
  <div ref="areaRef" class="chat-area">
    <div v-if="chatStore.messages.length === 0" class="empty-state">
      <div class="empty-icon">z</div>
      <p class="empty-title">zephyr</p>
      <p class="empty-sub">开始一段新的对话</p>
    </div>
    <MessageBubble v-for="(msg, i) in chatStore.messages" :key="msg.id" :message="msg" :isLast="i === chatStore.messages.length - 1 && chatStore.streaming" />
  </div>
</template>

<style scoped>
.chat-area { flex: 1; overflow-y: auto; padding: 20px 0; }
.chat-area::-webkit-scrollbar { width: 1px; }
.chat-area::-webkit-scrollbar-thumb { background: var(--el-border-color); border-radius: 1px; }
.empty-state { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; color: var(--el-text-color-secondary); }
.empty-icon { width: 48px; height: 48px; border-radius: 12px; background: rgba(204,120,92,0.12); display: flex; align-items: center; justify-content: center; font-family: Georgia, serif; font-size: 24px; color: var(--el-color-primary); margin-bottom: 12px; }
.empty-title { font-family: Georgia, 'Times New Roman', serif; font-size: 22px; letter-spacing: -0.3px; color: var(--el-text-color-primary); margin-bottom: 4px; }
.empty-sub { font-size: 14px; color: var(--el-text-color-placeholder); }
</style>
