<script setup lang="ts">
import { computed, defineAsyncComponent, h, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiError, queryString } from '../api'
import { useSessionStore } from '../stores/session'
import { statuses, priorities } from '../types'
import type { WorkspaceAnalytics } from '../workspace-types'
import ErrorNotice from '../components/ErrorNotice.vue'
const DailyChart = defineAsyncComponent({ loader: () => import('../components/analytics/DailyChart.vue'), errorComponent: { render: () => h('p', { role: 'alert' }, '图表加载失败，请查看下方每日数据表。') } })
const session = useSessionStore(), route = useRoute(), router = useRouter()
const days = computed(() => ['7', '14', '30'].includes(String(route.query.range)) ? Number(route.query.range) : 30)
const result = shallowRef<WorkspaceAnalytics | null>(null), error = shallowRef<ApiError | null>(null), pending = ref(false)
let generation = 0, controller: AbortController | undefined
const countStatus = (keys: string[]) => result.value?.statusCounts.filter(item => keys.includes(item.key)).reduce((sum, item) => sum + item.count, 0) || 0
const complete = computed(() => countStatus(['RESOLVED', 'CLOSED']))
const open = computed(() => result.value?.statusCounts.filter(item => !['RESOLVED', 'CLOSED'].includes(item.key)).reduce((sum, item) => sum + item.count, 0) || 0)
const urgent = computed(() => result.value?.priorityCounts.find(item => item.key === 'URGENT')?.count || 0)
const completion = computed(() => result.value?.ticketTotal ? `${Math.round(complete.value / result.value.ticketTotal * 100)}%` : '—')
const acceptance = computed(() => { const ai = result.value?.ai; return ai && ai.accepted + ai.rejected > 0 ? `${Math.round(ai.accepted / (ai.accepted + ai.rejected) * 100)}%` : '暂无反馈' })
const statusColor = (key: string) => ({ NEW: 'var(--color-info)', TRIAGED: 'var(--color-ai)', IN_PROGRESS: 'var(--color-primary)', WAITING_CUSTOMER: 'var(--color-warning)', WAITING_THIRD_PARTY: 'var(--color-warning)', RESOLVED: 'var(--color-success)', CLOSED: 'var(--color-text-secondary)' })[key] || 'var(--color-text-secondary)'
async function selectDays(value: number) { await router.replace({ query: { ...route.query, range: String(value) } }) }
async function load() {
  const current = ++generation, token = session.token, end = new Date(), start = new Date(end)
  start.setUTCDate(start.getUTCDate() - days.value + 1)
  controller?.abort(); controller = new AbortController()
  pending.value = true; error.value = null; result.value = null
  try {
    const next = await api.get<WorkspaceAnalytics>(`/workspace/analytics?${queryString({ from: start.toISOString().slice(0, 10), to: end.toISOString().slice(0, 10) })}`, { signal: controller.signal })
    if (current !== generation || token !== session.token) return
    const count = (value: unknown) => typeof value === 'number' && Number.isFinite(value) && value >= 0
    if (!Array.isArray(next.daily) || !Array.isArray(next.statusCounts) || !Array.isArray(next.priorityCounts) || !next.ai || !next.knowledge || !count(next.ticketTotal)
      || ![next.ai.completed, next.ai.refused, next.ai.accepted, next.ai.rejected, next.knowledge.publishedDocuments, next.knowledge.pendingReviews].every(count)
      || !next.statusCounts.every(item => typeof item.key === 'string' && count(item.count))
      || !next.priorityCounts.every(item => typeof item.key === 'string' && count(item.count))
      || !next.daily.every(item => typeof item.date === 'string' && count(item.created) && count(item.resolved))) throw new Error('Invalid response')
    result.value = next
  } catch (failure) {
    if (current !== generation || token !== session.token) return
    error.value = failure instanceof ApiError ? failure : new ApiError(0, { title: '运营统计加载失败，请重试。', status: 0 })
  } finally { if (current === generation) pending.value = false }
}
watch(days, () => { void load() }, { immediate: true, flush: 'sync' })
watch(() => [session.token, session.user] as const, () => { generation++; controller?.abort(); result.value = null; error.value = null; pending.value = false }, { flush: 'sync' })
onBeforeUnmount(() => { generation++; controller?.abort() })
</script>
<template>
  <div class="analytics-page">
    <header class="page-heading"><div><h1>运营分析</h1><p class="muted">用真实数据了解服务请求、知识与 AI 协作。</p></div><div class="range-options" role="group" aria-label="统计时间范围"><button v-for="value in [7, 14, 30]" :key="value" type="button" :data-days="value" :aria-pressed="days === value" @click="selectDays(value)">最近 {{ value }} 天</button></div></header>
    <ErrorNotice :error="error" reload @reload="load" /><div v-if="pending" class="loading-panel" role="status">正在汇总运营数据…</div>
    <template v-if="result && !pending">
      <p class="small muted">UTC {{ result.from }} 至 {{ result.to }} · 含起止日。工单指标基于该期间新建工单的当前状态。</p>
      <section class="kpi-grid" aria-label="服务请求概览"><article class="kpi-card" data-kpi="created"><h2>期间新建工单</h2><strong>{{ result.ticketTotal }}</strong><span>张 · 所选时间范围</span></article><article class="kpi-card" data-kpi="open"><h2>其中未结工单</h2><strong>{{ open }}</strong><span>张 · 当前尚未解决或关闭</span></article><article class="kpi-card" data-kpi="urgent"><h2>其中紧急优先级</h2><strong>{{ urgent }}</strong><span>张 · 当前优先级</span></article><article class="kpi-card" data-kpi="completion"><h2>完成占比</h2><strong>{{ completion }}</strong><span>已解决及已关闭 / 期间新建</span></article></section>
      <p v-if="!result.ticketTotal" class="empty-state">所选时间范围没有新建工单。</p>
      <section class="panel daily-panel" aria-labelledby="daily-heading"><div class="panel-header"><h2 id="daily-heading">每日新建与解决</h2><span class="small muted">单位：张 / UTC 日</span></div><p class="small muted">解决量按当日出现解决记录的不同工单计数，可能包含更早创建的工单。</p><DailyChart v-if="result.daily.length" :daily="result.daily" /><p v-else class="empty-state">暂无每日数据。</p><details class="analytics-table"><summary>查看每日数据表</summary><div class="table-wrap"><table><caption class="sr-only">每日工单数量，单位张，UTC</caption><thead><tr><th scope="col">日期</th><th scope="col">新建</th><th scope="col">解决</th></tr></thead><tbody><tr v-for="day in result.daily" :key="day.date"><th scope="row">{{ day.date }}</th><td>{{ day.created }}</td><td>{{ day.resolved }}</td></tr></tbody></table></div></details></section>
      <div class="analytics-columns"><section class="panel distribution-panel"><h2>工单状态阶段</h2><p class="small muted">期间新建工单的当前状态，单位：张。</p><div v-if="result.ticketTotal" class="status-stack" aria-hidden="true"><span v-for="item in result.statusCounts" :key="item.key" :style="{ width: `${item.count / result.ticketTotal * 100}%`, background: statusColor(item.key) }"></span></div><ul class="status-list"><li v-for="item in result.statusCounts" :key="item.key"><span class="status-dot" aria-hidden="true" :style="{ background: statusColor(item.key) }"></span><span>{{ statuses[item.key] || item.key }}</span><strong>{{ item.count }}</strong></li></ul><p v-if="!result.statusCounts.length" class="muted">暂无状态分布。</p></section>
      <section class="panel distribution-panel"><h2>工单优先级</h2><p class="small muted">期间新建工单的当前优先级，单位：张。</p><div v-for="item in result.priorityCounts" :key="item.key" class="priority-row"><div><span>{{ priorities[item.key] || item.key }}</span><strong>{{ item.count }}</strong></div><meter :class="{ urgent: item.key === 'URGENT' }" min="0" :value="item.count" :max="Math.max(result.ticketTotal, 1)" :aria-label="`${priorities[item.key] || item.key} ${item.count} 张`"></meter></div><p v-if="!result.priorityCounts.length" class="muted">暂无优先级分布。</p></section></div>
      <section class="panel collaboration-panel"><div class="panel-header"><h2>知识与 AI 协作</h2><router-link to="/knowledge">查看知识库 →</router-link></div><p class="small muted">知识数量为当前快照；AI 数量按所选 UTC 时间范围统计。</p><dl class="collaboration-grid"><div><dt>当前已发布文档</dt><dd>{{ result.knowledge.publishedDocuments }}</dd></div><div><dt>当前待审核版本</dt><dd>{{ result.knowledge.pendingReviews }}</dd></div><div><dt>AI 完成 / 其中拒答</dt><dd>{{ result.ai.completed }} / {{ result.ai.refused }}</dd></div><div data-ai-acceptance><dt>AI 采纳率</dt><dd>{{ acceptance }}</dd><small>采纳 {{ result.ai.accepted }} / 拒绝 {{ result.ai.rejected }}</small></div></dl><p class="small muted">采纳率 = 采纳 /（采纳 + 拒绝）；无反馈时不计算。该指标不代表模型准确率。</p></section>
    </template>
  </div>
