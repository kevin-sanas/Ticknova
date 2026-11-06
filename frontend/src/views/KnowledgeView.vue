<script setup lang="ts">
import { computed, inject, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteUpdate, routeLocationKey, routerKey } from 'vue-router'
import { api, ApiError, createRequestKey, queryString } from '../api'
import { useSessionStore } from '../stores/session'
import { useUnsavedChanges } from '../composables/unsaved'
import { formatTime } from '../types'
import type { KnowledgeSpace, KnowledgeDocument, KnowledgeUpload, KnowledgeDraft, KnowledgeCategory, KnowledgeDetail, KnowledgeChunk, Page, IngestState } from '../types'
import KnowledgeCategories from '../components/KnowledgeCategories.vue'
import AppIcon from '../components/AppIcon.vue'
import KnowledgeError from '../components/KnowledgeError.vue'
import KnowledgeFilePicker from '../components/KnowledgeFilePicker.vue'
import { chunksRichDoc, emptyRichDoc, normalizeRichDoc, richText, type RichDoc } from '../knowledge/rich'
import RichDocument from '../components/RichDocument.vue'
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
const spacesRead = operation(); const listRead = operation(); const previewRead = operation(); const write = operation(); const categoryRouteRead = operation()
const accessFailure = shallowRef<ApiError | null>(null); const spaces = ref<KnowledgeSpace[]>([]); const spaceId = ref(''); const documents = ref<KnowledgeDocument[]>([]); const total = ref(0); const page = ref(0)
const previewId = ref(''); const previewDocument = computed(() => documents.value.find(document => document.id === previewId.value) || documents.value[0]); const previewContent = shallowRef<RichDoc | null>(null); const previewReady = ref(false)
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
function clearPreview() { previewRead.cancel(); previewContent.value = null; previewReady.value = false }
function clearSpace() { clearPreview(); previewId.value = ''; listRead.cancel(); categoryRouteRead.cancel(); write.cancel(); documents.value = []; total.value = 0; categoryId.value = ''; categoryRouteRows.value = null; page.value = 0; query.value = ''; appliedQuery.value = ''; title.value = ''; uploadFile.value = null; articleTitle.value = ''; fileReset.value++; message.value = ''; uploadProgress.value = null; createOpen.value = false }
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
async function loadPreview() {
  clearPreview()
  const selected = previewDocument.value
  if (!session.authenticated || !selectedSpace.value?.canRead || accessFailure.value || !selected?.publishedVersionId) return
  const id = selected.id; const publishedId = selected.publishedVersionId
  await previewRead.run(async (signal, current) => {
    const detail = await api.get<KnowledgeDetail>(`/knowledge/documents/${encodeURIComponent(id)}`, { signal })
    if (!current()) return
    const version = detail.versions.find(item => item.id === detail.publishedVersionId && item.id === publishedId && item.ingestState === 'READY')
    if (!version) { previewReady.value = true; return }
    const path = `/knowledge/documents/${encodeURIComponent(id)}/versions/${encodeURIComponent(version.id)}`
    const [chunks, content] = await Promise.all([api.get<KnowledgeChunk[]>(`${path}/chunks`, { signal }), api.get<RichDoc | null>(`${path}/content`, { signal })])
    if (!current()) return
    previewContent.value = content ? normalizeRichDoc(content) : chunksRichDoc(chunks)
    previewReady.value = true
  })
}
watch([() => previewDocument.value?.id, () => previewDocument.value?.publishedVersionId, () => session.token, () => session.user?.id], () => { void loadPreview() }, { flush: 'sync' })
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
watch([spacesRead.error, listRead.error, previewRead.error, write.error, categoryRouteRead.error], errors => { const error = errors.find(error => error && [401, 403, 404].includes(error.status)); if (error) denyAccess(error) }, { flush: 'sync' })
watch([() => session.token, () => session.user?.id, () => session.user?.roles.join(',')], () => { routeGeneration++; clearSpace(); spacesRead.cancel(); spaces.value = []; spaceId.value = ''; accessFailure.value = null; if (session.authenticated) void loadSpaces() }, { flush: 'sync' })
onBeforeUnmount(() => { routeGeneration++; clearSpace(); spacesRead.cancel() }); void loadSpaces()
</script>
<template>
  <div class="page-heading"><div><h1>知识库</h1><p class="muted">集中管理知识文档，为员工提供自助查询与问题解决方案。</p></div><div class="inline-actions"><router-link v-if="session.user?.roles.includes('ADMIN')" to="/admin/knowledge-spaces" class="knowledge-manage-link"><AppIcon name="settings" :size="16" />管理空间与成员</router-link><router-link v-if="session.user?.roles.includes('KB_REVIEWER')" to="/knowledge/reviews" class="knowledge-header-link">审核待办</router-link><el-button v-if="spaceEditable" type="primary" @click="createOpen = true"><AppIcon name="plus" />新建文档</el-button></div></div>
  <KnowledgeError :error="accessFailure" retry @retry="loadSpaces" /><KnowledgeError :error="spacesRead.error.value" retry @retry="loadSpaces" /><KnowledgeError :error="categoryRouteRead.error.value" retry @retry="loadSpaces" />
  <p v-if="categoryRouteError" class="alert warning category-route-unavailable" role="alert">{{ categoryRouteError }}</p><p v-if="message" class="info-notice" role="status">{{ message }}</p>
  <form class="knowledge-toolbar" @submit.prevent="search"><div class="field grow"><label class="sr-only" for="knowledge-search">搜索文档标题</label><input id="knowledge-search" v-model="query" type="search" placeholder="搜索知识文档" maxlength="200" /></div><div class="field knowledge-space-control"><label class="sr-only" for="knowledge-space">知识空间</label><select id="knowledge-space" :value="spaceId" :disabled="spacesRead.pending.value" @change="selectSpace(($event.target as HTMLSelectElement).value)"><option value="" disabled>选择知识空间</option><option v-for="space in spaces" :key="space.id" :value="space.id">{{ space.name }}{{ space.active ? '' : '（已停用）' }}</option></select></div><el-button native-type="submit" :loading="listRead.pending.value"><AppIcon name="search" :size="16" />搜索</el-button><el-button :loading="spacesRead.pending.value" @click="reloadSpaces"><AppIcon name="history" :size="15" />刷新空间</el-button></form>
  <div class="knowledge-workspace" :class="{ 'without-navigation': !selectedSpace?.canRead || accessFailure || categoryRouteError || routeApplying }">
    <div v-if="selectedSpace?.canRead && !accessFailure && !categoryRouteError && !routeApplying" class="knowledge-local-nav">
      <KnowledgeCategories :key="spaceId" :space-id="spaceId" :can-edit="spaceEditable" :model-value="categoryId" :initial-categories="categoryRouteRows || undefined" @update:model-value="selectCategory" @denied="denyAccess" />
    </div>
    <div class="knowledge-main">
      <p v-if="spacesRead.pending.value || routeApplying" class="loading-panel" role="status">正在加载知识空间…</p>
      <div v-else-if="!spaces.length && !spacesRead.error.value && !accessFailure" class="panel empty-state"><AppIcon name="book" :size="32" /><h2>暂无可访问的知识空间</h2><p>请联系空间管理员授予阅读权限。</p></div>
      <section v-if="selectedSpace?.canRead && !accessFailure && !categoryRouteError" class="panel knowledge-section knowledge-list" :aria-busy="listRead.pending.value">
        <div class="panel-header"><div><h2>{{ selectedSpace.name }}</h2><span class="small muted">{{ total }} 篇文档 · 仅显示你有权访问的内容</span></div><el-button :loading="listRead.pending.value" @click="loadDocuments"><AppIcon name="history" :size="16" />刷新文档</el-button></div>
        <KnowledgeError :error="listRead.error.value" retry @retry="loadDocuments" /><p v-if="listRead.pending.value" class="loading-panel" role="status">正在加载文档…</p>
        <div v-else-if="!documents.length && !listRead.error.value" class="empty-state"><AppIcon name="file" :size="32" /><h3>{{ appliedQuery ? '没有找到匹配的文档' : '此空间暂无文档' }}</h3><p>{{ appliedQuery ? '试试其他关键词。' : spaceEditable ? '上传一份文件，或开始撰写团队的第一篇指南。' : '内容发布后会显示在这里。' }}</p><el-button v-if="spaceEditable && !appliedQuery" @click="createOpen = true">开始创建文档</el-button></div>
        <ul v-else class="knowledge-documents"><li v-for="document in documents" :key="document.id"><span class="document-file-icon"><AppIcon name="file" :size="20" /></span><div class="knowledge-document-label"><button class="knowledge-document-link" @click="openDocument(document.id)">{{ document.title }}</button><span class="ticket-number">{{ document.versionCount }} 个版本 · {{ formatTime(document.createdAt) }}</span></div><div class="knowledge-document-states"><button type="button" class="knowledge-preview-button" :aria-label="`查看${document.title}的信息`" :aria-pressed="previewDocument?.id === document.id" @click="previewId = document.id">查看信息</button><span class="badge" :class="document.publishedVersionId ? 'resolved' : 'closed'">{{ document.publishedVersionId ? '已发布' : '未发布' }}</span><span v-if="document.latestIngestState" class="badge">{{ states[document.latestIngestState] }}</span><AppIcon name="chevron" :size="16" /></div></li></ul>
        <div v-if="total > 20" class="pagination"><span>第 {{ page + 1 }} 页 · 共 {{ total }} 篇</span><div class="inline-actions"><el-button :disabled="page === 0 || listRead.pending.value" @click="changePage(-1)">上一页</el-button><el-button :disabled="(page + 1) * 20 >= total || listRead.pending.value" @click="changePage(1)">下一页</el-button></div></div>
      </section>
    </div>
    <aside v-if="selectedSpace?.canRead && !accessFailure && !categoryRouteError" class="knowledge-preview" :class="{ 'has-document': !!previewDocument }" aria-label="文档预览">
      <div class="knowledge-preview-heading"><h2>文档预览</h2><span v-if="previewDocument" class="badge" :class="previewDocument.publishedVersionId ? 'resolved' : 'closed'">{{ previewDocument.publishedVersionId ? '已发布' : '未发布' }}</span></div>
      <template v-if="previewDocument"><p class="small muted">{{ selectedSpace.name }}</p><h3>{{ previewDocument.title }}</h3><dl><div><dt>创建时间</dt><dd>{{ formatTime(previewDocument.createdAt) }}</dd></div><div><dt>版本数量</dt><dd>{{ previewDocument.versionCount }}</dd></div><div v-if="previewDocument.latestIngestState"><dt>解析状态</dt><dd>{{ states[previewDocument.latestIngestState] }}</dd></div></dl><div class="knowledge-preview-content" aria-label="已发布正文预览" :aria-busy="previewRead.pending.value"><p v-if="previewRead.pending.value" role="status" class="small muted">正在加载已发布正文…</p><KnowledgeError :error="previewRead.error.value" retry @retry="loadPreview" /><RichDocument v-if="previewContent && richText(previewContent).trim()" :content="previewContent" /><p v-else-if="previewReady || !previewDocument.publishedVersionId" class="small muted">暂无可预览的已发布正文。</p></div><el-button type="primary" @click="openDocument(previewDocument.id)">阅读全文</el-button></template><p v-else class="small muted">当前列表暂无文档。</p>
    </aside>
  </div>
  <el-drawer v-if="spaceEditable" v-model="createOpen" title="新建知识文档" size="540px" :before-close="closeCreate" :close-on-click-modal="false" destroy-on-close>
    <p class="muted">{{ selectedSpace?.name }} · 新版本由专职审核者核对后发布。</p>
    <div class="segmented-control" role="group" aria-label="创建方式"><button type="button" :aria-pressed="!articleOpen" :disabled="write.pending.value" @click="articleOpen = false"><AppIcon name="upload" :size="16" />上传文件</button><button type="button" :aria-pressed="articleOpen" :disabled="write.pending.value" @click="articleOpen = true"><AppIcon name="edit" :size="16" />撰写文章</button></div>
    <section class="knowledge-create">
      <form v-if="articleOpen" id="article-create-form" class="form-stack" @submit.prevent="createArticle"><div class="field"><label for="article-create-title">新文章标题</label><input id="article-create-title" v-model="articleTitle" :disabled="write.pending.value || !!accessFailure" maxlength="240" required placeholder="为这篇指南起个清晰的名字" /></div><p class="muted small">创建后进入文章编辑器。草稿自动保存，可预览并生成待审核版本。</p><div class="form-actions"><el-button native-type="submit" type="primary" :loading="write.pending.value" :disabled="!!accessFailure || !articleTitle.trim()">创建文章草稿</el-button></div></form>
      <form v-else id="knowledge-upload" class="form-stack" @submit.prevent="upload()"><div class="field"><label for="knowledge-title">文档标题</label><input id="knowledge-title" v-model="title" maxlength="240" required :disabled="write.pending.value || !!accessFailure" placeholder="例如：办公网络故障排查" /></div><div class="field"><label for="knowledge-file">原始文件</label><KnowledgeFilePicker id="knowledge-file" :key="fileReset" label="原始文件" :model-value="uploadFile" :disabled="write.pending.value || !!accessFailure" described-by="knowledge-file-help" :issue="uploadIssue" @update:model-value="chooseFile($event)" /></div><p id="knowledge-file-help" class="muted small">支持 PDF、DOCX、Markdown（.md）、TXT；单文件最多 20 MiB，PDF 最多 100 页。</p><div class="form-actions"><el-button native-type="submit" type="primary" :loading="write.pending.value" :disabled="!!accessFailure || !title.trim() || !uploadFile || !!uploadIssue">上传并解析</el-button></div></form>
    </section>
    <div v-if="uploadProgress !== null && write.pending.value" class="upload-progress" role="status"><label for="knowledge-upload-progress">{{ uploadProgress === 100 ? '上传完成，服务器正在接收…' : `文件上传 ${uploadProgress}%` }}</label><progress id="knowledge-upload-progress" :value="uploadProgress" max="100">{{ uploadProgress }}%</progress></div><KnowledgeError :error="write.error.value" />
  </el-drawer>
