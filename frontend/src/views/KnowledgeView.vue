<script setup lang="ts">
import { computed, inject, nextTick, onBeforeUnmount, reactive, ref, shallowRef, watch } from 'vue'
import { routeLocationKey, routerKey } from 'vue-router'
import { api, ApiError, createRequestKey, queryString } from '../api'
import { useSessionStore } from '../stores/session'
import { formatTime } from '../types'
import type { KnowledgeSpace, KnowledgeDocument, KnowledgeDetail, KnowledgeUpload, KnowledgeChunk, KnowledgeMember, KnowledgeCandidate, KnowledgeVersion, KnowledgeDraft, KnowledgeCategory, KnowledgeReviewTask, Page, IngestState } from '../types'
import RichDocument from '../components/RichDocument.vue'
import KnowledgeCategories from '../components/KnowledgeCategories.vue'
import { chunksRichDoc, emptyRichDoc, normalizeRichDoc, richHeadings, type RichDoc } from '../knowledge/rich'
import KnowledgeError from '../components/KnowledgeError.vue'
import KnowledgeFilePicker from '../components/KnowledgeFilePicker.vue'
import KnowledgeReviewQueue from '../components/KnowledgeReviewQueue.vue'

// Each channel owns its result. Abort is an optimization; generation checks also
// discard completions from servers and fetch implementations that ignore abort.
function operation() {
  const pending = ref(false); const error = shallowRef<ApiError | null>(null)
  let generation = 0; let controller: AbortController | undefined
  function cancel() { generation++; controller?.abort(); pending.value = false; error.value = null }
  async function run(task: (signal: AbortSignal, current: () => boolean) => Promise<void>) {
    cancel(); const own = generation; controller = new AbortController(); pending.value = true
    const current = () => own === generation
    try { await task(controller.signal, current) }
    catch (failure) { if (current() && !(failure instanceof DOMException && failure.name === 'AbortError')) error.value = failure instanceof ApiError ? failure : new ApiError(0, { status: 0, title: '操作未完成，请重试。' }) }
    finally { if (current()) pending.value = false }
  }
  return { pending, error, run, cancel }
}
const route = inject(routeLocationKey, null); const router = inject(routerKey, null)
const session = useSessionStore(); const spacesRead = operation(); const listRead = operation(); const detailRead = operation(); const write = operation(); const createWrite = operation(); const memberRead = operation(); const memberWrite = operation(); const chunkRead = operation(); const categoryRouteRead = operation()
const reviewOpen = ref(false); const reviewRevision = ref(0)
const operations = [spacesRead, listRead, detailRead, write, createWrite, memberRead, memberWrite, chunkRead, categoryRouteRead]
const accessFailure = shallowRef<ApiError | null>(null)
const routeApplying = ref(false); const categoryRouteError = ref(''); const categoryRouteRows = shallowRef<KnowledgeCategory[] | null>(null); let routeGeneration = 0
const unavailableCategory = '分类入口不可用：空间或分类不存在，或当前账号没有阅读权限。请从知识空间重新选择，或返回门户。'
const spaceRequestKey = createRequestKey(); const uploadRequestKey = createRequestKey()
const spaces = ref<KnowledgeSpace[]>([]); const spaceId = ref(''); const selectedSpace = computed(() => spaces.value.find(space => space.id === spaceId.value))
const editorRole = computed(() => !!session.user?.roles.some(role => ['KB_EDITOR', 'ADMIN'].includes(role)))
const reviewEligible = computed(() => !!session.user?.roles.includes('KB_EDITOR'))
const spaceEditable = computed(() => editorRole.value && !!selectedSpace.value?.active && !!selectedSpace.value?.canEdit && !!selectedSpace.value?.canRead)
const documents = ref<KnowledgeDocument[]>([]); const total = ref(0); const page = ref(0); const query = ref(''); const appliedQuery = ref('')
const selectedId = ref(''); const detail = ref<KnowledgeDetail | null>(null); const documentEditable = computed(() => spaceEditable.value && !!detail.value?.canEdit)
const title = ref(''); const uploadFile = shallowRef<File | null>(null); const versionFile = shallowRef<File | null>(null); const fileReset = ref(0); const versionFileReset = ref(0)
const message = ref(''); const createOpen = ref(false); const newSpaceName = ref(''); const membersOpen = ref(false); const members = ref<KnowledgeMember[]>([]); const candidates = ref<KnowledgeCandidate[]>([]); const membersLoaded = ref('')
const memberForm = reactive({ userId: '', canRead: true, canEdit: false, reason: '' }); const confirmation = ref<'disable' | 'archive' | ''>('')
const chunks = ref<KnowledgeChunk[]>([]); const chunksVersion = ref(''); const chunkSelection = ref<KnowledgeVersion | null>(null)
const citationUnavailable = ref(''); const highlightedChunk = ref(''); let activeReference = ''
const unavailableCitation = '引用已不可用：版本或片段不存在、已撤回或无权访问。请重新生成回答，或从文档列表选择可读内容。'
const referenceIdentity = () => JSON.stringify([route?.query.documentId, route?.query.versionId, route?.query.chunkId])
const categoryId = ref(''); const articleTitle = ref(''); const articleOpen = ref(false); const uploadProgress = ref<number | null>(null); const rejectVersion = ref(''); const rejectReason = ref(''); const articleKey = createRequestKey(); const viewed = new Set<string>()
const readerSnapshot = shallowRef<RichDoc | null>(null)
const readerState = computed(() => {
  try { return { content: readerSnapshot.value || chunksRichDoc(chunks.value), error: null } }
  catch (failure) { return { content: emptyRichDoc(), error: new ApiError(422, { status: 422, title: (failure as Error).message || '正文暂时无法显示，请查看下方来源片段或重新加载。' }) } }
})
const readerContent = computed(() => readerState.value.content); const outline = computed(() => readerState.value.error ? [] : richHeadings(readerContent.value)); const reviewLabels = { DRAFT: '待提交审核', PENDING: '待审核', APPROVED: '审核通过', REJECTED: '审核不通过' }
const states: Record<IngestState, string> = { QUEUED: '排队中', EXTRACTING: '解析中', CHUNKING: '分块中', READY: '解析就绪', FAILED: '解析失败' }
let pollTimer: ReturnType<typeof setTimeout> | undefined
function stopPoll() { if (pollTimer) clearTimeout(pollTimer); pollTimer = undefined }
function clearDetail() { activeReference = ''; citationUnavailable.value = ''; highlightedChunk.value = ''; rejectVersion.value = ''; rejectReason.value = ''; uploadProgress.value = null; stopPoll(); detailRead.cancel(); chunkRead.cancel(); write.cancel(); selectedId.value = ''; detail.value = null; chunks.value = []; readerSnapshot.value = null; chunksVersion.value = ''; chunkSelection.value = null; confirmation.value = ''; versionFile.value = null; versionFileReset.value++ }
function clearSpace() {
  categoryId.value = ''; articleTitle.value = ''; articleOpen.value = false; articleKey.reset(); listRead.cancel(); memberRead.cancel(); memberWrite.cancel(); clearDetail(); documents.value = []; total.value = 0; page.value = 0; query.value = ''; appliedQuery.value = ''; title.value = ''; uploadFile.value = null; fileReset.value++; membersOpen.value = false; members.value = []; candidates.value = []; membersLoaded.value = ''; Object.assign(memberForm, { userId: '', canRead: true, canEdit: false, reason: '' }); message.value = ''
}
async function loadSpaces() {
  if (!session.authenticated) return
  await spacesRead.run(async (signal, current) => {
    const result = await api.get<KnowledgeSpace[]>('/knowledge/spaces', { signal })
    if (!current()) return
    spaces.value = result.filter(space => space.canRead || space.canManageMembers || session.user?.roles.includes('ADMIN'))
    if (route?.query.documentId != null || route?.query.spaceId != null || route?.query.categoryId != null) { await applyKnowledgeRoute(); return }
    if (!spaces.value.some(space => space.id === spaceId.value)) spaceId.value = spaces.value.find(space => space.canRead)?.id || spaces.value[0]?.id || ''
    else if (!selectedSpace.value?.canRead) clearSpace()
    if (current()) await openDeepLink()
  })
}
async function loadDocuments() {
  const id = spaceId.value
  if (routeApplying.value || categoryRouteError.value || accessFailure.value || !id || !selectedSpace.value?.canRead || !session.authenticated) return
  await listRead.run(async (signal, current) => {
    const result = await api.get<Page<KnowledgeDocument>>(`/knowledge/spaces/${id}/documents?${queryString({ page: page.value, size: 20, q: appliedQuery.value, categoryId: categoryId.value })}`, { signal })
    if (!current()) return
    documents.value = result.items; total.value = result.total
  })
}
async function search() { page.value = 0; appliedQuery.value = query.value.trim(); await loadDocuments() }
async function changePage(delta: number) { page.value += delta; await loadDocuments() }
function toggleReviewTasks() {
  reviewOpen.value = !reviewOpen.value
}
async function openReviewTask(task: KnowledgeReviewTask) {
  if (!reviewEligible.value) return
  if (router && route) { await router.replace({ query: { documentId: task.documentId, versionId: task.versionId } }); return }
  if (spaceId.value !== task.spaceId) spaceId.value = task.spaceId
  await openDocument(task.documentId)
  const pendingVersion = detail.value?.versions.find(version => version.id === task.versionId)
  if (pendingVersion?.ingestState === 'READY') await loadChunks(pendingVersion)
}
function schedulePoll() {
  stopPoll()
  if (detail.value?.versions.some(version => ['QUEUED', 'EXTRACTING', 'CHUNKING'].includes(version.ingestState) || ['QUEUED', 'RUNNING', 'RETRYING'].includes(version.jobStatus || ''))) pollTimer = setTimeout(() => { void loadDetail() }, 3000)
}
async function loadDetail() {
  const id = selectedId.value
  if (accessFailure.value || !id || !selectedSpace.value?.canRead || !session.authenticated) return
  stopPoll()
  await detailRead.run(async (signal, current) => {
    const result = await api.get<KnowledgeDetail>(`/knowledge/documents/${id}`, { signal })
    if (!current()) return
    detail.value = result
    documents.value = documents.value.map(document => document.id === result.id ? result : document)
    if (chunksVersion.value && !documentEditable.value && chunksVersion.value !== result.publishedVersionId) { chunks.value = []; readerSnapshot.value = null; chunksVersion.value = ''; chunkSelection.value = null; chunkRead.cancel() }
    schedulePoll()
    if (!chunksVersion.value && !chunkSelection.value) await loadRequestedSource(result)
  })
}
async function openDocument(id: string) {
  if (router && route && (route.query.documentId !== id || route.query.versionId != null || route.query.chunkId != null || route.query.spaceId != null || route.query.categoryId != null)) { await router.replace({ query: { documentId: id } }); return }
  clearDetail(); selectedId.value = id; activeReference = JSON.stringify([id, undefined, undefined]); await loadDetail()
}
function chooseFile(file: File | null, laterVersion = false) { if (laterVersion) versionFile.value = file; else uploadFile.value = file; write.error.value = null }
function fileIssue(file: File | null) {
  return !file ? '' : !/\.(pdf|docx|md|txt)$/i.test(file.name) ? '仅支持 PDF、DOCX、Markdown（.md）和 TXT 文件。' : file.size > 20 * 1024 * 1024 ? '文件不能超过 20 MiB。' : file.size === 0 ? '文件内容不能为空。' : ''
}
const uploadIssue = computed(() => fileIssue(uploadFile.value))
const versionIssue = computed(() => fileIssue(versionFile.value))
function validateFile(file: File | null) {
  const error = !file ? '请选择文件。' : fileIssue(file)
  if (error) { write.error.value = new ApiError(422, { title: error, status: 422 }); return false }; return true
}
async function upload(laterVersion = false) {
  if (accessFailure.value || write.pending.value || !spaceEditable.value || (laterVersion && !documentEditable.value)) return
  const file = laterVersion ? versionFile.value : uploadFile.value
  if (!validateFile(file) || (!laterVersion && !title.value.trim())) return
  uploadProgress.value = null
  const id = spaceId.value; const docId = selectedId.value; const body = new FormData(); body.append('file', file!); if (!laterVersion) body.append('title', title.value.trim())
  await write.run(async (signal, current) => {
    const result = await api.upload<KnowledgeUpload>(laterVersion ? `/knowledge/documents/${docId}/versions` : `/knowledge/spaces/${id}/documents`, body, { signal, onProgress: percent => { if (current()) uploadProgress.value = percent }, key: laterVersion ? undefined : uploadRequestKey.for({ spaceId: id, title: title.value.trim() }) })
    if (!current()) return
    message.value = '文件已接收，正在处理。相同文件会复用已有版本与任务。'
    if (laterVersion || selectedId.value !== result.documentId) { versionFile.value = null; versionFileReset.value++ }
    if (!laterVersion) { uploadRequestKey.reset(); title.value = ''; uploadFile.value = null; fileReset.value++ }
    // Opening a result must not cancel this request channel before it has applied.
    stopPoll(); detailRead.cancel(); chunkRead.cancel(); detail.value = null; selectedId.value = result.documentId; chunks.value = []; readerSnapshot.value = null; chunksVersion.value = ''; chunkSelection.value = null; confirmation.value = ''
    await loadDetail(); if (current()) await loadDocuments()
  })
}
async function documentAction(action: 'publish' | 'retry' | 'disable' | 'archive', versionId?: string) {
  if (!documentEditable.value || write.pending.value || !detail.value) return
  const id = detail.value.id; const version = detail.value.versions.find(version => version.id === versionId)
  if (action === 'publish' && (!version?.canPublish || version.ingestState !== 'READY')) return
  if (action === 'retry' && version?.ingestState !== 'FAILED') return
  const path = `/knowledge/documents/${id}${action === 'archive' ? '' : action === 'disable' ? '/disable' : `/versions/${versionId}/${action}`}`
  await write.run(async (_signal, current) => {
    if (action === 'archive') await api.delete(path); else await api.post(path)
    if (!current()) return
    confirmation.value = ''; message.value = action === 'publish' ? '版本已发布。' : action === 'disable' ? '已撤回发布。' : action === 'archive' ? '文档已归档。' : '重试已提交。'
    chunks.value = []; readerSnapshot.value = null; chunksVersion.value = ''; chunkSelection.value = null; chunkRead.cancel()
    if (action === 'archive') { stopPoll(); selectedId.value = ''; detail.value = null; detailRead.cancel() } else await loadDetail()
    if (current()) await loadDocuments()
  })
}
async function loadRequestedSource(document: KnowledgeDetail) {
  const requested = route?.query.versionId; const chunk = route?.query.chunkId
  if ((requested != null && (typeof requested !== 'string' || !requested)) || (chunk != null && (typeof chunk !== 'string' || !chunk || !requested))) { citationUnavailable.value = unavailableCitation; return }
  const version = document.versions.find(version => version.id === (requested || document.publishedVersionId))
  if (requested && (!version || version.ingestState !== 'READY' || (!documentEditable.value && version.id !== document.publishedVersionId))) { citationUnavailable.value = unavailableCitation; return }
  if (version?.ingestState === 'READY') await loadChunks(version)
}
async function loadChunks(version: KnowledgeVersion) {
  if (accessFailure.value || !detail.value || version.ingestState !== 'READY' || (!documentEditable.value && version.id !== detail.value.publishedVersionId)) return
  const requestedVersion = route?.query.versionId; const requestedChunk = route?.query.chunkId
  const id = detail.value.id; chunks.value = []; readerSnapshot.value = null; chunksVersion.value = ''; highlightedChunk.value = ''; citationUnavailable.value = ''; chunkSelection.value = version
  if (requestedVersion && version.id !== requestedVersion) { citationUnavailable.value = unavailableCitation; chunkRead.cancel(); return }
  await chunkRead.run(async (signal, current) => {
    const [result, snapshot] = await Promise.all([api.get<KnowledgeChunk[]>(`/knowledge/documents/${id}/versions/${version.id}/chunks`, { signal }), api.get<RichDoc | null>(`/knowledge/documents/${id}/versions/${version.id}/content`, { signal })])
    if (!current()) return
    if (requestedChunk && (typeof requestedChunk !== 'string' || !result.some(chunk => chunk.id === requestedChunk))) { citationUnavailable.value = unavailableCitation; return }
    chunks.value = result; readerSnapshot.value = snapshot ? normalizeRichDoc(snapshot) : null; chunksVersion.value = version.id
    if (version.id === detail.value?.publishedVersionId && !viewed.has(id)) {
      viewed.add(id)
      try { await api.post(`/knowledge/documents/${id}/views`, undefined, { signal }) } catch (failure) { viewed.delete(id); if (current() && failure instanceof ApiError && [401, 403, 404].includes(failure.status)) throw failure }
    }
    if (!current() || !requestedChunk) return
    highlightedChunk.value = requestedChunk as string; await nextTick()
    if (!current()) return
    const source = document.getElementById(`knowledge-source-${requestedChunk}`)
    source?.focus({ preventScroll: true }); source?.scrollIntoView({ block: 'center' })
  })
}
async function createArticle() {
  if (accessFailure.value || !spaceEditable.value || write.pending.value || !articleTitle.value.trim()) return
  const space = spaceId.value; const payload = { title: articleTitle.value.trim(), categoryId: categoryId.value || null, content: emptyRichDoc() }
  await write.run(async (signal, current) => { const result = await api.post<KnowledgeDraft>(`/knowledge/spaces/${space}/articles`, payload, { signal, key: articleKey.for({ space, ...payload }) }); if (!current()) return; articleKey.reset(); await router?.push(`/knowledge/articles/${result.id}/edit`) })
}
async function reviewAction(version: KnowledgeVersion, action: 'request' | 'APPROVED' | 'REJECTED') {
  if (!detail.value || !documentEditable.value || write.pending.value || (action === 'request' ? !version.canRequestReview : !version.canReview) || (action === 'REJECTED' && !rejectReason.value.trim())) return
  const id = detail.value.id
  await write.run(async (signal, current) => { await api.post(`/knowledge/documents/${id}/versions/${version.id}/${action === 'request' ? 'review-requests' : 'reviews'}`, action === 'request' ? undefined : { decision: action, ...(action === 'REJECTED' ? { reason: rejectReason.value.trim() } : {}) }, { signal }); if (!current()) return; rejectVersion.value = ''; rejectReason.value = ''; message.value = action === 'request' ? '已提交审核。另一位有权限的知识编辑者可从“审核待办”打开版本。' : action === 'APPROVED' ? '审核通过。请点击“发布此版本”完成发布。' : '已退回修改，理由已保存。'; await loadDetail(); if (current() && reviewOpen.value) reviewRevision.value++ })
}
async function openDeepLink() {
  const id = route?.query.documentId; const reference = referenceIdentity(); if (typeof id !== 'string' || !session.authenticated || (selectedId.value === id && activeReference === reference)) return
  clearDetail(); selectedId.value = id; activeReference = reference
  await detailRead.run(async (signal, current) => { const result = await api.get<KnowledgeDetail>(`/knowledge/documents/${id}`, { signal }); if (!current()) return; if (!spaces.value.some(space => space.id === result.spaceId && space.canRead)) throw new ApiError(404, { status: 404, title: '当前账号无法访问此知识文档。' }); if (spaceId.value !== result.spaceId) spaceId.value = result.spaceId; selectedId.value = id; activeReference = reference; detail.value = result; schedulePoll(); await loadRequestedSource(result) })
}
async function applyKnowledgeRoute() {
  if (!session.authenticated) return
  const own = ++routeGeneration; const previousSpace = spaceId.value; const address = { ...route?.query }
  categoryRouteRead.cancel(); routeApplying.value = true; accessFailure.value = null; categoryRouteError.value = ''; categoryRouteRows.value = null; clearSpace(); spaceId.value = ''
  try {
    if (address.documentId != null) {
      if (typeof address.documentId !== 'string' || !address.documentId) { citationUnavailable.value = unavailableCitation; return }
      await openDeepLink()
    } else if (address.spaceId != null || address.categoryId != null) {
      const target = typeof address.spaceId === 'string' ? spaces.value.find(space => space.id === address.spaceId) : undefined
      if (!target || (address.categoryId != null && (typeof address.categoryId !== 'string' || !address.categoryId))) { categoryRouteError.value = unavailableCategory; return }
      if (!target.canRead) {
        // Space configuration is a distinct authorized capability. It must not
        // request content, and cannot be used to enter a category reader.
        if (address.categoryId == null && target.canManageMembers) spaceId.value = target.id
        else categoryRouteError.value = unavailableCategory
        return
      }
      await categoryRouteRead.run(async (signal, current) => {
        const rows = await api.get<KnowledgeCategory[]>(`/knowledge/spaces/${target.id}/categories`, { signal })
        if (!current() || own !== routeGeneration) return
        if (address.categoryId && !rows.some(category => category.id === address.categoryId && category.spaceId === target.id)) { categoryRouteError.value = unavailableCategory; return }
        spaceId.value = target.id; categoryRouteRows.value = rows; categoryId.value = typeof address.categoryId === 'string' ? address.categoryId : ''
      })
      if (categoryRouteRead.error.value) categoryRouteError.value = unavailableCategory
    } else {
      spaceId.value = spaces.value.find(space => space.id === previousSpace && space.canRead)?.id || spaces.value.find(space => space.canRead)?.id || spaces.value[0]?.id || ''
    }
  } finally {
    if (own === routeGeneration) { routeApplying.value = false; if (!accessFailure.value && !categoryRouteError.value && spaceId.value) await loadDocuments() }
  }
}
async function selectSpace(id: string) {
  if (router && route) { await router.replace({ query: { spaceId: id } }); return }
  spaceId.value = id
}
async function selectCategory(id: string) {
  if (router && route) { await router.replace({ query: { spaceId: spaceId.value, categoryId: id || undefined } }); return }
  categoryId.value = id
}
watch(categoryId, () => { page.value = 0; if (!routeApplying.value && selectedSpace.value?.canRead) void loadDocuments() }, { flush: 'sync' })
watch([articleTitle, categoryId], () => articleKey.reset(), { flush: 'sync' })
watch([() => route?.query.documentId, () => route?.query.versionId, () => route?.query.chunkId, () => route?.query.spaceId, () => route?.query.categoryId], () => { if (spaces.value.length) void applyKnowledgeRoute() })
async function createSpace() {
  if (!editorRole.value || createWrite.pending.value || !newSpaceName.value.trim()) return
  const payload = { name: newSpaceName.value.trim() }
  await createWrite.run(async (signal, current) => { const result = await api.post<KnowledgeSpace>('/knowledge/spaces', payload, { signal, key: spaceRequestKey.for(payload) }); if (!current()) return; spaceRequestKey.reset(); createOpen.value = false; newSpaceName.value = ''; spaces.value = [...spaces.value.filter(space => space.id !== result.id), result]; if (spaceId.value === result.id) { await loadDocuments() } else await selectSpace(result.id) })
}
async function loadMembers() {
  if (!membersOpen.value || !selectedSpace.value?.canManageMembers) return
  const id = spaceId.value; membersLoaded.value = ''; members.value = []; candidates.value = []
  await memberRead.run(async (signal, current) => {
    const [rows, users] = await Promise.all([api.get<KnowledgeMember[]>(`/knowledge/spaces/${id}/members`, { signal }), api.get<KnowledgeCandidate[]>(`/knowledge/spaces/${id}/member-candidates`, { signal })])
    if (current()) { members.value = rows; candidates.value = users; membersLoaded.value = id }
  })
}
async function openMembers() { membersOpen.value = !membersOpen.value; memberRead.cancel(); membersLoaded.value = ''; if (membersOpen.value) await loadMembers() }
async function showReviewerSetup() {
  if (!membersOpen.value) await openMembers()
  await nextTick()
  document.getElementById('knowledge-member-form')?.scrollIntoView({ block: 'center' })
}
function editMember(member: KnowledgeMember) { Object.assign(memberForm, { userId: member.userId, canRead: member.canRead, canEdit: member.canEdit, reason: '' }) }
const membersReady = computed(() => membersOpen.value && membersLoaded.value === spaceId.value && !!membersLoaded.value && !memberRead.pending.value && !memberWrite.pending.value && !!selectedSpace.value?.canManageMembers)
async function saveMember() {
  if (!membersReady.value || !memberForm.userId || !memberForm.reason.trim()) return
  const id = spaceId.value; const userId = memberForm.userId; const payload = { canRead: memberForm.canRead, canEdit: memberForm.canRead && memberForm.canEdit, reason: memberForm.reason.trim() }
  await memberWrite.run(async (_signal, current) => { await api.put(`/knowledge/spaces/${id}/members/${userId}`, payload); if (!current()) return; message.value = '成员权限已保存。'; memberForm.reason = ''; await loadMembers(); if (current()) { await loadSpaces(); if (current()) { await loadDocuments(); if (selectedId.value) await loadDetail() } } })
}
watch(newSpaceName, () => spaceRequestKey.reset(), { flush: 'sync' })
// A File object is immutable: retain its identity for a retry, invalidate on a
// new selection even if filename, size and lastModified happen to match.
watch([title, uploadFile, spaceId], () => uploadRequestKey.reset(), { flush: 'sync' })
watch(spaceId, () => { if (routeApplying.value) return; accessFailure.value = null; categoryRouteError.value = ''; categoryRouteRows.value = null; createWrite.cancel(); clearSpace(); void loadDocuments() }, { flush: 'sync' })
watch([documentEditable, () => detail.value?.publishedVersionId], () => {
  const shownVersion = chunksVersion.value || chunkSelection.value?.id
  if (shownVersion && !documentEditable.value && shownVersion !== detail.value?.publishedVersionId) { chunkRead.cancel(); chunks.value = []; readerSnapshot.value = null; chunksVersion.value = ''; chunkSelection.value = null }
}, { flush: 'sync' })
watch(reviewEligible, allowed => { if (!allowed) reviewOpen.value = false }, { flush: 'sync' })
function denyAccess(denied: ApiError) {
  // Fresh object denial invalidates every independent content subtree, including
  // category requests owned by the child. Unrelated upload inputs remain local.
  activeReference = ''; highlightedChunk.value = ''; if (route?.query.versionId || route?.query.chunkId) citationUnavailable.value = unavailableCitation
  if (!route?.query.documentId && (route?.query.spaceId || route?.query.categoryId)) categoryRouteError.value = unavailableCategory
  categoryRouteRows.value = null; accessFailure.value = denied; stopPoll()
  for (const operation of operations) operation.cancel()
  reviewOpen.value = false
  categoryId.value = ''; chunks.value = []; readerSnapshot.value = null; chunksVersion.value = ''; chunkSelection.value = null; detail.value = null; documents.value = []; total.value = 0; members.value = []; candidates.value = []; membersLoaded.value = ''; confirmation.value = ''; message.value = ''
}
watch(operations.map(operation => operation.error), errors => {
  const denied = errors.find(error => error?.status === 403 || error?.status === 404)
  if (denied) denyAccess(denied)
}, { flush: 'sync' })
async function reloadAccess() { accessFailure.value = null; await loadSpaces(); if (!accessFailure.value) await loadDocuments() }
watch(() => session.token, () => { routeGeneration++; routeApplying.value = false; categoryRouteRead.cancel(); categoryRouteError.value = ''; categoryRouteRows.value = null; accessFailure.value = null; spaceRequestKey.reset(); uploadRequestKey.reset(); viewed.clear(); spacesRead.cancel(); createWrite.cancel(); reviewOpen.value = false; clearSpace(); spaces.value = []; spaceId.value = ''; newSpaceName.value = ''; createOpen.value = false; if (session.authenticated) void loadSpaces() }, { flush: 'sync' })
onBeforeUnmount(() => { routeGeneration++; categoryRouteRead.cancel(); spacesRead.cancel(); createWrite.cancel(); clearSpace() })
void loadSpaces()
</script>

