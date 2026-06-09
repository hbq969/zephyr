<script lang="ts" setup>
import { ref, watch } from 'vue'
import { Icon } from '@iconify/vue'
import axios from '@/network'
import type { SkillConfig } from '@/types/chat'

const props = defineProps<{ visible: boolean }>()
const emit = defineEmits<{ close: [] }>()

const skills = ref<SkillConfig[]>([])

watch(() => props.visible, (v) => {
  if (v) {
    axios({ url: '/skill/list', method: 'get' }).then(res => {
      if (res.data.state === 'OK') skills.value = res.data.body
    }).catch(() => {})
  }
})
</script>

<template>
  <teleport to="body">
    <div v-if="visible" class="panel-overlay" @click.self="emit('close')">
      <div class="slash-panel">
        <div class="panel-header">
          <span>可用技能</span>
          <el-button circle size="small" @click="emit('close')"><Icon icon="lucide:x" /></el-button>
        </div>
        <div class="panel-body">
          <div v-for="s in skills" :key="s.id || s.skillName" class="panel-item">
            <div>
              <div class="item-title">{{ s.displayName || s.skillName }}</div>
              <div class="item-meta">{{ s.description }}</div>
            </div>
            <span class="source-badge">{{ s.source }}</span>
          </div>
          <div v-if="skills.length === 0" class="panel-empty">暂无可用技能</div>
        </div>
      </div>
    </div>
  </teleport>
</template>

<style scoped>
.panel-overlay { position: fixed; inset: 0; background: rgba(0,0,0,0.3); z-index: 300; display: flex; align-items: center; justify-content: center; }
.slash-panel { background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 12px; width: 480px; max-height: 420px; display: flex; flex-direction: column; }
.panel-header { display: flex; justify-content: space-between; align-items: center; padding: 14px 16px; border-bottom: 1px solid var(--el-border-color-light); font-weight: 600; font-size: 15px; }
.panel-body { overflow-y: auto; flex: 1; }
.panel-item { display: flex; align-items: center; justify-content: space-between; padding: 10px 16px; }
.item-title { font-weight: 500; color: var(--el-text-color-primary); }
.item-meta { font-size: 12px; color: var(--el-text-color-placeholder); margin-top: 2px; }
.panel-empty { padding: 40px; text-align: center; color: var(--el-text-color-placeholder); }
.source-badge { font-size: 11px; background: var(--el-fill-color); color: var(--el-text-color-secondary); padding: 2px 8px; border-radius: 4px; flex-shrink: 0; }
</style>