</template>
<style scoped>
.knowledge-workspace.without-navigation{grid-template-columns:minmax(0,1fr)}.knowledge-local-nav{position:sticky;top:24px;min-width:0;padding-top:4px}.knowledge-local-nav>.el-button{margin:8px 0 20px}.knowledge-main{min-width:0}.knowledge-documents li{gap:16px}.knowledge-document-label{flex:1}.knowledge-document-link{overflow-wrap:anywhere;text-align:left;color:var(--color-text)}.document-file-icon{display:grid;place-items:center;flex:none;width:38px;height:42px;background:var(--color-primary-soft);border-radius:var(--radius-control);color:var(--color-primary)}.knowledge-manage-link{display:flex;gap:8px;align-items:center;padding:20px 0;border-top:1px solid var(--color-border);font-size:var(--text-xs)}.knowledge-list>.filter-form{padding-bottom:20px;border-bottom:1px solid var(--color-border)}.knowledge-create{margin-top:28px}.knowledge-document-states{flex:none}

.page-heading h1{font-size:25px;line-height:1.25;margin:0 0 6px}.page-heading p{font-size:13px;margin:0}.knowledge-header-link,.knowledge-manage-link{display:inline-flex;align-items:center;gap:7px;padding:8px 12px;border:1px solid #d3dce6;border-radius:7px;color:#345c83;background:#fff;text-decoration:none;font-size:13px;font-weight:600}.knowledge-toolbar{display:flex;align-items:center;gap:10px;background:#fff;border:1px solid #dce2e8;border-radius:8px;padding:12px;margin:0 0 14px}.knowledge-toolbar .grow{flex:1;min-width:120px}.knowledge-toolbar .field{margin:0}.knowledge-toolbar input,.knowledge-toolbar select{height:38px}.knowledge-space-control{width:190px;flex:none}.knowledge-workspace{display:grid;grid-template-columns:minmax(190px,210px) minmax(330px,1.1fr) minmax(320px,1fr);gap:14px;align-items:start}.knowledge-local-nav{position:static;padding:16px;border:1px solid #dce2e8;border-radius:9px;background:#fff}.knowledge-local-nav>.el-button{margin:8px 0 12px}.knowledge-local-nav :deep(.knowledge-categories){padding:0;border:0;box-shadow:none}.knowledge-local-nav :deep(.knowledge-categories .panel-header){margin:0 0 14px;padding:0 0 14px;border-bottom:1px solid #e5eaf0}.knowledge-local-nav :deep(.knowledge-categories nav button){display:block;width:100%;padding:9px 10px;text-align:left;border:0;border-radius:6px;background:transparent;color:#30485f}.knowledge-local-nav :deep(.knowledge-categories nav button.selected){background:#e4eef9;color:#28527c;font-weight:700}.knowledge-local-nav :deep(.knowledge-categories .panel-header button){border:0;background:none;color:#355e89;font-size:12px;cursor:pointer}.knowledge-manage-link{display:inline-flex;justify-content:center}.knowledge-list{padding:0;overflow:hidden;border:1px solid #dce2e8;border-radius:9px;background:#fff}.knowledge-list>.panel-header{padding:17px 20px;border-bottom:1px solid #e5eaf0;margin:0}.knowledge-list>.panel-header h2{font-size:16px;margin:0 0 4px}.knowledge-list>.filter-form{padding:13px 20px;margin:0}.knowledge-list>.empty-state,.knowledge-list>.loading-panel{margin:20px}.knowledge-documents{margin:0;padding:0;list-style:none}.knowledge-documents li{display:flex;align-items:center;min-height:88px;padding:14px 20px;border-bottom:1px solid #e7ebef}.knowledge-documents li:hover{background:#f7faff}.knowledge-document-link{border:0;padding:0;background:transparent;color:#243c54;font-weight:700;font-size:15px;cursor:pointer;text-decoration:none}.knowledge-document-label{display:flex;flex-direction:column;gap:8px;min-width:0}.knowledge-document-states{display:flex;align-items:center;gap:8px}.knowledge-list .pagination{padding:14px 20px}.document-file-icon{background:#e9f1fa}.knowledge-create{margin-top:24px}.knowledge-preview{background:#fff;border:1px solid #dce2e8;border-radius:9px;padding:18px;min-width:0}.knowledge-preview.has-document{display:flex;flex-direction:column;min-height:min(610px,calc(100vh - 225px))}.knowledge-preview-heading{display:flex;align-items:center;justify-content:space-between;gap:8px;padding-bottom:13px;border-bottom:1px solid #e5eaf0}.knowledge-preview h2{font-size:16px;margin:0}.knowledge-preview h3{font-size:18px;line-height:1.4;margin:12px 0 20px;overflow-wrap:anywhere}.knowledge-preview-content{margin:14px 0;flex:1;max-height:400px;overflow:auto;line-height:1.65;color:#344c62;overflow-wrap:anywhere}.knowledge-preview-content :deep(.rich-document){font-size:13px}.knowledge-preview-content :deep(.rich-document h1),.knowledge-preview-content :deep(.rich-document h2),.knowledge-preview-content :deep(.rich-document h3){font-size:14px;margin:12px 0 6px}.knowledge-preview-content :deep(.rich-document pre){white-space:pre-wrap;overflow-wrap:anywhere}.knowledge-preview dl{border-top:1px solid #e5eaf0;padding-top:10px}.knowledge-preview dl>div{display:flex;justify-content:space-between;gap:8px;margin:12px 0;font-size:13px}.knowledge-preview dt{color:#65798b}.knowledge-preview dd{margin:0;text-align:right}.knowledge-preview>.el-button{align-self:flex-start}.knowledge-preview-button{border:0;background:transparent;color:#355e89;font-size:12px;cursor:pointer;white-space:nowrap}.knowledge-preview-button[aria-pressed=true]{font-weight:700}@media(max-width:1150px){.knowledge-workspace{grid-template-columns:minmax(185px,220px) minmax(0,1fr)}.knowledge-preview{grid-column:2}}@media(max-width:780px){.knowledge-toolbar{flex-wrap:wrap}.knowledge-toolbar .grow{flex-basis:100%}.knowledge-space-control{flex:1;width:auto}.knowledge-workspace{grid-template-columns:1fr}.knowledge-preview{grid-column:auto}.knowledge-local-nav{position:static}.knowledge-documents li{flex-wrap:wrap}.knowledge-document-states{margin-left:54px}}
</style>
