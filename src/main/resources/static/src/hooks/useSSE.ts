import { ref } from 'vue'
import type { ChatEvent } from '@/types/chat'

export function useSSE() {
  const isStreaming = ref(false)
  const abortController = ref<AbortController | null>(null)

  async function sendMessage(
    message: string,
    conversationId: string,
    onEvent: (event: ChatEvent) => void,
    onDone: () => void,
    onError: (err: Error) => void
  ) {
    isStreaming.value = true
    const controller = new AbortController()
    abortController.value = controller

    try {
      const baseUrl = import.meta.env.VITE_API_URL || ''
      const resp = await fetch(`${baseUrl}/chat/send`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ message, conversationId }),
        signal: controller.signal
      })

      if (!resp.ok || !resp.body) {
        throw new Error(`HTTP ${resp.status}`)
      }

      const reader = resp.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await reader.read()
        if (done) break

        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() ?? ''

        for (const line of lines) {
          if (line.startsWith('data: ')) {
            const data = line.slice(6).trim()
            if (data === '[DONE]') {
              onDone()
              return
            }
            try {
              const event: ChatEvent = JSON.parse(data)
              onEvent(event)
            } catch {
              // skip malformed JSON lines
            }
          }
        }
      }
      onDone()
    } catch (err: unknown) {
      if (err instanceof Error && err.name !== 'AbortError') {
        onError(err)
      }
    } finally {
      isStreaming.value = false
    }
  }

  function abort() {
    abortController.value?.abort()
    isStreaming.value = false
  }

  return { isStreaming, sendMessage, abort }
}
