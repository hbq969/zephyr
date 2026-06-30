<script lang="ts" setup>
import { ref, onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { getLangData } from '@/i18n/locale'
import { msg } from '@/utils/Utils'
import { Icon } from '@iconify/vue'
import axios from '@/network'

const router = useRouter()
const route = useRoute()
const langData = getLangData()

const kbId = route.params.kbId as string
const kbName = ref('')

const query = ref('')
const topK = ref(5)
const results = ref<any[]>([])
const searched = ref(false)

const fetchKbName = () => {
  axios({ url: '/knowledge/kb/list', method: 'get' })
    .then(res => {
      if (res.data.state === 'OK') {
        const kb = res.data.body.find((k: any) => k.id === kbId)
        if (kb) kbName.value = kb.name
      }
    })
}

const doSearch = () => {
  if (!query.value.trim()) { msg('请输入查询文本', 'warning'); return }
  axios({ url: `/knowledge/kb/${kbId}/recall-test`, method: 'post', data: { query: query.value.trim(), topK: topK.value } })
    .then(res => {
      searched.value = true
      if (res.data.state === 'OK') results.value = res.data.body || []
      else msg(res.data.errorMessage, 'warning')
    })
    .catch(err => msg(err?.response?.data?.errorMessage || '搜索失败', 'error'))
}

const scoreTagType = (score: number) => score >= 0.7 ? 'success' : score >= 0.4 ? 'warning' : 'info'
const scorePercent = (score: number) => (score * 100).toFixed(1) + '%'
const fmtScore = (score: number) => score?.toFixed(4) ?? '-'

const highlightText = (text: string, q: string) => {
  if (!q || !text) return text
  const words = q.split(/\s+/).filter(w => w.length > 0)
  let result = text
  for (const word of words) {
    const escaped = word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
    result = result.replace(new RegExp(`(${escaped})`, 'gi'), '<mark class="hl">$1</mark>')
  }
  return result
}

onMounted(() => { fetchKbName() })
</script>

<template>
  <div class="recall-page">
    <div class="page-header">
      <button class="back-btn" @click="router.push('/settings/knowledge')">
        <Icon icon="lucide:chevron-left" />
      </button>
      <h2>{{ langData.knowledgeMgmt_recallTest }} · <strong>{{ kbName || '...' }}</strong></h2>
    </div>

    <!-- 查询区 -->
    <div class="query-bar">
      <el-input
        v-model="query"
        :placeholder="langData.knowledgeMgmt_recallQueryPlaceholder"
        class="query-input"
        @keyup.enter="doSearch"
      />
      <el-select v-model="topK" style="width:100px">
        <el-option :value="3" label="Top 3" />
        <el-option :value="5" label="Top 5" />
        <el-option :value="10" label="Top 10" />
        <el-option :value="20" label="Top 20" />
      </el-select>
      <el-button type="primary" @click="doSearch">
        <Icon icon="lucide:search" style="margin-right:4px" /> {{ langData.knowledgeMgmt_recallSearch }}
      </el-button>
    </div>

    <!-- 空状态 -->
    <div v-if="!searched" class="empty-state">
      <Icon icon="lucide:search" width="48" style="color: var(--el-text-color-placeholder)" />
      <p>{{ langData.knowledgeMgmt_recallEmpty }}</p>
    </div>

    <!-- 结果区 -->
    <template v-else-if="results.length > 0">
      <div class="result-header">
        {{ langData.knowledgeMgmt_recallResultCount.replace('{count}', String(results.length)) }}
      </div>
      <div class="result-list">
        <div v-for="(r, i) in results" :key="i" class="result-item">
          <div class="result-top">
            <span class="rank" :class="{ 'rank-first': i === 0 }">{{ i + 1 }}</span>
            <span class="source">{{ langData.knowledgeMgmt_recallSource }}: {{ r.sourceFile || '-' }}</span>
            <span class="score-detail">({{ langData.knowledgeMgmt_recallVecScore }}: {{ fmtScore(r.vecScore) }}, {{ langData.knowledgeMgmt_recallKwScore }}: {{ fmtScore(r.kwScore) }}, {{ langData.knowledgeMgmt_recallRrfScore }}: {{ fmtScore(r.rrfScore) }})</span>
            <el-tag :type="scoreTagType(r.score)" size="small" effect="dark" round>
              {{ scorePercent(r.score) }}
            </el-tag>
          </div>
          <div class="result-content" v-html="highlightText(r.content, query)" />
        </div>
      </div>
    </template>

    <!-- 无结果 -->
    <div v-else class="empty-state">
      <Icon icon="lucide:file-search" width="48" style="color: var(--el-text-color-placeholder)" />
      <p>未找到匹配结果</p>
    </div>
  </div>
</template>

<style scoped>
.recall-page { max-width: 880px; margin: 0 auto; padding: 24px; }

.page-header { display: flex; align-items: center; gap: 12px; margin-bottom: 24px; }
.back-btn {
  width: 36px; height: 36px; border-radius: 50%;
  border: 1px solid var(--el-border-color); background: var(--el-bg-color);
  cursor: pointer; display: flex; align-items: center; justify-content: center;
  color: var(--el-text-color-secondary); font-size: 18px;
}
.back-btn:hover { background: var(--el-fill-color-light); }
h2 { font-family: Georgia, serif; font-weight: 400; font-size: 22px; letter-spacing: -0.3px; color: var(--el-text-color-primary); margin: 0; }

.query-bar { display: flex; gap: 8px; margin-bottom: 24px; }
.query-input { flex: 1; }

.empty-state { text-align: center; padding: 80px 24px; color: var(--el-text-color-placeholder); font-size: 14px; }

.result-header { font-size: 13px; font-weight: 500; color: var(--el-text-color-primary); padding: 8px 14px; background: var(--el-fill-color-light); border-radius: 8px 8px 0 0; border: 1px solid var(--el-border-color); border-bottom: none; }

.result-list { border: 1px solid var(--el-border-color); border-radius: 0 0 10px 10px; overflow: hidden; }
.result-item { padding: 14px 16px; border-bottom: 1px solid var(--el-border-color); background: var(--el-bg-color); }
.result-item:last-child { border-bottom: none; }

.result-top { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.rank { background: var(--el-text-color-secondary); color: #fff; border-radius: 50%; width: 22px; height: 22px; display: inline-flex; align-items: center; justify-content: center; font-size: 12px; font-weight: 600; flex-shrink: 0; }
.rank-first { background: var(--el-color-primary); }
.source { font-size: 12px; color: var(--el-text-color-secondary); flex: 1; }

.result-content { font-size: 14px; line-height: 1.7; color: var(--el-text-color-primary); margin-bottom: 6px; }
.result-content :deep(.hl) { background: #fff3cd; padding: 0 2px; border-radius: 2px; }

.score-detail { font-size: 11px; font-family: ui-monospace, monospace; color: var(--el-text-color-placeholder); }

html.dark .back-btn:hover { background: var(--el-fill-color); }
html.dark .result-content :deep(.hl) { background: #5a4a00; }
</style>
