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
import AppIcon from '../components/AppIcon.vue'
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
  <div class="tickets-workspace">
  <div class="page-heading"><div><h1>工单工作台</h1><p class="muted">{{ isProcessor ? '集中处理服务请求，让每一步进展清晰可见。' : '提交问题，跟踪进展，与服务团队保持沟通。' }}</p></div><div class="heading-actions"><router-link v-if="canCreate" to="/tickets/new" class="primary-link"><AppIcon name="plus" :size="16" />新建工单</router-link></div></div>
<section class="panel tickets-board" aria-labelledby="ticket-list-heading" :aria-busy="operation.pending.value">
  <div class="board-heading"><div><h2 id="ticket-list-heading">{{ isProcessor ? '工作队列' : '我的工单' }}</h2><p>按当前账号权限展示</p></div><span>{{ result.total }} 条工单</span></div>
<div v-if="isProcessor" class="inbox-tabs" role="group" aria-label="工作队列"><el-button :aria-pressed="workView === 'all'" @click="changeView('all')">全部工单</el-button><el-button :aria-pressed="workView === 'mine'" @click="changeView('mine')">分配给我</el-button><el-button :aria-pressed="workView === 'unassigned'" @click="changeView('unassigned')">待分配</el-button></div>
  <section class="filter-panel" aria-label="工单筛选">
    <form class="filter-form" @submit.prevent="search">
      <div class="field grow"><label for="ticket-search">搜索工单</label><input id="ticket-search" v-model="filter.keyword" type="search" placeholder="搜索标题或工单编号" maxlength="200" /></div>
      <div class="field"><label for="status-filter">状态</label><select id="status-filter" v-model="filter.status"><option value="">全部状态</option><option v-for="(label, value) in statuses" :key="value" :value="value">{{ label }}</option></select></div>
      <div class="field"><label for="priority-filter">优先级</label><select id="priority-filter" v-model="filter.priority"><option value="">全部优先级</option><option v-for="(label, value) in priorities" :key="value" :value="value">{{ label }}</option></select></div>
      <div v-if="isProcessor" class="field"><label for="queue-filter">处理队列</label><select id="queue-filter" v-model="filter.queueId"><option value="">全部队列</option><option v-for="queue in queues" :key="queue.id" :value="queue.id">{{ queue.name }}</option></select></div>
      <details class="ticket-more-filters"><summary><AppIcon name="settings" :size="16" />更多筛选</summary><div class="advanced-filter-fields">      <div class="field"><label for="service-filter">服务目录</label><select id="service-filter" v-model="filter.serviceId"><option value="">全部服务</option><option v-for="service in services" :key="service.id" :value="service.id">{{ service.name }}</option></select></div>
      <div class="field"><label for="tag-filter">标签</label><input id="tag-filter" v-model="filter.tag" placeholder="输入标签" maxlength="32" /></div>
      <div v-if="isProcessor" class="field"><label for="assignee-filter">处理人</label><select id="assignee-filter" v-model="filter.assigneeId"><option value="">全部处理人</option><option :value="session.user?.id">分配给我</option></select></div>
      <div class="field"><label for="sort-filter">排序</label><select id="sort-filter" v-model="filter.sort"><option value="updatedAt,desc">最近更新</option><option value="createdAt,desc">最新创建</option><option value="ticketNo,asc">工单编号升序</option></select></div>
