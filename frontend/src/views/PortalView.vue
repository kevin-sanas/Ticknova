<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiError, queryString } from '../api'
import { useSessionStore } from '../stores/session'
import { hasTicketModule } from '../access'
import { statuses, priorities, formatTime } from '../types'
import type { WorkspacePortal, WorkspaceTodo } from '../workspace-types'
import ErrorNotice from '../components/ErrorNotice.vue'
import AppIcon from '../components/AppIcon.vue'
const session = useSessionStore(), route = useRoute(), router = useRouter()
const routeQuery = computed(() => typeof route.query.q === 'string' ? route.query.q : '')
const query = ref(routeQuery.value), mode = ref<'knowledge' | 'assistant'>('knowledge')
const result = shallowRef<WorkspacePortal | null>(null), error = shallowRef<ApiError | null>(null), pending = ref(false)
let generation = 0, controller: AbortController | undefined
const ticketsAllowed = computed(() => hasTicketModule(session.user))
const todosOpen = ref(false), todosPending = ref(false), todosError = ref(false)
const todos = shallowRef<WorkspaceTodo[]>([])
const todoTrigger = ref<HTMLButtonElement | null>(null), todoClose = ref<HTMLButtonElement | null>(null)
let todoGeneration = 0, todoController: AbortController | undefined
async function loadTodos() {
  const current = ++todoGeneration, token = session.token
  todoController?.abort(); todoController = new AbortController()
  todos.value = []; todosError.value = false; todosPending.value = true
  try {
    const next = await api.get<WorkspaceTodo[]>('/workspace/todos', { signal: todoController.signal })
    if (current !== todoGeneration || token !== session.token) return
    if (!Array.isArray(next)) throw new Error('Invalid response')
    todos.value = next
  } catch {
    if (current === todoGeneration && token === session.token) todosError.value = true
  } finally { if (current === todoGeneration) todosPending.value = false }
}
async function openTodos() {
  if (todosOpen.value) { closeTodos(); return }
  todosOpen.value = true
  void loadTodos()
  await nextTick(); todoClose.value?.focus()
}
function closeTodos() {
  todosOpen.value = false; todoGeneration++; todoController?.abort()
  todos.value = []; todosPending.value = false; todosError.value = false
  todoTrigger.value?.focus()
}
const ticketHeading = computed(() => session.user?.roles.includes('AGENT') ? '可访问工单' : '我的工单')
const documents = computed(() => result.value ? result.value.query ? result.value.knowledge : result.value.popularDocuments : [])
const ticketPreview = computed(() => result.value?.tickets.slice(0, 3) || [])
const shortcuts = computed(() => [...new Set(result.value?.popularDocuments.map(item => item.title) || [])].slice(0, 3))
const categoryIcon = (name: string) => /网络|连接|wifi|wi-fi|vpn/i.test(name) ? 'wifi' : /账号|权限|账户/i.test(name) ? 'users' : /软件|办公|应用/i.test(name) ? 'grid' : 'folder'
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
  todoGeneration++; todoController?.abort(); todos.value = []; todosOpen.value = false; todosPending.value = false; todosError.value = false
}, { flush: 'sync' })
onBeforeUnmount(() => { generation++; controller?.abort(); todoGeneration++; todoController?.abort() })
</script>
<template>
  <div class="portal-page">
    <header class="portal-heading"><div><h1>有什么可以帮你？</h1><p>查找知识、向助手提问，或提交工单获取支持。</p></div>
      <div class="portal-pet" @keydown.esc.stop="closeTodos">
        <button ref="todoTrigger" type="button" class="portal-pet-trigger" aria-label="打开待处理工单" aria-controls="portal-todo-panel" :aria-expanded="todosOpen" @click="openTodos"><img :src="'/knowledge-assistant-mascot.png'" alt="" /><span>待处理</span></button>
        <section v-if="todosOpen" id="portal-todo-panel" data-testid="todo-panel" class="portal-todo-panel" aria-label="待处理工单">
          <div class="todo-heading"><div><h2>待处理工单</h2><p>按当前账号和工单状态显示</p></div><button ref="todoClose" type="button" class="todo-close" aria-label="关闭待处理工单" @click="closeTodos">关闭</button></div>
          <p v-if="todosPending" class="todo-state" role="status">正在加载待处理工单…</p>
          <div v-else-if="todosError" class="todo-state" role="alert"><p>无法加载待处理工单，请重试。</p><button type="button" class="todo-retry" @click="loadTodos">重试</button></div>
          <p v-else-if="!todos.length" class="todo-state">暂无待处理工单</p>
          <ul v-else class="todo-list"><li v-for="todo in todos" :key="todo.id"><router-link :to="`/tickets/${todo.id}`"><span class="todo-title">{{ todo.title }}</span><span class="todo-meta">#{{ todo.ticketNo }} · {{ todo.reason === 'WAITING_CUSTOMER' ? '等待申请人' : `分配给我 · ${statuses[todo.status] || todo.status}` }}</span></router-link></li></ul>
          <p class="todo-caption">显示最近更新的 20 条，不代表今日到期。</p>
        </section>
      </div>
    </header>
    <div :class="['portal-columns', { 'portal-columns--full': !ticketsAllowed }]">
      <div class="portal-main">
        <section class="portal-search-area" aria-labelledby="portal-search-heading">
          <h2 id="portal-search-heading" class="sr-only">查找帮助</h2>
          <div class="search-modes" role="group" aria-label="选择搜索方式"><button type="button" data-mode="knowledge" :aria-pressed="mode === 'knowledge'" @click="mode = 'knowledge'"><AppIcon name="search" :size="16" />搜索知识</button><button type="button" data-mode="assistant" :aria-pressed="mode === 'assistant'" @click="mode = 'assistant'"><AppIcon name="ai" :size="16" />问知识助手</button></div>
          <form id="portal-search-form" class="portal-search" role="search" @submit.prevent="search"><label for="portal-search" class="sr-only">{{ mode === 'assistant' ? '向知识助手提问' : '搜索知识' }}</label><input id="portal-search" v-model="query" type="search" maxlength="256" :placeholder="mode === 'assistant' ? '输入你的问题，助手将查找可用知识…' : '搜索知识文章、操作指南或常见问题…'" /><el-button type="primary" native-type="submit" :loading="pending && mode === 'knowledge'">{{ mode === 'assistant' ? '询问' : '搜索' }}</el-button></form>
          <div v-if="shortcuts.length" class="search-shortcuts"><span>常见问题</span><button v-for="word in shortcuts" :key="word" type="button" @click="searchShortcut(word)">{{ word }}</button></div>
        </section>
        <ErrorNotice :error="error" reload @reload="load" />
        <div v-if="pending" class="loading-panel" role="status" aria-label="正在加载服务门户">正在查找你有权访问的内容…</div>
        <template v-if="result && !pending">
          <section class="portal-categories" aria-labelledby="portal-categories-heading"><div class="section-heading"><h2 id="portal-categories-heading">按分类查找帮助</h2><router-link to="/knowledge">查看知识库 <AppIcon name="chevron" :size="14" /></router-link></div>
            <nav v-if="result.categories.length" class="category-grid" aria-label="知识分类"><router-link v-for="category in result.categories.slice(0, 4)" :key="category.id" class="category-card" :to="{ path: '/knowledge', query: { spaceId: category.spaceId, categoryId: category.id } }" :title="category.spaceName"><span class="category-icon"><img v-if="categoryIcon(category.name) === 'wifi'" :src="'/wifi.svg'" alt="" /><AppIcon v-else :name="categoryIcon(category.name)" :size="21" /></span><strong>{{ category.name }}</strong><small>{{ category.spaceName }} · {{ category.documentCount }} 篇</small><AppIcon class="category-chevron" name="chevron" :size="14" /></router-link></nav>
            <p v-else class="category-empty">暂无可访问的知识分类。</p>
          </section>
          <section class="portal-documents" aria-labelledby="portal-documents-heading"><div class="section-heading"><h2 id="portal-documents-heading">{{ result.query ? '搜索结果' : '实用知识推荐' }}</h2><router-link :to="{path: '/knowledge', query: result.query ? {q: result.query} : {}}">查看全部 <AppIcon name="chevron" :size="14" /></router-link></div>
            <p v-if="!documents.length" class="document-empty">{{ result.query ? '没有找到相关知识，可尝试调整关键词。' : '还没有你可阅读的已发布文档。' }}</p>
            <div v-else class="document-list"><article v-for="item in documents.slice(0, 3)" :key="item.id" class="document-card"><span class="document-icon"><AppIcon name="file" :size="20" /></span><div class="document-content"><h3><router-link :to="`/knowledge/documents/${item.id}`">{{ item.title }}</router-link></h3><p class="document-snippet">{{ item.snippet || '打开文档查看详细内容。' }}</p><div class="document-details"><span>{{ categoryName(item.categoryId) }}</span><span>{{ item.viewCount }} 次阅读</span></div></div><time v-if="item.publishedAt" :datetime="item.publishedAt">发布于 {{ formatTime(item.publishedAt) }}</time></article></div>
          </section>
        </template>
      </div>
      <aside v-if="ticketsAllowed && result && !pending" class="portal-tickets" aria-labelledby="portal-tickets-heading"><div class="panel-header"><h2 id="portal-tickets-heading">{{ ticketHeading }}</h2><router-link :to="{ path: '/tickets', query: result.query ? { keyword: result.query } : {} }">查看全部 <AppIcon name="chevron" :size="14" /></router-link></div><router-link class="ticket-create" to="/tickets/new"><AppIcon name="plus" :size="17" />提交新工单</router-link><p v-if="!result.tickets.length" class="ticket-empty">{{ result.query ? '没有找到相关工单。' : '暂无可查看的工单。' }}</p><article v-for="ticket in ticketPreview" :key="ticket.id" class="portal-ticket"><div class="ticket-top"><span class="ticket-number">#{{ ticket.ticketNo }}</span><span :class="['badge', 'status', ticket.status.toLowerCase()]">{{ statuses[ticket.status] || ticket.status }}</span></div><h3><router-link :to="`/tickets/${ticket.id}`">{{ ticket.title }}</router-link></h3><div class="ticket-meta"><time :datetime="ticket.updatedAt">最近更新：{{ formatTime(ticket.updatedAt) }}</time><span>{{ priorities[ticket.priority] || ticket.priority }}</span></div></article></aside>
    </div>
  </div>
