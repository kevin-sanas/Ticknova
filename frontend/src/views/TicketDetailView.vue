<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { api, ApiError, createRequestKey } from '../api'
import { useOperation } from '../composables/operation'
import { useUnsavedChanges } from '../composables/unsaved'
import { ticketReturnPath } from '../composables/ticketScroll'
import { useSessionStore } from '../stores/session'
import type { AiResult, Comment, Queue, QueueMember, TicketDetail, TicketSummary } from '../types'
import { formatTime, fullTime, priorities, statuses } from '../types'
import ErrorNotice from '../components/ErrorNotice.vue'
import StatusBadge from '../components/StatusBadge.vue'
import AiSuggestionPanel from '../components/AiSuggestionPanel.vue'
const id = String(useRoute().params.id); const session = useSessionStore()
const returnPath = computed(() => ticketReturnPath(JSON.stringify([session.user?.id, session.expiresAt])))
const detail = ref<TicketDetail | null>(null); const read = useOperation(); const mutation = useOperation(); const memberOp = useOperation()
const showProperties = ref(true); const showCopilot = ref(true)
const compactQuery = typeof window.matchMedia === 'function' ? window.matchMedia('(max-width: 850px)') : null
const compact = ref(compactQuery?.matches || false)
const propertiesDrawer = ref(false); const copilotDrawer = ref(false)
const propertiesHost = ref<HTMLElement | null>(null); const copilotHost = ref<HTMLElement | null>(null)
function compactChanged(event: MediaQueryListEvent) { compact.value = event.matches; if (!event.matches) { propertiesDrawer.value = false; copilotDrawer.value = false } }
onMounted(() => compactQuery?.addEventListener('change', compactChanged))
onBeforeUnmount(() => compactQuery?.removeEventListener('change', compactChanged))
const tab = ref('public'); const success = ref(''); const validation = ref('')
const drafts = reactive({ PUBLIC_REPLY: '', INTERNAL_NOTE: '' })
const commentKeys = { PUBLIC_REPLY: createRequestKey(), INTERNAL_NOTE: createRequestKey() }; const transitionKey = createRequestKey()
const edit = reactive({ priority: 'NORMAL', tags: '' }); const assignment = reactive({ queueId: '', assigneeId: '' }); const transition = reactive({ toStatus: '', reason: '' })
const queues = ref<Queue[]>([]); const members = ref<QueueMember[]>([])
let memberRequest = 0; let detailGeneration = 0
const saved = reactive({ priority: 'NORMAL', tags: '', queueId: '', assigneeId: '' })
useUnsavedChanges(() => session.authenticated && !!detail.value && (!!drafts.PUBLIC_REPLY || !!drafts.INTERNAL_NOTE || !!transition.toStatus || !!transition.reason || edit.priority !== saved.priority || edit.tags !== saved.tags || assignment.queueId !== saved.queueId || assignment.assigneeId !== saved.assigneeId))
onBeforeUnmount(() => { detailGeneration++; memberRequest++ })
const internalAllowed = computed(() => !!detail.value?.capabilities.canReadInternal && !!session.user?.roles.includes('AGENT'))
const visibility = computed(() => tab.value === 'internal' && internalAllowed.value ? 'INTERNAL_NOTE' : 'PUBLIC_REPLY')
const comments = computed<Comment[]>(() => visibility.value === 'INTERNAL_NOTE' ? detail.value?.internalNotes || [] : detail.value?.publicReplies || [])
const canComment = computed(() => tab.value !== 'history' && (visibility.value === 'INTERNAL_NOTE' ? internalAllowed.value && detail.value?.capabilities.canCommentInternal : detail.value?.capabilities.canCommentPublic))
const assignableMembers = computed(() => members.value.filter(member => member.active && member.canProcess))
const assignableQueues = computed(() => queues.value.filter(queue => queue.active && queue.canProcess))
const canClaim = computed(() => assignableMembers.value.some(member => member.userId === session.user?.id) && assignment.queueId === detail.value?.ticket.queueId)
const reasonRequired = computed(() => transition.toStatus === 'RESOLVED' || (detail.value?.ticket.status === 'CLOSED' && transition.toStatus === 'IN_PROGRESS'))
async function navigateRecords(event: KeyboardEvent) {
  const records = internalAllowed.value ? ['public', 'internal', 'history'] : ['public', 'history']
  const index = records.indexOf(tab.value)
  const next = event.key === 'ArrowRight' ? records[(index + 1) % records.length] : event.key === 'ArrowLeft' ? records[(index + records.length - 1) % records.length] : event.key === 'Home' ? records[0] : event.key === 'End' ? records.at(-1) : undefined
  if (!next) return
  event.preventDefault(); tab.value = next; await nextTick(); document.getElementById(`${next}-tab`)?.focus()
}
async function loadMembers(queueId: string) {
  const request = ++memberRequest
  members.value = []
  if (!queueId) return
  memberOp.pending.value = true; memberOp.error.value = null
  try { const result = await api.get<QueueMember[]>(`/queues/${queueId}/members`); if (request === memberRequest) members.value = result }
  catch (failure) { if (request === memberRequest) memberOp.error.value = failure instanceof ApiError ? failure : new ApiError(0, { title: '无法加载队列成员，请重试。', status: 0 }) }
  finally { if (request === memberRequest) memberOp.pending.value = false }
}
watch(() => assignment.queueId, async (next, previous) => { if (previous && next !== previous) assignment.assigneeId = ''; await loadMembers(next) })
async function load(initial = false) {
  await read.run(async () => {
    const current = ++detailGeneration; const token = session.token
    const next = await api.get<TicketDetail>(`/tickets/${id}`)
    if (current !== detailGeneration || token !== session.token || !session.authenticated) return
    detail.value = next
    if (initial) { edit.priority = next.ticket.priority; edit.tags = next.ticket.tags.join('，'); assignment.queueId = next.ticket.queueId; assignment.assigneeId = next.ticket.assigneeId || ''; Object.assign(saved, edit, assignment) }
    if (next.capabilities.canAssign) queues.value = await api.get<Queue[]>('/queues')
    if (!internalAllowed.value && tab.value === 'internal') tab.value = 'public'
  })
  if (read.error.value && [401, 403, 404].includes(read.error.value.status)) { detail.value = null; clearAiDraft() }
}
// Destinations remain independently attributable when a different suggestion kind is accepted.
const appliedAi: { publicDraft: string | null; priority: string | null; tags: string | null } = { publicDraft: null, priority: null, tags: null }
function clearAiDraft() {
  if (appliedAi.publicDraft !== null && drafts.PUBLIC_REPLY === appliedAi.publicDraft) drafts.PUBLIC_REPLY = ''
  if (appliedAi.priority !== null && edit.priority === appliedAi.priority) edit.priority = detail.value?.ticket.priority || 'NORMAL'
  if (appliedAi.tags !== null && edit.tags === appliedAi.tags) edit.tags = detail.value?.ticket.tags.join('，') || ''
  appliedAi.publicDraft = null; appliedAi.priority = null; appliedAi.tags = null
}
function acceptAi(next: AiResult) {
  if (!detail.value?.capabilities.canCommentPublic) return
  if (next.kind === 'REPLY' || next.kind === 'SUMMARY') { drafts.PUBLIC_REPLY = (next.kind === 'SUMMARY' ? next.summary : next.answer) || ''; tab.value = 'public'; appliedAi.publicDraft = drafts.PUBLIC_REPLY }
  if (next.kind === 'CLASSIFICATION' && detail.value.capabilities.canEdit) { if (next.priority) { edit.priority = next.priority; appliedAi.priority = edit.priority }; edit.tags = next.tags.join('，'); appliedAi.tags = edit.tags }
}
watch(() => session.token, () => { detailGeneration++; memberRequest++; clearAiDraft(); detail.value = null; drafts.PUBLIC_REPLY = ''; drafts.INTERNAL_NOTE = ''; members.value = []; queues.value = [] }, { flush: 'sync' })
watch(mutation.error, failure => { if (failure && [401, 403, 404].includes(failure.status)) { detailGeneration++; clearAiDraft(); detail.value = null } }, { flush: 'sync' })
async function reloadLatest() {
  await load()
  if (!read.error.value) { mutation.error.value = null; success.value = '已载入最新工单，你的输入已保留，请核对后再次提交。' }
}
async function refreshAfterMutation(message: string) { success.value = message; await load() }
function startAction() { validation.value = ''; success.value = '' }
async function sendComment() {
  startAction(); const kind = visibility.value; const original = drafts[kind]; const body = original.trim(); if (!body) return
  const payload = { visibility: kind, body }
  await mutation.run(async () => { await api.post(`/tickets/${id}/comments`, payload, { key: commentKeys[kind].for(payload) }); if (drafts[kind] === original) drafts[kind] = ''; commentKeys[kind].reset(); await refreshAfterMutation(kind === 'PUBLIC_REPLY' ? '公开回复已发送。' : '内部备注已添加。') })
}
async function changeStatus() {
  if (mutation.pending.value) return
  startAction(); if (!detail.value || !transition.toStatus) return
  if (reasonRequired.value && !transition.reason.trim()) { validation.value = '请填写解决说明或重开原因。'; return }
  const payload = { toStatus: transition.toStatus, reason: transition.reason.trim(), version: detail.value.ticket.version }
  await mutation.run(async () => { await api.post<TicketSummary>(`/tickets/${id}/transitions`, payload, { key: transitionKey.for(payload) }); transition.toStatus = ''; transition.reason = ''; transitionKey.reset(); await refreshAfterMutation('工单状态已更新。') })
}
async function saveProperties() {
  startAction(); if (!detail.value) return
  const tags = [...new Set(edit.tags.split(/[,，]/).map(tag => tag.trim()).filter(Boolean))]
  if (tags.length > 10 || tags.some(tag => tag.length > 32)) { validation.value = '最多 10 个标签，每个标签最多 32 个字符。'; return }
  const priority = edit.priority; const tagInput = edit.tags
  await mutation.run(async () => { await api.patch<TicketSummary>(`/tickets/${id}`, { priority, tags, version: detail.value!.ticket.version }); saved.priority = priority; saved.tags = tagInput; await refreshAfterMutation('优先级和标签已保存。') })
}
async function saveAssignment(claim = false) {
  startAction(); if (!detail.value || !assignment.queueId) return
  const assigneeId = claim ? session.user?.id : assignment.assigneeId || null
  const queueId = assignment.queueId
  await mutation.run(async () => { await api.put<TicketSummary>(`/tickets/${id}/assignment`, { queueId, assigneeId, version: detail.value!.ticket.version }); if (claim && assignment.queueId === queueId) assignment.assigneeId = assigneeId || ''; saved.queueId = queueId; saved.assigneeId = assigneeId || ''; await refreshAfterMutation(claim ? '已领取工单。' : '工单分配已更新。') })
}
onMounted(() => load(true))
</script>
<template>
  <router-link :to="returnPath" class="back-link">← 返回工单工作台</router-link>
  <ErrorNotice :error="read.error.value" reload @reload="load()" />
  <div v-if="!detail && read.pending.value" class="panel loading-panel" role="status"><div v-for="i in 5" :key="i" class="loading-line" aria-hidden="true"></div><span class="muted">正在加载工单…</span></div>
  <template v-if="detail">
    <div class="page-heading detail-heading"><div><span class="ticket-number">{{ detail.ticket.ticketNo }}</span><h1>{{ detail.ticket.title }}</h1><div class="detail-summary"><StatusBadge :value="detail.ticket.status" /><StatusBadge :value="detail.ticket.priority" kind="priority" /><span class="small muted">{{ detail.ticket.requesterName }} 创建于 {{ formatTime(detail.ticket.createdAt) }}</span></div></div><div class="heading-actions"><el-button :loading="read.pending.value" @click="reloadLatest">刷新工单</el-button></div></div>
    <ErrorNotice :error="mutation.error.value" :reload="mutation.conflict.value" @reload="reloadLatest" />
    <div v-if="success" class="info-notice" role="status">{{ success }}</div><div v-if="validation" class="error-notice" role="alert">{{ validation }}</div>
    <div v-if="compact" class="detail-mobile-actions"><el-button @click="showProperties = true; propertiesDrawer = true">工单信息与操作</el-button><el-button @click="showCopilot = true; copilotDrawer = true">AI Copilot</el-button></div>
    <div class="detail-grid" :class="{ 'properties-collapsed': !showProperties, 'copilot-collapsed': !showCopilot }">
