<script setup lang="ts">
import { computed, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiError, queryString } from '../api'
import { useSessionStore } from '../stores/session'
import { hasTicketModule } from '../access'
import { statuses, priorities, formatTime } from '../types'
import type { WorkspacePortal } from '../workspace-types'
import ErrorNotice from '../components/ErrorNotice.vue'
const session = useSessionStore(), route = useRoute(), router = useRouter()
const routeQuery = computed(() => typeof route.query.q === 'string' ? route.query.q : '')
const query = ref(routeQuery.value), mode = ref<'knowledge' | 'assistant'>('knowledge')
const result = shallowRef<WorkspacePortal | null>(null), error = shallowRef<ApiError | null>(null), pending = ref(false)
let generation = 0, controller: AbortController | undefined
const ticketsAllowed = computed(() => hasTicketModule(session.user))
const ticketHeading = computed(() => session.user?.roles.includes('AGENT') ? '可访问工单' : '我的工单')
const documents = computed(() => result.value ? result.value.query ? result.value.knowledge : result.value.popularDocuments : [])
const ticketPreview = computed(() => result.value?.tickets.slice(0, 3) || [])
const popularTitle = computed(() => result.value?.query ? '搜索结果' : result.value?.popularDocuments.some(item => item.viewCount > 0) ? '热门帮助文档' : '最近发布的知识')
const shortcuts = computed(() => [...new Set(result.value?.popularDocuments.map(item => item.title) || [])].slice(0, 3))
const categoryName = (id: string | null) => result.value?.categories.find(item => item.id === id)?.name || '未分类'
async function load() {
  const current = ++generation, token = session.token
  controller?.abort(); controller = new AbortController()
  pending.value = true; error.value = null; result.value = null
  try {
    const next = await api.get<WorkspacePortal>(`/workspace/portal?${queryString({ q: routeQuery.value.trim() })}`, { signal: controller.signal })
    if (current !== generation || token !== session.token) return
    if (!Array.isArray(next.knowledge) || !Array.isArray(next.tickets) || !Array.isArray(next.categories) || !Array.isArray(next.popularDocuments)) throw new Error('Invalid response')
    result.value = next
  } catch (failure) {
    if (current !== generation || token !== session.token) return
    error.value = failure instanceof ApiError ? failure : new ApiError(0, { title: '无法加载服务门户，请重试。', status: 0 })
  } finally { if (current === generation) pending.value = false }
}
async function search() {
  const q = query.value.trim()
  if (mode.value === 'assistant') { await router.push({ path: '/assistant', query: q ? { q } : {} }); return }
  if (q === routeQuery.value) { if (!pending.value) await load(); return }
  await router.replace({ query: q ? { q } : {} })
}
async function searchShortcut(value: string) { mode.value = 'knowledge'; query.value = value; await search() }
watch(routeQuery, () => { query.value = routeQuery.value; void load() }, { immediate: true, flush: 'sync' })
watch(() => [session.token, session.user] as const, () => {
  generation++; controller?.abort(); result.value = null; query.value = ''; pending.value = false; error.value = null
}, { flush: 'sync' })
onBeforeUnmount(() => { generation++; controller?.abort() })
</script>
<template>
  <div class="portal-page">
    <header class="page-heading"><div><h1>员工服务门户</h1><p class="muted">查找帮助、向知识助手提问，或让服务团队为你跟进。</p></div><router-link v-if="ticketsAllowed" class="primary-link" to="/tickets/new">＋ 提交工单</router-link></header>
    <section class="portal-search-area" aria-labelledby="portal-search-heading">
      <h2 id="portal-search-heading">有什么可以帮到你？</h2><p class="muted">先找一份指南，让工作继续顺畅进行。</p>
      <div class="search-modes" role="group" aria-label="选择搜索方式"><button type="button" data-mode="knowledge" :aria-pressed="mode === 'knowledge'" @click="mode = 'knowledge'">搜索知识库</button><button type="button" data-mode="assistant" :aria-pressed="mode === 'assistant'" @click="mode = 'assistant'">问知识助手</button></div>
      <form id="portal-search-form" class="portal-search" role="search" @submit.prevent="search"><label for="portal-search" class="sr-only">{{ mode === 'assistant' ? '向知识助手提问' : '搜索问题、知识和工单' }}</label><input id="portal-search" v-model="query" type="search" maxlength="256" :placeholder="mode === 'assistant' ? '描述你的问题，助手将查找可引用的知识…' : '搜索问题或帮助文档，例如 VPN 无法连接…'" /><el-button type="primary" native-type="submit" :loading="pending && mode === 'knowledge'">{{ mode === 'assistant' ? '向助手提问' : '搜索' }}</el-button></form>
      <div v-if="shortcuts.length" class="search-shortcuts"><span>快捷搜索：</span><button v-for="word in shortcuts" :key="word" type="button" @click="searchShortcut(word)">{{ word }}</button></div>
    </section>
    <ErrorNotice :error="error" reload @reload="load" />
    <div v-if="pending" class="loading-panel" role="status" aria-label="正在搜索服务内容"><div v-for="n in 3" :key="n" class="loading-line" aria-hidden="true"></div>正在查找你有权访问的内容…</div>
    <template v-if="result && !pending">
      <nav v-if="result.categories.length" class="category-chips" aria-label="常见问题分类"><router-link v-for="category in result.categories" :key="category.id" :to="{ path: '/knowledge', query: { spaceId: category.spaceId, categoryId: category.id } }" :title="category.spaceName">{{ category.name }} <span>{{ category.documentCount }}</span></router-link></nav>
      <div class="portal-columns">
        <section class="portal-main" aria-labelledby="portal-documents-heading"><div class="panel-header"><h2 id="portal-documents-heading">{{ popularTitle }}</h2><router-link to="/knowledge">浏览知识库 →</router-link></div><p class="small muted">仅显示当前账号可阅读的已发布知识。</p>
          <p v-if="!documents.length" class="empty-state">{{ result.query ? '没有找到相关知识，可以调整关键词或提交工单。' : '还没有你可阅读的已发布文档。' }}</p>
          <div v-else class="document-grid"><article v-for="item in documents" :key="item.id" class="document-card"><span class="badge">{{ categoryName(item.categoryId) }}</span><h3><router-link :to="`/knowledge/documents/${item.id}`">{{ item.title }}</router-link></h3><p class="document-snippet">{{ item.snippet || '打开文档查看完整内容。' }}</p><div class="document-meta"><time v-if="item.publishedAt" :datetime="item.publishedAt">发布于 {{ formatTime(item.publishedAt) }}</time><span v-else>发布时间未提供</span><span>{{ item.viewCount }} 次阅读</span></div></article></div>
        </section>
        <aside v-if="ticketsAllowed" class="panel portal-tickets" aria-labelledby="portal-tickets-heading"><div class="panel-header"><h2 id="portal-tickets-heading">{{ ticketHeading }}</h2><router-link :to="{ path: '/tickets', query: result.query ? { keyword: result.query } : {} }">查看全部 →</router-link></div><p class="small muted">{{ result.query ? '与搜索关键词相关的可访问工单。' : '查看服务请求的最新进展。' }}</p><p v-if="!result.tickets.length" class="empty-state">{{ result.query ? '没有找到相关工单。' : '还没有待跟进的工单，可以先搜索帮助内容。' }}</p><article v-for="ticket in ticketPreview" :key="ticket.id" class="portal-ticket"><span class="ticket-number">#{{ ticket.ticketNo }}</span><h3><router-link :to="`/tickets/${ticket.id}`">{{ ticket.title }}</router-link></h3><div class="ticket-meta"><span :class="['badge', 'status', ticket.status.toLowerCase()]">{{ statuses[ticket.status] || ticket.status }}</span><span class="small muted">{{ priorities[ticket.priority] || ticket.priority }}</span></div><time class="small muted" :datetime="ticket.updatedAt">更新于 {{ formatTime(ticket.updatedAt) }}</time></article><div class="ticket-help"><p>还没有找到答案？</p><router-link to="/tickets/new">提交工单，让服务团队帮你解决 →</router-link></div></aside>
      </div>
    </template>
  </div>