</template>
<style scoped>
.portal-heading{position:relative}.portal-heading-actions{display:flex;align-items:center;gap:16px}.portal-pet{position:relative;flex:none}.portal-pet-trigger{display:flex;flex-direction:column;align-items:center;justify-content:center;gap:0;border:0;border-radius:10px;background:transparent;color:#315b83;font-size:12px;cursor:pointer;padding:4px 8px}.portal-pet-trigger img{width:62px;height:62px;object-fit:contain;animation:pet-float 4s ease-in-out infinite}.portal-pet-trigger:hover{background:#edf3f8}.portal-pet-trigger:focus-visible,.todo-close:focus-visible,.todo-list a:focus-visible,.todo-retry:focus-visible{outline:2px solid #315b83;outline-offset:2px}.portal-todo-panel{position:absolute;right:0;top:calc(100% + 8px);z-index:20;width:min(320px,calc(100vw - 32px));max-height:min(500px,70vh);overflow:auto;padding:16px;border:1px solid #d6dfe8;border-radius:12px;background:#fff;box-shadow:0 12px 30px rgba(28,50,76,.14)}.todo-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}.todo-heading h2{font-size:16px;line-height:1.4;margin:0}.todo-heading p,.todo-caption{font-size:12px;color:#5e6f80;margin:4px 0 0}.todo-close,.todo-retry{border:0;background:transparent;color:#315b83;font-size:13px;cursor:pointer;padding:3px 5px}.todo-list{list-style:none;margin:12px 0;padding:0;display:grid;gap:6px}.todo-list a{display:flex;flex-direction:column;gap:4px;padding:10px;border:1px solid #dbe5ee;border-radius:8px;background:#f4f8fb;color:#243d57;text-decoration:none;overflow-wrap:anywhere}.todo-list a:hover{background:#e9f1f8}.todo-title{font-size:14px;font-weight:600}.todo-meta{font-size:12px;color:#5d7185}.todo-state{padding:16px 0;font-size:13px;color:#586a7c}.todo-state p{margin:0}.todo-caption{border-top:1px solid #e8edf2;padding-top:10px}@keyframes pet-float{0%,100%{transform:translateY(0)}50%{transform:translateY(-3px)}}@media(prefers-reduced-motion:reduce){.portal-pet-trigger img{animation:none}}@media(max-width:650px){.portal-heading{flex-wrap:wrap;gap:14px}.portal-heading-actions{width:100%;justify-content:space-between}.portal-todo-panel{right:0}}

.portal-page{min-width:0;width:100%;max-width:1500px;margin:0 auto;color:#1d3045}.portal-heading{display:flex;align-items:center;justify-content:space-between;min-height:78px;margin-bottom:18px}.portal-heading h1{font-size:29px;line-height:1.25;margin:0 0 7px;color:#172a3e}.portal-heading p{font-size:14px;color:#647386;margin:0}.portal-columns{display:grid;grid-template-columns:minmax(0,1fr) 305px;gap:16px;align-items:start}.portal-main{min-width:0}.portal-search-area,.portal-tickets,.document-list,.document-empty,.category-empty{background:#fff;border:1px solid #dce3e9;border-radius:10px}.portal-search-area{padding:18px;margin-bottom:24px}.search-modes{display:inline-flex;border:1px solid #dce3ea;border-radius:7px;overflow:hidden;margin-bottom:14px}.search-modes button{display:flex;align-items:center;justify-content:center;gap:7px;min-height:36px;min-width:132px;border:0;background:#f7f9fb;color:#536a81;padding:0 14px;font-size:13px;cursor:pointer}.search-modes button[aria-pressed=true]{background:#365f89;color:#fff}.portal-search{display:flex;gap:9px}.portal-search input{min-width:0;flex:1;height:40px;border:1px solid #c9d4df;border-radius:7px;padding:0 12px;font-size:13px}.portal-search :deep(.el-button){height:40px;min-width:76px}.search-shortcuts{display:flex;gap:6px;align-items:center;overflow:hidden;white-space:nowrap;margin-top:10px;color:#6b7c8c;font-size:11px}.search-shortcuts button{max-width:150px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;border:0;background:#f1f5f8;color:#526e88;border-radius:5px;padding:4px 7px;cursor:pointer}.portal-categories{margin-bottom:23px}.section-heading{display:flex;align-items:center;justify-content:space-between;gap:12px;margin:0 0 12px}.section-heading h2,.portal-tickets h2{font-size:17px;line-height:1.3;margin:0;color:#1d3045}.section-heading a,.portal-tickets .panel-header a{display:inline-flex;align-items:center;gap:2px;font-size:12px;font-weight:600;color:#315f8b;text-decoration:none;white-space:nowrap}.category-grid{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:12px}.category-card{position:relative;display:flex;flex-direction:column;align-items:flex-start;min-width:0;min-height:108px;padding:13px 15px;border:1px solid #dce3e9;border-radius:9px;background:#fff;color:#1d3045;text-decoration:none}.category-card:hover{border-color:#91acc5;background:#f9fbfd;text-decoration:none}.category-icon,.document-icon{display:grid;place-items:center;background:#eaf1f7;color:#315f8b;border-radius:7px;width:32px;height:32px;flex:none}.category-icon img{width:21px;height:21px}.category-card strong{font-size:14px;line-height:1.35;margin-top:8px;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.category-card small{max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#708194;font-size:11px;margin-top:3px}.category-chevron{position:absolute;right:10px;top:50%;color:#7890a7}.category-empty,.document-empty,.ticket-empty{padding:18px;color:#718092;font-size:13px}.document-list{padding:0 15px}.document-card{display:flex;align-items:center;gap:12px;min-width:0;padding:11px 0}.document-card+.document-card{border-top:1px solid #e7edf2}.document-content{flex:1;min-width:0}.document-card h3{font-size:13px;margin:0 0 3px}.document-card h3 a{color:#203a54;text-decoration:none}.document-card h3 a:hover{color:#315f8b}.document-snippet{overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#68798b;font-size:11px;margin:0}.document-details{display:flex;gap:8px;color:#8190a0;font-size:10px;margin-top:3px}.document-card time{font-size:11px;color:#718092;white-space:nowrap}.portal-tickets{padding:17px}.portal-tickets .panel-header{display:flex;align-items:center;justify-content:space-between;gap:8px;margin-bottom:16px}.ticket-create{display:flex;align-items:center;justify-content:center;gap:6px;min-height:39px;background:#365f89;color:#fff;border-radius:7px;font-size:13px;font-weight:600;text-decoration:none}.ticket-create:hover{background:#2c547d;color:#fff;text-decoration:none}.portal-ticket{padding:15px 0;border-bottom:1px solid #e7edf2}.portal-ticket:last-child{padding-bottom:0;border-bottom:0}.ticket-top{display:flex;align-items:center;justify-content:space-between;gap:8px}.ticket-number{background:#edf3f8;color:#41698f;border-radius:4px;padding:3px 6px;font-size:11px;font-weight:600}.portal-ticket h3{font-size:13px;margin:8px 0}.portal-ticket h3 a{color:#1f364d;text-decoration:none;overflow-wrap:anywhere}.portal-ticket h3 a:hover{color:#315f8b}.ticket-meta{display:flex;justify-content:space-between;gap:8px;color:#758597;font-size:11px}.ticket-meta time{white-space:nowrap}@media(max-width:1000px){.portal-columns{grid-template-columns:minmax(0,1fr) 280px}.category-grid{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:700px){.portal-columns{grid-template-columns:minmax(0,1fr)}.portal-tickets{order:2}.portal-heading h1{font-size:25px}.document-card time{display:none}}@media(max-width:430px){.category-grid{gap:8px}.category-card{padding:12px;min-height:100px}.search-modes button{min-width:112px}.portal-search-area{padding:14px}}.portal-columns--full{grid-template-columns:minmax(0,1fr)}
</style>
