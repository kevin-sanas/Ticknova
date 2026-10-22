<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { api, queryString } from '../api'
import { useOperation } from '../composables/operation'
import { useDialogFocus } from '../composables/dialogFocus'
import type { AuditEvent, Page } from '../types'
import { fullTime } from '../types'
import ErrorNotice from '../components/ErrorNotice.vue'
const operation = useOperation(); const action = ref(''); const appliedAction = ref(''); const page = ref(1)
const result = ref<Page<AuditEvent>>({ items: [], total: 0, page: 0, size: 20 }); const selected = ref<AuditEvent | null>(null); const dialog = ref(false)
const dialogFocus = useDialogFocus()
async function load() { await operation.run(async () => { result.value = await api.get<Page<AuditEvent>>(`/audit-events?${queryString({ page: page.value - 1, size: 20, action: appliedAction.value })}`) }) }
async function search() { page.value = 1; appliedAction.value = action.value.trim(); await load() }
async function changePage(next: number) { page.value = next; await load() }
function inspect(event: AuditEvent, trigger?: Event) { dialogFocus.capture(trigger); selected.value = event; dialog.value = true }
onMounted(load)
</script>
<template>
  <div class="page-heading"><div><h1>审计记录</h1><p class="muted">查看操作人、资源与追踪编号，回溯关键变更。</p></div></div>
  <section class="panel filter-panel"><form class="filter-form" @submit.prevent="search"><div class="field grow"><label for="audit-action">操作类型</label><input id="audit-action" v-model="action" maxlength="100" placeholder="输入完整操作类型，例如 TICKET_CREATED" /></div><div class="filter-actions"><el-button type="primary" native-type="submit" :loading="operation.pending.value">查询</el-button><el-button @click="action = ''; search()">重置</el-button></div></form></section>
  <ErrorNotice :error="operation.error.value" reload @reload="load" />
  <section class="panel"><div class="panel-header"><h2>操作记录 <span class="small muted">{{ result.total }} 条</span></h2><span class="muted">时间按本地时区显示</span></div><div v-if="operation.pending.value" class="loading-panel" role="status">正在加载审计记录…</div><template v-else><div class="table-wrap desktop-table"><table><thead><tr><th scope="col">时间</th><th scope="col">操作人</th><th scope="col">操作类型</th><th scope="col">资源类型</th><th scope="col">详情</th></tr></thead><tbody><tr v-for="event in result.items" :key="event.id"><td><time :datetime="event.createdAt">{{ fullTime(event.createdAt) }}</time></td><td>{{ event.actorName || event.actorId || '系统' }}</td><td>{{ event.action }}</td><td>{{ event.subjectType }}</td><td><el-button text type="primary" @click="inspect(event, $event)">查看记录</el-button></td></tr></tbody></table></div><div class="mobile-cards"><article v-for="event in result.items" :key="event.id" class="ticket-card audit-card"><strong class="small">{{ event.action }}</strong><p class="small muted" style="margin:10px 0">{{ event.actorName || event.actorId || '系统' }} · {{ event.subjectType }}</p><time class="small muted" :datetime="event.createdAt">{{ fullTime(event.createdAt) }}</time><div class="inline-actions"><el-button text type="primary" @click="inspect(event, $event)">查看记录</el-button></div></article></div><div v-if="!result.items.length && !operation.error.value" class="empty-state">暂无符合条件的审计记录。</div></template><div class="pagination"><span>共 {{ result.total }} 条</span><el-pagination :current-page="page" :page-size="20" :total="result.total" :pager-count="5" layout="prev, pager, next" @current-change="changePage" /></div></section>
  <el-dialog v-model="dialog" title="审计记录详情" width="620px" @closed="dialogFocus.restore"><template v-if="selected"><dl class="meta-list"><div><dt>操作类型</dt><dd>{{ selected.action }}</dd></div><div><dt>操作人</dt><dd>{{ selected.actorName || selected.actorId || '系统' }}</dd></div><div><dt>资源类型</dt><dd>{{ selected.subjectType }}</dd></div><div><dt>资源编号</dt><dd>{{ selected.subjectId }}</dd></div><div><dt>追踪编号</dt><dd>{{ selected.traceId }}</dd></div><div><dt>发生时间</dt><dd>{{ fullTime(selected.createdAt) }}</dd></div></dl><h3 style="margin-top:25px">变更元数据</h3><pre class="preformatted">{{ JSON.stringify(selected.metadata, null, 2) }}</pre></template></el-dialog>
</template>