</div></details>
      <div class="filter-actions"><el-button type="primary" native-type="submit" :loading="operation.pending.value">查询</el-button><el-button @click="reset">重置</el-button></div>
    </form>
  </section>
  <ErrorNotice :error="operation.error.value" reload @reload="load" />
  <ErrorNotice :error="catalog.error.value" reload @reload="loadCatalog" />
    <div v-if="operation.pending.value" class="loading-panel" role="status" aria-label="正在加载工单"><div v-for="i in 4" :key="i" class="loading-line" aria-hidden="true"></div><span class="small muted">正在加载工单…</span></div>
    <template v-else-if="result.items.length">
      <div class="table-wrap desktop-table"><table><thead><tr><th scope="col">工单</th><th scope="col">状态</th><th scope="col">优先级</th><th scope="col" class="hide-medium">服务 / 队列</th><th scope="col">处理人</th><th scope="col">更新时间</th></tr></thead><tbody><tr v-for="ticket in result.items" :key="ticket.id"><td class="ticket-cell"><router-link :to="`/tickets/${ticket.id}`" class="ticket-title">{{ ticket.title }}</router-link><span class="ticket-number">{{ ticket.ticketNo }}</span></td><td><StatusBadge :value="ticket.status" /></td><td><StatusBadge :value="ticket.priority" kind="priority" /></td><td class="hide-medium">{{ ticket.serviceName || '未指定服务' }}<span class="ticket-number">{{ ticket.queueName }}</span></td><td>{{ ticket.assigneeName || '待分配' }}</td><td><time :datetime="ticket.updatedAt">{{ formatTime(ticket.updatedAt) }}</time></td></tr></tbody></table></div>
      <div class="mobile-cards"><article v-for="ticket in result.items" :key="ticket.id" class="ticket-card"><div class="ticket-card-top"><span class="ticket-number">{{ ticket.ticketNo }}</span><StatusBadge :value="ticket.status" /></div><router-link :to="`/tickets/${ticket.id}`" class="ticket-title">{{ ticket.title }}</router-link><div class="ticket-card-meta"><span>{{ ticket.assigneeName || '待分配' }} · {{ ticket.queueName }}</span><StatusBadge :value="ticket.priority" kind="priority" /></div><div class="ticket-card-meta" style="margin-top:10px"><span>{{ ticket.serviceName || '未指定服务' }}</span><time :datetime="ticket.updatedAt">{{ formatTime(ticket.updatedAt) }}</time></div></article></div>
    </template>
    <div v-else-if="!operation.error.value" class="empty-state"><div class="empty-symbol"><AppIcon name="inbox" :size="25" /></div><h2>还没有符合条件的工单</h2><p>试试调整筛选条件，或创建一张工单。</p><router-link v-if="canCreate" class="empty-create" to="/tickets/new">创建工单<AppIcon name="chevron" :size="14" /></router-link></div>
    <div class="pagination"><span>共 {{ result.total }} 条，每页 20 条</span><el-pagination :current-page="page" :page-size="20" :total="result.total" :pager-count="5" layout="prev, pager, next" :disabled="operation.pending.value" @current-change="changePage" /></div>
  </section>
  </div>
</template>

<style scoped>
.ticket-more-filters{display:contents}.ticket-more-filters>summary{display:flex;align-items:center;justify-content:center;gap:7px;min-height:40px;border:1px solid var(--color-border);border-radius:var(--radius-control);padding:0 13px;cursor:pointer;list-style:none;font-size:var(--text-sm);order:2}.ticket-more-filters>summary:hover{background:var(--color-primary-soft);color:var(--color-primary)}.ticket-more-filters[open]>summary{background:var(--color-primary-soft);color:var(--color-primary);border-color:var(--color-primary-border)}.advanced-filter-fields{flex-basis:100%;order:4;display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:16px;padding-top:20px;margin-top:4px;border-top:1px solid var(--color-border)}.filter-actions{order:3}.advanced-filter-fields .field{min-width:0}
.tickets-workspace{color:#1c2e40}.tickets-workspace .page-heading{margin-bottom:20px}.tickets-workspace .page-heading h1{font-size:26px}.tickets-board{padding:0;overflow:hidden;border:1px solid #dce3eb;border-radius:9px}.board-heading{display:flex;justify-content:space-between;align-items:center;gap:16px;padding:18px 20px 10px}.board-heading h2{font-size:16px;margin:0}.board-heading p{font-size:12px;color:#65788b;margin:6px 0 0}.board-heading>span{font-size:12px;color:#65788b;white-space:nowrap}.tickets-board .inbox-tabs{display:flex;gap:24px;padding:0 20px;margin:0;border-bottom:1px solid #e8edf1}.tickets-board .inbox-tabs :deep(.el-button){height:auto;padding:12px 0;margin:0;border:0;border-bottom:2px solid transparent;border-radius:0;color:#65778a;background:transparent;font-weight:500}.tickets-board .inbox-tabs :deep(.el-button[aria-pressed=true]){border-bottom-color:#365f89;color:#365f89;font-weight:700}.tickets-board .filter-panel{padding:14px 20px;border-bottom:1px solid #e9edf1}.tickets-board .filter-form{gap:9px;align-items:end}.tickets-board .filter-form .field{min-width:110px}.tickets-board .filter-form label{font-size:12px}.tickets-board .filter-form input,.tickets-board .filter-form select{min-height:38px}.tickets-board .desktop-table{margin:0}.tickets-board th{background:#f7f9fb;color:#5c6c7d;font-size:12px}.tickets-board td{font-size:13px}.tickets-board .ticket-title{text-decoration:none;font-weight:650;color:#26394c}.tickets-board .ticket-title:hover{color:#355f8a}.tickets-board .ticket-number{color:#8393a1}.tickets-board .pagination{padding:12px 20px;border-top:1px solid #e9edf1}.tickets-board .loading-panel,.tickets-board .empty-state{padding:24px}.empty-create{display:inline-flex;align-items:center;gap:4px;text-decoration:none}.tickets-workspace :deep(.error-notice){margin:12px 20px}@media(max-width:800px){.tickets-board .filter-form{align-items:stretch}.advanced-filter-fields{grid-template-columns:repeat(2,minmax(0,1fr))}.board-heading{padding:16px}.tickets-board .filter-panel{padding:12px 16px}}
</style>
