<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter, onBeforeRouteUpdate } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, ApiError } from '../api'
import { useSessionStore } from '../stores/session'
import { useUnsavedChanges } from '../composables/unsaved'
import { formatTime } from '../types'
import type { KnowledgeAction, KnowledgeChunk, KnowledgeDetail, KnowledgeUpload, KnowledgeVersion } from '../types'
import { chunksRichDoc, emptyRichDoc, normalizeRichDoc, richHeadings, type RichDoc } from '../knowledge/rich'
import RichDocument from '../components/RichDocument.vue'
import KnowledgeError from '../components/KnowledgeError.vue'
import KnowledgeFilePicker from '../components/KnowledgeFilePicker.vue'
import DocumentActionBar from '../components/DocumentActionBar.vue'

const session = useSessionStore(); const route = useRoute(); const router = useRouter()
const detail = shallowRef<KnowledgeDetail | null>(null); const chunks = ref<KnowledgeChunk[]>([]); const snapshot = shallowRef<RichDoc | null>(null); const selectedVersion = ref(''); const highlightedChunk = ref('')
const error = shallowRef<ApiError | null>(null); const sourceError = shallowRef<ApiError | null>(null); const writeError = shallowRef<ApiError | null>(null); const loading = ref(false); const sourceLoading = ref(false); const pending = ref(false); const citationUnavailable = ref(''); const message = ref('')
const rejectVersion = ref(''); const rejectReason = ref(''); const uploadOpen = ref(false); const versionFile = shallowRef<File | null>(null); const fileReset = ref(0); const uploadProgress = ref<number | null>(null)
let generation = 0; let readGeneration = 0; let sourceGeneration = 0; let readController: AbortController | undefined; let sourceController: AbortController | undefined; let writeController: AbortController | undefined; let pollTimer: ReturnType<typeof setTimeout> | undefined
const viewed = new Set<string>(); const unavailableCitation = '引用已不可用：版本或片段不存在、已撤回或无权访问。请重新生成回答，或从文档列表选择可读内容。'
const tab = computed(() => ['overview', 'versions', 'parse'].includes(String(route.query.tab)) ? String(route.query.tab) : 'overview')
const knowledgeDestination = computed(() => ({ path: '/knowledge', query: detail.value ? { spaceId: detail.value.spaceId } : {} }))
const reviewReturn = computed(() => route.query.fromReview === '1' && !!session.user?.roles.includes('KB_REVIEWER'))
const closeDestination = computed(() => reviewReturn.value ? '/knowledge/reviews' : knowledgeDestination.value)
const reader = computed(() => { try { return { content: snapshot.value || chunksRichDoc(chunks.value), error: null } } catch (failure) { return { content: emptyRichDoc(), error: new ApiError(422, { status: 422, title: (failure as Error).message || '正文暂时无法显示。' }) } } })
const documentActions: KnowledgeAction[] = ['EDIT_DRAFT', 'UPLOAD_VERSION', 'DISABLE', 'ARCHIVE']
const currentActionVersion = computed(() => detail.value?.versions.find(version => version.id === route.query.versionId && version.allowedActions?.length) || detail.value?.versions.find(version => version.id === detail.value?.latestVersionId && version.allowedActions?.length) || detail.value?.versions.find(version => version.allowedActions?.length))
const headerActions = computed(() => [...new Set([...(detail.value?.allowedActions || []).filter(action => documentActions.includes(action)), ...(currentActionVersion.value?.allowedActions || [])])])
const outline = computed(() => reader.value.error ? [] : richHeadings(reader.value.content))
const versionIssue = computed(() => !versionFile.value ? '' : !/\.(pdf|docx|md|txt)$/i.test(versionFile.value.name) ? '仅支持 PDF、DOCX、Markdown（.md）和 TXT 文件。' : versionFile.value.size > 20 * 1024 * 1024 ? '文件不能超过 20 MiB。' : versionFile.value.size === 0 ? '文件内容不能为空。' : '')
const { confirmDiscard } = useUnsavedChanges(() => !!versionFile.value || !!rejectReason.value)
onBeforeRouteUpdate((to, from) => to.params.documentId !== from.params.documentId || to.query.versionId !== from.query.versionId ? confirmDiscard() : true)
const states: Record<string, string> = { QUEUED: '排队中', EXTRACTING: '解析中', CHUNKING: '分块中', READY: '解析就绪', FAILED: '解析失败' }
const reviewLabels: Record<string, string> = { DRAFT: '待提交审核', PENDING: '待审核', APPROVED: '审核通过', REJECTED: '审核不通过' }
function stopPoll() { if (pollTimer) clearTimeout(pollTimer); pollTimer = undefined }
function clearSource() { sourceGeneration++; sourceController?.abort(); chunks.value = []; snapshot.value = null; selectedVersion.value = ''; highlightedChunk.value = ''; sourceLoading.value = false; sourceError.value = null }
function purge() { generation++; readGeneration++; stopPoll(); readController?.abort(); writeController?.abort(); clearSource(); detail.value = null; loading.value = false; pending.value = false; rejectVersion.value = ''; rejectReason.value = ''; versionFile.value = null; fileReset.value++; uploadOpen.value = false; uploadProgress.value = null; message.value = '' }
function problem(failure: unknown) { return failure instanceof ApiError ? failure : new ApiError(0, { status: 0, title: '操作未完成，请重试。' }) }
function denied(issue: ApiError) { if (![401, 403, 404].includes(issue.status)) return false; purge(); error.value = issue; if (route.query.versionId || route.query.chunkId) citationUnavailable.value = unavailableCitation; return true }
function schedulePoll() {
  stopPoll()
  if (detail.value?.versions.some(version => ['QUEUED', 'EXTRACTING', 'CHUNKING'].includes(version.ingestState) || ['QUEUED', 'RUNNING', 'RETRYING'].includes(version.jobStatus || '') || ['PENDING', 'PROCESSING'].includes(version.embeddingState || ''))) pollTimer = setTimeout(() => { void load() }, 3000)
}
function actions(version?: KnowledgeVersion) { return version?.allowedActions || [] }
function can(action: KnowledgeAction, version?: KnowledgeVersion) { return (version ? actions(version) : detail.value?.allowedActions || []).includes(action) }
function headerAction(action: KnowledgeAction) { return act(action, documentActions.includes(action) ? undefined : currentActionVersion.value) }
async function changeTab(value: string) { await router.replace({ query: { ...route.query, tab: value } }) }
async function loadSource(version: KnowledgeVersion) {
  const documentId = detail.value?.id; if (!documentId) return
  clearSource(); citationUnavailable.value = ''; sourceError.value = null
  const requested = route.query.versionId; const requestedChunk = route.query.chunkId
  if (requested != null && requested !== version.id) { citationUnavailable.value = unavailableCitation; return }
  const own = generation; const sourceOwn = sourceGeneration; sourceController = new AbortController(); sourceLoading.value = true; const signal = sourceController.signal
  const current = () => own === generation && sourceOwn === sourceGeneration && detail.value?.id === documentId
  try {
    const [rows, content] = await Promise.all([api.get<KnowledgeChunk[]>(`/knowledge/documents/${documentId}/versions/${version.id}/chunks`, { signal }), api.get<RichDoc | null>(`/knowledge/documents/${documentId}/versions/${version.id}/content`, { signal })])
    if (!current()) return
    if (requestedChunk != null && (typeof requestedChunk !== 'string' || !requestedChunk || !rows.some(chunk => chunk.id === requestedChunk))) { citationUnavailable.value = unavailableCitation; return }
    chunks.value = rows; snapshot.value = content ? normalizeRichDoc(content) : null; selectedVersion.value = version.id
    if (version.id === detail.value?.publishedVersionId && !viewed.has(documentId)) { viewed.add(documentId); try { await api.post(`/knowledge/documents/${documentId}/views`, undefined, { signal }) } catch (failure) { viewed.delete(documentId); if (current() && failure instanceof ApiError && [401, 403, 404].includes(failure.status)) throw failure } }
    if (!current() || typeof requestedChunk !== 'string') return
    highlightedChunk.value = requestedChunk; await nextTick(); if (!current()) return
    const element = document.getElementById(`knowledge-source-${requestedChunk}`); element?.focus({ preventScroll: true }); element?.scrollIntoView({ block: 'center' })
  } catch (failure) { if (current() && !(failure instanceof DOMException && failure.name === 'AbortError')) { const issue = problem(failure); if (!denied(issue)) { clearSource(); sourceError.value = issue } } }
  finally { if (current()) sourceLoading.value = false }
}
async function load() {
  if (!session.authenticated) return
  stopPoll(); readController?.abort(); const own = generation; const ownRead = ++readGeneration; readController = new AbortController(); loading.value = true; error.value = null
  const current = () => own === generation && ownRead === readGeneration
  try {
    const result = await api.get<KnowledgeDetail>(`/knowledge/documents/${String(route.params.documentId)}`, { signal: readController.signal }); if (!current()) return
    detail.value = result
    const requested = route.query.versionId; const chunk = route.query.chunkId
    if (requested != null && (typeof requested !== 'string' || !requested) || chunk != null && (!requested || typeof chunk !== 'string' || !chunk)) { clearSource(); citationUnavailable.value = unavailableCitation; schedulePoll(); return }
    const versionId = requested || selectedVersion.value || result.publishedVersionId
    const version = result.versions.find(version => version.id === versionId)
    if (requested && (!version || version.ingestState !== 'READY')) { clearSource(); citationUnavailable.value = unavailableCitation }
    else if (version?.ingestState === 'READY') await loadSource(version)
    else clearSource()
    if (current() && detail.value) schedulePoll()
  } catch (failure) { if (current() && !(failure instanceof DOMException && failure.name === 'AbortError')) { const issue = problem(failure); if (!denied(issue)) error.value = issue } }
  finally { if (current()) loading.value = false }
}
async function runWrite(task: (signal: AbortSignal) => Promise<unknown>, success: string, destination?: 'reviews' | 'list') {
  if (!detail.value || pending.value) return
  const own = generation; const id = detail.value.id; const spaceId = detail.value.spaceId; pending.value = true; writeError.value = null; uploadProgress.value = null; writeController = new AbortController()
  try {
    await task(writeController.signal); if (own !== generation || detail.value?.id !== id) return
    rejectVersion.value = ''; rejectReason.value = ''; versionFile.value = null; fileReset.value++; uploadOpen.value = false; message.value = success
    if (destination) { ElMessage.success(success); purge(); await router.push(destination === 'reviews' ? '/knowledge/reviews' : { path: '/knowledge', query: { spaceId } }); return }
    await load()
  } catch (failure) { if (own === generation && !(failure instanceof DOMException && failure.name === 'AbortError')) { const issue = problem(failure); if (!denied(issue)) writeError.value = issue } }
  finally { if (own === generation) pending.value = false }
}
async function act(action: KnowledgeAction, version?: KnowledgeVersion) {
  if (!detail.value || pending.value || !can(action, version) || !documentActions.includes(action) && !version) return
  const id = detail.value.id
  if (action === 'EDIT_DRAFT') { await router.push(`/knowledge/articles/${id}/edit`); return }
  if (action === 'UPLOAD_VERSION') { uploadOpen.value = true; await nextTick(); document.querySelector<HTMLButtonElement>('#knowledge-version-upload .file-picker-trigger')?.focus(); return }
  if (action === 'REJECT') { rejectVersion.value = version!.id; await changeTab('versions'); await nextTick(); document.getElementById('review-reason')?.focus(); return }
  if (action === 'DISABLE' || action === 'ARCHIVE') {
    const own = generation
    try { await ElMessageBox.confirm(action === 'DISABLE' ? '撤回后，空间成员将立即无法阅读此文档正文。确认撤回发布？' : '归档后，文档将从空间列表移除，并撤回所有版本。确认归档？', action === 'DISABLE' ? '撤回发布' : '归档文档', { confirmButtonText: action === 'DISABLE' ? '确认撤回' : '确认归档', cancelButtonText: '取消', type: 'warning', closeOnClickModal: false }) } catch { return }
    if (own !== generation || !can(action)) return
  }
  const base = `/knowledge/documents/${id}`; const versionBase = `${base}/versions/${version?.id}`
  const path = action === 'DISABLE' ? `${base}/disable` : action === 'ARCHIVE' ? base : action === 'SUBMIT_REVIEW' ? `${versionBase}/review-requests` : action === 'APPROVE' ? `${versionBase}/reviews` : action === 'RETRY_PARSE' ? `${versionBase}/retry` : action === 'RETRY_INDEX' ? `${versionBase}/index/retry` : `${versionBase}/publish`
  await runWrite(async signal => { if (action === 'ARCHIVE') await api.delete(path); else await api.post(path, action === 'APPROVE' ? { decision: 'APPROVED' } : undefined, { signal }) }, action === 'PUBLISH' ? '版本已发布。' : action === 'APPROVE' ? '审核通过，等待编辑者发布。' : action === 'SUBMIT_REVIEW' ? '已提交专职审核者审核。' : action === 'DISABLE' ? '已撤回发布。' : action === 'ARCHIVE' ? '文档已归档。' : '重试已提交，请等待后台处理。', action === 'APPROVE' ? 'reviews' : action === 'ARCHIVE' ? 'list' : undefined)
}
async function reject() {
  const version = detail.value?.versions.find(version => version.id === rejectVersion.value)
  if (!version || !can('REJECT', version) || !rejectReason.value.trim()) return
  await runWrite(signal => api.post(`/knowledge/documents/${detail.value!.id}/versions/${version.id}/reviews`, { decision: 'REJECTED', reason: rejectReason.value.trim() }, { signal }), '已退回修改，理由已保存。', 'reviews')
}
async function upload() {
  if (!can('UPLOAD_VERSION') || !versionFile.value || versionIssue.value) return
  const id = detail.value!.id; const file = versionFile.value
  await runWrite(signal => { const body = new FormData(); body.append('file', file); return api.upload<KnowledgeUpload>(`/knowledge/documents/${id}/versions`, body, { signal, onProgress: percent => { if (!signal.aborted) uploadProgress.value = percent } }) }, '文件已接收，正在处理。相同文件会复用已有版本与任务。')
}
watch([() => route.params.documentId, () => route.query.versionId, () => route.query.chunkId, () => session.token, () => session.user?.id, () => session.user?.roles.join(',')], () => { purge(); error.value = null; sourceError.value = null; writeError.value = null; citationUnavailable.value = ''; if (session.authenticated) void load() }, { flush: 'sync' })
onBeforeUnmount(purge); void load()
</script>
<template>
  <div class="knowledge-document-page">
    <nav class="document-route-tabs" aria-label="知识文档页签">
      <router-link :to="knowledgeDestination" class="document-route-tab">知识协同</router-link>
      <router-link v-if="reviewReturn" to="/knowledge/reviews" class="document-route-tab">审核待办</router-link>
      <span class="document-route-tab active" aria-current="page" :title="detail?.title || '文档详情'">
        <span class="document-route-tab-label">{{ detail?.title || '文档详情' }}</span>
        <router-link
          :to="closeDestination"
          class="document-route-close"
          :aria-label="reviewReturn ? '关闭文档详情并返回审核待办' : '关闭文档详情并返回知识协同'"
          :title="reviewReturn ? '返回审核待办' : '返回知识协同'"
        ><span aria-hidden="true">×</span></router-link>
      </span>
    </nav>
    <KnowledgeError :error="error" retry @retry="load" /><p v-if="loading && !detail" class="loading-panel" role="status">正在加载文档详情…</p>
    <p v-if="citationUnavailable" class="alert warning citation-unavailable" role="alert">{{ citationUnavailable }}</p>
    <section v-if="detail" class="panel knowledge-detail" :aria-busy="loading">
      <header class="document-heading"><div><p class="small muted">知识文档 · {{ detail.versionCount }} 个版本</p><h1>{{ detail.title }}</h1><p class="small muted">{{ detail.publishedVersionId ? '已发布版本可供有权成员阅读' : '尚未发布' }} · {{ formatTime(detail.createdAt) }}</p></div><el-button :loading="loading" @click="load">刷新状态</el-button></header>
      <DocumentActionBar :actions="headerActions" :pending="pending" @action="headerAction" />
      <p v-if="detail.publishedVersionId && detail.latestVersionId !== detail.publishedVersionId" class="info-notice">新版本处理或待发布期间，旧版本仍保持发布。</p>
      <KnowledgeError :error="writeError" /><p v-if="message" role="status" class="info-notice">{{ message }}</p>
      <form v-if="uploadOpen && can('UPLOAD_VERSION')" id="knowledge-version-upload" class="form-stack document-upload" @submit.prevent="upload"><label for="knowledge-version-file">上传修正后的文件</label><p class="small muted">相同文件会复用已有版本与任务。</p><KnowledgeFilePicker id="knowledge-version-file" :key="fileReset" label="新版本文件" :model-value="versionFile" :issue="versionIssue" :disabled="pending" @update:model-value="versionFile = $event" /><div class="inline-actions"><el-button native-type="submit" type="primary" :loading="pending" :disabled="!versionFile || !!versionIssue">上传新版本文件</el-button><el-button :disabled="pending" @click="uploadOpen = false">收起</el-button></div></form>
      <p v-if="pending && uploadProgress !== null" role="status">文件上传 {{ uploadProgress }}%</p>
      <el-tabs :model-value="tab" @update:model-value="changeTab(String($event))"><el-tab-pane label="文档概览" name="overview" /><el-tab-pane label="版本历史" name="versions" /><el-tab-pane label="解析结果" name="parse" /></el-tabs>
      <section v-if="tab === 'overview'" aria-label="文档概览"><KnowledgeError :error="sourceError" retry @retry="load" /><KnowledgeError :error="reader.error" /><p v-if="sourceLoading" role="status">正在核对正文权限…</p><div v-if="selectedVersion && !citationUnavailable" class="article-editor-layout"><article class="knowledge-reader"><RichDocument :content="reader.content" /></article><aside class="article-outline" aria-label="文章目录"><h2>文章目录</h2><nav v-if="outline.length"><a v-for="heading in outline" :key="heading.id" :href="`#${heading.id}`">{{ heading.text }}</a></nav><p v-else class="small muted">此正文暂无标题目录。</p></aside></div><p v-else-if="!sourceLoading && !sourceError && !citationUnavailable" class="empty-state">此文档尚无可阅读的已发布正文，可在版本历史查看授权版本。</p></section>
      <section v-if="tab === 'versions'" aria-label="版本历史"><ol class="version-list"><li v-for="version in detail.versions" :key="version.id"><div class="panel-header"><h2>版本 {{ version.versionNo }}</h2><div class="inline-actions"><span class="badge">{{ states[version.ingestState] }}</span><span class="badge">{{ reviewLabels[version.reviewState] }}</span></div></div><p class="small knowledge-file-name">{{ version.fileName }} · {{ formatTime(version.createdAt) }}</p><p class="small muted">{{ version.chunkCount }} 个片段 · 已尝试 {{ version.attemptCount }} 次<span v-if="version.jobStatus === 'RETRYING'"> · 自动重试中</span></p><p v-if="version.errorCode" class="error-text" role="status">解析失败：{{ version.errorCode }}</p><p v-if="version.embeddingState === 'FAILED'" class="error-text" role="status">AI 索引失败：{{ version.embeddingErrorCode || '原因待查' }}。正文仍可阅读；此版本暂不参与知识问答检索。</p><p v-if="['PENDING', 'PROCESSING'].includes(version.embeddingState || '')" class="info-notice">{{ version.embeddingState === 'PROCESSING' ? 'AI 索引构建中' : 'AI 索引待处理' }}；审核与发布状态独立。</p><p v-if="version.reviewReason" class="info-notice">审核意见：{{ version.reviewReason }}</p><div v-if="version.id === detail.latestVersionId && version.ingestState === 'READY' && version.reviewState === 'DRAFT' && version.hasEligibleReviewer === false && can('SUBMIT_REVIEW', version)" class="alert warning" role="status"><p>当前空间尚无可用的专职审核者；提交后将等待管理员配置审核权限。</p><router-link v-if="session.user?.roles.includes('ADMIN')" :to="{ path: '/admin/knowledge-spaces', query: { spaceId: detail.spaceId } }">管理知识空间 →</router-link><p v-else>请联系管理员配置此空间的审核权限。</p></div><DocumentActionBar :actions="actions(version)" :pending="pending" :secondary="true" @action="act($event, version)" /><el-button v-if="version.ingestState === 'READY'" :loading="sourceLoading && selectedVersion === version.id" @click="router.replace({ query: { ...route.query, versionId: version.id, chunkId: undefined, tab: 'parse' } })">查看解析结果</el-button><form v-if="rejectVersion === version.id && can('REJECT', version)" id="review-reject-form" class="form-stack" @submit.prevent="reject"><label for="review-reason">不通过理由</label><textarea id="review-reason" v-model="rejectReason" required maxlength="500" :disabled="pending" /><div class="inline-actions"><el-button native-type="submit" type="danger" :loading="pending" :disabled="!rejectReason.trim()">确认审核不通过</el-button><el-button :disabled="pending" @click="rejectVersion = ''; rejectReason = ''">取消</el-button></div></form></li></ol></section>
      <section v-if="tab === 'parse' || highlightedChunk" class="knowledge-chunks" aria-label="引用来源与解析片段"><h2>引用来源与解析片段</h2><KnowledgeError :error="sourceError" retry @retry="load" /><p v-if="sourceLoading" role="status">正在核对来源权限…</p><p v-if="highlightedChunk" role="status" class="citation-located info-notice">已定位引用片段，来源版本及当前访问权限已核对。</p><article v-for="chunk in chunks" :id="`knowledge-source-${chunk.id}`" :key="chunk.id" tabindex="-1" class="knowledge-source-chunk" :class="{ cited: highlightedChunk === chunk.id }"><p class="small muted">片段 {{ chunk.chunkIndex + 1 }}<span v-if="chunk.pageNo"> · 第 {{ chunk.pageNo }} 页</span><span v-if="chunk.headingPath"> · {{ chunk.headingPath }}</span></p><pre class="plain-text">{{ chunk.content }}</pre></article><p v-if="!chunks.length && !sourceLoading && !sourceError && !citationUnavailable" class="empty-state">此版本暂无解析片段。请在版本历史选择要核对的版本。</p></section>
    </section>
  </div>
