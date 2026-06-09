import { defineStore } from 'pinia'
import { ref } from 'vue'
import type { Message } from '@/types/chat'

export const useChatStore = defineStore('chat', () => {
  const messages = ref<Message[]>([])
  const streaming = ref(false)
  const currentThinking = ref('')

  function addMessage(msg: Message) {
    messages.value.push(msg)
  }

  function appendToken(token: string) {
    const last = messages.value[messages.value.length - 1]
    if (last && last.role === 'assistant') {
      last.content += token
    }
  }

  function setThinking(text: string) {
    currentThinking.value = text
  }

  function updateLastThinking(text: string) {
    const last = messages.value[messages.value.length - 1]
    if (last && last.role === 'assistant') {
      last.thinking = (last.thinking || '') + text
    }
  }

  function clearMessages() {
    messages.value = []
    currentThinking.value = ''
  }

  return { messages, streaming, currentThinking, addMessage, appendToken, setThinking, updateLastThinking, clearMessages }
})