<template>
  <template v-if="session.authenticated">
    <div class="page-heading"><div><h1>知识管理</h1><p class="muted">按分类整理文档、协作编写，由另一位编辑审核后发布。</p></div><div class="inline-actions"><router-link v-if="reviewEligible" class="review-back-link" to="/knowledge/reviews">审核待办</router-link><el-button v-if="editorRole" @click="createOpen = !createOpen">创建空间</el-button></div></div>
    <router-link v-if="reviewEligible && route?.query.fromReview === '1'" class="review-back-link" to="/knowledge/reviews">← 返回审核待办</router-link>
    <div v-if="message" class="info-notice" role="status">{{ message }}</div>
    <KnowledgeError :error="accessFailure" retry @retry="reloadAccess" /><div v-if="citationUnavailable" class="alert warning citation-unavailable" role="alert">{{ citationUnavailable }}</div><p v-else-if="highlightedChunk" class="info-notice citation-located" role="status">已定位引用片段，来源版本及当前访问权限已核对。</p>
    <KnowledgeError :error="spacesRead.error.value" retry @retry="loadSpaces" /><KnowledgeError :error="categoryRouteRead.error.value" retry @retry="loadSpaces" /><div v-if="categoryRouteError" class="alert warning category-route-unavailable" role="alert">{{ categoryRouteError }}</div><p v-if="routeApplying" class="loading-panel" role="status">正在核对知识入口与访问权限…</p>
    <section v-if="createOpen && editorRole" class="panel knowledge-section"><h2>创建知识空间</h2><KnowledgeError :error="createWrite.error.value" /><form id="knowledge-space-create" class="filter-form" @submit.prevent="createSpace"><div class="field grow"><label for="knowledge-space-name">空间名称</label><input id="knowledge-space-name" v-model="newSpaceName" maxlength="160" required :disabled="createWrite.pending.value" /></div><el-button native-type="submit" type="primary" :loading="createWrite.pending.value">保存空间</el-button></form></section>
    <section class="panel knowledge-section" :aria-busy="spacesRead.pending.value"><div class="knowledge-toolbar"><div class="field grow"><label for="knowledge-space">知识空间</label><select id="knowledge-space" :value="spaceId" @change="selectSpace(($event.target as HTMLSelectElement).value)" :disabled="spacesRead.pending.value"><option value="" disabled>选择知识空间</option><option v-for="space in spaces" :key="space.id" :value="space.id">{{ space.name }}{{ !space.canRead ? '（仅空间配置）' : !space.active ? '（已停用）' : '' }}</option></select></div><el-button :loading="spacesRead.pending.value" @click="loadSpaces">刷新空间</el-button><el-button v-if="selectedSpace?.canManageMembers" @click="openMembers">{{ membersOpen ? '收起成员' : '管理成员' }}</el-button></div><p v-if="spacesRead.pending.value" class="muted small" role="status">正在加载空间…</p><div v-else-if="!spaces.length && !spacesRead.error.value" class="empty-state">暂无可访问的知识空间。请联系空间管理员授予阅读权限。</div><p v-if="selectedSpace && !selectedSpace.canRead" class="muted small">此账号可管理空间配置。阅读文档需要单独授予空间阅读权限。</p></section>
    <section v-if="reviewEligible" class="panel knowledge-section knowledge-review-queue"><div class="panel-header"><div><h2>审核待办</h2><p class="muted small">另一位具有当前空间编辑权限的知识编辑者在这里处理提交的版本。</p></div><el-button :aria-expanded="reviewOpen" aria-controls="knowledge-review-tasks" @click="toggleReviewTasks">{{ reviewOpen ? '收起待办' : '待我审核' }}</el-button></div><div v-if="reviewOpen" id="knowledge-review-tasks"><KnowledgeReviewQueue :key="reviewRevision" @open="openReviewTask" /></div></section>
    <section v-if="membersOpen && selectedSpace?.canManageMembers" class="panel knowledge-section"><h2>空间成员 · {{ selectedSpace.name }}</h2><p class="muted small">编辑权限还需账号具有知识编辑或管理员角色。取消阅读与编辑权限可撤销成员访问。</p><KnowledgeError :error="memberRead.error.value" retry @retry="loadMembers" /><KnowledgeError :error="memberWrite.error.value" /><p v-if="memberRead.pending.value" role="status">正在加载成员…</p><div v-for="member in members" :key="member.userId" class="knowledge-member"><div><strong>{{ member.displayName }}</strong><span class="ticket-number">{{ member.username }} · {{ member.canRead ? '可阅读' : '不可阅读' }} · {{ member.canEdit ? '可编辑' : '不可编辑' }}</span></div><el-button :disabled="!membersReady" @click="editMember(member)">修改权限</el-button></div><p v-if="!memberRead.pending.value && !members.length && !memberRead.error.value" class="muted small">此空间暂无成员。</p><form id="knowledge-member-form" class="form-stack knowledge-member-form" @submit.prevent="saveMember"><div class="field"><label for="knowledge-member-user">添加或修改成员</label><select id="knowledge-member-user" v-model="memberForm.userId" :disabled="!membersReady" required><option value="" disabled>选择启用的用户</option><option v-for="user in candidates" :key="user.id" :value="user.id">{{ user.displayName }}（{{ user.username }}）</option></select></div><div class="checkbox-group"><label class="checkbox-label"><input v-model="memberForm.canRead" type="checkbox" :disabled="!membersReady" />允许阅读</label><label class="checkbox-label"><input v-model="memberForm.canEdit" type="checkbox" :disabled="!membersReady || !memberForm.canRead" />允许编辑</label></div><div class="field"><label for="knowledge-member-reason">变更理由</label><input id="knowledge-member-reason" v-model="memberForm.reason" required maxlength="500" :disabled="!membersReady" placeholder="例如：知识访问申请已审批" /></div><div><el-button native-type="submit" type="primary" :disabled="!membersReady || !memberForm.userId || !memberForm.reason.trim()" :loading="memberWrite.pending.value">保存成员权限</el-button></div></form></section>
    <template v-if="selectedSpace?.canRead"><div class="knowledge-workspace"><KnowledgeCategories v-if="!accessFailure && !routeApplying && !categoryRouteError" :key="spaceId" :model-value="categoryId" :initial-categories="categoryRouteRows || undefined" @update:model-value="selectCategory" :space-id="spaceId" :can-edit="spaceEditable" @denied="denyAccess" /><div class="knowledge-main">
      <section v-if="spaceEditable" class="panel knowledge-section knowledge-create"><div class="panel-header"><h2>撰写与导入</h2><el-button :disabled="!!accessFailure" @click="articleOpen = !articleOpen">撰写文章</el-button></div><form v-if="articleOpen" id="article-create-form" class="filter-form" @submit.prevent="createArticle"><div class="field grow"><label for="article-create-title">新文章标题</label><input id="article-create-title" v-model="articleTitle" :disabled="write.pending.value || !!accessFailure" maxlength="240" required /></div><el-button native-type="submit" :loading="write.pending.value" :disabled="!!accessFailure || !articleTitle.trim()">创建文章草稿</el-button></form><p id="knowledge-file-help" class="muted small">支持 PDF、DOCX、Markdown（.md）、TXT；单文件最多 20 MiB，PDF 最多 100 页。解析后需由另一位编辑审核通过再发布。</p><form id="knowledge-upload" class="form-stack" @submit.prevent="upload()"><div class="form-row"><div class="field"><label for="knowledge-title">文档标题</label><input id="knowledge-title" v-model="title" maxlength="240" required :disabled="write.pending.value || !!accessFailure" placeholder="例如：办公网络故障排查" /></div><div class="field"><label for="knowledge-file">原始文件</label><KnowledgeFilePicker id="knowledge-file" :key="fileReset" label="原始文件" :model-value="uploadFile" :disabled="write.pending.value || !!accessFailure" described-by="knowledge-file-help" :issue="uploadIssue" @update:model-value="chooseFile($event)" /></div></div><div><el-button native-type="submit" type="primary" :loading="write.pending.value" :disabled="!!accessFailure || !title.trim() || !uploadFile || !!uploadIssue">上传并解析</el-button></div></form></section>
      <div v-if="uploadProgress !== null && write.pending.value" class="upload-progress" role="status"><label for="knowledge-upload-progress">{{ uploadProgress === 100 ? '上传完成，服务器正在接收…' : `文件上传 ${uploadProgress}%` }}</label><progress id="knowledge-upload-progress" :value="uploadProgress" max="100">{{ uploadProgress }}%</progress></div><KnowledgeError :error="write.error.value" />
      <section class="panel knowledge-section knowledge-list" :aria-busy="listRead.pending.value"><div class="panel-header"><h2>空间文档 <span class="small muted">{{ total }} 篇</span></h2><el-button :loading="listRead.pending.value" text @click="loadDocuments">刷新文档</el-button></div><form class="filter-form" @submit.prevent="search"><div class="field grow"><label for="knowledge-search">搜索文档标题</label><input id="knowledge-search" v-model="query" type="search" placeholder="输入标题关键词" maxlength="200" /></div><el-button native-type="submit" :loading="listRead.pending.value">搜索</el-button></form><KnowledgeError :error="listRead.error.value" retry @retry="loadDocuments" /><p v-if="listRead.pending.value" class="loading-panel" role="status">正在加载文档…</p><div v-else-if="!documents.length && !listRead.error.value" class="empty-state">{{ appliedQuery ? '没有找到匹配的文档，请调整关键词。' : '此空间暂无文档。' }}</div><ul v-else class="knowledge-documents"><li v-for="document in documents" :key="document.id" :class="{ selected: selectedId === document.id }"><div class="knowledge-document-label"><button class="knowledge-document-link" @click="openDocument(document.id)">{{ document.title }}</button><span class="ticket-number">{{ document.versionCount }} 个版本 · {{ formatTime(document.createdAt) }}</span></div><div class="knowledge-document-states"><span class="badge" :class="document.publishedVersionId ? 'resolved' : 'closed'">{{ document.publishedVersionId ? '已发布' : '未发布' }}</span><span v-if="document.latestIngestState" class="badge">{{ states[document.latestIngestState] }}</span></div></li></ul><div v-if="total > 20" class="pagination"><span>第 {{ page + 1 }} 页 · 共 {{ total }} 篇</span><div class="inline-actions"><el-button :disabled="page === 0 || listRead.pending.value" @click="changePage(-1)">上一页</el-button><el-button :disabled="(page + 1) * 20 >= total || listRead.pending.value" @click="changePage(1)">下一页</el-button></div></div></section>
      <KnowledgeError :error="detailRead.error.value" retry @retry="loadDetail" />
      <p v-if="selectedId && detailRead.pending.value && !detail" class="loading-panel" role="status">正在加载文档详情…</p>
      <section v-if="detail" class="panel knowledge-section knowledge-detail" :aria-busy="detailRead.pending.value"><div class="panel-header"><h2>{{ detail.title }}</h2><el-button text :loading="detailRead.pending.value" @click="loadDetail">刷新状态</el-button></div><p class="small muted"><span class="badge">{{ documentEditable ? '可编辑' : '仅阅读' }}</span> {{ detail.publishedVersionId ? '已发布版本可供空间成员阅读。' : '尚未发布，空间成员暂时无法阅读正文。' }}</p><div v-if="detail.publishedVersionId && detail.latestVersionId !== detail.publishedVersionId" class="info-notice">新版本处理或待发布期间，旧版本仍保持发布。</div><div v-if="documentEditable" class="inline-actions"><el-button @click="router?.push(`/knowledge/articles/${detail.id}/edit`)">编辑文章草稿</el-button><el-button v-if="detail.publishedVersionId" :disabled="write.pending.value || !!accessFailure" @click="confirmation = 'disable'">撤回发布</el-button><el-button type="danger" plain :disabled="write.pending.value || !!accessFailure" @click="confirmation = 'archive'">归档文档</el-button></div><div v-if="confirmation && documentEditable" class="knowledge-confirm" role="alert"><p>{{ confirmation === 'disable' ? '撤回后，空间成员将立即无法阅读此文档正文。确认撤回发布？' : '归档后，文档将从空间列表移除，并撤回所有版本。确认归档？' }}</p><div class="inline-actions"><el-button type="danger" :loading="write.pending.value" @click="documentAction(confirmation)">{{ confirmation === 'disable' ? '确认撤回' : '确认归档' }}</el-button><el-button :disabled="write.pending.value || !!accessFailure" @click="confirmation = ''">取消</el-button></div></div><form v-if="documentEditable" id="knowledge-version-upload" class="form-stack knowledge-version-upload" @submit.prevent="upload(true)"><div class="field"><label for="knowledge-version-file">上传新版本</label><KnowledgeFilePicker id="knowledge-version-file" :key="versionFileReset" label="上传新版本" :model-value="versionFile" :disabled="write.pending.value || !!accessFailure" described-by="knowledge-version-file-help" :issue="versionIssue" @update:model-value="chooseFile($event, true)" /><small id="knowledge-version-file-help">同一文档上传相同文件会复用已有版本与任务。支持格式、20 MiB 与 100 页限制同上。</small></div><div><el-button native-type="submit" :loading="write.pending.value" :disabled="!versionFile || !!versionIssue">上传新版本并解析</el-button></div></form><KnowledgeError v-if="chunksVersion" :error="readerState.error" /><div v-if="chunksVersion && !readerState.error" class="knowledge-reader-layout"><article class="knowledge-reader"><h1>{{ detail.title }}</h1><p class="small muted">{{ chunksVersion === detail.publishedVersionId ? '当前发布版本' : '编辑预览 · 尚未发布' }}</p><RichDocument :content="readerContent" /></article><aside class="article-outline" aria-label="文章目录"><h3>文章目录</h3><nav v-if="outline.length"><a v-for="heading in outline" :key="heading.id" :href="`#${heading.id}`" :class="`level-${heading.level}`">{{ heading.text }}</a></nav><p v-else class="small muted">本文暂无章节标题。</p></aside></div><h3>版本历史与审核</h3><ol class="knowledge-versions"><li v-for="version in detail.versions" :key="version.id"><div class="panel-header"><h3>版本 {{ version.versionNo }} <span v-if="version.id === detail.publishedVersionId" class="badge resolved">当前发布</span></h3><span class="badge" :class="version.ingestState === 'READY' ? 'resolved' : version.ingestState === 'FAILED' ? 'knowledge-failed' : 'new'">{{ states[version.ingestState] }}</span></div><p class="review-state"><span class="badge">{{ reviewLabels[version.reviewState] || '审核状态待刷新' }}</span><span v-if="version.reviewedAt" class="small muted"> · {{ formatTime(version.reviewedAt) }}</span></p><p v-if="version.reviewReason" class="review-reason">审核意见：{{ version.reviewReason }}</p><p v-if="version.reviewState === 'PENDING' && version.authorId === session.user?.id" class="info-notice review-guidance">已提交审核。另一位拥有知识编辑者角色及本空间读写权限的成员可从“审核待办”处理；你不能审核自己的版本。</p><div v-if="version.reviewState === 'PENDING' && version.authorId === session.user?.id && version.hasEligibleReviewer === false" class="alert warning review-recovery" role="alert"><p>当前空间尚无其他可审核成员。请为另一账号授予知识编辑者角色，以及本空间的阅读和编辑权限。</p><div class="inline-actions"><el-button v-if="selectedSpace?.canManageMembers" @click="showReviewerSetup">配置审核人</el-button><router-link v-if="session.user?.roles.includes('ADMIN')" class="review-back-link" to="/admin/users">管理用户角色</router-link></div></div><p v-if="version.reviewState === 'PENDING' && version.canReview" class="info-notice review-guidance">这是待你审核的版本。请先查看解析结果，核对内容后选择审核通过或退回修改。</p><p v-if="documentEditable && version.reviewState === 'APPROVED' && version.canPublish" class="info-notice review-guidance">审核已通过，但尚未发布。点击下方“发布此版本”后，空间成员才会读到新内容。</p><p v-if="version.reviewState === 'REJECTED'" class="info-notice review-guidance">此版本已退回。请修改文章草稿或上传修正版本，解析完成后重新提交审核。</p><p class="small knowledge-file-name">{{ version.fileName }} · {{ formatTime(version.createdAt) }}</p><p class="small muted">{{ version.chunkCount }} 个片段 · 已尝试 {{ version.attemptCount }} 次<span v-if="version.jobStatus === 'RETRYING'"> · 自动重试中</span></p><p v-if="version.errorCode" class="error-text" role="status">解析失败：{{ version.errorCode }}。可核对文件后重试，或上传修正版本。</p><div class="inline-actions"><el-button v-if="documentEditable && version.canRequestReview" :disabled="write.pending.value || !!accessFailure" @click="reviewAction(version, 'request')">提交审核</el-button><el-button v-if="documentEditable && version.canReview" :disabled="write.pending.value || !!accessFailure" @click="reviewAction(version, 'APPROVED')">审核通过</el-button><el-button v-if="documentEditable && version.canReview" :disabled="write.pending.value || !!accessFailure" @click="rejectVersion = version.id">审核不通过</el-button><el-button v-if="documentEditable && version.ingestState === 'FAILED'" :disabled="write.pending.value || !!accessFailure" @click="documentAction('retry', version.id)">重试解析</el-button><el-button v-if="documentEditable && version.canPublish && version.ingestState === 'READY' && version.id !== detail.publishedVersionId" type="primary" :disabled="write.pending.value || !!accessFailure" @click="documentAction('publish', version.id)">发布此版本</el-button><el-button v-if="version.ingestState === 'READY' && (documentEditable || version.id === detail.publishedVersionId)" :loading="chunkRead.pending.value && chunkSelection?.id === version.id" @click="loadChunks(version)">查看解析结果</el-button></div><form v-if="rejectVersion === version.id && version.canReview" id="review-reject-form" class="form-stack review-reject-form" @submit.prevent="reviewAction(version, 'REJECTED')"><div class="field"><label for="review-reason">不通过理由</label><textarea id="review-reason" v-model="rejectReason" maxlength="500" required :disabled="write.pending.value || !!accessFailure" /></div><div class="inline-actions"><el-button native-type="submit" type="danger" :loading="write.pending.value" :disabled="!rejectReason.trim()">确认审核不通过</el-button><el-button :disabled="write.pending.value || !!accessFailure" @click="rejectVersion = ''; rejectReason = ''">取消</el-button></div></form></li></ol><KnowledgeError :error="chunkRead.error.value" :retry="!!chunkSelection" @retry="chunkSelection && loadChunks(chunkSelection)" /><details v-if="chunksVersion" class="knowledge-chunks" :open="!!highlightedChunk"><summary>查看引用来源与解析片段 · {{ chunks.length }} 个片段</summary><p class="muted small">以下内容按纯文本展示，请核对后发布。</p><article v-for="chunk in chunks" :id="`knowledge-source-${chunk.id}`" :key="chunk.id" tabindex="-1" class="knowledge-source-chunk" :class="{ cited: highlightedChunk === chunk.id }"><p class="small muted">片段 {{ chunk.chunkIndex + 1 }}<span v-if="chunk.pageNo"> · 第 {{ chunk.pageNo }} 页</span><span v-if="chunk.headingPath"> · {{ chunk.headingPath }}</span></p><pre class="plain-text">{{ chunk.content }}</pre></article><p v-if="!chunks.length" class="muted small">此版本暂无解析片段。</p></details></section>
    </div></div></template>
  </template>
  <div v-else class="empty-state">登录已失效，请重新登录。</div>
</template>