</template>
<style scoped>
.analytics-page{min-width:0}.analytics-page .page-heading{flex-wrap:wrap}.range-options{display:flex;flex-wrap:wrap;gap:var(--space-2)}.range-options button{background:var(--color-surface);border:1px solid var(--color-border);color:var(--color-text-secondary);border-radius:var(--radius-control);padding:var(--space-2) var(--space-4);min-height:var(--control-height)}.range-options button[aria-pressed=true]{background:var(--color-primary-soft);border-color:var(--color-primary);color:var(--color-primary);font-weight:600}.kpi-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:var(--space-4);margin:var(--space-5) 0}.kpi-card{min-width:0;background:var(--color-surface);border:1px solid var(--color-border);border-radius:var(--radius-card);padding:var(--space-5)}.kpi-card h2{font-size:var(--text-sm);color:var(--color-text-secondary);margin-bottom:var(--space-3)}.kpi-card strong{display:block;font-size:var(--text-title);line-height:1.4;margin-bottom:var(--space-2)}.kpi-card span{font-size:var(--text-xs);color:var(--color-text-secondary);overflow-wrap:anywhere}.analytics-page .panel{padding:var(--space-5);min-width:0}.analytics-page .panel h2{font-size:var(--text-section)}.analytics-page .daily-panel{margin-top:var(--space-5)}.analytics-table{margin-top:var(--space-4)}.analytics-table summary{cursor:pointer;color:var(--color-primary);padding:var(--space-2) 0}.analytics-table .table-wrap{margin:var(--space-3) 0 0}.analytics-columns{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:var(--space-5);margin:var(--space-5) 0}.analytics-columns>.panel{margin:0}.status-stack{height:var(--space-4);display:flex;overflow:hidden;border-radius:var(--radius-control);margin:var(--space-5) 0}.status-list{display:grid;gap:var(--space-3);list-style:none;padding:0;margin:var(--space-5) 0 0}.status-list li{display:flex;align-items:center;gap:var(--space-3)}.status-list strong{margin-left:auto}.status-dot{width:var(--space-2);height:var(--space-2);border-radius:50%;flex-shrink:0}.priority-row{margin-top:var(--space-4)}.priority-row>div{display:flex;justify-content:space-between;gap:var(--space-3);margin-bottom:var(--space-2)}.priority-row meter{display:block;width:100%;height:var(--space-4);accent-color:var(--color-primary)}.priority-row meter::-webkit-meter-bar{background:var(--color-surface-muted);border:0}.priority-row meter::-webkit-meter-optimum-value{background:var(--color-primary)}.priority-row meter.urgent::-webkit-meter-optimum-value{background:var(--color-danger)}.priority-row meter::-moz-meter-bar{background:var(--color-primary)}.priority-row meter.urgent::-moz-meter-bar{background:var(--color-danger)}.collaboration-panel{border-top:var(--space-1) solid var(--color-ai)}.collaboration-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:var(--space-5);margin:var(--space-5) 0}.collaboration-grid dt{color:var(--color-text-secondary);font-size:var(--text-sm)}.collaboration-grid dd{font-size:var(--text-title);font-weight:600;margin:var(--space-2) 0;color:var(--color-ai);overflow-wrap:anywhere}.collaboration-grid small{color:var(--color-text-secondary)}
@media(max-width:1000px){.kpi-grid,.collaboration-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:600px){.analytics-columns{grid-template-columns:minmax(0,1fr)}.kpi-card,.analytics-page .panel{padding:var(--space-4)}.kpi-grid{gap:var(--space-3)}.range-options button{min-height:var(--control-height-touch);padding:var(--space-2) var(--space-3)}.analytics-page .panel-header{flex-wrap:wrap}.collaboration-grid{gap:var(--space-4)}}
</style>
