<script lang="ts" setup>
import { ref, watch, computed, onMounted, onBeforeUnmount } from 'vue'
import { Icon } from '@iconify/vue'

const props = defineProps<{
  content: string
  animating: boolean   // 响应是否仍在生成中（控制动效和文字）
}>()

const collapsed = ref(true)
watch(() => props.animating, (v) => { if (v) collapsed.value = false })

const hasContent = computed(() => props.content && props.content.length > 0)

let rafId = 0

function stopEffect() {
  cancelAnimationFrame(rafId)
  rafId = 0
}

watch(() => props.animating, (v) => { if (!v) stopEffect() })

onMounted(() => { if (!props.animating) stopEffect() })
onBeforeUnmount(stopEffect)
</script>

<template>
  <div class="thinking-block" :class="{ collapsed, animating }">
    <div class="thinking-header" @click="collapsed = !collapsed">
      <span class="thinking-text">
        <template v-if="animating">
          思考中<span class="dot-anim"><i>.</i><i>.</i><i>.</i></span>
        </template>
        <template v-else>
          <Icon icon="lucide:brain" class="brain-icon" /> 已深度思考
        </template>
      </span>
      <Icon :icon="collapsed ? 'lucide:chevron-down' : 'lucide:chevron-up'" class="chevron" />
    </div>
    <div v-if="hasContent && !collapsed" class="thinking-body">{{ content }}</div>
  </div>
</template>

<style scoped>
.thinking-block { margin: 8px 0; }
.thinking-header { display: flex; align-items: center; gap: 6px; padding: 6px 0; cursor: pointer; font-size: 13px; color: var(--el-text-color-secondary); user-select: none; }
.thinking-header:hover { color: var(--el-text-color-primary); }

.thinking-text { display: inline-flex; align-items: center; }
.brain-icon { color: var(--el-color-primary); font-size: 16px; flex-shrink: 0; margin-right: 4px; }

.chevron { transition: transform 0.2s; font-size: 14px; color: var(--el-text-color-placeholder); flex-shrink: 0; margin-left: 4px; }
.thinking-body { padding: 8px 0 8px 22px; font-size: 13px; color: var(--el-text-color-placeholder); line-height: 1.65; border-left: 2px solid var(--el-border-color); margin-left: 7px; white-space: pre-wrap; word-break: break-word; }
</style>

<!-- @keyframes 放在非 scoped 块，避免 Vue 3 的 scoped 哈希重命名问题 -->
<style>
.dot-anim i {
  font-style: normal;
  animation: dotBounce 1.4s ease-in-out infinite;
}
.dot-anim i:nth-child(1) { animation-delay: 0s; }
.dot-anim i:nth-child(2) { animation-delay: 0.2s; }
.dot-anim i:nth-child(3) { animation-delay: 0.4s; }
@keyframes dotBounce {
  0%, 80%, 100% { opacity: 0; transform: translateY(0); }
  40% { opacity: 1; transform: translateY(-2px); }
}
</style>
