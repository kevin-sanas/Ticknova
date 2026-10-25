<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { api, ApiError, queryString } from '../api'
import { useSessionStore } from '../stores/session'
import { formatTime } from '../types'
import type { KnowledgeReviewTask, Page } from '../types'
import KnowledgeError from './KnowledgeError.vue'

const emit = defineEmits<{ open: [task: KnowledgeReviewTask] }>()
const session = useSessionStore()
const allowed = computed(() => session.authenticated && !!session.user?.roles.includes('KB_REVIEWER'))
const tasks = ref<KnowledgeReviewTask[]>([])
const total = ref(0)
const page = ref(0)
const pending = ref(false)
const error = shallowRef<ApiError | null>(null)
let generation = 0
let controller: AbortController | undefined

function cancel() {
  generation++
  controller?.abort()
  pending.value = false
  error.value = null
  tasks.value = []
  total.value = 0
}
async function load() {
  if (!allowed.value) return
  cancel()
  const own = generation
  controller = new AbortController()
  pending.value = true
  try {
    const result = await api.get<Page<KnowledgeReviewTask>>(`/knowledge/review-tasks?${queryString({ page: page.value, size: 20 })}`, { signal: controller.signal })
    if (own === generation) { tasks.value = result.items; total.value = result.total }
  } catch (failure) {
    if (own === generation && !(failure instanceof DOMException && failure.name === 'AbortError'))
      error.value = failure instanceof ApiError ? failure : new ApiError(0, { status: 0, title: '审核待办加载失败，请重试。' })
  } finally {
    if (own === generation) pending.value = false
  }
}
async function changePage(delta: number) { if (pending.value) return; page.value += delta; await load() }
watch([() => session.token, () => session.user?.id, allowed], () => { cancel(); page.value = 0; if (allowed.value) void load() }, { flush: 'sync' })
onBeforeUnmount(cancel)
void load()
</script>

<template>
  <div class="knowledge-review-tasks" :aria-busy="pending">
    <div class="panel-header"><div><h3>待我审核 <span class="small muted">{{ total }} 项</span></h3><p class="muted small">打开版本核对正文，再选择审核通过或退回修改；审核通过后还需发布。</p></div><el-button aria-label="刷新待办" :loading="pending" @click="load">刷新待办</el-button></div>
    <div v-if="!allowed" class="empty-state">当前账号没有知识审核权限。需要知识审核者角色及对应空间的阅读、审核权限。</div>
    <template v-else>
      <KnowledgeError :error="error" retry @retry="load" />
      <p v-if="pending" class="loading-panel" role="status">正在加载审核待办…</p>
      <div v-else-if="!tasks.length && !error" class="empty-state">暂无待你审核的版本。提交者不能审核自己的版本；如预期有待办，请联系空间管理员核对成员权限。</div>
      <ul v-else-if="!error" class="knowledge-documents review-task-list">
        <li v-for="task in tasks" :key="task.versionId"><div class="knowledge-document-label"><strong>{{ task.title }}</strong><span class="ticket-number">版本 {{ task.versionNo }} · {{ task.fileName }} · {{ formatTime(task.versionCreatedAt) }}</span></div><button class="review-task-open" :aria-label="`审核${task.title}的版本 ${task.versionNo}`" @click="emit('open', task)">打开审核</button></li>
      </ul>
      <div v-if="total > 20 && !error" class="pagination"><span>第 {{ page + 1 }} 页 · 共 {{ total }} 项</span><div class="inline-actions"><el-button :disabled="page === 0 || pending" @click="changePage(-1)">上一页</el-button><el-button :disabled="(page + 1) * 20 >= total || pending" @click="changePage(1)">下一页</el-button></div></div>
    </template>
  </div>
</template>