<aside class="detail-properties-column" aria-label="工单信息与操作"><button class="column-toggle" :aria-expanded="showProperties" aria-controls="ticket-properties-content" @click="showProperties = !showProperties">{{ showProperties ? '收起工单信息' : '展开工单信息' }}</button><Teleport :disabled="!compact || !propertiesHost" :to="propertiesHost || 'body'"><div id="ticket-properties-content" v-show="showProperties" class="detail-aside">
        <section class="panel"><h2>工单信息</h2><dl class="meta-list"><div><dt>申请人</dt><dd>{{ detail.ticket.requesterName }}</dd></div><div><dt>服务目录</dt><dd>{{ detail.ticket.serviceName || '未指定' }}</dd></div><div><dt>处理队列</dt><dd>{{ detail.ticket.queueName }}</dd></div><div><dt>处理人</dt><dd>{{ detail.ticket.assigneeName || '待分配' }}</dd></div><div><dt>更新时间</dt><dd>{{ fullTime(detail.ticket.updatedAt) }}</dd></div><div><dt>当前版本</dt><dd>v{{ detail.ticket.version }}</dd></div></dl></section>
        <section v-if="detail.allowedTransitions.length" class="panel"><h2>更新状态</h2><form class="settings-form" @submit.prevent="changeStatus"><div class="field"><label for="target-status">目标状态</label><select id="target-status" v-model="transition.toStatus" :disabled="mutation.pending.value" required><option value="" disabled>选择下一步状态</option><option v-for="state in detail.allowedTransitions" :key="state" :value="state">{{ statuses[state] || state }}</option></select></div><div class="field"><label for="transition-reason">{{ transition.toStatus === 'RESOLVED' ? '解决说明' : '流转原因' }}{{ reasonRequired ? '（必填）' : '（可选）' }}</label><textarea id="transition-reason" v-model="transition.reason" :disabled="mutation.pending.value" rows="3" maxlength="10000" :required="reasonRequired" style="min-height:88px"></textarea><small>流转原因及解决说明对申请人可见。</small></div><el-button type="primary" native-type="submit" :loading="mutation.pending.value" :disabled="mutation.pending.value || !transition.toStatus">确认更新</el-button></form></section>
        <section v-if="detail.capabilities.canAssign || detail.capabilities.canEdit" class="panel"><h2>处理设置</h2>
          <form v-if="detail.capabilities.canAssign" class="settings-form" @submit.prevent="saveAssignment(false)"><div class="field"><label for="assignment-queue">处理队列</label><select id="assignment-queue" v-model="assignment.queueId" required><option value="" disabled>选择处理队列</option><option v-for="queue in assignableQueues" :key="queue.id" :value="queue.id">{{ queue.name }}</option></select></div><ErrorNotice :error="memberOp.error.value" reload @reload="loadMembers(assignment.queueId)" /><div class="field"><label for="assignment-person">处理人</label><select id="assignment-person" v-model="assignment.assigneeId" :disabled="memberOp.pending.value"><option value="">暂不分配</option><option v-for="member in assignableMembers" :key="member.userId" :value="member.userId">{{ member.displayName }}</option></select><small>仅展示此队列内启用且有处理权限的成员。</small></div><div class="inline-actions"><el-button native-type="submit" :loading="mutation.pending.value">保存分配</el-button><el-button v-if="canClaim && detail.ticket.assigneeId !== session.user?.id" :disabled="mutation.pending.value" @click="saveAssignment(true)">领取工单</el-button></div></form>
          <form v-if="detail.capabilities.canEdit" class="settings-form" @submit.prevent="saveProperties"><div class="field"><label for="edit-priority">优先级</label><select id="edit-priority" v-model="edit.priority"><option v-for="(label,value) in priorities" :key="value" :value="value">{{ label }}</option></select></div><div class="field"><label for="edit-tags">标签</label><input id="edit-tags" v-model="edit.tags" maxlength="330" placeholder="使用逗号分隔" /><small>最多 10 个，每个最多 32 个字符。</small></div><el-button native-type="submit" :loading="mutation.pending.value">保存属性</el-button></form>
        </section>
      </div></Teleport></aside>
      <div class="detail-main">
        <section class="panel"><h2>问题描述</h2><div class="plain-text">{{ detail.description }}</div><div v-if="detail.ticket.tags.length" class="tag-list" style="margin-top:20px"><span v-for="tag in detail.ticket.tags" :key="tag" class="tag">{{ tag }}</span></div></section>
        <section v-if="detail.resolutionNote" class="panel"><h2>解决说明</h2><div class="plain-text">{{ detail.resolutionNote }}</div></section>

        <section class="panel" aria-label="工单沟通和记录">
          <div class="section-tabs" role="tablist" aria-label="工单记录" @keydown="navigateRecords"><button id="public-tab" role="tab" :tabindex="tab === 'public' ? 0 : -1" :aria-selected="tab === 'public'" aria-controls="conversation-panel" :class="{ active: tab === 'public' }" @click="tab = 'public'">公开回复 {{ detail.publicReplies.length }}</button><button v-if="internalAllowed" id="internal-tab" role="tab" :tabindex="tab === 'internal' ? 0 : -1" :aria-selected="tab === 'internal'" aria-controls="conversation-panel" :class="{ active: tab === 'internal' }" @click="tab = 'internal'">内部备注 {{ detail.internalNotes.length }}</button><button id="history-tab" role="tab" :tabindex="tab === 'history' ? 0 : -1" :aria-selected="tab === 'history'" aria-controls="conversation-panel" :class="{ active: tab === 'history' }" @click="tab = 'history'">流转记录 {{ detail.transitions.length }}</button></div>
          <div id="conversation-panel" role="tabpanel" :aria-labelledby="`${tab}-tab`">
            <template v-if="tab !== 'history'"><p v-if="tab === 'internal'" class="info-notice">内部备注仅供有权限的处理人员查看，申请人无法读取。</p><div v-if="!comments.length" class="empty-state" style="padding:25px 0"><p>{{ tab === 'internal' ? '还没有内部备注' : '还没有公开回复，补充信息或开始沟通吧。' }}</p></div><article v-for="comment in comments" :key="comment.id" class="comment"><span class="avatar" aria-hidden="true">{{ comment.authorName.slice(0, 1) }}</span><div class="comment-content"><div class="comment-header"><strong>{{ comment.authorName }}</strong><time :datetime="comment.createdAt" :title="fullTime(comment.createdAt)">{{ formatTime(comment.createdAt) }}</time></div><p class="plain-text">{{ comment.body }}</p></div></article></template>
            <template v-else><ol v-if="detail.transitions.length" class="history-list"><li v-for="item in detail.transitions" :key="item.id"><div class="history-line"><strong>{{ item.actorName }}</strong><span>{{ statuses[item.fromStatus] || item.fromStatus }} → {{ statuses[item.toStatus] || item.toStatus }}</span></div><time :datetime="item.createdAt">{{ fullTime(item.createdAt) }}</time><p v-if="item.reason">{{ item.reason }}</p></li></ol><div v-else class="empty-state">工单尚无状态流转记录。</div></template>
            <form v-if="canComment" class="form-stack reply-form" @submit.prevent="sendComment"><div class="field"><label for="comment-body">{{ tab === 'internal' ? '添加内部备注' : '撰写公开回复' }}</label><textarea id="comment-body" v-model="drafts[visibility]" rows="4" maxlength="10000" required :placeholder="tab === 'internal' ? '记录排查过程，内容仅处理团队可见…' : '补充问题信息或回复服务团队…'"></textarea><small>{{ drafts[visibility].length.toLocaleString() }} / 10,000{{ tab === 'public' ? ' · 申请人与服务团队均可见' : '' }}</small></div><div class="form-actions"><el-button type="primary" native-type="submit" :disabled="!drafts[visibility].trim()" :loading="mutation.pending.value">{{ tab === 'internal' ? '保存备注' : '发送回复' }}</el-button></div></form>
          </div>
        </section>
      </div>

