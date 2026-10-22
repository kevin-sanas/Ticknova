<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, nextTick, reactive, ref, watch } from 'vue'
import { useRoute, useRouter, onBeforeRouteLeave } from 'vue-router'
import { api, ApiError, queryString } from '../api'
import { useSessionStore } from '../stores/session'
import { useOperation } from '../composables/operation'
import type { Page, Queue, Service, TicketSummary } from '../types'
import { formatTime, priorities, statuses } from '../types'
import ErrorNotice from '../components/ErrorNotice.vue'
import StatusBadge from '../components/StatusBadge.vue'
import { rememberTicketScroll, restoreTicketScroll } from '../composables/ticketScroll'
const session = useSessionStore(); const route = useRoute(); const router = useRouter(); const operation = useOperation(); const catalog = useOperation()
const canCreate = computed(() => session.user?.roles.some(role => ['AGENT', 'REQUESTER'].includes(role)))
const isProcessor = computed(() => !!session.user?.roles.includes('AGENT'))
const queues = ref<Queue[]>([]); const services = ref<Service[]>([])
const result = ref<Page<TicketSummary>>({ items: [], total: 0, page: 0, size: 20 }); const page = ref(readPage())
const workView = ref(isProcessor.value && ['mine','unassigned'].includes(String(route.query.view)) ? String(route.query.view) : 'all')
const emptyFilter = () => ({ keyword: '', status: '', priority: '', queueId: '', serviceId: '', tag: '', assigneeId: '', sort: 'updatedAt,desc' })
const initial = emptyFilter(); for (const key of Object.keys(initial) as (keyof typeof initial)[]) if (typeof route.query[key] === 'string') initial[key] = String(route.query[key]).slice(0, key === 'keyword' ? 200 : 100)
if (!isProcessor.value) { initial.queueId = ''; initial.assigneeId = '' }
const filter = reactive(initial); const applied = ref({ ...filter }); let generation = 0
let controller: AbortController | undefined; let catalogGeneration = 0
const scrollIdentity = () => JSON.stringify([session.user?.id, session.expiresAt])
function readPage() { return Math.floor(Math.max(1, Math.min(50000, Number(route.query.page) || 1))) }
const requestQuery = () => ({ ...applied.value, assigneeId: workView.value === 'mine' ? session.user?.id : workView.value === 'unassigned' ? '' : applied.value.assigneeId, unassigned: workView.value === 'unassigned' ? 'true' : undefined, page: page.value - 1, size: 20 })
async function load() {
  controller?.abort(); controller = new AbortController()
  const current = ++generation; const token = session.token; operation.pending.value = true; operation.error.value = null
  try { const next = await api.get<Page<TicketSummary>>('/tickets?' + queryString(requestQuery()), { signal: controller.signal }); if (current === generation && token === session.token) result.value = next }
  catch (failure) {
    if (current !== generation || token !== session.token || (failure instanceof DOMException && failure.name === 'AbortError')) return
    operation.error.value = failure instanceof ApiError ? failure : new ApiError(0, { status: 0, title: '工单加载失败，请重试。' })
    if ([401,403,404].includes(operation.error.value.status)) result.value = { items: [], total: 0, page: 0, size: 20 }
  } finally { if (current === generation && token === session.token) operation.pending.value = false }
}
async function loadCatalog() { await catalog.run(async () => { const current = ++catalogGeneration; const token = session.token; const [serviceData, queueData] = await Promise.all([api.get<Service[]>('/services'), isProcessor.value ? api.get<Queue[]>('/queues') : Promise.resolve([])]); if (current === catalogGeneration && token === session.token) { services.value = serviceData; queues.value = queueData } }) }
let syncingUrl = false
async function syncUrl() { const query:Record<string,string> = {}; for (const [key,value] of Object.entries(applied.value)) if (value) query[key] = value; if (page.value > 1) query.page = String(page.value); if (workView.value !== 'all') query.view = workView.value; syncingUrl = true; try { await router.replace({query}) } finally { syncingUrl = false } }
watch(() => route.fullPath, async () => {
  if (syncingUrl || route.path !== '/tickets') return
  const next = emptyFilter()
  for (const key of Object.keys(next) as (keyof typeof next)[]) if (typeof route.query[key] === 'string') next[key] = String(route.query[key]).slice(0, key === 'keyword' ? 200 : 100)
  if (!isProcessor.value) { next.queueId = ''; next.assigneeId = '' }
  Object.assign(filter, next); applied.value = { ...next }; page.value = readPage()
  workView.value = isProcessor.value && ['mine','unassigned'].includes(String(route.query.view)) ? String(route.query.view) : 'all'
  await load()
})
async function search() { page.value = 1; applied.value = { ...filter }; await syncUrl(); await load() }
async function reset() { Object.assign(filter, emptyFilter()); workView.value = 'all'; await search() }
async function changePage(next: number) { page.value = next; await syncUrl(); await load() }
async function changeView(next:string) { workView.value = next; filter.assigneeId = ''; await search() }
onBeforeRouteLeave(() => { rememberTicketScroll(scrollIdentity(), route.fullPath, window.scrollY) })
watch(() => session.token, () => { generation++; catalogGeneration++; controller?.abort(); operation.pending.value = false; result.value = { items: [], total: 0, page: 0, size: 20 }; services.value = []; queues.value = [] }, { flush: 'sync' })
onBeforeUnmount(() => { generation++; catalogGeneration++; controller?.abort() })
onMounted(async () => {
  await Promise.all([load(), loadCatalog()])
  await nextTick(); const top = restoreTicketScroll(scrollIdentity(), route.fullPath); if (top !== null) requestAnimationFrame(() => window.scrollTo({top,behavior:'instant'}))
})
</script>
<template>
  <div class="page-heading"><div><h1>工单工作台</h1><p class="muted">{{ isProcessor ? '集中处理服务请求，让每一步进展清晰可见。' : '提交问题，跟踪进展，与服务团队保持沟通。' }}</p></div><div class="heading-actions"><router-link v-if="canCreate" to="/tickets/new" class="primary-link"><span aria-hidden="true">＋&nbsp;</span>新建工单</router-link></div></div>
