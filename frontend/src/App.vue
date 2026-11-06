<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSessionStore } from './stores/session'
import { hasTicketModule } from './access'
import { roles as roleNames } from './types'
import AppNavigation from './components/AppNavigation.vue'
import AppIcon from './components/AppIcon.vue'
import { confirmBeforeLogout } from './composables/unsaved'
const route = useRoute(); const router = useRouter(); const session = useSessionStore()
const mobileNav = ref(false)
const loggingOut = ref(false)
const bare = computed(() => route.path === '/login' || route.path === '/change-password')
const admin = computed(() => session.user?.roles.includes('ADMIN'))
const userRoleNames = computed(() => session.user?.roles.map(role => roleNames[role] || role).join(' · '))
const nav = computed(() => [
  { label: '服务与工单', icon: 'inbox', items: [
    { path: '/portal', label: '服务门户', icon: 'search' },
    ...(hasTicketModule(session.user) ? [{ path: '/tickets', label: '工单工作台', icon: 'inbox' }, { path: '/tickets/new', label: '新建工单', icon: 'plus' }] : []),
  ] },
  { label: '知识协作', icon: 'book', items: [
    { path: '/knowledge', label: '知识库', icon: 'book' },
    ...(session.user?.roles.includes('KB_REVIEWER') ? [{ path: '/knowledge/reviews', label: '审核待办', icon: 'history' }] : []),
    { path: '/assistant', label: '知识助手', icon: 'ai' },
  ] },
  { label: '运营与配置', icon: 'settings', items: admin.value ? [
    { path: '/admin/analytics', label: '运营分析', icon: 'chart' },
    { path: '/admin/queues', label: '队列管理', icon: 'grid' },
    { path: '/admin/services', label: '服务目录', icon: 'grid' },
    { path: '/admin/knowledge-spaces', label: '知识空间', icon: 'book' },
    { path: '/admin/audit', label: '审计记录', icon: 'history' },
  ] : [] },
  { label: '账号与权限', icon: 'users', items: admin.value ? [
    { path: '/admin/users', label: '用户与角色', icon: 'users' },
  ] : [] },
].filter(group => group.items.length))
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
    <div v-if="bare" class="auth-shell"><main id="main-content"><section class="auth-story" aria-label="Ticknova"><div class="auth-brand"><span class="brand-mark" aria-hidden="true"><AppIcon name="check" :size="18" /></span>Ticknova</div><p class="eyebrow">SERVICE · KNOWLEDGE · COLLABORATION</p><h2>让每一个问题，<br />成为下一次的答案。</h2><p class="muted">连接服务请求、团队经验与有据可查的 AI 建议。<br />在一个清晰的工作空间里，让协作持续向前。</p><div class="auth-story-note"><AppIcon name="lock" :size="16" />关键操作，由你确认。</div></section><div class="auth-form-area"><router-view /></div></main></div>
    <div v-else class="workspace">
      <aside class="sidebar" aria-label="工作空间导航">
        <router-link class="brand" to="/"><span class="brand-mark" aria-hidden="true"><AppIcon name="check" :size="18" /></span><span>Ticknova<small>让服务与知识相连</small></span></router-link>
        <AppNavigation :groups="nav" />
        <div class="sidebar-profile"><span class="avatar" aria-hidden="true">{{ session.user?.displayName?.slice(0, 1) || 'U' }}</span><span><strong>{{ session.user?.displayName }}</strong><small>{{ userRoleNames }}</small></span></div>
      </aside>
      <el-drawer v-model="mobileNav" title="工作空间" direction="ltr" size="min(288px, 88vw)" class="navigation-drawer"><router-link class="brand" to="/" @click="mobileNav = false"><span class="brand-mark" aria-hidden="true"><AppIcon name="check" :size="18" /></span><span>Ticknova</span></router-link><AppNavigation :groups="nav" @navigate="mobileNav = false" /></el-drawer>
      <div class="workspace-body">
        <header class="topbar">
          <nav class="topbar-title" aria-label="当前工作区"><button class="icon-button mobile-menu" aria-label="打开导航" :aria-expanded="mobileNav" @click="mobileNav = !mobileNav"><AppIcon name="grid" /></button><span class="breadcrumb-brand">Ticknova</span><span class="breadcrumb-divider" aria-hidden="true">/</span><span aria-current="page">{{ route.meta.title }}</span></nav>
          <el-dropdown trigger="click" @command="(command: string) => command === 'logout' ? logout() : router.push('/change-password')">
            <button class="profile-trigger" aria-label="打开个人菜单"><span class="avatar" aria-hidden="true">{{ session.user?.displayName?.slice(0, 1) || 'U' }}</span><span class="user-label">{{ session.user?.displayName }}<small>{{ userRoleNames }}</small></span><AppIcon name="down" :size="14" /></button>
            <template #dropdown><el-dropdown-menu><el-dropdown-item command="password">修改密码</el-dropdown-item><el-dropdown-item command="logout" :disabled="loggingOut" divided>退出登录</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </header>
        <main id="main-content" class="main-content"><router-view :key="route.path" /></main>
        <footer class="workspace-footer">Ticknova <span>每一个问题，都有迹可循。</span></footer>
      </div>
    </div>
  </el-config-provider>
</template>

<style>
.breadcrumb-brand{font-weight:650;color:#253f5a}.breadcrumb-divider{color:#a1adba}.sidebar{background:#293f59;color:#eef5ff;border-right:0;padding:24px 12px 16px}.sidebar .brand{color:#fff;margin:0 8px 32px}.sidebar .brand small{color:#c4d2e2}.sidebar .brand-mark{background:#eef4fb;color:#365a82}.sidebar-profile{margin-top:auto;display:flex;align-items:center;gap:10px;padding:16px 8px 0;border-top:1px solid #506379;color:#fff}.sidebar-profile strong,.sidebar-profile small{display:block}.sidebar-profile small{color:#c4d2e2;font-size:12px;margin-top:3px}.sidebar-profile .avatar,.profile-trigger .avatar{flex:none}.profile-trigger{display:flex;align-items:center;gap:9px;background:transparent;border:0;cursor:pointer;color:var(--color-text-primary);border-radius:8px;padding:4px}.profile-trigger:focus-visible{outline:2px solid #477db3;outline-offset:3px}.user-label{text-align:left}.workspace-footer{display:none}.workspace-body{background:#f3f1ee}.main-content{padding-top:24px}
@media(max-width:600px){.topbar-title{flex:1;gap:8px;overflow:hidden;white-space:nowrap}.topbar-title [aria-current="page"]{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.profile-trigger .user-label{display:none}.profile-trigger{gap:4px}}
</style>