<aside class="detail-copilot-column" aria-label="AI Copilot 与相关知识"><button class="column-toggle" :aria-expanded="showCopilot" aria-controls="ticket-copilot-content" @click="showCopilot = !showCopilot">{{ showCopilot ? '收起 AI Copilot' : '展开 AI Copilot' }}</button><Teleport :disabled="!compact || !copilotHost" :to="copilotHost || 'body'"><div id="ticket-copilot-content" v-show="showCopilot"><AiSuggestionPanel v-if="detail.capabilities.canCommentPublic" :ticket-id="id" :ticket-version="detail.ticket.version" :can-edit="detail.capabilities.canEdit" :public-draft="drafts.PUBLIC_REPLY" :priority-draft="edit.priority" :tags-draft="edit.tags" @accepted="acceptAi" @unavailable="clearAiDraft" /><p v-if="!detail.capabilities.canCommentPublic" class="info-notice">当前账号或工单状态不允许采纳回复建议。</p><router-link to="/knowledge" class="related-knowledge-link">浏览授权知识 →</router-link></div></Teleport></aside>
    </div>
    <el-drawer v-model="propertiesDrawer" title="工单信息与操作" size="min(440px, 100vw)" class="ticket-properties-drawer" :destroy-on-close="false"><div ref="propertiesHost"></div></el-drawer>
    <el-drawer v-model="copilotDrawer" title="AI Copilot 与相关知识" size="min(440px, 100vw)" class="ticket-copilot-drawer" :destroy-on-close="false"><div ref="copilotHost"></div></el-drawer>
  </template>
</template>