<div v-if="isProcessor" class="inbox-tabs" role="group" aria-label="工作队列"><el-button :aria-pressed="workView === 'all'" @click="changeView('all')">全部工单</el-button><el-button :aria-pressed="workView === 'mine'" @click="changeView('mine')">分配给我</el-button><el-button :aria-pressed="workView === 'unassigned'" @click="changeView('unassigned')">待分配</el-button></div>
  <section class="panel filter-panel" aria-label="工单筛选">
    <form class="filter-form" @submit.prevent="search">
      <div class="field grow"><label for="ticket-search">搜索工单</label><input id="ticket-search" v-model="filter.keyword" type="search" placeholder="搜索标题或工单编号" maxlength="200" /></div>
      <div class="field"><label for="status-filter">状态</label><select id="status-filter" v-model="filter.status"><option value="">全部状态</option><option v-for="(label, value) in statuses" :key="value" :value="value">{{ label }}</option></select></div>
      <div class="field"><label for="priority-filter">优先级</label><select id="priority-filter" v-model="filter.priority"><option value="">全部优先级</option><option v-for="(label, value) in priorities" :key="value" :value="value">{{ label }}</option></select></div>
      <div v-if="isProcessor" class="field"><label for="queue-filter">处理队列</label><select id="queue-filter" v-model="filter.queueId"><option value="">全部队列</option><option v-for="queue in queues" :key="queue.id" :value="queue.id">{{ queue.name }}</option></select></div>
      <div class="field"><label for="service-filter">服务目录</label><select id="service-filter" v-model="filter.serviceId"><option value="">全部服务</option><option v-for="service in services" :key="service.id" :value="service.id">{{ service.name }}</option></select></div>
      <div class="field"><label for="tag-filter">标签</label><input id="tag-filter" v-model="filter.tag" placeholder="输入标签" maxlength="32" /></div>
      <div v-if="isProcessor" class="field"><label for="assignee-filter">处理人</label><select id="assignee-filter" v-model="filter.assigneeId"><option value="">全部处理人</option><option :value="session.user?.id">分配给我</option></select></div>
      <div class="field"><label for="sort-filter">排序</label><select id="sort-filter" v-model="filter.sort"><option value="updatedAt,desc">最近更新</option><option value="createdAt,desc">最新创建</option><option value="ticketNo,asc">工单编号升序</option></select></div>
      <div class="filter-actions"><el-button type="primary" native-type="submit" :loading="operation.pending.value">查询</el-button><el-button @click="reset">重置</el-button></div>
    </form>
  </section>
  <ErrorNotice :error="operation.error.value" reload @reload="load" />
  <ErrorNotice :error="catalog.error.value" reload @reload="loadCatalog" />
  <section class="panel" aria-labelledby="ticket-list-heading" :aria-busy="operation.pending.value">
    <div class="panel-header"><h2 id="ticket-list-heading">{{ isProcessor ? '可访问的工单' : '我的工单' }} <span class="muted small">{{ result.total }} 条</span></h2><span class="muted">按当前账号权限展示</span></div>
    <div v-if="operation.pending.value" class="loading-panel" role="status" aria-label="正在加载工单"><div v-for="i in 4" :key="i" class="loading-line" aria-hidden="true"></div><span class="small muted">正在加载工单…</span></div>
    <template v-else-if="result.items.length">
      <div class="table-wrap desktop-table"><table><thead><tr><th scope="col">工单</th><th scope="col">状态</th><th scope="col">优先级</th><th scope="col" class="hide-medium">服务 / 队列</th><th scope="col">处理人</th><th scope="col">更新时间</th></tr></thead><tbody><tr v-for="ticket in result.items" :key="ticket.id"><td class="ticket-cell"><router-link :to="`/tickets/${ticket.id}`" class="ticket-title">{{ ticket.title }}</router-link><span class="ticket-number">{{ ticket.ticketNo }}</span></td><td><StatusBadge :value="ticket.status" /></td><td><StatusBadge :value="ticket.priority" kind="priority" /></td><td class="hide-medium">{{ ticket.serviceName || '未指定服务' }}<span class="ticket-number">{{ ticket.queueName }}</span></td><td>{{ ticket.assigneeName || '待分配' }}</td><td><time :datetime="ticket.updatedAt">{{ formatTime(ticket.updatedAt) }}</time></td></tr></tbody></table></div>
      <div class="mobile-cards"><article v-for="ticket in result.items" :key="ticket.id" class="ticket-card"><div class="ticket-card-top"><span class="ticket-number">{{ ticket.ticketNo }}</span><StatusBadge :value="ticket.status" /></div><router-link :to="`/tickets/${ticket.id}`" class="ticket-title">{{ ticket.title }}</router-link><div class="ticket-card-meta"><span>{{ ticket.assigneeName || '待分配' }} · {{ ticket.queueName }}</span><StatusBadge :value="ticket.priority" kind="priority" /></div><div class="ticket-card-meta" style="margin-top:10px"><span>{{ ticket.serviceName || '未指定服务' }}</span><time :datetime="ticket.updatedAt">{{ formatTime(ticket.updatedAt) }}</time></div></article></div>
    </template>
    <div v-else-if="!operation.error.value" class="empty-state"><div class="empty-symbol" aria-hidden="true">▤</div><h2>还没有符合条件的工单</h2><p>试试调整筛选条件，或创建一张工单。</p><router-link v-if="canCreate" to="/tickets/new">创建工单 →</router-link></div>
    <div class="pagination"><span>共 {{ result.total }} 条，每页 20 条</span><el-pagination :current-page="page" :page-size="20" :total="result.total" :pager-count="5" layout="prev, pager, next" :disabled="operation.pending.value" @current-change="changePage" /></div>
  </section>
</template>