</template>
<style scoped>
.portal-page{min-width:0}.portal-page .page-heading{margin-bottom:var(--space-5);flex-wrap:wrap}.portal-search-area{background:var(--color-primary-soft);border:1px solid var(--color-primary-border);border-radius:var(--radius-container);padding:var(--space-6);margin-bottom:var(--space-5)}.portal-search-area h2{font-size:var(--text-title);margin-bottom:var(--space-2)}.portal-search-area>p{margin-bottom:var(--space-5)}.search-modes{display:flex;gap:var(--space-5);margin-bottom:var(--space-4)}.search-modes button{border:0;border-bottom:2px solid transparent;background:transparent;color:var(--color-text-secondary);padding:var(--space-2) 0;min-height:var(--control-height)}.search-modes button[aria-pressed=true]{border-color:var(--color-primary);color:var(--color-primary);font-weight:600}.search-modes button[data-mode=assistant][aria-pressed=true]{border-color:var(--color-ai);color:var(--color-ai)}.portal-search-area .portal-search{display:flex;gap:var(--space-3);margin:0;max-width:none;padding:var(--space-2);box-shadow:none;border:1px solid var(--color-control-border);border-radius:var(--radius-control);background:var(--color-surface)}.portal-search input{min-width:0;flex:1;border:0;background:transparent;padding:var(--space-2);color:var(--color-text)}.search-shortcuts{display:flex;gap:var(--space-3);flex-wrap:wrap;margin-top:var(--space-4);font-size:var(--text-sm);color:var(--color-text-secondary)}.search-shortcuts button{border:0;background:transparent;color:var(--color-primary);padding:0;max-width:100%;overflow-wrap:anywhere;text-align:left}.search-shortcuts button:hover{text-decoration:underline}.category-chips{display:flex;gap:var(--space-3);flex-wrap:wrap;margin-bottom:var(--space-6)}.category-chips a{display:inline-flex;gap:var(--space-2);align-items:center;border:1px solid var(--color-border);border-radius:var(--radius-control);background:var(--color-surface);padding:var(--space-2) var(--space-4);overflow-wrap:anywhere;min-width:0;max-width:100%}.category-chips span{color:var(--color-text-secondary)}.portal-columns{display:grid;grid-template-columns:minmax(0,1fr) minmax(260px,320px);gap:var(--space-5);align-items:start}.portal-main{min-width:0}.portal-main>.panel-header{margin-bottom:var(--space-2);flex-wrap:wrap}.document-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:var(--space-4)}.document-card{display:flex;flex-direction:column;min-width:0;padding:var(--space-5);border:1px solid var(--color-border);border-radius:var(--radius-card);background:var(--color-surface)}.document-card>.badge{align-self:flex-start;max-width:100%;white-space:normal;overflow-wrap:anywhere}.document-card h3{font-size:var(--text-section);margin:var(--space-4) 0 var(--space-3);overflow-wrap:anywhere}.document-snippet{display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:2;line-clamp:2;overflow:hidden;overflow-wrap:anywhere;color:var(--color-text-secondary);margin-bottom:var(--space-5)}.document-meta{display:flex;flex-wrap:wrap;gap:var(--space-2) var(--space-4);font-size:var(--text-xs);color:var(--color-text-secondary);margin-top:auto}.portal-tickets{min-width:0;padding:var(--space-5)}.portal-tickets h2{font-size:var(--text-section)}.portal-ticket{border-top:1px solid var(--color-border);padding:var(--space-4) 0}.portal-ticket h3{margin:var(--space-2) 0;overflow-wrap:anywhere}.ticket-meta{display:flex;gap:var(--space-3);align-items:center;margin-bottom:var(--space-2)}.ticket-help{border-top:1px solid var(--color-border);padding-top:var(--space-4);font-size:var(--text-sm)}.ticket-help p{margin-bottom:var(--space-2)}
@media(max-width:1100px){.portal-columns{grid-template-columns:minmax(0,1fr)}.portal-tickets{margin-top:0}}@media(max-width:600px){.portal-search-area{padding:var(--space-4)}.portal-search-area h2{font-size:var(--text-section)}.document-grid{grid-template-columns:minmax(0,1fr)}.document-card{padding:var(--space-4)}.search-modes{gap:var(--space-4)}.portal-search-area .portal-search{flex-wrap:wrap}.portal-search input{flex-basis:100%}.portal-search .el-button{width:100%;margin-left:0}.category-chips a{min-height:var(--control-height-touch)}}
</style>
