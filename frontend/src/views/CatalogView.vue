<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { api, ApiError, queryString } from '../api'
import { useOperation } from '../composables/operation'
import { useUnsavedChanges } from '../composables/unsaved'
import { useSessionStore } from '../stores/session'
import { useDialogFocus } from '../composables/dialogFocus'
import type { Page, Queue, QueueMember, Service, User } from '../types'
import ErrorNotice from '../components/ErrorNotice.vue'
import { Plus, Refresh, Edit, Search, User as UserIcon } from '@element-plus/icons-vue'
const props = defineProps<{ kind: 'queues' | 'services' }>()
const name = computed(() => props.kind === 'queues' ? '队列' : '服务'); const read = useOperation(); const write = useOperation(); const memberRead = useOperation(); const memberWrite = useOperation()
const result = ref<Page<Queue | Service>>({ items: [], total: 0, page: 0, size: 20 }); const items = computed(() => result.value.items)
const page = ref(1); const query = ref(''); const status = ref(''); const applied = ref({ q: '', active: '' })
const dialog = ref(false); const selected = ref<Queue | Service | null>(null); const form = reactive({ code: '', name: '', active: true })
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
async function load() { await read.run(async () => { result.value = await api.get<Page<Queue | Service>>(`/admin/${props.kind}?${queryString({ page: page.value - 1, size: 20, ...applied.value })}`) }) }
onMounted(load)
async function search() { if (read.pending.value) return; page.value = 1; applied.value = { q: query.value.trim(), active: status.value }; await load() }
async function reset() { if (read.pending.value) return; query.value = ''; status.value = ''; await search() }
async function changePage(next: number) { if (read.pending.value) return; page.value = next; await load() }
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
  <div class="page-heading"><div><h1>{{ kind === 'queues' ? '队列管理' : '服务目录' }}</h1><p class="muted">{{ kind === 'queues' ? '组织处理团队，为成员显式授予队列权限。' : '维护服务入口，帮助申请人准确描述服务需求。' }}</p></div><div class="heading-actions"><el-button type="primary" @click="open(undefined, $event)"><Plus class="action-icon" />新建{{ name }}</el-button></div></div>
  <ErrorNotice :error="read.error.value" reload @reload="load" />
  <section class="panel" :aria-busy="read.pending.value"><div class="panel-header"><h2>全部{{ name }} <span class="small muted">{{ result.total }} 项</span></h2><el-button plain :loading="read.pending.value" @click="load"><Refresh class="action-icon" />刷新</el-button></div><form id="catalog-filter-form" class="filter-form management-filter-form" @submit.prevent="search"><div class="field grow"><label for="catalog-query">搜索{{ name }}</label><Search class="search-icon" aria-hidden="true" /><input id="catalog-query" v-model="query" type="search" maxlength="160" placeholder="名称或编码" /></div><div class="field"><label for="catalog-status">状态</label><el-select id="catalog-status" v-model="status" class="catalog-status-filter" placeholder="全部状态"><el-option label="全部状态" value="" /><el-option label="启用" value="true" /><el-option label="停用" value="false" /></el-select></div><div class="filter-actions"><el-button type="primary" native-type="submit" :loading="read.pending.value">查询</el-button><el-button :disabled="read.pending.value" @click="reset">重置</el-button></div></form><div v-if="read.pending.value" class="loading-panel" role="status">正在加载{{ name }}…</div>
    <template v-else><div class="table-wrap desktop-table" role="region" :aria-label="`${name}列表`" tabindex="0"><table><thead><tr><th scope="col">名称</th><th scope="col">编码</th><th scope="col">状态</th><th scope="col">操作</th></tr></thead><tbody><tr v-for="item in items" :key="item.id"><td><strong>{{ item.name }}</strong></td><td>{{ item.code }}</td><td><span class="badge" :class="item.active ? 'resolved' : 'closed'">{{ item.active ? '启用' : '停用' }}</span></td><td><div class="inline-actions"><el-button text type="primary" @click="open(item, $event)"><Edit class="action-icon" />编辑</el-button><el-button v-if="kind === 'queues'" text type="primary" :disabled="memberWrite.pending.value || !!confirmedRemoval" @click="openMembers(item, $event)"><UserIcon class="action-icon" />管理成员</el-button></div></td></tr></tbody></table></div>
      <div class="mobile-cards"><article v-for="item in items" :key="item.id" class="ticket-card"><div class="panel-header"><div><strong>{{ item.name }}</strong><span class="ticket-number">{{ item.code }}</span></div><span class="badge" :class="item.active ? 'resolved' : 'closed'">{{ item.active ? '启用' : '停用' }}</span></div><div class="inline-actions"><el-button text type="primary" @click="open(item, $event)"><Edit class="action-icon" />编辑</el-button><el-button v-if="kind === 'queues'" text type="primary" :disabled="memberWrite.pending.value || !!confirmedRemoval" @click="openMembers(item, $event)"><UserIcon class="action-icon" />管理成员</el-button></div></article></div><div v-if="!items.length && !read.error.value" class="empty-state">{{ applied.q || applied.active ? `没有符合条件的${name}。` : `暂无${name}，可点击右上角创建。` }}</div></template><div class="pagination"><span>共 {{ result.total }} 项</span><el-pagination :current-page="page" :page-size="20" :total="result.total" :pager-count="5" :disabled="read.pending.value" layout="prev, pager, next" @current-change="changePage" /></div>
  </section>
  <el-dialog v-model="dialog" :title="`${selected ? '编辑' : '新建'}${name}`" width="460px" :close-on-click-modal="false" :before-close="beforeClose" @closed="dialogFocus.restore"><ErrorNotice :error="write.error.value" /><form class="form-stack dialog-form" @submit.prevent="save"><div class="field"><label for="catalog-code">编码</label><input id="catalog-code" v-model="form.code" :maxlength="kind === 'queues' ? 80 : 60" pattern="[A-Z][A-Z0-9_-]*" required :disabled="!!selected || write.pending.value" placeholder="例如 NETWORK_SUPPORT" /><small>以大写字母开头，可含大写字母、数字、下划线或连字符。创建后不可修改。</small></div><div class="field"><label for="catalog-name">名称</label><input id="catalog-name" v-model="form.name" :disabled="write.pending.value" maxlength="160" required /></div><label v-if="selected" class="checkbox-label"><input v-model="form.active" type="checkbox" :disabled="write.pending.value" />启用此{{ name }}</label><div class="form-actions"><el-button type="primary" native-type="submit" :loading="write.pending.value" :disabled="write.pending.value">保存</el-button><el-button :disabled="write.pending.value" @click="closeDialog">取消</el-button></div></form></el-dialog>
  <el-dialog v-model="memberDialog" :title="`队列成员 · ${memberQueue?.name || ''}`" width="640px" :close-on-click-modal="false" :before-close="beforeMemberClose" @closed="memberFocus.restore"><div ref="memberContent" tabindex="-1"><ErrorNotice :error="memberRead.error.value" reload @reload="loadMembers" /><ErrorNotice :error="memberWrite.error.value" /><div v-if="memberMessage" class="info-notice" role="status">{{ memberMessage }}</div><p class="small muted">成员资格与处理权限分别管理。只有启用且有处理权限的成员可被分配工单。</p>
    <div v-if="memberRead.pending.value" class="small muted" role="status">正在加载成员…</div>
    <div v-for="member in members" :key="member.userId" class="member-row"><div><strong class="small">{{ member.displayName }}</strong><span class="ticket-number">{{ member.username }} · {{ member.canProcess ? '可处理工单' : '仅成员' }}{{ !member.active ? ' · 账号停用' : '' }}</span></div><div class="inline-actions"><el-button size="small" :disabled="!membersReady" @click="grant(member.userId, !member.canProcess, member.queueId)">{{ member.canProcess ? '撤销处理权限' : '授予处理权限' }}</el-button><el-popconfirm title="确定将此成员移出队列？" confirm-button-text="移出" cancel-button-text="取消" @confirm="confirmRemoval(member.userId, member.queueId)" @hide="afterRemovalPopupHidden(member.userId, member.queueId)"><template #reference><el-button size="small" type="danger" plain :disabled="!membersReady">移出</el-button></template></el-popconfirm></div></div>
    <div v-if="!members.length && !memberRead.pending.value && !memberRead.error.value" class="empty-state" style="padding:25px">此队列暂无成员。</div>
    <form class="form-stack" style="padding-top:24px" @submit.prevent="grant()"><div class="field"><label for="member-user">添加或更新成员</label><select id="member-user" v-model="memberForm.userId" :disabled="!membersReady" required><option value="" disabled>选择一个启用的用户</option><option v-for="user in users.filter(user => user.active)" :key="user.id" :value="user.id">{{ user.displayName }}（{{ user.username }}）</option></select></div><el-pagination v-if="userTotal > 100" :current-page="userPage" :page-size="100" :total="userTotal" :disabled="memberRead.pending.value || memberWrite.pending.value || !!confirmedRemoval" layout="prev, pager, next" :pager-count="5" @current-change="userPageChange" /><label class="checkbox-label"><input v-model="memberForm.canProcess" type="checkbox" :disabled="!membersReady" />允许处理此队列工单</label><div class="form-actions"><el-button type="primary" native-type="submit" :loading="memberWrite.pending.value" :disabled="!membersReady || !memberForm.userId">保存成员</el-button></div></form>
  </div></el-dialog>
