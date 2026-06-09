<script lang="ts" setup>
import { Icon } from '@iconify/vue'

interface MemoryItem { name: string; type: string; updatedAt: string }
const memories: MemoryItem[] = [
  { name: '用户偏好：简洁回复风格', type: 'user', updatedAt: '2026-06-08' },
  { name: '项目使用 Spring Boot 3.5.4', type: 'project', updatedAt: '2026-06-07' },
  { name: '禁止使用自定义颜色值', type: 'feedback', updatedAt: '2026-06-05' },
  { name: 'Pipeline 问题跟踪在 Linear INGEST', type: 'reference', updatedAt: '2026-06-03' },
]

const typeLabel: Record<string, string> = { user: '用户', project: '项目', feedback: '反馈', reference: '参考' }
const typeColor: Record<string, string> = { user: 'var(--el-color-primary)', project: 'var(--el-color-success)', feedback: 'var(--el-color-warning)', reference: 'var(--el-text-color-secondary)' }
</script>

<template>
  <div class="settings-page">
    <div class="page-header">
      <button class="back-btn" @click="$router.push('/chat')"><Icon icon="lucide:chevron-left" /></button>
      <h2>记忆管理</h2>
    </div>
    <div class="page-body">
      <div v-for="m in memories" :key="m.name" class="setting-row">
        <div class="row-left">
          <Icon icon="lucide:file-text" class="row-icon" />
          <div>
            <div class="row-title">{{ m.name }}</div>
            <div class="row-meta"><span class="type-tag" :style="{ color: typeColor[m.type] }">{{ typeLabel[m.type] }}</span> · {{ m.updatedAt }}</div>
          </div>
        </div>
        <button class="del-btn"><Icon icon="lucide:trash-2" /></button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.settings-page { max-width: 680px; margin: 0 auto; padding: 24px; }
.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 24px; }
.back-btn { width: 32px; height: 32px; border-radius: 50%; border: 1px solid var(--el-border-color); background: var(--el-bg-color); cursor: pointer; display: flex; align-items: center; justify-content: center; color: var(--el-text-color-secondary); }
.back-btn:hover { background: var(--el-fill-color-light); }
h2 { font-family: Georgia, serif; font-weight: 400; font-size: 22px; letter-spacing: -0.3px; color: var(--el-text-color-primary); margin: 0; }
.setting-row { display: flex; align-items: center; justify-content: space-between; padding: 12px; border-bottom: 1px solid var(--el-border-color); }
.row-left { display: flex; align-items: center; gap: 10px; }
.row-icon { color: var(--el-text-color-secondary); font-size: 16px; }
.row-title { font-size: 14px; color: var(--el-text-color-primary); }
.row-meta { font-size: 12px; color: var(--el-text-color-placeholder); margin-top: 2px; display: flex; align-items: center; gap: 4px; }
.type-tag { font-weight: 500; }
.del-btn { width: 28px; height: 28px; border-radius: 50%; border: none; background: transparent; color: var(--el-text-color-placeholder); cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 14px; transition: all 0.15s; }
.del-btn:hover { background: rgba(198,69,69,0.08); color: var(--el-color-danger); }
</style>
