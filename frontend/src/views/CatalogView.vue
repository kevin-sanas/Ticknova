<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api, ApiError } from '../api'
import { useOperation } from '../composables/operation'
import { useUnsavedChanges } from '../composables/unsaved'
import { useSessionStore } from '../stores/session'
import { useDialogFocus } from '../composables/dialogFocus'
import type { Page, Queue, QueueMember, Service, User } from '../types'
import ErrorNotice from '../components/ErrorNotice.vue'
const props = defineProps<{ kind: 'queues' | 'services' }>()
const name = computed(() => props.kind === 'queues' ? '队列' : '服务'); const read = useOperation(); const write = useOperation(); const memberRead = useOperation(); const memberWrite = useOperation()
const items = ref<(Queue | Service)[]>([]); const dialog = ref(false); const selected = ref<Queue | Service | null>(null); const form = reactive({ code: '', name: '', active: true })
const memberDialog = ref(false); const memberQueue = ref<Queue | Service | null>(null); const members = ref<(QueueMember & { queueId: string })[]>([]); const users = ref<User[]>([]); const userPage = ref(1); const userTotal = ref(0); const memberForm = reactive({ userId: '', canProcess: true }); const memberMessage = ref('')
const loadedMemberQueueId = ref('')
const memberContent = ref<HTMLElement | null>(null)
const confirmedRemoval = ref<{ userId: string; queueId: string } | null>(null)
const session = useSessionStore(); const savedForm = ref('')
const dialogFocus = useDialogFocus(); const memberFocus = useDialogFocus()
const formSnapshot = () => JSON.stringify(form)
const { confirmDiscard } = useUnsavedChanges(() => session.authenticated && ((dialog.value && formSnapshot() !== savedForm.value) || (memberDialog.value && !!memberForm.userId)))
let memberReadGeneration = 0
const membersReady = computed(() => memberDialog.value && !!loadedMemberQueueId.value && loadedMemberQueueId.value === memberQueue.value?.id && !memberRead.pending.value && !memberWrite.pending.value && !confirmedRemoval.value)
function clearMemberRows() { loadedMemberQueueId.value = ''; members.value = []; users.value = []; userTotal.value = 0 }
function invalidateMemberRead() { memberReadGeneration++; clearMemberRows(); memberRead.pending.value = false; confirmedRemoval.value = null }
watch(memberDialog, (visible) => { if (!visible) invalidateMemberRead() })
onBeforeUnmount(invalidateMemberRead)
async function load() { await read.run(async () => { items.value = await api.get<(Queue | Service)[]>(`/${props.kind}${props.kind === 'services' ? '?includeInactive=true' : ''}`) }) }
onMounted(load)
function open(item?: Queue | Service, event?: Event) { if (write.pending.value) return; dialogFocus.capture(event); selected.value = item || null; Object.assign(form, { code: item?.code || '', name: item?.name || '', active: item?.active ?? true }); savedForm.value = formSnapshot(); write.error.value = null; dialog.value = true }
async function save() { if (write.pending.value) return; await write.run(async () => { if (selected.value) await api.patch(`/${props.kind}/${selected.value.id}`, { name: form.name.trim(), active: form.active }); else await api.post(`/${props.kind}`, { code: form.code.trim(), name: form.name.trim() }); dialog.value = false; await load() }) }
async function loadMembers() {
  const queueId = memberQueue.value?.id
  if (!queueId || !memberDialog.value) return
  const generation = ++memberReadGeneration
  const requestedPage = userPage.value - 1
  // Preserve same-queue keyed rows so refresh never tears down a focus owner.
  // A different queue is still cleared immediately, and failed reads purge it.
  if (loadedMemberQueueId.value !== queueId) clearMemberRows()
  memberRead.pending.value = true; memberRead.error.value = null
  const isCurrent = () => generation === memberReadGeneration && memberDialog.value && memberQueue.value?.id === queueId
  try {
    const [memberData, userData] = await Promise.all([api.get<QueueMember[]>(`/queues/${queueId}/members`), api.get<Page<User>>(`/users?page=${requestedPage}&size=100`)])
    if (!isCurrent()) return
    members.value = memberData.map(member => ({ ...member, queueId })); users.value = userData.items; userTotal.value = userData.total; loadedMemberQueueId.value = queueId
  } catch (failure) {
    if (isCurrent()) { clearMemberRows(); memberRead.error.value = failure instanceof ApiError ? failure : new ApiError(0, { title: '无法加载队列成员，请重试。', status: 0 }) }
  } finally { if (isCurrent()) memberRead.pending.value = false }
}
async function openMembers(item: Queue | Service, event?: Event) {
  if (memberWrite.pending.value || confirmedRemoval.value) return
  memberFocus.capture(event)
  invalidateMemberRead(); memberQueue.value = item; memberForm.userId = ''; memberForm.canProcess = true; memberMessage.value = ''; memberWrite.error.value = null; userPage.value = 1; memberDialog.value = true
  await loadMembers()
}
async function grant(userId = memberForm.userId, canProcess = memberForm.canProcess, queueId = loadedMemberQueueId.value) {
  if (!membersReady.value || !userId || queueId !== memberQueue.value?.id) return
  memberMessage.value = ''
  const complete = await memberWrite.run(async () => {
    await api.put(`/queues/${queueId}/members/${userId}`, { canProcess })
    if (memberDialog.value && memberQueue.value?.id === queueId) { memberForm.userId = ''; await loadMembers(); return !memberRead.error.value }
    return false
  })
  await restoreMemberFocus()
  if (complete && memberDialog.value && memberQueue.value?.id === queueId) memberMessage.value = '队列成员权限已保存。'
}
function confirmRemoval(userId: string, queueId: string) {
  if (membersReady.value && queueId === memberQueue.value?.id) confirmedRemoval.value = { userId, queueId }
}
async function afterRemovalPopupHidden(userId: string, queueId: string) {
  // Tooltip's hide event precedes its final focus cleanup. Let it release its
  // focus layer before the mutation can remove the trigger and its popconfirm.
  await nextTick()
  if (confirmedRemoval.value?.userId === userId && confirmedRemoval.value.queueId === queueId) {
    confirmedRemoval.value = null
    await remove(userId, queueId)
  } else await restoreMemberFocus()
}
async function restoreMemberFocus() {
  await nextTick()
  if (memberDialog.value && memberContent.value?.isConnected && (!document.activeElement?.isConnected || document.activeElement === document.body)) memberContent.value.focus({ preventScroll: true })
}
async function remove(userId: string, queueId: string) {
  if (!membersReady.value || queueId !== memberQueue.value?.id) return
  memberMessage.value = ''
  const complete = await memberWrite.run(async () => {
    await api.delete(`/queues/${queueId}/members/${userId}`)
    if (memberDialog.value && memberQueue.value?.id === queueId) { await loadMembers(); return !memberRead.error.value }
    return false
  })
  await restoreMemberFocus()
  if (complete && memberDialog.value && memberQueue.value?.id === queueId) memberMessage.value = '成员已移出此队列。'
}
async function userPageChange(next: number) { if (memberRead.pending.value || memberWrite.pending.value || confirmedRemoval.value) return; userPage.value = next; await loadMembers() }
async function beforeClose(done: () => void) { if (!write.pending.value && await confirmDiscard()) done() }
async function closeDialog() { await beforeClose(() => { dialog.value = false }) }
async function beforeMemberClose(done: () => void) { if (!memberWrite.pending.value && !confirmedRemoval.value && await confirmDiscard()) { invalidateMemberRead(); memberForm.userId = ''; done() } }
</script>
<template>
  <div class="page-heading"><div><h1>{{ kind === 'queues' ? '队列管理' : '服务目录' }}</h1><p class="muted">{{ kind === 'queues' ? '组织处理团队，为成员显式授予队列权限。' : '维护服务入口，帮助申请人准确描述服务需求。' }}</p></div><div class="heading-actions"><el-button type="primary" @click="open(undefined, $event)">＋ 新建{{ name }}</el-button></div></div>
  <ErrorNotice :error="read.error.value" reload @reload="load" />
  <section class="panel" :aria-busy="read.pending.value"><div class="panel-header"><h2>全部{{ name }} <span class="small muted">{{ items.length }} 项</span></h2><el-button text :loading="read.pending.value" @click="load">刷新</el-button></div><div v-if="read.pending.value" class="loading-panel" role="status">正在加载{{ name }}…</div>
    <template v-else><div class="table-wrap desktop-table"><table><thead><tr><th scope="col">名称</th><th scope="col">编码</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead><tbody><tr v-for="item in items" :key="item.id"><td><strong>{{ item.name }}</strong></td><td>{{ item.code }}</td><td><span class="badge" :class="item.active ? 'resolved' : 'closed'">{{ item.active ? '启用' : '停用' }}</span></td><td><div class="inline-actions"><el-button text type="primary" @click="open(item, $event)">编辑</el-button><el-button v-if="kind === 'queues'" text type="primary" :disabled="memberWrite.pending.value || !!confirmedRemoval" @click="openMembers(item, $event)">管理成员</el-button></div></td></tr></tbody></table></div>
      <div class="mobile-cards"><article v-for="item in items" :key="item.id" class="ticket-card"><div class="panel-header"><div><strong>{{ item.name }}</strong><span class="ticket-number">{{ item.code }}</span></div><span class="badge" :class="item.active ? 'resolved' : 'closed'">{{ item.active ? '启用' : '停用' }}</span></div><div class="inline-actions"><el-button text type="primary" @click="open(item, $event)">编辑</el-button><el-button v-if="kind === 'queues'" text type="primary" :disabled="memberWrite.pending.value || !!confirmedRemoval" @click="openMembers(item, $event)">管理成员</el-button></div></article></div><div v-if="!items.length && !read.error.value" class="empty-state">暂无{{ name }}，可点击右上角创建。</div></template>
  </section>
  <el-dialog v-model="dialog" :title="`${selected ? '编辑' : '新建'}${name}`" width="460px" :close-on-click-modal="false" :before-close="beforeClose" @closed="dialogFocus.restore"><ErrorNotice :error="write.error.value" /><form class="form-stack dialog-form" @submit.prevent="save"><div class="field"><label for="catalog-code">编码</label><input id="catalog-code" v-model="form.code" :maxlength="kind === 'queues' ? 80 : 60" pattern="[A-Z][A-Z0-9_-]*" required :disabled="!!selected || write.pending.value" placeholder="例如 NETWORK_SUPPORT" /><small>以大写字母开头，可含大写字母、数字、下划线或连字符。创建后不可修改。</small></div><div class="field"><label for="catalog-name">名称</label><input id="catalog-name" v-model="form.name" :disabled="write.pending.value" maxlength="160" required /></div><label v-if="selected" class="checkbox-label"><input v-model="form.active" type="checkbox" :disabled="write.pending.value" />启用此{{ name }}</label><div class="form-actions"><el-button type="primary" native-type="submit" :loading="write.pending.value" :disabled="write.pending.value">保存</el-button><el-button :disabled="write.pending.value" @click="closeDialog">取消</el-button></div></form></el-dialog>
  <el-dialog v-model="memberDialog" :title="`队列成员 · ${memberQueue?.name || ''}`" width="640px" :close-on-click-modal="false" :before-close="beforeMemberClose" @closed="memberFocus.restore"><div ref="memberContent" tabindex="-1"><ErrorNotice :error="memberRead.error.value" reload @reload="loadMembers" /><ErrorNotice :error="memberWrite.error.value" /><div v-if="memberMessage" class="info-notice" role="status">{{ memberMessage }}</div><p class="small muted">成员资格与处理权限分别管理。只有启用且有处理权限的成员可被分配工单。</p>
    <div v-if="memberRead.pending.value" class="small muted" role="status">正在加载成员…</div>
    <div v-for="member in members" :key="member.userId" class="member-row"><div><strong class="small">{{ member.displayName }}</strong><span class="ticket-number">{{ member.username }} · {{ member.canProcess ? '可处理工单' : '仅成员' }}{{ !member.active ? ' · 账号停用' : '' }}</span></div><div class="inline-actions"><el-button size="small" :disabled="!membersReady" @click="grant(member.userId, !member.canProcess, member.queueId)">{{ member.canProcess ? '撤销处理权限' : '授予处理权限' }}</el-button><el-popconfirm title="确定将此成员移出队列？" confirm-button-text="移出" cancel-button-text="取消" @confirm="confirmRemoval(member.userId, member.queueId)" @hide="afterRemovalPopupHidden(member.userId, member.queueId)"><template #reference><el-button size="small" type="danger" plain :disabled="!membersReady">移出</el-button></template></el-popconfirm></div></div>
    <div v-if="!members.length && !memberRead.pending.value" class="empty-state" style="padding:25px">此队列暂无成员。</div>
    <form class="form-stack" style="padding-top:24px" @submit.prevent="grant()"><div class="field"><label for="member-user">添加或更新成员</label><select id="member-user" v-model="memberForm.userId" :disabled="!membersReady" required><option value="" disabled>选择一个启用的用户</option><option v-for="user in users.filter(user => user.active)" :key="user.id" :value="user.id">{{ user.displayName }}（{{ user.username }}）</option></select></div><el-pagination v-if="userTotal > 100" :current-page="userPage" :page-size="100" :total="userTotal" :disabled="memberRead.pending.value || memberWrite.pending.value || !!confirmedRemoval" layout="prev, pager, next" :pager-count="5" @current-change="userPageChange" /><label class="checkbox-label"><input v-model="memberForm.canProcess" type="checkbox" :disabled="!membersReady" />允许处理此队列工单</label><div class="form-actions"><el-button type="primary" native-type="submit" :loading="memberWrite.pending.value" :disabled="!membersReady || !memberForm.userId">保存成员</el-button></div></form>
  </div></el-dialog>
</template>
