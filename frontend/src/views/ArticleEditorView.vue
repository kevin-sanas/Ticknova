<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { api, ApiError, createRequestKey } from '../api'
import { useSessionStore } from '../stores/session'
import { useUnsavedChanges } from '../composables/unsaved'
import { createDraftAutosave } from '../knowledge/autosave'
import { normalizeRichDoc, richHeadings, richText, type RichDoc } from '../knowledge/rich'
import type { KnowledgeCategory, KnowledgeDetail, KnowledgeDraft, KnowledgeDraftSource, KnowledgeUpload, KnowledgeVersion } from '../types'
import RichTextEditor from '../components/RichTextEditor.vue'
import RichDocument from '../components/RichDocument.vue'
import KnowledgeError from '../components/KnowledgeError.vue'
const session = useSessionStore(); const route = useRoute(); const router = useRouter()
const loading = ref(false); const committing = ref(false); const error = shallowRef<ApiError | null>(null); const categories = ref<KnowledgeCategory[]>([]); const preview = ref(false); const contentError = ref(''); const message = ref('')
const sourceVersion = shallowRef<KnowledgeVersion | null>(null); const sourcePreview = shallowRef<KnowledgeDraftSource | null>(null); const sourceError = shallowRef<ApiError | null>(null); const sourceLoading = ref(false); const importing = ref(false)
let generation = 0; let controller: AbortController | undefined
const saver = createDraftAutosave(async (payload, signal) => {
  const id = String(route.params.id); const result = await api.put<KnowledgeDraft>(`/knowledge/documents/${id}/draft`, { ...payload, content: normalizeRichDoc(payload.content) }, { signal }); return { ...result, content: normalizeRichDoc(result.content) }
})
const state = saver.state; const draft = computed(() => state.value.draft); const headings = computed(() => draft.value ? richHeadings(draft.value.content) : [])
const dirty = computed(() => state.value.dirty || !!contentError.value)
const { confirmDiscard } = useUnsavedChanges(() => dirty.value)
onBeforeRouteUpdate((to, from) => to.params.id !== from.params.id ? confirmDiscard() : true)
const statusLabel = computed(() => ({ saved: '已保存', unsaved: '有未保存更改', saving: '正在保存…', error: '保存失败，修改已保留', conflict: '保存冲突，修改已保留' })[state.value.status])
const saveError = computed(() => state.value.error instanceof ApiError ? state.value.error : null)
const commitKey = createRequestKey()
function edit(values: Partial<KnowledgeDraft>) { if (draft.value) { saver.edit({ ...draft.value, ...values }); commitKey.reset() } }
function editContent(content: RichDoc) { contentError.value = ''; edit({ content }) }
function clear() { generation++; controller?.abort(); saver.reset(null); categories.value = []; preview.value = false; contentError.value = ''; message.value = ''; committing.value = false; loading.value = false; sourceVersion.value = null; sourcePreview.value = null; sourceError.value = null; sourceLoading.value = false; importing.value = false }
async function load() {
  clear(); error.value = null; if (!session.authenticated) return
  const own = generation; controller = new AbortController(); loading.value = true
  try {
    const result = await api.get<KnowledgeDraft>(`/knowledge/documents/${String(route.params.id)}/draft`, { signal: controller.signal })
    const rows = await api.get<KnowledgeCategory[]>(`/knowledge/spaces/${result.spaceId}/categories`, { signal: controller.signal })
    if (own !== generation) return
    saver.reset({ ...result, content: normalizeRichDoc(result.content) }); categories.value = rows
    if (!richText(result.content).trim()) {
      try {
        const detail = await api.get<KnowledgeDetail>(`/knowledge/documents/${result.id}`, { signal: controller.signal })
        if (own !== generation) return
        sourceVersion.value = detail.versions.find(version => version.id === detail.latestVersionId && version.ingestState === 'READY' && version.chunkCount > 0)
          || detail.versions.find(version => version.id === detail.publishedVersionId && version.ingestState === 'READY' && version.chunkCount > 0) || null
      } catch (failure) {
        if (own !== generation || failure instanceof DOMException && failure.name === 'AbortError') return
        const issue = failure instanceof ApiError ? failure : new ApiError(0, { status: 0, title: '解析版本状态加载失败，请重试。' })
        if ([401, 403, 404].includes(issue.status)) error.value = issue; else sourceError.value = issue
      }
    }
  } catch (failure) { if (own === generation && !(failure instanceof DOMException && failure.name === 'AbortError')) error.value = failure instanceof ApiError ? failure : new ApiError(0, { status: 0, title: (failure as Error).message || '草稿加载失败，请重试。' }) }
  finally { if (own === generation) loading.value = false }
}
async function reload() { if (!dirty.value || await confirmDiscard()) await load() }
async function save() { if (!draft.value?.title.trim() || contentError.value) return false; return saver.flush() }
async function previewSource() {
  if (!draft.value || !sourceVersion.value || dirty.value || sourceLoading.value) return
  const own = generation; const id = draft.value.id; const version = sourceVersion.value.id
  sourceLoading.value = true; sourceError.value = null; sourcePreview.value = null
  try {
    const result = await api.get<KnowledgeDraftSource>(`/knowledge/documents/${id}/versions/${version}/draft-source`, { signal: controller?.signal })
    if (own !== generation || sourceVersion.value?.id !== version) return
    sourcePreview.value = result.content ? { ...result, content: normalizeRichDoc(result.content) } : result
  } catch (failure) {
    if (own === generation && !(failure instanceof DOMException && failure.name === 'AbortError')) sourceError.value = failure instanceof ApiError ? failure : new ApiError(0, { status: 0, title: '解析正文预览失败，请重试。' })
  } finally { if (own === generation) sourceLoading.value = false }
}
async function importSource() {
  if (!draft.value || !sourcePreview.value?.importable || !sourcePreview.value.content || dirty.value || importing.value || richText(draft.value.content).trim()) return
  const own = generation; importing.value = true; sourceError.value = null
  try {
    saver.edit({ ...draft.value, content: sourcePreview.value.content })
    const saved = await saver.flush()
    if (own === generation && saved) { sourcePreview.value = null; message.value = '解析正文已导入并保存，可继续编辑。' }
  } finally { if (own === generation) importing.value = false }
}
async function commit() {
  if (!draft.value || committing.value || contentError.value || !draft.value.title.trim() || !richText(draft.value.content).trim()) return
  const own = generation; const id = draft.value.id; const token = session.token; const actor = session.user?.id; const signal = controller?.signal
  const current = () => own === generation && token === session.token && actor === session.user?.id && String(route.params.id) === id && draft.value?.id === id
  committing.value = true; error.value = null
  try {
    const saved = await save()
    if (!current() || !saved || state.value.dirty || !draft.value) return
    const version = draft.value.version
    await api.post<KnowledgeUpload>(`/knowledge/documents/${id}/draft/versions`, { version }, { signal, key: commitKey.for({ id, version }) })
    if (!current()) return
    message.value = '新版本已提交解析；就绪后需由另一位编辑审核通过才能发布。'; await router.push({ path: '/knowledge', query: { documentId: id } })
  }
  catch (failure) { if (current()) error.value = failure instanceof ApiError ? failure : new ApiError(0, { status: 0, title: '提交版本失败，请重试。' }) }
  finally { if (own === generation) committing.value = false }
}
watch(() => state.value.error, failure => { if (failure instanceof ApiError && [401, 403, 404].includes(failure.status)) { clear(); error.value = failure } }, { flush: 'sync' })
watch(() => error.value, failure => { if (failure && [401, 403, 404].includes(failure.status)) clear() }, { flush: 'sync' })
watch([() => route.params.id, () => session.token], () => { clear(); if (session.authenticated) void load() }, { flush: 'sync' })
onBeforeUnmount(() => { clear(); saver.dispose() }); void load()
</script>
<template>
  <div class="article-editor-page">
    <div class="page-heading"><div><h1>编辑知识文章</h1><p class="muted">草稿自动保存；新版本由另一位编辑审核后发布。</p></div><el-button @click="router.push({ path: '/knowledge', query: draft ? { documentId: draft.id } : {} })">返回知识中心</el-button></div>
    <KnowledgeError :error="error" retry @retry="reload" /><p v-if="loading" class="loading-panel" role="status">正在加载授权草稿…</p>
    <template v-if="session.authenticated && draft">
      <KnowledgeError v-if="!sourceVersion" :error="sourceError" retry @retry="reload" />
      <div class="article-save-bar"><span role="status" aria-live="polite" :class="['save-status', state.status]">{{ statusLabel }}</span><span class="small muted">草稿版本 {{ draft.version }}</span><div class="inline-actions"><el-button id="article-preview" :disabled="!!contentError" :aria-pressed="preview" @click="preview = !preview">{{ preview ? '继续编辑' : '预览文章' }}</el-button><el-button id="article-save" :disabled="!dirty || state.status === 'conflict' || !!contentError || !draft.title.trim()" :loading="state.status === 'saving'" @click="save">保存草稿</el-button><el-button type="primary" :loading="committing" :disabled="state.status === 'conflict' || !!contentError || !draft.title.trim() || !richText(draft.content).trim()" @click="commit">生成待审核版本</el-button></div></div>
      <KnowledgeError :error="saveError" /><div v-if="state.status === 'conflict'" class="alert warning" role="alert"><strong>保存冲突</strong><p>服务器草稿已被其他编辑修改。你的修改保留在当前编辑器中；请先复制需要保留的内容，再重新载入服务器版本。</p><el-button id="article-reload" @click="reload">重新载入服务器版本</el-button></div><p v-if="contentError" class="error-text" role="alert">{{ contentError }}</p><p v-if="message" class="info-notice" role="status">{{ message }}</p>
      <div v-if="sourceVersion && !richText(draft.content).trim()" class="info-notice article-source-recovery"><strong>{{ draft.version > 0 ? '已保存的空白草稿' : '解析正文可导入' }}</strong><p>版本 {{ sourceVersion.versionNo }} 已解析完成。先预览，再确认导入正文；导入前不会改动当前草稿。</p><el-button id="article-preview-source" :loading="sourceLoading" :disabled="dirty || importing" @click="previewSource">预览解析正文</el-button><p v-if="dirty" class="small muted">请先保存当前修改，再导入解析正文。</p><KnowledgeError :error="sourceError" retry @retry="previewSource" /><p v-if="sourcePreview && !sourcePreview.importable" role="alert">{{ sourcePreview.reason }}</p><section v-if="sourcePreview?.content && sourcePreview.importable" class="article-source-preview" aria-label="待导入的解析正文"><h2>版本 {{ sourcePreview.versionNo }} · 导入预览</h2><RichDocument :content="sourcePreview.content" /><div class="inline-actions"><el-button type="primary" :loading="importing" :disabled="dirty" @click="importSource">确认导入正文</el-button><el-button :disabled="importing" @click="sourcePreview = null">取消</el-button></div></section></div>
      <div class="article-editor-layout"><section class="panel article-authoring"><div class="form-row"><div class="field grow"><label for="article-title">文章标题</label><input id="article-title" :value="draft.title" maxlength="240" required :disabled="committing" @input="edit({ title: ($event.target as HTMLInputElement).value })" /></div><div class="field"><label for="article-category">文章分类</label><select id="article-category" :value="draft.categoryId || ''" :disabled="committing" @change="edit({ categoryId: ($event.target as HTMLSelectElement).value || null })"><option value="">未分类</option><option v-for="category in categories" :key="category.id" :value="category.id">{{ category.name }}</option></select></div></div><div v-if="preview" class="article-preview"><h1>{{ draft.title }}</h1><RichDocument :content="draft.content" /></div><RichTextEditor v-else :model-value="draft.content" :disabled="committing" @update:model-value="editContent" @error="contentError = $event" /></section><aside class="article-outline" aria-label="文章目录"><h2>文章目录</h2><nav v-if="preview && headings.length"><a v-for="heading in headings" :key="heading.id" :href="`#${heading.id}`" :class="`level-${heading.level}`">{{ heading.text }}</a></nav><ol v-else-if="headings.length"><li v-for="heading in headings" :key="heading.id">{{ heading.text }}</li></ol><p v-else class="muted small">添加标题后自动生成目录。</p></aside></div>
    </template>
    <p v-else-if="!loading && !error" class="empty-state">当前没有可编辑的授权草稿。</p>
  </div>
</template>
