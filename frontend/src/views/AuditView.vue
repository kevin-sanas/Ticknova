<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, queryString } from '../api'
import { useOperation } from '../composables/operation'
import { useDialogFocus } from '../composables/dialogFocus'
import type { AuditEvent, AuditFilterOptions, Page } from '../types'
import { fullTime } from '../types'
import ErrorNotice from '../components/ErrorNotice.vue'
import { View } from '@element-plus/icons-vue'
const operation = useOperation(); const filterOperation = useOperation()
const action = ref(''); const actor = ref(''); const fromDate = ref(''); const toDate = ref(''); const validation = ref('')
const applied = ref({ action: '', actor: '', from: '', to: '' }); const page = ref(1)
const filters = ref<AuditFilterOptions>({ actions: [], actors: [] })
const result = ref<Page<AuditEvent>>({ items: [], total: 0, page: 0, size: 20 }); const selected = ref<AuditEvent | null>(null); const drawer = ref(false)
const dialogFocus = useDialogFocus()
function localBoundary(value: string, nextDay = false) {
  if (!value) return ''
  const [year, month, day] = value.split('-').map(Number)
  return new Date(year!, month! - 1, day! + (nextDay ? 1 : 0)).toISOString()
}
async function loadFilters() { await filterOperation.run(async () => { filters.value = await api.get<AuditFilterOptions>('/audit-events/filters') }) }
async function load() { await operation.run(async () => { result.value = await api.get<Page<AuditEvent>>(`/audit-events?${queryString({ page: page.value - 1, size: 20, ...applied.value })}`) }) }
async function search() {
  if (operation.pending.value) return
  validation.value = ''
  if (fromDate.value && toDate.value && fromDate.value > toDate.value) { validation.value = '开始日期不能晚于结束日期。'; return }
  page.value = 1
  applied.value = { action: action.value, actor: actor.value, from: localBoundary(fromDate.value), to: localBoundary(toDate.value, true) }
  await load()
}
async function reset() { if (operation.pending.value) return; action.value = ''; actor.value = ''; fromDate.value = ''; toDate.value = ''; await search() }
async function changePage(next: number) { if (operation.pending.value) return; page.value = next; await load() }
function inspect(event: AuditEvent, trigger?: Event) { dialogFocus.capture(trigger); selected.value = event; drawer.value = true }
onMounted(() => { void load(); void loadFilters() })
</script>
<template>
  <div class="page-heading"><div><h1>审计记录</h1><p class="muted">查看操作人、资源与追踪编号，回溯关键变更。</p></div></div>
  <ErrorNotice :error="filterOperation.error.value" reload @reload="loadFilters" />
  <ErrorNotice :error="operation.error.value" reload @reload="load" />
  <section class="panel"><div class="panel-header"><h2>操作记录 <span v-if="!operation.pending.value && !operation.error.value" class="small muted">{{ result.total }} 条</span></h2><span class="muted">时间按本地时区显示</span></div>
  <div class="filter-panel">
    <form class="filter-form audit-filter-form" @submit.prevent="search">
      <div class="field"><label for="audit-action">操作类型</label><el-select id="audit-action" v-model="action" class="audit-action-select" clearable filterable placeholder="全部操作"><el-option v-for="option in filters.actions" :key="option" :label="option" :value="option" /></el-select></div>
      <div class="field"><label for="audit-actor">操作人</label><el-select id="audit-actor" v-model="actor" class="audit-actor-select" clearable filterable placeholder="全部操作人"><el-option v-for="option in filters.actors" :key="option.value" :label="option.label" :value="option.value" /></el-select></div>
      <div class="field"><label for="audit-from">开始日期</label><input id="audit-from" v-model="fromDate" type="date" /></div>
      <div class="field"><label for="audit-to">结束日期（含）</label><input id="audit-to" v-model="toDate" type="date" /></div>
      <div class="filter-actions"><el-button type="primary" native-type="submit" :loading="operation.pending.value">查询</el-button><el-button :disabled="operation.pending.value" @click="reset">重置</el-button></div>
    </form>
    <p class="small muted audit-time-help">按当前设备时区选日，查询时转换为 UTC 时间范围。</p>
    <p v-if="validation" class="error-text" role="alert">{{ validation }}</p>
  </div>
