<script setup lang="ts">
import { computed, inject, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteUpdate, routeLocationKey, routerKey } from 'vue-router'
import { api, ApiError, createRequestKey, queryString } from '../api'
import { useSessionStore } from '../stores/session'
import { useUnsavedChanges } from '../composables/unsaved'
import { formatTime } from '../types'
import type { KnowledgeSpace, KnowledgeDocument, KnowledgeUpload, KnowledgeDraft, KnowledgeCategory, Page, IngestState } from '../types'
import KnowledgeCategories from '../components/KnowledgeCategories.vue'
import KnowledgeError from '../components/KnowledgeError.vue'
import KnowledgeFilePicker from '../components/KnowledgeFilePicker.vue'
import { emptyRichDoc } from '../knowledge/rich'
function operation() {
  const pending = ref(false); const error = shallowRef<ApiError | null>(null); let generation = 0; let controller: AbortController | undefined
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
const session = useSessionStore(); const route = inject(routeLocationKey, null); const router = inject(routerKey, null)
const spacesRead = operation(); const listRead = operation(); const write = operation(); const categoryRouteRead = operation()
const accessFailure = shallowRef<ApiError | null>(null); const spaces = ref<KnowledgeSpace[]>([]); const spaceId = ref(''); const documents = ref<KnowledgeDocument[]>([]); const total = ref(0); const page = ref(0)
const selectedSpace = computed(() => spaces.value.find(space => space.id === spaceId.value)); const spaceEditable = computed(() => !!session.user?.roles.includes('KB_EDITOR') && !!selectedSpace.value?.active && !!selectedSpace.value?.canRead && !!selectedSpace.value?.canEdit)
const query = ref(''); const appliedQuery = ref(''); const categoryId = ref(''); const categoryRouteRows = shallowRef<KnowledgeCategory[] | null>(null); const routeApplying = ref(false); const categoryRouteError = ref('')
const createOpen = ref(false); const title = ref(''); const uploadFile = shallowRef<File | null>(null); const fileReset = ref(0); const articleOpen = ref(false); const articleTitle = ref(''); const message = ref(''); const uploadProgress = ref<number | null>(null)
const uploadKey = createRequestKey(); const articleKey = createRequestKey(); let routeGeneration = 0
const states: Record<IngestState, string> = { QUEUED: '排队中', EXTRACTING: '解析中', CHUNKING: '分块中', READY: '解析就绪', FAILED: '解析失败' }
const unavailableCategory = '分类入口不可用：空间或分类不存在，或当前账号没有阅读权限。请从知识空间重新选择，或返回门户。'
const uploadIssue = computed(() => !uploadFile.value ? '' : !/\.(pdf|docx|md|txt)$/i.test(uploadFile.value.name) ? '仅支持 PDF、DOCX、Markdown（.md）和 TXT 文件。' : uploadFile.value.size > 20 * 1024 * 1024 ? '文件不能超过 20 MiB。' : uploadFile.value.size === 0 ? '文件内容不能为空。' : '')
const { confirmDiscard } = useUnsavedChanges(() => !!title.value || !!uploadFile.value || !!articleTitle.value)
onBeforeRouteUpdate((to, from) => JSON.stringify(to.query) !== JSON.stringify(from.query) ? confirmDiscard() : true)
async function closeCreate(done: () => void) { if (!write.pending.value && await confirmDiscard()) { title.value = ''; uploadFile.value = null; articleTitle.value = ''; fileReset.value++; done() } }
async function reloadSpaces() { if (await confirmDiscard()) await loadSpaces() }
function chooseFile(file: File | null) { uploadFile.value = file; write.error.value = null }
function clearSpace() { listRead.cancel(); categoryRouteRead.cancel(); write.cancel(); documents.value = []; total.value = 0; categoryId.value = ''; categoryRouteRows.value = null; page.value = 0; query.value = ''; appliedQuery.value = ''; title.value = ''; uploadFile.value = null; articleTitle.value = ''; fileReset.value++; message.value = ''; uploadProgress.value = null; createOpen.value = false }
function denyAccess(error: ApiError) { routeGeneration++; clearSpace(); spaces.value = []; spaceId.value = ''; spacesRead.cancel(); accessFailure.value = error }
async function loadSpaces() {
  if (!session.authenticated) return
  accessFailure.value = null
  await spacesRead.run(async (signal, current) => { const result = await api.get<KnowledgeSpace[]>('/knowledge/spaces', { signal }); if (!current()) return; spaces.value = result.filter(space => space.canRead); await applyRoute() })
}
async function applyRoute() {
  if (route?.query.documentId != null) {
    if (typeof route.query.documentId === 'string' && route.query.documentId) await router?.replace({ path: `/knowledge/documents/${encodeURIComponent(route.query.documentId)}`, query: { versionId: route.query.versionId, chunkId: route.query.chunkId, fromReview: route.query.fromReview, tab: route.query.tab } })
    else { clearSpace(); spaceId.value = ''; categoryRouteError.value = '引用已不可用：文档入口无效。' }
    return
  }
  const previous = spaceId.value; const own = ++routeGeneration; clearSpace(); spaceId.value = ''; categoryRouteError.value = ''; routeApplying.value = true
  try {
    const requestedPage = route?.query.page
    const parsedPage = typeof requestedPage === 'string' && /^\d+$/.test(requestedPage) ? Number(requestedPage) : 0
    page.value = Number.isSafeInteger(parsedPage) && parsedPage >= 0 ? parsedPage : 0
    query.value = typeof route?.query.q === 'string' ? route.query.q.trim() : ''; appliedQuery.value = query.value
    if (requestedPage != null && requestedPage !== String(page.value) && router) { await router.replace({ query: { ...route?.query, page: String(page.value) } }); return }
    const requested = route?.query.spaceId; const requestedCategory = route?.query.categoryId
    const target = requested == null ? spaces.value.find(space => space.id === previous) || spaces.value[0] : spaces.value.find(space => space.id === requested)
    if (!target || requestedCategory != null && (typeof requestedCategory !== 'string' || !requestedCategory)) { if (requested != null || requestedCategory != null) categoryRouteError.value = unavailableCategory; return }
    if (requestedCategory == null) { spaceId.value = target.id; return }
    await categoryRouteRead.run(async (signal, current) => {
      const rows = await api.get<KnowledgeCategory[]>(`/knowledge/spaces/${target.id}/categories`, { signal }); if (!current() || own !== routeGeneration) return
      if (requestedCategory && !rows.some(row => row.id === requestedCategory && row.spaceId === target.id)) { categoryRouteError.value = unavailableCategory; return }
      categoryRouteRows.value = rows; spaceId.value = target.id; categoryId.value = typeof requestedCategory === 'string' ? requestedCategory : ''
    })
  } finally { if (own === routeGeneration) { routeApplying.value = false; await loadDocuments() } }
}
async function loadDocuments() {
  const id = spaceId.value; if (!id || routeApplying.value || categoryRouteError.value || accessFailure.value || !selectedSpace.value?.canRead) return
  await listRead.run(async (signal, current) => { const result = await api.get<Page<KnowledgeDocument>>(`/knowledge/spaces/${id}/documents?${queryString({ page: page.value, size: 20, q: appliedQuery.value, categoryId: categoryId.value })}`, { signal }); if (current()) { documents.value = result.items; total.value = result.total } })
}
async function selectSpace(id: string) { if (!router && !await confirmDiscard()) return; if (router) await router.push({ path: '/knowledge', query: { spaceId: id, ...(appliedQuery.value ? { q: appliedQuery.value, page: '0' } : {}) } }); else { spaceId.value = id; await applyRoute() } }
async function selectCategory(id: string) { if (!router && !await confirmDiscard()) return; if (router) await router.push({ path: '/knowledge', query: { spaceId: spaceId.value, categoryId: id || undefined, ...(appliedQuery.value ? { q: appliedQuery.value, page: '0' } : {}) } }); else { categoryId.value = id; page.value = 0; await loadDocuments() } }
async function search() {
  const term = query.value.trim()
  if (router && (term !== appliedQuery.value || page.value !== 0)) { await router.push({ path: '/knowledge', query: { ...route?.query, spaceId: spaceId.value, q: term || undefined, page: '0' } }); return }
  page.value = 0; appliedQuery.value = term; await loadDocuments()
}
async function changePage(delta: number) { const next = Math.max(0, page.value + delta); if (router) await router.push({ path: '/knowledge', query: { ...route?.query, spaceId: spaceId.value, q: appliedQuery.value || undefined, page: String(next) } }); else { page.value = next; await loadDocuments() } }
async function openDocument(id: string) { await router?.push(`/knowledge/documents/${encodeURIComponent(id)}`) }
async function upload() {
  if (!spaceEditable.value || write.pending.value || accessFailure.value || !title.value.trim() || !uploadFile.value || uploadIssue.value) return
  const file = uploadFile.value; const id = spaceId.value; const name = title.value.trim(); uploadProgress.value = null
  await write.run(async (signal, current) => {
    const body = new FormData(); body.append('file', file); body.append('title', name)
    const result = await api.upload<KnowledgeUpload>(`/knowledge/spaces/${id}/documents`, body, { signal, key: uploadKey.for({ id, name }), onProgress: percent => { if (current()) uploadProgress.value = percent } }); if (!current()) return
    title.value = ''; uploadFile.value = null; fileReset.value++; uploadKey.reset(); message.value = '文件已接收，正在处理。相同文件会复用已有版本与任务。'
    await router?.push(`/knowledge/documents/${result.documentId}`)
  })
}
async function createArticle() {
  if (!spaceEditable.value || write.pending.value || accessFailure.value || !articleTitle.value.trim()) return
  const payload = { title: articleTitle.value.trim(), categoryId: categoryId.value || null, content: emptyRichDoc() }; const space = spaceId.value
  await write.run(async (signal, current) => { const result = await api.post<KnowledgeDraft>(`/knowledge/spaces/${space}/articles`, payload, { signal, key: articleKey.for({ space, ...payload }) }); if (current()) { articleTitle.value = ''; articleKey.reset(); await router?.push(`/knowledge/articles/${result.id}/edit`) } })
}
watch([title, uploadFile, spaceId], () => uploadKey.reset(), { flush: 'sync' }); watch([articleTitle, categoryId], () => articleKey.reset(), { flush: 'sync' })
watch([() => route?.query.spaceId, () => route?.query.categoryId, () => route?.query.documentId, () => route?.query.q, () => route?.query.page], () => { if (spaces.value.length) void applyRoute() })
watch([spacesRead.error, listRead.error, write.error, categoryRouteRead.error], errors => { const error = errors.find(error => error && [401, 403, 404].includes(error.status)); if (error) denyAccess(error) }, { flush: 'sync' })
watch([() => session.token, () => session.user?.id, () => session.user?.roles.join(',')], () => { routeGeneration++; clearSpace(); spacesRead.cancel(); spaces.value = []; spaceId.value = ''; accessFailure.value = null; if (session.authenticated) void loadSpaces() }, { flush: 'sync' })
onBeforeUnmount(() => { routeGeneration++; clearSpace(); spacesRead.cancel() }); void loadSpaces()
</script>
<template>
  <div class="page-heading"><div><h1>知识协同</h1><p class="muted">在空间中查找、整理和协作编写知识。</p></div><div class="inline-actions"><router-link v-if="session.user?.roles.includes('KB_REVIEWER')" to="/knowledge/reviews">审核待办</router-link><router-link v-if="session.user?.roles.includes('ADMIN')" to="/admin/knowledge-spaces">管理知识空间</router-link></div></div>
  <KnowledgeError :error="accessFailure" retry @retry="loadSpaces" /><KnowledgeError :error="spacesRead.error.value" retry @retry="loadSpaces" /><KnowledgeError :error="categoryRouteRead.error.value" retry @retry="loadSpaces" />
  <p v-if="categoryRouteError" class="alert warning category-route-unavailable" role="alert">{{ categoryRouteError }}</p><p v-if="message" class="info-notice" role="status">{{ message }}</p>
  <section class="panel knowledge-section"><div class="knowledge-toolbar"><div class="field grow"><label for="knowledge-space">知识空间</label><select id="knowledge-space" :value="spaceId" :disabled="spacesRead.pending.value" @change="selectSpace(($event.target as HTMLSelectElement).value)"><option value="" disabled>选择知识空间</option><option v-for="space in spaces" :key="space.id" :value="space.id">{{ space.name }}{{ space.active ? '' : '（已停用）' }}</option></select></div><el-button :loading="spacesRead.pending.value" @click="reloadSpaces">刷新空间</el-button></div><p v-if="spacesRead.pending.value || routeApplying" role="status">正在加载知识空间…</p><p v-else-if="!spaces.length && !spacesRead.error.value && !accessFailure" class="empty-state">暂无可访问的知识空间。请联系空间管理员授予阅读权限。</p></section>
  <div v-if="selectedSpace?.canRead && !accessFailure && !categoryRouteError" class="knowledge-workspace"><KnowledgeCategories v-if="!routeApplying" :key="spaceId" :space-id="spaceId" :can-edit="spaceEditable" :model-value="categoryId" :initial-categories="categoryRouteRows || undefined" @update:model-value="selectCategory" @denied="denyAccess" /><div class="knowledge-main">
      <section class="panel knowledge-section knowledge-list" :aria-busy="listRead.pending.value"><div class="panel-header"><h2>空间文档 <span class="small muted">{{ total }} 篇</span></h2><div class="inline-actions"><el-button :loading="listRead.pending.value" @click="loadDocuments">刷新文档</el-button><el-button v-if="spaceEditable" type="primary" @click="createOpen = true">新建文档</el-button></div></div><form class="filter-form" @submit.prevent="search"><div class="field grow"><label for="knowledge-search">搜索文档标题</label><input id="knowledge-search" v-model="query" type="search" placeholder="输入标题关键词" maxlength="200" /></div><el-button native-type="submit" :loading="listRead.pending.value">搜索</el-button></form><KnowledgeError :error="listRead.error.value" retry @retry="loadDocuments" /><p v-if="listRead.pending.value" class="loading-panel" role="status">正在加载文档…</p><div v-else-if="!documents.length && !listRead.error.value" class="empty-state">{{ appliedQuery ? '没有找到匹配的文档，请调整关键词。' : '此空间暂无文档。' }}</div><ul v-else class="knowledge-documents"><li v-for="document in documents" :key="document.id" ><div class="knowledge-document-label"><button class="knowledge-document-link" @click="openDocument(document.id)">{{ document.title }}</button><span class="ticket-number">{{ document.versionCount }} 个版本 · {{ formatTime(document.createdAt) }}</span></div><div class="knowledge-document-states"><span class="badge" :class="document.publishedVersionId ? 'resolved' : 'closed'">{{ document.publishedVersionId ? '已发布' : '未发布' }}</span><span v-if="document.latestIngestState" class="badge">{{ states[document.latestIngestState] }}</span></div></li></ul><div v-if="total > 20" class="pagination"><span>第 {{ page + 1 }} 页 · 共 {{ total }} 篇</span><div class="inline-actions"><el-button :disabled="page === 0 || listRead.pending.value" @click="changePage(-1)">上一页</el-button><el-button :disabled="(page + 1) * 20 >= total || listRead.pending.value" @click="changePage(1)">下一页</el-button></div></div></section>
      <el-dialog v-if="spaceEditable" v-model="createOpen" title="新建知识文档" width="min(720px, 94vw)" :before-close="closeCreate" :close-on-click-modal="false" destroy-on-close><section class="knowledge-create"><div class="panel-header"><h2>撰写与导入</h2><el-button :disabled="!!accessFailure" @click="articleOpen = !articleOpen">撰写文章</el-button></div><form v-if="articleOpen" id="article-create-form" class="filter-form" @submit.prevent="createArticle"><div class="field grow"><label for="article-create-title">新文章标题</label><input id="article-create-title" v-model="articleTitle" :disabled="write.pending.value || !!accessFailure" maxlength="240" required /></div><el-button native-type="submit" :loading="write.pending.value" :disabled="!!accessFailure || !articleTitle.trim()">创建文章草稿</el-button></form><p id="knowledge-file-help" class="muted small">支持 PDF、DOCX、Markdown（.md）、TXT；单文件最多 20 MiB，PDF 最多 100 页。解析后需由专职审核者审核通过再发布。</p><form id="knowledge-upload" class="form-stack" @submit.prevent="upload()"><div class="form-row"><div class="field"><label for="knowledge-title">文档标题</label><input id="knowledge-title" v-model="title" maxlength="240" required :disabled="write.pending.value || !!accessFailure" placeholder="例如：办公网络故障排查" /></div><div class="field"><label for="knowledge-file">原始文件</label><KnowledgeFilePicker id="knowledge-file" :key="fileReset" label="原始文件" :model-value="uploadFile" :disabled="write.pending.value || !!accessFailure" described-by="knowledge-file-help" :issue="uploadIssue" @update:model-value="chooseFile($event)" /></div></div><div><el-button native-type="submit" type="primary" :loading="write.pending.value" :disabled="!!accessFailure || !title.trim() || !uploadFile || !!uploadIssue">上传并解析</el-button></div></form></section>
      <div v-if="uploadProgress !== null && write.pending.value" class="upload-progress" role="status"><label for="knowledge-upload-progress">{{ uploadProgress === 100 ? '上传完成，服务器正在接收…' : `文件上传 ${uploadProgress}%` }}</label><progress id="knowledge-upload-progress" :value="uploadProgress" max="100">{{ uploadProgress }}%</progress></div><KnowledgeError :error="write.error.value" /></el-dialog>
    </div></div>
</template>
<style scoped>
.knowledge-workspace { align-items: start; }
.knowledge-main { min-width: 0; }
.knowledge-documents li { gap: var(--space-3); }
.knowledge-document-link { overflow-wrap: anywhere; text-align: left; }
@media (max-width: 768px) { .knowledge-workspace { grid-template-columns: minmax(0, 1fr); } .knowledge-document-states { flex-wrap: wrap; } }
</style>