</template>
<style scoped>
.knowledge-document-page { min-width: 0; }
.document-route-tabs { display: flex; align-items: end; gap: var(--space-1); min-width: 0; margin-bottom: var(--space-4); overflow-x: auto; border-bottom: 1px solid var(--color-border); scrollbar-width: thin; }
.document-route-tab { display: inline-flex; align-items: center; gap: var(--space-2); min-height: var(--control-height); max-width: min(38vw, 440px); padding: 0 var(--space-4); border: 1px solid transparent; border-bottom: 0; border-radius: var(--radius-control) var(--radius-control) 0 0; color: var(--color-text-secondary); font-size: var(--text-sm); font-weight: 500; white-space: nowrap; }
a.document-route-tab:hover { background: var(--color-surface-muted); color: var(--color-primary); text-decoration: none; }
.document-route-tab.active { position: relative; margin-bottom: -1px; border-color: var(--color-border); background: var(--color-surface); box-shadow: inset 0 3px 0 var(--color-primary); color: var(--color-text); font-weight: 600; }
.document-route-tab-label { min-width: 0; overflow: hidden; text-overflow: ellipsis; }
.document-route-close { display: inline-grid; place-items: center; width: 28px; height: 28px; flex: 0 0 28px; border-radius: var(--radius-control); color: var(--color-text-secondary); font-size: var(--text-section); font-weight: 400; line-height: 1; }
.document-route-close:hover { background: var(--color-primary-soft); color: var(--color-primary); text-decoration: none; }
.knowledge-detail { padding: var(--space-5); }
.document-heading { display: flex; justify-content: space-between; align-items: start; gap: var(--space-4); margin-bottom: var(--space-4); }
.document-heading h1 { overflow-wrap: anywhere; margin: var(--space-2) 0; }
.knowledge-reader { min-width: 0; }
.document-upload { padding-block: var(--space-4); }
.knowledge-chunks { min-width: 0; }
.knowledge-source-chunk { scroll-margin-block: var(--space-5); }
.version-list { margin: 0; padding: 0; list-style: none; }
.version-list > li { padding-block: var(--space-5); border-bottom: 1px solid var(--color-border); }
.version-list :deep(.document-actions) { margin-bottom: var(--space-3); }
@media (max-width: 768px) { .document-route-tab { max-width: 72vw; padding-inline: var(--space-3); } .knowledge-detail { padding: var(--space-4); } .document-heading { flex-direction: column; } .article-editor-layout { grid-template-columns: minmax(0, 1fr); } }
</style>
