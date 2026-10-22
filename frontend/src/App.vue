<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSessionStore } from './stores/session'
import { hasTicketModule } from './access'
import { roles as roleNames } from './types'
import AppNavigation from './components/AppNavigation.vue'
import { confirmBeforeLogout } from './composables/unsaved'
const route = useRoute(); const router = useRouter(); const session = useSessionStore()
const mobileNav = ref(false)
const collapsed = ref(false)
const loggingOut = ref(false)
const bare = computed(() => route.path === '/login' || route.path === '/change-password')
const admin = computed(() => session.user?.roles.includes('ADMIN'))
const userRoleNames = computed(() => session.user?.roles.map(role => roleNames[role] || role).join(' · '))
const nav = computed(() => [
  { path: '/portal', label: '员工服务门户', icon: 'search' },
  ...(hasTicketModule(session.user) ? [{ path: '/tickets', label: '工单工作台', icon: 'inbox' }] : []),
  { path: '/knowledge', label: '知识协同', icon: 'book' },
  ...(session.user?.roles.includes('KB_EDITOR') ? [{ path: '/knowledge/reviews', label: '审核待办', icon: 'history' }] : []),
  { path: '/assistant', label: '知识助手', icon: 'ai' },
  ...(admin.value ? [{ path: '/admin/analytics', label: '运营分析', icon: 'chart' }, { path: '/admin/users', label: '用户与角色', icon: 'users' }, { path: '/admin/queues', label: '队列管理', icon: 'grid' }, { path: '/admin/services', label: '服务目录', icon: 'grid' }, { path: '/admin/audit', label: '审计记录', icon: 'history' }] : []),
])
watch(() => session.token, (token) => { if (!token && route.path !== '/login') void router.replace('/login') })
watch(() => route.path, () => { mobileNav.value = false })
const timer = window.setInterval(() => { if (session.token && session.expiresAt <= Date.now()) session.clear('登录已过期，请重新登录。') }, 1000)
onBeforeUnmount(() => window.clearInterval(timer))
async function logout() {
  if (loggingOut.value) return
  loggingOut.value = true; const token = session.token
  try {
    if (!await confirmBeforeLogout() || token !== session.token) return
    try { await session.logout() } catch { /* Confirmed logout still clears the local session if the server is unreachable. */ }
    if (!session.authenticated) await router.replace('/login')
  } finally { loggingOut.value = false }
}
</script>
<template>
  <el-config-provider>
    <a class="skip-link" href="#main-content">跳到主要内容</a>
    <div v-if="bare" class="auth-shell"><main id="main-content"><router-view /></main></div>
    <div v-else class="workspace" :class="{ 'nav-collapsed': collapsed }">
      <aside class="sidebar" aria-label="工作空间导航">
        <router-link class="brand" to="/"><span class="brand-mark" aria-hidden="true">t</span><span v-if="!collapsed">Ticknova<small>让服务与知识相连</small></span></router-link>
        <div v-if="!collapsed" class="nav-caption">工作空间</div>
        <AppNavigation :items="nav" :collapsed="collapsed" />
        <div class="sidebar-footer"><button class="icon-button" :aria-label="collapsed ? '展开导航' : '收起导航'" :aria-expanded="!collapsed" @click="collapsed = !collapsed">{{ collapsed ? '→' : '←' }}</button><span v-if="!collapsed">清晰记录，高效协作</span></div>
      </aside>
      <el-drawer v-model="mobileNav" title="工作空间" direction="ltr" size="min(288px, 88vw)" class="navigation-drawer"><router-link class="brand" to="/" @click="mobileNav = false"><span class="brand-mark" aria-hidden="true">t</span><span>Ticknova</span></router-link><AppNavigation :items="nav" @navigate="mobileNav = false" /></el-drawer>
      <div class="workspace-body">
        <header class="topbar">
          <div class="topbar-title"><button class="icon-button mobile-menu" aria-label="打开导航" :aria-expanded="mobileNav" @click="mobileNav = !mobileNav">☰</button><router-link to="/portal" class="breadcrumb-home">服务空间</router-link><span aria-hidden="true">/</span><span>{{ route.meta.title }}</span></div>
          <div class="user-menu"><span class="avatar" aria-hidden="true">{{ session.user?.displayName?.slice(0, 1) || 'U' }}</span><span class="user-label">{{ session.user?.displayName }}<small>{{ userRoleNames }}</small></span><el-button text :loading="loggingOut" @click="logout">退出</el-button></div>
        </header>
        <main id="main-content" class="main-content"><router-view :key="route.path" /></main>
        <footer class="workspace-footer">Ticknova <span>每一个问题，都有迹可循。</span></footer>
      </div>
    </div>
  </el-config-provider>
</template>