<div v-if="operation.pending.value" class="loading-panel" role="status">正在加载审计记录…</div><template v-else-if="!operation.error.value"><div class="table-wrap desktop-table" role="region" aria-label="审计记录列表" tabindex="0"><table><thead><tr><th scope="col">时间</th><th scope="col">操作人</th><th scope="col">操作类型</th><th scope="col">资源类型</th><th scope="col">详情</th></tr></thead><tbody><tr v-for="event in result.items" :key="event.id"><td><time :datetime="event.createdAt">{{ fullTime(event.createdAt) }}</time></td><td>{{ event.actorName || event.actorId || '系统' }}</td><td>{{ event.action }}</td><td>{{ event.subjectType }}</td><td><el-button text type="primary" @click="inspect(event, $event)"><View class="action-icon" />查看记录</el-button></td></tr></tbody></table></div><div class="mobile-cards"><article v-for="event in result.items" :key="event.id" class="ticket-card audit-card"><strong class="small">{{ event.action }}</strong><p class="small muted" style="margin:10px 0">{{ event.actorName || event.actorId || '系统' }} · {{ event.subjectType }}</p><time class="small muted" :datetime="event.createdAt">{{ fullTime(event.createdAt) }}</time><div class="inline-actions"><el-button text type="primary" @click="inspect(event, $event)"><View class="action-icon" />查看记录</el-button></div></article></div><div v-if="!result.items.length && !operation.error.value" class="empty-state">暂无符合条件的审计记录。</div></template><div v-if="!operation.pending.value && !operation.error.value" class="pagination"><span>共 {{ result.total }} 条</span><el-pagination :current-page="page" :page-size="20" :total="result.total" :pager-count="5" :disabled="operation.pending.value" layout="prev, pager, next" @current-change="changePage" /></div></section>
  <el-drawer v-model="drawer" title="审计记录详情" size="min(640px, 100vw)" @closed="dialogFocus.restore"><template v-if="selected"><dl class="meta-list"><div><dt>操作类型</dt><dd>{{ selected.action }}</dd></div><div><dt>操作人</dt><dd>{{ selected.actorName || selected.actorId || '系统' }}</dd></div><div><dt>资源类型</dt><dd>{{ selected.subjectType }}</dd></div><div><dt>资源编号</dt><dd>{{ selected.subjectId || '无' }}</dd></div><div><dt>追踪编号</dt><dd>{{ selected.traceId || '无' }}</dd></div><div><dt>发生时间</dt><dd>{{ fullTime(selected.createdAt) }}</dd></div></dl><h3 class="audit-metadata-title">变更元数据</h3><pre class="preformatted">{{ JSON.stringify(selected.metadata, null, 2) }}</pre></template></el-drawer>
</template>
<style scoped>
.audit-filter-form { display: grid; align-items: end; grid-template-columns: minmax(190px, 1.2fr) minmax(180px, 1fr) minmax(150px, .8fr) minmax(150px, .8fr) auto; }
.audit-action-select, .audit-actor-select { width: 100%; }
.audit-time-help { margin: 10px 0 0; }
.audit-metadata-title { margin-top: var(--space-6); }
.page-heading{margin-bottom:22px}.page-heading h1{font-size:26px;color:#1d3045}.page-heading p{margin-top:6px;color:#64778a}.panel{padding:20px;border:1px solid #dbe2e9;border-radius:10px;background:#fff}.filter-panel{background:#f9fbfc;margin-bottom:16px}.panel-header{gap:12px;margin-bottom:18px}.panel-header h2{font-size:16px;color:#20364d}.audit-filter-form{gap:12px}.audit-filter-form .field label{font-size:12px;color:#5b6e81}.audit-filter-form .field input{height:40px;border-color:#ccd8e3;border-radius:7px}.audit-filter-form :deep(.el-select__wrapper){min-height:40px}.desktop-table table{width:100%;border-collapse:collapse}.desktop-table th{background:#f6f8fa;color:#627388;font-size:12px;font-weight:600}.desktop-table th,.desktop-table td{padding:13px 14px;border-bottom:1px solid #e7edf2;text-align:left;overflow-wrap:anywhere}.desktop-table tbody tr:hover{background:#f8fafc}.desktop-table :deep(.el-button.is-text){border:1px solid #d2dee9;border-radius:6px;padding:7px 12px}.pagination{padding-top:16px}.audit-card{border-color:#dce5ed;border-radius:8px}
@media (max-width: 1080px) { .audit-filter-form { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 640px) { .audit-filter-form { grid-template-columns: 1fr; } .panel{padding:15px}.page-heading h1{font-size:23px} :deep(.el-drawer) { width: 100% !important; } }

.action-icon{width:14px;height:14px;margin-right:6px;flex:none}.panel{padding:0;overflow:hidden}.panel-header{padding:15px 18px;margin:0;border-bottom:1px solid #ebeff3}.panel-header h2 span{font-size:12px;font-weight:400;color:#718295;margin-left:5px}.panel-header>.muted{font-size:12px}.filter-panel{padding:13px 16px;background:#fff;margin:0;border-bottom:1px solid #e8edf2}.audit-filter-form{display:flex;align-items:end;flex-wrap:wrap;gap:9px}.audit-filter-form .field{min-width:122px;flex:0 1 160px}.audit-filter-form .field label{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}.audit-filter-form .field input,.audit-filter-form :deep(.el-select__wrapper){height:36px;min-height:36px}.audit-filter-form :deep(.el-button){min-height:34px}.audit-time-help{font-size:11px;margin:8px 0 0}.desktop-table{margin:0;max-height:max(180px,calc(100dvh - 430px));overflow:auto}.desktop-table thead th{position:sticky;top:0;z-index:1}.desktop-table th,.desktop-table td{padding:10px 16px;font-size:12px}.desktop-table th{height:38px;font-size:11px}.desktop-table td{height:52px}.desktop-table :deep(.el-button.is-text){height:34px;padding:7px 12px}.pagination{padding:12px 16px;border-top:1px solid #edf1f5}.mobile-cards{padding:12px}.empty-state{margin:0 16px 16px}@media(max-width:640px){.panel{padding:0}.filter-panel{padding:12px}.audit-filter-form .field{flex:1 1 140px}}
</style>
<style scoped>
@media(max-width:640px){.desktop-table{display:none}.mobile-cards{display:grid;gap:10px;max-height:min(55dvh,500px);overflow:auto}.mobile-cards .audit-card{min-width:0;padding:12px;border:1px solid #dce5ed;border-radius:8px;overflow-wrap:anywhere}.mobile-cards .inline-actions{margin-top:8px}}
</style>
