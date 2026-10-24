<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api, ApiError, queryString } from '../api'
import { useSessionStore } from '../stores/session'
import { hasTicketModule } from '../access'
import { statuses, priorities, formatTime } from '../types'
import type { WorkspacePortal } from '../workspace-types'
import ErrorNotice from '../components/ErrorNotice.vue'
const session = useSessionStore(); const route = useRoute(); const router = useRouter()
const query = ref(String(route.query.q || '')); const result = shallowRef<WorkspacePortal | null>(null)
const pending = ref(false); const error = shallowRef<ApiError | null>(null); let generation = 0
const ticketsAllowed = computed(() => hasTicketModule(session.user))
const popularTitle = computed(() => result.value?.popularDocuments.some(item => item.viewCount > 0) ? '热门帮助文档' : '最近发布的知识')
async function search() {
  const current = ++generation; const token = session.token; pending.value = true; error.value = null
  try {
    const next = await api.get<WorkspacePortal>(`/workspace/portal?${queryString({ q: query.value.trim() })}`)
    if (current !== generation || token !== session.token) return
    if (!Array.isArray(next.knowledge) || !Array.isArray(next.tickets)) throw new Error('Invalid response')
    result.value = next
    await router.replace({ query: query.value.trim() ? { q: query.value.trim() } : {} })
  } catch (failure) {
    if (current !== generation) return
    error.value = failure instanceof ApiError ? failure : new ApiError(0, { title: '无法加载服务门户，请重试。', status: 0 })
    result.value = null
  } finally { if (current === generation) pending.value = false }
}
watch(() => session.token, () => { generation++; result.value = null; query.value = ''; pending.value = false; error.value = null }, { flush: 'sync' })
onMounted(search); onBeforeUnmount(() => { generation++ })
</script>
<template>
  <div class="portal-intro"><h1>工作中的问题，从这里开始。</h1><p class="muted">查找帮助、向知识助手提问，或让服务团队为你跟进。</p>
    <form id="portal-search-form" class="portal-search" role="search" @submit.prevent="search"><label for="portal-search" class="sr-only">搜索问题、知识和工单</label><svg aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"><circle cx="10" cy="10" r="7"/><path d="m15 15 6 6"/></svg><input id="portal-search" v-model="query" type="search" maxlength="256" placeholder="例如：VPN 无法连接、密码重置…" /><el-button type="primary" native-type="submit" :loading="pending">搜索</el-button></form>
    <div class="portal-actions"><router-link :to="{ path: '/assistant', query: query ? { q: query } : {} }" class="ai-entry">向知识助手提问 <span aria-hidden="true">↗</span></router-link><router-link v-if="ticketsAllowed" to="/tickets/new">找不到答案？创建工单 →</router-link></div>
  </div>
  <ErrorNotice :error="error" reload @reload="search" />
  <div v-if="pending" class="loading-panel" role="status" aria-label="正在搜索服务内容"><div v-for="n in 3" :key="n" class="loading-line" aria-hidden="true"></div>正在查找你有权访问的内容…</div>
  <template v-if="result && !pending">
    <section v-if="result.query" class="panel portal-results" aria-labelledby="portal-results-title"><h2 id="portal-results-title">搜索结果</h2><p class="small muted">仅显示当前账号可访问的已发布知识与工单。</p><article v-for="item in result.knowledge" :key="item.id" class="document-result"><router-link :to="{ path: '/knowledge', query: { documentId: item.id } }">{{ item.title }}</router-link><p>{{ item.snippet }}</p><span class="small muted">知识文档</span></article><p v-if="!result.knowledge.length && !result.tickets.length" class="empty-state">没有找到相关内容。可以调整关键词，或联系服务团队。</p></section>
    <div class="portal-content"><section class="portal-main"><div class="panel-header"><h2>{{ popularTitle }}</h2><router-link to="/knowledge">浏览知识库 →</router-link></div><div v-if="!result.popularDocuments.length" class="empty-state">还没有你可阅读的已发布文档。</div><article v-for="item in result.popularDocuments" :key="item.id" class="document-result"><router-link :to="{ path: '/knowledge', query: { documentId: item.id } }">{{ item.title }}</router-link><p>{{ item.snippet }}</p><span class="small muted">{{ item.viewCount > 0 ? `${item.viewCount} 次阅读` : '已发布知识' }}</span></article></section>
    <aside class="portal-categories"><h2>常见问题分类</h2><p v-if="!result.categories.length" class="muted">当前可读空间尚未设置分类。</p><ul class="category-links"><li v-for="category in result.categories" :key="category.id"><router-link :to="{ path: '/knowledge', query: { spaceId: category.spaceId, categoryId: category.id } }"><span>{{ category.name }}</span><small>{{ category.spaceName }}</small></router-link></li></ul></aside></div>
    <section v-if="ticketsAllowed" class="panel portal-tickets"><div class="panel-header"><h2>{{ result.query ? '相关工单' : '我的工单与进展' }}</h2><router-link to="/tickets">查看全部 →</router-link></div><p v-if="!result.tickets.length" class="empty-state">{{ result.query ? '没有找到相关工单。' : '还没有待跟进的工单，可以先搜索帮助内容。' }}</p><div v-for="ticket in result.tickets" :key="ticket.id" class="portal-ticket-row"><div><span class="ticket-number">{{ ticket.ticketNo }}</span><router-link :to="`/tickets/${ticket.id}`">{{ ticket.title }}</router-link></div><div class="inline-actions"><span class="badge">{{ statuses[ticket.status] || ticket.status }}</span><span class="small muted">{{ priorities[ticket.priority] || ticket.priority }}</span><time :datetime="ticket.updatedAt">{{ formatTime(ticket.updatedAt) }}</time></div></div></section>
  </template>
</template>
<style scoped>
.portal-main .document-result p {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  overflow: hidden;
}
</style>
