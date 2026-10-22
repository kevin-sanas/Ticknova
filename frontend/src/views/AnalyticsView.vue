<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, shallowRef, ref, watch } from 'vue'
import { api, ApiError, queryString } from '../api'
import { useSessionStore } from '../stores/session'
import { statuses, priorities } from '../types'
import type { WorkspaceAnalytics } from '../workspace-types'
import ErrorNotice from '../components/ErrorNotice.vue'
const session = useSessionStore(); const today = new Date(); const first = new Date(today); first.setUTCDate(first.getUTCDate() - 29)
const range = reactive({ from: first.toISOString().slice(0, 10), to: today.toISOString().slice(0, 10) })
const result = shallowRef<WorkspaceAnalytics | null>(null); const error = shallowRef<ApiError | null>(null); const pending = ref(false); let generation = 0
const maximum = computed(() => Math.max(1, ...result.value?.daily.flatMap(day => [day.created, day.resolved]) || []))
async function load() {
  const current = ++generation; const token = session.token; pending.value = true; error.value = null
  try { const next = await api.get<WorkspaceAnalytics>(`/workspace/analytics?${queryString(range)}`); if (current === generation && token === session.token) result.value = next }
  catch (failure) { if (current === generation) { result.value = null; error.value = failure instanceof ApiError ? failure : new ApiError(0, { title: '运营统计加载失败，请重试。', status: 0 }) } }
  finally { if (current === generation) pending.value = false }
}
watch(() => session.token, () => { generation++; result.value = null; error.value = null }, { flush: 'sync' }); onBeforeUnmount(() => { generation++ }); onMounted(load)
</script>
<template>
  <div class="page-heading"><div><h1>运营分析</h1><p class="muted">查看服务请求与知识协作的实际进展。</p></div></div>
  <form class="panel filter-form analytics-range" @submit.prevent="load"><div class="field"><label for="analytics-from">开始日期（UTC）</label><input id="analytics-from" v-model="range.from" type="date" required :max="range.to" /></div><div class="field"><label for="analytics-to">结束日期（UTC）</label><input id="analytics-to" v-model="range.to" type="date" required :min="range.from" /></div><el-button native-type="submit" type="primary" :loading="pending">更新统计</el-button><p class="small muted">含起止日，最多 366 天。只显示真实业务计数。</p></form>
  <ErrorNotice :error="error" reload @reload="load" /><div v-if="pending" class="loading-panel" role="status">正在汇总运营数据…</div>
  <template v-if="result && !pending">
    <section class="panel analytics-summary"><h2>服务请求概览</h2><p>UTC {{ result.from }} 至 {{ result.to }} 期间创建了 <strong>{{ result.ticketTotal }}</strong> 张工单。以下状态和优先级为这些工单的当前情况。</p><div v-if="!result.ticketTotal" class="empty-state">所选时间范围没有新建工单。</div><div class="analytics-distributions"><div><h3>按状态（张）</h3><div v-for="item in result.statusCounts" :key="item.key" class="distribution-row"><span>{{ statuses[item.key] || item.key }}</span><meter :value="item.count" :max="Math.max(result.ticketTotal, 1)" :aria-label="`${statuses[item.key] || item.key} ${item.count} 张`"></meter><strong>{{ item.count }}</strong></div></div><div><h3>按优先级（张）</h3><div v-for="item in result.priorityCounts" :key="item.key" class="distribution-row"><span>{{ priorities[item.key] || item.key }}</span><meter :value="item.count" :max="Math.max(result.ticketTotal, 1)" :aria-label="`${priorities[item.key] || item.key} ${item.count} 张`"></meter><strong>{{ item.count }}</strong></div></div></div></section>
    <section class="panel"><h2>每日新建与解决</h2><p class="small muted">单位：张 / UTC 日。解决量按当日出现解决记录的不同工单计数，可能包含更早创建的工单。</p><div class="trend-legend"><span>● 新建</span><span>○ 解决</span></div><div class="analytics-trend" role="img" :aria-label="`UTC ${result.from} 至 ${result.to} 每日工单数量，详细值见下表`"><div v-for="day in result.daily" :key="day.date" class="trend-day" :title="`${day.date}：新建 ${day.created}，解决 ${day.resolved}`"><div class="trend-bars"><span class="created-bar" :style="{ height: `${day.created / maximum * 100}%` }"></span><span class="resolved-bar" :style="{ height: `${day.resolved / maximum * 100}%` }"></span></div><small>{{ day.date.slice(5) }}</small></div></div><details class="analytics-table"><summary>查看每日数据表</summary><div class="table-wrap"><table><caption class="sr-only">每日工单数量，单位张，UTC</caption><thead><tr><th scope="col">日期</th><th scope="col">新建</th><th scope="col">解决</th></tr></thead><tbody><tr v-for="day in result.daily" :key="day.date"><th scope="row">{{ day.date }}</th><td>{{ day.created }}</td><td>{{ day.resolved }}</td></tr></tbody></table></div></details></section>
    <section class="panel"><h2>知识与 AI 协作</h2><p class="small muted">知识数量为当前快照；AI 数量按所选 UTC 时间范围统计。</p><dl class="analytics-facts"><div><dt>当前已发布文档</dt><dd>{{ result.knowledge.publishedDocuments }}</dd></div><div><dt>当前待审核版本</dt><dd>{{ result.knowledge.pendingReviews }}</dd></div><div><dt>AI 完成 / 其中拒答</dt><dd>{{ result.ai.completed }} / {{ result.ai.refused }}</dd></div><div><dt>采纳 / 拒绝反馈</dt><dd>{{ result.ai.accepted }} / {{ result.ai.rejected }}</dd></div></dl><p class="small muted">这些计数用于协作跟进，不代表模型准确率或人工质量评测。</p></section>
  </template>
</template>
