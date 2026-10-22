import { createRouter, createWebHistory } from 'vue-router'
import { useSessionStore } from './stores/session'
import { destinationFor, mayVisit } from './access'
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('./views/LoginView.vue'), meta: { title: '登录' } },
    { path: '/change-password', component: () => import('./views/ChangePasswordView.vue'), meta: { title: '修改密码' } },
    { path: '/portal', component: () => import('./views/PortalView.vue'), meta: { title: '员工服务门户' } },
    { path: '/admin/analytics', component: () => import('./views/AnalyticsView.vue'), meta: { title: '运营分析' } },
    { path: '/knowledge/articles/:id/edit', component: () => import('./views/ArticleEditorView.vue'), meta: { title: '编辑知识文章' } },
    { path: '/tickets', component: () => import('./views/TicketsView.vue'), meta: { title: '工单工作台' } },
    { path: '/tickets/new', component: () => import('./views/NewTicketView.vue'), meta: { title: '新建工单' } },
    { path: '/tickets/:id', component: () => import('./views/TicketDetailView.vue'), meta: { title: '工单详情' } },
    { path: '/knowledge', component: () => import('./views/KnowledgeView.vue'), meta: { title: '知识管理' } },
    { path: '/knowledge/reviews', component: () => import('./views/KnowledgeReviewView.vue'), meta: { title: '审核待办' } },
    { path: '/assistant', component: () => import('./views/AssistantView.vue'), meta: { title: '知识助手' } },
    { path: '/admin/users', component: () => import('./views/UsersView.vue'), meta: { title: '用户与角色' } },
    { path: '/admin/queues', component: () => import('./views/CatalogView.vue'), props: { kind: 'queues' }, meta: { title: '队列管理' } },
    { path: '/admin/services', component: () => import('./views/CatalogView.vue'), props: { kind: 'services' }, meta: { title: '服务目录' } },
    { path: '/admin/audit', component: () => import('./views/AuditView.vue'), meta: { title: '审计记录' } },
    { path: '/no-module', component: () => import('./views/StateView.vue'), props: { kind: 'module' }, meta: { title: '账号工作区' } },
    { path: '/forbidden', component: () => import('./views/StateView.vue'), props: { kind: 'forbidden' }, meta: { title: '无访问权限' } },
    { path: '/', redirect: () => { const session = useSessionStore(); return session.authenticated && session.user ? destinationFor(session.user) : '/login' } },
    { path: '/:pathMatch(.*)*', component: () => import('./views/StateView.vue'), props: { kind: 'missing' }, meta: { title: '页面不存在' } },
  ],
  scrollBehavior: (_to, _from, savedPosition) => savedPosition || ({ top: 0 }),
})
router.beforeEach((to) => {
  const session = useSessionStore()
  if (!session.authenticated) { if (session.token) session.clear('登录已过期，请重新登录。'); return to.path === '/login' ? true : '/login' }
  if (!session.user) return '/login'
  if (to.path === '/login') return destinationFor(session.user)
  if (session.user.mustChangePassword && to.path !== '/change-password') return '/change-password'
  if (!mayVisit(session.user, to.path)) return '/forbidden'
  return true
})
router.afterEach((to) => { document.title = `${to.meta.title || '工作台'} · Ticknova` })
