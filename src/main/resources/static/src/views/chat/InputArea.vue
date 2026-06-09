<script lang="ts" setup>
import { ref } from 'vue'
import { useSettingsStore } from '@/store/settings'
import { Icon } from '@iconify/vue'

const emit = defineEmits<{ send: [text: string] }>()
const text = ref('')
const inputRef = ref<HTMLTextAreaElement>()
const settingsStore = useSettingsStore()
const showModelList = ref(false)

function onInput() {
  const el = inputRef.value
  if (el) { el.style.height = 'auto'; el.style.height = Math.min(el.scrollHeight, 160) + 'px' }
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); doSend() }
}

function doSend() {
  const msg = text.value.trim()
  if (!msg) return
  emit('send', msg)
  text.value = ''
  const el = inputRef.value
  if (el) { el.style.height = 'auto' }
}

function toggleModelList() {
  if (settingsStore.models.length === 0) return
  showModelList.value = !showModelList.value
}

async function selectModel(name: string) {
  const m = settingsStore.models.find(x => x.name === name)
  if (m?.id) { await settingsStore.setDefaultModelRemote(m.id) }
  else { settingsStore.setModel(name) }
  showModelList.value = false
}

function closeModelList() { showModelList.value = false }
</script>

<template>
  <div class="input-section">
    <div class="input-container">
      <textarea
        ref="inputRef"
        class="input-textarea"
        v-model="text"
        placeholder="给 zephyr 发送消息...  Enter 发送 · Shift+Enter 换行  / 查看命令"
        rows="1"
        @input="onInput"
        @keydown="onKeydown"
      ></textarea>
      <div class="input-toolbar">
        <div class="input-left">
          <div class="model-pick" @click.stop="toggleModelList">
            <span>{{ settingsStore.models.length ? settingsStore.currentModel : '无' }}</span>
            <Icon icon="lucide:chevron-down" class="pick-arrow" />
            <div v-if="showModelList" class="model-dropdown" @click.stop>
              <div v-for="m in settingsStore.models" :key="m.name" class="model-option" :class="{ current: settingsStore.currentModel === m.name }" @click="selectModel(m.name)">
                <span>{{ m.name }}</span>
                <Icon v-if="settingsStore.currentModel === m.name" icon="lucide:check" class="check-icon" />
              </div>
            </div>
          </div>
        </div>
        <div class="input-right">
          <button class="action-btn" title="上传附件">
            <Icon icon="lucide:paperclip" />
          </button>
          <button class="send-btn" :class="{ 'has-text': text.trim() }" @click="doSend" :disabled="!text.trim()">
            <Icon icon="lucide:arrow-up" />
          </button>
        </div>
      </div>
    </div>
    <Teleport to="body">
      <div v-if="showModelList" class="model-overlay" @click="closeModelList"></div>
    </Teleport>
  </div>
</template>

<script lang="ts">
export default { inheritAttrs: false }
</script>

<style scoped>
.input-section { padding: 0 24px 12px; }
.input-container { max-width: 820px; margin: 0 auto; background: var(--el-bg-color); border: 1px solid var(--el-border-color); border-radius: 12px; padding: 8px 12px; transition: border-color 0.2s; }
.input-container:focus-within { border-color: var(--el-color-primary); }

.input-textarea { width: 100%; resize: none; border: none; background: transparent; color: var(--el-text-color-primary); font-family: 'Inter', -apple-system, sans-serif; font-size: 15px; padding: 6px 0; max-height: 160px; min-height: 40px; outline: none; line-height: 1.6; }
.input-textarea::placeholder { color: var(--el-text-color-placeholder); }

.input-toolbar { display: flex; align-items: center; justify-content: space-between; padding-top: 4px; }

.input-left { display: flex; align-items: center; }

/* Model picker — text-only, subtle hover bg */
.model-pick {
  position: relative; display: flex; align-items: center; gap: 3px;
  padding: 3px 8px; border-radius: 6px; cursor: pointer;
  font-size: 12px; color: var(--el-text-color-secondary);
  transition: background 0.15s; user-select: none;
}
.model-pick:hover { background: var(--el-fill-color-light); color: var(--el-text-color-primary); }
.pick-arrow { font-size: 10px; opacity: 0.5; }

/* Dropdown */
.model-dropdown {
  position: absolute; bottom: calc(100% + 8px); left: 0;
  background: var(--el-bg-color); border: 1px solid var(--el-border-color);
  border-radius: 10px; box-shadow: 0 8px 32px rgba(0,0,0,0.1);
  min-width: 180px; padding: 4px; z-index: 100;
}
.model-option {
  display: flex; align-items: center; justify-content: space-between;
  padding: 7px 10px; border-radius: 6px; cursor: pointer;
  font-size: 13px; color: var(--el-text-color-primary);
  transition: background 0.1s;
}
.model-option:hover { background: var(--el-fill-color-light); }
.model-option.current { color: var(--el-color-primary); }
.check-icon { font-size: 15px; color: var(--el-color-primary); }

.model-overlay { position: fixed; inset: 0; z-index: 99; }

.input-right { display: flex; align-items: center; gap: 4px; }
.action-btn { width: 30px; height: 30px; border-radius: 50%; border: none; background: transparent; color: var(--el-text-color-secondary); cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 18px; transition: all 0.15s; }
.action-btn:hover { background: var(--el-fill-color-light); color: var(--el-text-color-primary); }
.send-btn { width: 32px; height: 32px; border-radius: 50%; border: none; background: var(--el-fill-color); color: var(--el-text-color-placeholder); cursor: pointer; display: flex; align-items: center; justify-content: center; font-size: 18px; transition: all 0.15s; }
.send-btn.has-text { background: var(--el-color-primary); color: #fff; }
.send-btn.has-text:hover { background: var(--el-color-primary-dark-2); }
.send-btn:disabled { cursor: default; }
</style>