</template>
<style scoped>
.management-filter-form { margin-bottom: var(--space-5); }
.catalog-status-filter { width: 100%; }
.page-heading{margin-bottom:22px}.page-heading h1{font-size:26px;color:#1d3045}.page-heading p{margin-top:6px;color:#64778a}.panel{padding:20px;border:1px solid #dbe2e9;border-radius:10px;background:#fff}.panel-header{gap:12px;margin-bottom:18px}.panel-header h2{font-size:16px;color:#20364d}.management-filter-form{padding:14px;background:#f7f9fb;border:1px solid #e1e8ef;border-radius:8px;gap:12px}.management-filter-form .field label{font-size:12px;color:#5b6e81}.management-filter-form .field input{height:40px;border-color:#ccd8e3;border-radius:7px}.management-filter-form :deep(.el-select__wrapper){min-height:40px}.desktop-table table{width:100%;border-collapse:collapse}.desktop-table th{background:#f6f8fa;color:#627388;font-size:12px;font-weight:600}.desktop-table th,.desktop-table td{padding:13px 14px;border-bottom:1px solid #e7edf2;text-align:left}.desktop-table tbody tr:hover{background:#f8fafc}.desktop-table td strong{color:#223b54}.desktop-table :deep(.el-button.is-text){border:1px solid #d2dee9;border-radius:6px;padding:7px 12px}.pagination{padding-top:16px}.ticket-card{border-color:#dce5ed;border-radius:8px}
@media(max-width:640px){.panel{padding:15px}.management-filter-form{padding:12px}.page-heading h1{font-size:23px}}

.action-icon{width:14px;height:14px;margin-right:6px;flex:none}.panel{padding:0;overflow:hidden}.panel-header{padding:15px 18px;margin:0;border-bottom:1px solid #ebeff3}.panel-header h2 span{font-size:12px;font-weight:400;color:#718295;margin-left:5px}.management-filter-form{display:flex;align-items:end;flex-wrap:wrap;gap:9px;margin:0;padding:13px 16px;border:0;border-bottom:1px solid #e8edf2;border-radius:0;background:#fff}.management-filter-form .field{min-width:122px;flex:0 1 150px}.management-filter-form .field.grow{position:relative;min-width:180px;flex:1 1 250px;max-width:330px}.management-filter-form .field label{position:absolute;width:1px;height:1px;padding:0;margin:-1px;overflow:hidden;clip:rect(0,0,0,0);white-space:nowrap;border:0}.management-filter-form .search-icon{position:absolute;left:12px;top:11px;width:15px;height:15px;color:#657b91;pointer-events:none}.management-filter-form .field.grow input{padding-left:34px}.management-filter-form .field input,.management-filter-form :deep(.el-select__wrapper){height:36px;min-height:36px}.management-filter-form :deep(.el-button),.panel-header :deep(.el-button){min-height:34px}.desktop-table{margin:0;max-height:max(180px,calc(100dvh - 400px));overflow:auto}.desktop-table thead th{position:sticky;top:0;z-index:1}.desktop-table th,.desktop-table td{padding:10px 16px;font-size:12px}.desktop-table th{height:38px;font-size:11px}.desktop-table td{height:52px}.desktop-table :deep(.el-button.is-text){height:34px;padding:7px 12px}.pagination{padding:12px 16px;border-top:1px solid #edf1f5}.mobile-cards{padding:12px}.empty-state{margin:0 16px 16px}@media(max-width:640px){.panel{padding:0}.management-filter-form{padding:12px}.management-filter-form .field.grow{max-width:none;flex-basis:100%}.management-filter-form .field{flex:1 1 130px}}
</style>
<style scoped>
@media(max-width:640px){.desktop-table{display:none}.mobile-cards{display:grid;gap:10px;max-height:min(55dvh,500px);overflow:auto}.mobile-cards .ticket-card{min-width:0;padding:12px;border:1px solid #dce5ed;border-radius:8px}.mobile-cards .panel-header{padding:0 0 10px;border:0}.mobile-cards .panel-header>div{min-width:0;overflow-wrap:anywhere}.mobile-cards .ticket-number{display:block;margin-top:4px}.mobile-cards .inline-actions{gap:6px}}
</style>
