<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, reactive, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { api, ApiError, createRequestKey } from '../api'
import { useSessionStore } from '../stores/session'
import { useUnsavedChanges } from '../composables/unsaved'
import { roles as roleNames } from '../types'
import type { KnowledgeCandidate, KnowledgeMember, KnowledgeSpace } from '../types'
import KnowledgeError from '../components/KnowledgeError.vue'

const route = useRoute(); const router = useRouter(); const session = useSessionStore()
const admin = computed(() => session.authenticated && !!session.user?.roles.includes('ADMIN'))
const spaces = ref<KnowledgeSpace[]>([]); const spaceId = ref(''); const selectedSpace = computed(() => spaces.value.find(space => space.id === spaceId.value))
const members = ref<KnowledgeMember[]>([]); const candidates = ref<KnowledgeCandidate[]>([]); const membersOpen = ref(false); const createOpen = ref(false); const newSpaceName = ref('')
const error = shallowRef<ApiError | null>(null); const memberError = shallowRef<ApiError | null>(null); const loading = ref(false); const memberLoading = ref(false); const pending = ref(false); const message = ref(''); const validation = ref('')
const form = reactive({ userId: '', canRead: true, canEdit: false, canReview: false, reason: '' })
const dirty = computed(() => !!form.userId || !!form.reason || !!newSpaceName.value)
const { confirmDiscard } = useUnsavedChanges(() => dirty.value)
onBeforeRouteUpdate((to, from) => to.query.spaceId !== from.query.spaceId ? confirmDiscard() : true)
const createKey = createRequestKey(); let generation = 0; let memberGeneration = 0; let controller: AbortController | undefined; let memberController: AbortController | undefined; let writeController: AbortController | undefined
function resetForm() { Object.assign(form, { userId: '', canRead: true, canEdit: false, canReview: false, reason: '' }); validation.value = '' }
function clearMembers() { memberGeneration++; memberController?.abort(); writeController?.abort(); writeController = undefined; members.value = []; candidates.value = []; memberLoading.value = false; pending.value = false; resetForm() }
function purge() { generation++; controller?.abort(); clearMembers(); spaces.value = []; spaceId.value = ''; membersOpen.value = false; createOpen.value = false; newSpaceName.value = ''; loading.value = false; message.value = '' }
function problem(failure: unknown) { return failure instanceof ApiError ? failure : new ApiError(0, { status: 0, title: '操作未完成，请重试。' }) }
function denied(issue: ApiError) { if (![401, 403, 404].includes(issue.status)) return false; purge(); error.value = issue; return true }
function roles(values?: string[]) { return (values || []).map(role => roleNames[role] || role).join(' · ') || '未提供角色' }
async function load() {
  if (!admin.value) return
  const own = ++generation; controller?.abort(); controller = new AbortController(); loading.value = true; error.value = null
  try {
    const result = await api.get<KnowledgeSpace[]>('/knowledge/spaces', { signal: controller.signal }); if (own !== generation) return
    spaces.value = result.filter(space => space.canManageMembers)
    const requested = route.query.spaceId
    if (requested != null && (typeof requested !== 'string' || !spaces.value.some(space => space.id === requested))) { clearMembers(); spaceId.value = ''; error.value = new ApiError(404, { status: 404, title: '此知识空间不存在或无管理权限。' }); return }
    spaceId.value = typeof requested === 'string' ? requested : spaces.value.find(space => space.id === spaceId.value)?.id || spaces.value[0]?.id || ''
  } catch (failure) { if (own === generation && !(failure instanceof DOMException && failure.name === 'AbortError')) { const issue = problem(failure); if (!denied(issue)) error.value = issue } }
  finally { if (own === generation) loading.value = false }
}
async function selectSpace(id: string) {
  if (!await confirmDiscard()) return
  membersOpen.value = false; clearMembers(); memberError.value = null; newSpaceName.value = ''; await router.replace({ query: { spaceId: id } })
}
async function loadMembers() {
  if (!admin.value || !selectedSpace.value?.canManageMembers) return
  const id = spaceId.value; const own = ++memberGeneration; memberController?.abort(); memberController = new AbortController(); memberLoading.value = true; memberError.value = null; members.value = []; candidates.value = []
  try {
    const [rows, users] = await Promise.all([api.get<KnowledgeMember[]>(`/knowledge/spaces/${id}/members`, { signal: memberController.signal }), api.get<KnowledgeCandidate[]>(`/knowledge/spaces/${id}/member-candidates`, { signal: memberController.signal })])
    if (own === memberGeneration) { members.value = rows; candidates.value = users }
  } catch (failure) { if (own === memberGeneration && !(failure instanceof DOMException && failure.name === 'AbortError')) { const issue = problem(failure); if (!denied(issue)) memberError.value = issue } }
  finally { if (own === memberGeneration) memberLoading.value = false }
}
async function openMembers() { if (!selectedSpace.value?.canManageMembers) return; membersOpen.value = true; await loadMembers(); await nextTick(); document.getElementById('knowledge-member-user')?.focus() }
async function closeMembers(done: () => void) { if (!pending.value && await confirmDiscard()) { resetForm(); memberError.value = null; clearMembers(); done() } }
async function closeCreate(done: () => void) { if (!pending.value && await confirmDiscard()) { newSpaceName.value = ''; done() } }
function editMember(member: KnowledgeMember) { Object.assign(form, { userId: member.userId, canRead: member.canRead, canEdit: member.canEdit, canReview: member.canReview === true, reason: '' }); memberError.value = null }
async function saveMember() {
  if (!admin.value || !selectedSpace.value?.canManageMembers || memberLoading.value || pending.value || !form.userId || !form.reason.trim()) return
  validation.value = ''
  if (form.canEdit && form.canReview) { validation.value = '编辑与审核权限不能同时授予同一空间成员。'; return }
  if (!form.canRead && (form.canEdit || form.canReview)) { validation.value = '编辑或审核权限需要同时允许阅读。'; return }
  const id = spaceId.value; const own = memberGeneration; const payload = { canRead: form.canRead, canEdit: form.canEdit, canReview: form.canReview, reason: form.reason.trim() }; pending.value = true; memberError.value = null; const control = new AbortController(); writeController = control
  try { await api.put(`/knowledge/spaces/${id}/members/${form.userId}`, payload, { signal: writeController.signal }); if (own !== memberGeneration) return; resetForm(); message.value = '成员权限已保存。'; await loadMembers() }
  catch (failure) { if (own === memberGeneration && !(failure instanceof DOMException && failure.name === 'AbortError')) { const issue = problem(failure); if (!denied(issue)) memberError.value = issue } }
  finally { if (writeController === control) pending.value = false }
}
async function createSpace() {
  if (!admin.value || pending.value || !newSpaceName.value.trim()) return
  const own = generation; pending.value = true; error.value = null; const control = new AbortController(); writeController = control; const payload = { name: newSpaceName.value.trim() }
  try { const result = await api.post<KnowledgeSpace>('/knowledge/spaces', payload, { signal: writeController.signal, key: createKey.for(payload) }); if (own !== generation) return; newSpaceName.value = ''; createKey.reset(); createOpen.value = false; message.value = '知识空间已创建。'; await router.replace({ query: { spaceId: result.id } }); await load() }
  catch (failure) { if (own === generation && !(failure instanceof DOMException && failure.name === 'AbortError')) { const issue = problem(failure); if (!denied(issue)) error.value = issue } }
  finally { if (writeController === control) pending.value = false }
}
watch(newSpaceName, () => createKey.reset(), { flush: 'sync' })
watch(() => route.query.spaceId, () => { clearMembers(); membersOpen.value = false; memberError.value = null; void load() })
watch([() => session.token, () => session.user?.id, admin], () => { purge(); error.value = null; memberError.value = null; if (admin.value) void load() }, { flush: 'sync' })
onBeforeUnmount(purge); void load()
</script>
<template>
  <div class="page-heading"><div><h1>知识空间管理</h1><p class="muted">管理空间与成员。文档阅读、编辑和审核权限分别授予。</p></div><el-button v-if="admin" type="primary" @click="createOpen = true">创建空间</el-button></div>
  <KnowledgeError :error="error" retry @retry="load" /><p v-if="message" class="info-notice" role="status">{{ message }}</p>
  <section v-if="admin" class="panel knowledge-section"><div class="knowledge-toolbar"><div class="field grow"><label for="knowledge-space">知识空间</label><select id="knowledge-space" :value="spaceId" :disabled="loading || pending" @change="selectSpace(($event.target as HTMLSelectElement).value)"><option value="" disabled>选择知识空间</option><option v-for="space in spaces" :key="space.id" :value="space.id">{{ space.name }}{{ space.active ? '' : '（已停用）' }}</option></select></div><el-button :loading="loading" @click="load">刷新空间</el-button><el-button v-if="selectedSpace" :disabled="loading || pending" @click="openMembers">管理成员</el-button></div><p v-if="loading" role="status">正在加载知识空间…</p><p v-else-if="!spaces.length && !error" class="empty-state">暂无可管理的知识空间。</p><div v-if="selectedSpace" class="space-summary"><h2>{{ selectedSpace.name }}</h2><p class="muted">{{ selectedSpace.active ? '空间启用中' : '空间已停用' }} · {{ selectedSpace.canRead ? '你拥有此空间阅读权限' : '你未被授予此空间阅读权限' }}</p><router-link v-if="selectedSpace.canRead" :to="{ path: '/knowledge', query: { spaceId } }">查看空间文档 →</router-link></div></section>
  <p v-else class="empty-state">当前账号没有空间管理权限。</p>
  <el-dialog v-model="createOpen" title="创建知识空间" width="min(520px, 94vw)" :before-close="closeCreate" :close-on-click-modal="false" destroy-on-close><KnowledgeError :error="error" /><form id="knowledge-space-create" class="form-stack" @submit.prevent="createSpace"><label for="knowledge-space-name">空间名称</label><input id="knowledge-space-name" v-model="newSpaceName" maxlength="160" required :disabled="pending" /><el-button native-type="submit" type="primary" :loading="pending" :disabled="!newSpaceName.trim()">保存空间</el-button></form></el-dialog>
  <el-dialog v-model="membersOpen" :title="`空间成员 · ${selectedSpace?.name || ''}`" width="min(760px, 94vw)" :before-close="closeMembers" :close-on-click-modal="false" destroy-on-close>
    <p class="muted">编辑者需要知识编辑角色，审核者需要知识审核角色。编辑与审核权限互斥。</p><KnowledgeError :error="memberError" retry @retry="loadMembers" /><p v-if="memberLoading" role="status">正在加载成员…</p>
    <ul class="member-list"><li v-for="member in members" :key="member.userId"><div><strong>{{ member.displayName }}</strong><p class="small muted">{{ member.username }} · {{ roles(member.roles) }}</p><p class="small">{{ member.canRead ? '可阅读' : '不可阅读' }} · {{ member.canEdit ? '可编辑' : '不可编辑' }} · {{ member.canReview === true ? '可审核' : '不可审核' }}</p></div><el-button :disabled="memberLoading || pending" @click="editMember(member)">修改权限</el-button></li></ul><p v-if="!memberLoading && !members.length && !memberError" class="empty-state">此空间暂无成员。</p>
    <form id="knowledge-member-form" class="form-stack" @submit.prevent="saveMember"><div class="field"><label for="knowledge-member-user">添加或修改成员</label><select id="knowledge-member-user" v-model="form.userId" required :disabled="memberLoading || pending || !!memberError && !candidates.length"><option value="" disabled>选择启用的用户</option><option v-for="user in candidates" :key="user.id" :value="user.id">{{ user.displayName }}（{{ user.username }}） · {{ roles(user.roles) }}</option></select></div><div class="checkbox-group"><label class="checkbox-label"><input id="knowledge-member-read" v-model="form.canRead" type="checkbox" :disabled="pending" />允许阅读</label><label class="checkbox-label"><input id="knowledge-member-edit" v-model="form.canEdit" type="checkbox" :disabled="pending" />允许编辑</label><label class="checkbox-label"><input id="knowledge-member-review" v-model="form.canReview" type="checkbox" :disabled="pending" />允许审核</label></div><p v-if="validation" role="alert" class="error-text">{{ validation }}</p><div class="field"><label for="knowledge-member-reason">变更理由</label><input id="knowledge-member-reason" v-model="form.reason" required maxlength="500" :disabled="pending" /></div><el-button native-type="submit" type="primary" :loading="pending" :disabled="memberLoading || !form.userId || !form.reason.trim()">保存成员权限</el-button></form>
  </el-dialog>
</template>
<style scoped>
.space-summary { border-top: 1px solid var(--color-border); margin-top: var(--space-5); padding-top: var(--space-4); }
.member-list { list-style: none; padding: 0; margin: var(--space-4) 0; max-height: 35vh; overflow: auto; }
.member-list li { display: flex; justify-content: space-between; gap: var(--space-4); align-items: center; padding-block: var(--space-3); border-bottom: 1px solid var(--color-border); }
.member-list li > div { min-width: 0; overflow-wrap: anywhere; }
@media (max-width: 768px) { .member-list li { flex-wrap: wrap; } .checkbox-group { flex-wrap: wrap; gap: var(--space-3); } }
</style>
