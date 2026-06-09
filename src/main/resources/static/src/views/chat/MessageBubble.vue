<script lang="ts" setup>
import { computed } from 'vue'
import { Icon } from '@iconify/vue'
import type { Message } from '@/types/chat'
import ThinkingBlock from './ThinkingBlock.vue'
import ToolCallCard from './ToolCallCard.vue'
import MarkdownIt from 'markdown-it'
import hljs from 'highlight.js'

const props = defineProps<{ message: Message; isLast: boolean }>()

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  highlight(str: string, lang: string) {
    if (lang && hljs.getLanguage(lang)) {
      try { return hljs.highlight(str, { language: lang }).value } catch {}
    }
    return ''
  }
})

const rendered = computed(() => props.message.role === 'assistant' ? md.render(props.message.content) : '')
const streaming = computed(() => props.isLast)
</script>

<template>
  <div class="msg-row" :class="message.role">
    <div v-if="message.role === 'user'" class="msg-bubble user-bubble">
      {{ message.content }}
    </div>
    <div v-else class="msg-bubble ai-bubble">
      <div class="ai-header">
        <div class="ai-avatar">
          <Icon icon="lucide:sparkles" />
        </div>
        <span class="ai-label">zephyr</span>
      </div>
      <div v-if="message.thinking" class="mb-2">
        <ThinkingBlock :content="message.thinking" :streaming="streaming && !message.content" />
      </div>
      <div v-for="tc in message.toolCalls" :key="tc.name" class="mb-2">
        <ToolCallCard :tool="tc" />
      </div>
      <div v-if="message.content" class="markdown-body" v-html="rendered"></div>
    </div>
  </div>
</template>

<style scoped>
.msg-row { padding: 14px 24px; max-width: 820px; margin: 0 auto; }
.msg-row.user { display: flex; justify-content: flex-end; }
.msg-bubble { max-width: 88%; border-radius: 12px; padding: 12px 16px; font-size: 15px; line-height: 1.65; }
.user-bubble { background: var(--el-fill-color-light); color: var(--el-text-color-primary); border-radius: 12px 12px 4px 12px; }
.ai-bubble { background: transparent; border-radius: 12px 12px 12px 4px; color: var(--el-text-color-regular); padding-top: 0; }
.mb-2 { margin-bottom: 8px; }

.ai-header { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; padding-top: 8px; }
.ai-avatar { width: 28px; height: 28px; border-radius: 8px; background: rgba(204,120,92,0.12); display: flex; align-items: center; justify-content: center; color: var(--el-color-primary); font-size: 14px; flex-shrink: 0; }
.ai-label { font-size: 13px; font-weight: 600; color: var(--el-text-color-primary); }

.markdown-body :deep(h3) { font-family: Georgia, 'Times New Roman', serif; font-weight: 400; font-size: 20px; letter-spacing: -0.3px; margin: 14px 0 6px; color: var(--el-text-color-primary); }
.markdown-body :deep(p) { margin: 6px 0; }
.markdown-body :deep(ul) { padding-left: 20px; margin: 6px 0; }
.markdown-body :deep(li) { margin: 3px 0; }
.markdown-body :deep(a) { color: var(--el-color-primary); }
.markdown-body :deep(code) { background: var(--el-fill-color); padding: 1px 6px; border-radius: 4px; font-family: 'JetBrains Mono', 'SF Mono', monospace; font-size: 13px; color: var(--el-color-primary-dark-2); }
.markdown-body :deep(pre) { background: #181715; color: #faf9f5; border-radius: 8px; padding: 16px; margin: 8px 0; overflow-x: auto; font-family: 'JetBrains Mono', 'SF Mono', monospace; font-size: 13px; line-height: 1.55; }
.markdown-body :deep(pre code) { background: transparent; color: inherit; padding: 0; border-radius: 0; font-size: inherit; }
</style>
