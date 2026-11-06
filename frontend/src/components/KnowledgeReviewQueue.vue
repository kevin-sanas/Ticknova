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
const selectedId = ref('')
const selectedTask = computed(() => tasks.value.find(task => task.versionId === selectedId.value) || tasks.value[0])
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
  selectedId.value = ''
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
      <div v-else-if="!error" class="review-grid">
        <div class="review-table-scroll"><table class="review-table"><thead><tr><th scope="col">文档</th><th scope="col">版本</th><th scope="col">文件</th><th scope="col">版本创建时间</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead><tbody><tr v-for="task in tasks" :key="task.versionId" :class="{ selected: selectedTask?.versionId === task.versionId }"><td><button type="button" class="review-select" :aria-pressed="selectedTask?.versionId === task.versionId" @click="selectedId = task.versionId">{{ task.title }}</button></td><td>{{ task.versionNo }}</td><td>{{ task.fileName }}</td><td>{{ formatTime(task.versionCreatedAt) }}</td><td><span class="review-pending">待审核</span></td><td><button class="review-task-open" :aria-label="`审核${task.title}的版本 ${task.versionNo}`" @click="emit('open', task)">打开审核</button></td></tr></tbody></table></div>
        <aside v-if="selectedTask" class="review-summary" aria-label="审核详情"><div class="review-summary-head"><h3>审核详情</h3><span class="review-pending">待审核</span></div><h4>{{ selectedTask.title }}</h4><dl><div><dt>版本</dt><dd>{{ selectedTask.versionNo }}</dd></div><div><dt>文件</dt><dd>{{ selectedTask.fileName }}</dd></div><div><dt>版本创建时间</dt><dd>{{ formatTime(selectedTask.versionCreatedAt) }}</dd></div></dl><p>打开文档核对正文，再决定通过或退回。</p><el-button type="primary" @click="emit('open', selectedTask)">查看文档</el-button></aside>
      </div>
      <div v-if="total > 20 && !error" class="pagination"><span>第 {{ page + 1 }} 页 · 共 {{ total }} 项</span><div class="inline-actions"><el-button :disabled="page === 0 || pending" @click="changePage(-1)">上一页</el-button><el-button :disabled="(page + 1) * 20 >= total || pending" @click="changePage(1)">下一页</el-button></div></div>
    </template>
  </div>
</template>

<style scoped>
.review-grid{display:grid;grid-template-columns:minmax(0,1fr) 270px;gap:14px;align-items:start}.review-table-scroll{overflow:auto;border:1px solid #e1e7ed;border-radius:8px;min-width:0}.review-table{width:100%;border-collapse:collapse;font-size:13px;text-align:left}.review-table th{background:#f6f8fa;color:#64768a;font-size:12px;font-weight:600}.review-table th,.review-table td{padding:13px 12px;border-bottom:1px solid #e9edf2;vertical-align:middle;overflow-wrap:anywhere}.review-table tr:last-child td{border-bottom:0}.review-table tbody tr.selected{background:#f2f7fc}.review-select{padding:0;border:0;background:none;color:#315c86;text-align:left;font-weight:600;cursor:pointer}.review-select:hover{text-decoration:underline}.review-task-open{border:1px solid #ccd9e6;border-radius:6px;padding:7px 10px;background:#fff;color:#315d87;white-space:nowrap;cursor:pointer}.review-pending{display:inline-block;border-radius:5px;background:#fff1d9;color:#8c5b13;padding:3px 7px;font-size:12px;white-space:nowrap}.review-summary{border:1px solid #e1e7ed;border-radius:8px;padding:16px;min-width:0}.review-summary-head{display:flex;justify-content:space-between;align-items:center;gap:8px;border-bottom:1px solid #e9edf2;padding-bottom:12px}.review-summary h3{font-size:15px;margin:0}.review-summary h4{font-size:15px;margin:16px 0;overflow-wrap:anywhere}.review-summary dl{font-size:12px;line-height:1.5}.review-summary dl>div{display:flex;justify-content:space-between;gap:10px;margin:8px 0}.review-summary dt,.review-summary p{color:#64768a}.review-summary dd{margin:0;text-align:right;overflow-wrap:anywhere}.review-summary p{border-top:1px solid #e9edf2;padding-top:14px;font-size:12px;line-height:1.6}@media(max-width:1000px){.review-grid{grid-template-columns:1fr}.review-table{min-width:620px}}
</style>
