import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus, { ElMessage } from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Component } from 'vue'
import { bindApiSession } from '../src/api'
import { mayVisit, destinationFor } from '../src/access'
import { useSessionStore } from '../src/stores/session'
import DocumentActionBar from '../src/components/DocumentActionBar.vue'

const space = { id: 's1', name: '运维空间', active: true, canRead: true, canEdit: true, canReview: false, canManageMembers: true }
const version = { id: 'v2', versionNo: 2, fileName: 'guide.md', ingestState: 'READY', reviewState: 'PENDING', chunkCount: 1, attemptCount: 1, createdAt: '2026-10-24T01:00:00Z', allowedActions: ['APPROVE', 'REJECT'], canReview: false }
const detail = { id: 'd1', spaceId: 's1', title: '独立文档', versionCount: 2, canEdit: false, publishedVersionId: 'v1', latestVersionId: 'v2', versions: [version], allowedActions: [] }
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
let view: VueWrapper | undefined
Object.defineProperty(Element.prototype, 'scrollIntoView', { configurable: true, value: () => {} })
// Production router scrollBehavior runs here; jsdom provides no viewport scrolling.
beforeEach(() => { vi.stubGlobal('scrollTo', vi.fn()) })
afterEach(() => { view?.unmount(); view = undefined; ElMessage.closeAll(); vi.restoreAllMocks(); vi.unstubAllGlobals(); vi.useRealTimers(); document.body.innerHTML = '' })
async function render(name: string, path: string, extra?: (url: string, init: RequestInit) => Response | Promise<Response> | undefined, roles = ['KB_REVIEWER']) {
  const modules = import.meta.glob('../src/views/Knowledge*.vue')
  expect(modules[`../src/views/${name}.vue`], `${name} must exist`).toBeDefined()
  const component = (await modules[`../src/views/${name}.vue`]!() as { default: Component }).default
  const pinia = createPinia(); const session = useSessionStore(pinia)
  session.accept({ accessToken: 'routes-token', expiresIn: 1800, user: { id: 'u1', username: 'reviewer', displayName: '审核者', roles, mustChangePassword: false } })
  bindApiSession({ getToken: () => session.token, onUnauthorized: () => session.clear() })
  const requests: { url: string; init: RequestInit }[] = []
  vi.stubGlobal('fetch', (url: string, init: RequestInit) => {
    requests.push({ url, init }); const result = extra?.(url, init); if (result) return Promise.resolve(result)
    return Promise.resolve(json(url.endsWith('/knowledge/spaces') ? [space] : url.endsWith('/documents/d1') ? detail : url.endsWith('/content') ? null : url.endsWith('/chunks') ? [{ id: 'c2', chunkIndex: 0, content: '准确版本正文' }] : url.includes('/documents?') ? { items: [detail], total: 1 } : []))
  })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/knowledge', component: name === 'KnowledgeView' ? component : { template: '<p>知识列表</p>' } }, { path: '/knowledge/documents/:documentId', component: name === 'KnowledgeDocumentView' ? component : { template: '<p>详情页面</p>' } }, { path: '/knowledge/reviews', component: { template: '<p>审核待办</p>' } }, { path: '/admin/knowledge-spaces', component: name === 'KnowledgeSpaceAdminView' ? component : { template: '<p>空间管理</p>' } }] })
  await router.push(path); await router.isReady(); view = mount({ template: '<router-view />' }, { attachTo: document.body, global: { plugins: [pinia, ElementPlus, router] } }); await flushPromises()
  return { view, router, session, requests }
}
async function click(wrapper: VueWrapper, label: string) { const button = wrapper.findAll('button, [role="tab"]').find(item => item.text() === label); expect(button, label).toBeDefined(); await button!.trigger('click'); await flushPromises() }

it('keeps archive in an accessible more menu and emits the server action', async () => {
  const wrapper = mount(DocumentActionBar, {
    attachTo: document.body,
    props: { actions: ['EDIT_DRAFT', 'ARCHIVE'] },
    global: { plugins: [ElementPlus] }
  })
  expect(wrapper.findAll('button').some(button => button.text() === '归档文档')).toBe(false)
  const more = wrapper.get('button[aria-label="更多文档操作"]')
  expect(more.text()).toContain('更多'); expect(more.element.tagName).toBe('BUTTON'); expect(more.attributes('aria-haspopup')).toBe('menu')
  more.element.dispatchEvent(new MouseEvent('click', { bubbles: true, button: 0 })); await flushPromises()
  await vi.waitFor(() => expect(more.attributes('aria-expanded')).toBe('true'))
  const archive = [...document.querySelectorAll<HTMLElement>('.el-dropdown-menu__item')].find(item => item.textContent?.trim() === '归档文档')
  expect(archive).toBeDefined(); expect(archive!.getAttribute('role')).toBe('menuitem'); archive!.click(); await flushPromises()
  expect(wrapper.emitted('action')).toEqual([['ARCHIVE']])
  wrapper.unmount()
})

it('restricts the review route to the dedicated reviewer role', () => {
  const user = { id: 'u1', username: 'u', displayName: 'U', mustChangePassword: false, roles: ['KB_EDITOR'] }
  expect(mayVisit(user, '/knowledge/reviews')).toBe(false)
  expect(mayVisit({ ...user, roles: ['KB_REVIEWER'] }, '/knowledge/reviews')).toBe(true)
  expect(destinationFor({ ...user, roles: ['KB_REVIEWER'] })).toBe('/knowledge/reviews')
})
it('keeps document details and member forms off the list and navigates to the independent page', async () => {
  const { view, router, requests } = await render('KnowledgeView', '/knowledge', undefined, ['KB_EDITOR', 'ADMIN'])
  expect(view.find('.knowledge-detail').exists()).toBe(false); expect(view.find('#knowledge-member-form').exists()).toBe(false)
  expect(view.find('#knowledge-file').exists()).toBe(false)
  expect(view.text()).not.toContain('审核待办')
  await click(view, '独立文档'); expect(router.currentRoute.value.path).toBe('/knowledge/documents/d1')
  expect(requests.some(row => row.url.endsWith('/documents/d1'))).toBe(false)
})
it('renders document navigation as route tabs with a real close destination', async () => {
  const { view } = await render('KnowledgeDocumentView', '/knowledge/documents/d1')
  const navigation = view.get('nav[aria-label="知识文档页签"]')
  expect(navigation.get('a.document-route-tab').attributes('href')).toBe('/knowledge?spaceId=s1')
  expect(navigation.get('[aria-current="page"] .document-route-tab-label').text()).toBe('独立文档')
  expect(navigation.get('a[aria-label="关闭文档详情并返回知识协同"]').attributes('href')).toBe('/knowledge?spaceId=s1')
  expect(navigation.text()).not.toContain('/')
})
it('loads the exact citation, uses URL tabs, and never substitutes a missing source', async () => {
  const { view, router } = await render('KnowledgeDocumentView', '/knowledge/documents/d1?versionId=v2&chunkId=c2&tab=parse')
  expect(view.text()).toContain('准确版本正文'); expect(view.find('#knowledge-source-c2').exists()).toBe(true)
  await click(view, '版本历史'); expect(router.currentRoute.value.query).toMatchObject({ tab: 'versions', versionId: 'v2', chunkId: 'c2' })
  await router.push('/knowledge/documents/d1?versionId=missing&chunkId=c2'); await flushPromises()
  expect(view.text()).toContain('引用已不可用'); expect(view.text()).not.toContain('准确版本正文')
})
it('consumes only allowedActions even when deprecated capability fields disagree', async () => {
  const { view, requests, router } = await render('KnowledgeDocumentView', '/knowledge/documents/d1?versionId=v2&tab=versions')
  expect(view.text()).toContain('审核通过'); expect(view.text()).not.toContain('发布此版本'); expect(view.text()).not.toContain('编辑文章草稿')
  await click(view, '审核通过'); expect(requests.some(row => row.url.endsWith('/v2/reviews') && row.init.body === '{"decision":"APPROVED"}')).toBe(true)
  expect(router.currentRoute.value.path).toBe('/knowledge/reviews'); expect(view.find('.knowledge-detail').exists()).toBe(false)
  expect(requests.filter(row => row.url.endsWith('/documents/d1'))).toHaveLength(1)
})
it('grants no actions when the server omits allowedActions during migration', async () => {
  const { view } = await render('KnowledgeDocumentView', '/knowledge/documents/d1?tab=versions', url => url.endsWith('/documents/d1') ? json({ ...detail, canEdit: true, versions: [{ ...version, allowedActions: undefined, canReview: true, canPublish: true }] }) : undefined)
  expect(view.text()).not.toContain('审核通过'); expect(view.text()).not.toContain('发布此版本'); expect(view.text()).not.toContain('归档文档')
})
it.each([401, 403, 404])('purges private source and stops actions after %s on refresh', async status => {
  let denied = false
  const { view } = await render('KnowledgeDocumentView', '/knowledge/documents/d1?versionId=v2', url => denied && url.endsWith('/documents/d1') ? json({ title: '访问已撤销', status }, status) : undefined)
  expect(view.text()).toContain('准确版本正文'); denied = true; await click(view, '刷新状态')
  expect(view.text()).not.toContain('准确版本正文'); expect(view.text()).not.toContain('独立文档'); expect(view.text()).not.toContain('审核通过')
})
it('opens members in an Element Plus dialog and preserves rejected ACL input', async () => {
  const { view, requests } = await render('KnowledgeSpaceAdminView', '/admin/knowledge-spaces', (url, init) => url.endsWith('/member-candidates') ? json([{ id: 'u2', displayName: '专职审核', username: 'reviewer', roles: ['KB_REVIEWER'] }]) : init.method === 'PUT' ? json({ status: 409, title: '权限已变化' }, 409) : undefined, ['ADMIN'])
  await click(view, '管理成员'); await flushPromises(); expect(document.querySelector('.el-dialog')).not.toBeNull()
  const select = document.querySelector<HTMLSelectElement>('#knowledge-member-user')!; select.value = 'u2'; select.dispatchEvent(new Event('change', { bubbles: true }))
  const reason = document.querySelector<HTMLInputElement>('#knowledge-member-reason')!; reason.value = '授权审核'; reason.dispatchEvent(new Event('input', { bubbles: true }))
  const review = document.querySelector<HTMLInputElement>('#knowledge-member-review')!; review.click(); await flushPromises()
  document.querySelector<HTMLFormElement>('#knowledge-member-form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await flushPromises()
  expect(requests.find(row => row.init.method === 'PUT')?.init.body).toContain('"canReview":true')
  expect(document.querySelector<HTMLInputElement>('#knowledge-member-reason')!.value).toBe('授权审核')
  expect(document.body.textContent).toContain('权限已变化')
})

it('redirects legacy citation links in the production router and guards reviewer/admin entry', async () => {
  const pinia = createPinia(); const session = useSessionStore(pinia)
  session.accept({ accessToken: 'routing', expiresIn: 1800, user: { id: 'u1', username: 'u', displayName: 'U', roles: ['KB_EDITOR'], mustChangePassword: false } })
  const { router } = await import('../src/router')
  await router.push('/knowledge?documentId=d1&versionId=v2&chunkId=c2&fromReview=1'); await router.isReady()
  expect(router.currentRoute.value.path).toBe('/knowledge/documents/d1')
  expect(router.currentRoute.value.query).toEqual({ versionId: 'v2', chunkId: 'c2', fromReview: '1' })
  await router.push('/knowledge/reviews'); expect(router.currentRoute.value.path).toBe('/forbidden')
  await router.push('/admin/knowledge-spaces'); expect(router.currentRoute.value.path).toBe('/forbidden')
  session.user!.roles = ['KB_REVIEWER']; await router.push('/knowledge/reviews'); expect(router.currentRoute.value.path).toBe('/knowledge/reviews')
})

it('fences a late parse source after the exact source URL changes', async () => {
  let release!: (response: Response) => void
  const { view, router } = await render('KnowledgeDocumentView', '/knowledge/documents/d1?versionId=v2&tab=parse', url => url.endsWith('/documents/d1') ? json({ ...detail, versions: [version, { ...version, id: 'v1' }] }) : url.endsWith('/v2/chunks') ? new Promise(resolve => { release = resolve }) : url.endsWith('/v1/chunks') ? json([{ id: 'c1', chunkIndex: 0, content: '新选中的精确来源' }]) : undefined)
  await router.push('/knowledge/documents/d1?versionId=v1&tab=parse'); await flushPromises()
  expect(view.text()).toContain('新选中的精确来源')
  release(json([{ id: 'c2', chunkIndex: 0, content: '迟到的私有来源' }])); await flushPromises()
  expect(view.text()).not.toContain('迟到的私有来源'); expect(view.text()).toContain('新选中的精确来源')
})

it('protects unsaved upload input on a same-page space change and keeps focus in the dialog', async () => {
  const { view, router } = await render('KnowledgeView', '/knowledge', undefined, ['KB_EDITOR'])
  await click(view, '新建文档'); await flushPromises()
  const input = document.querySelector<HTMLInputElement>('#knowledge-title')!; input.value = '未提交标题'; input.dispatchEvent(new Event('input', { bubbles: true }))
  await flushPromises(); const navigate = router.push('/knowledge?spaceId=s2'); await flushPromises()
  const cancel = [...document.querySelectorAll<HTMLButtonElement>('.el-message-box button')].find(button => button.textContent?.includes('继续编辑'))!
  expect(cancel).toBeDefined(); cancel.click(); await navigate; await flushPromises()
  expect(router.currentRoute.value.query.spaceId).toBeUndefined(); expect(document.querySelector<HTMLInputElement>('#knowledge-title')!.value).toBe('未提交标题')
})

it('rejects mutually exclusive member rights locally and preserves the form', async () => {
  const { view, requests } = await render('KnowledgeSpaceAdminView', '/admin/knowledge-spaces', url => url.endsWith('/member-candidates') ? json([{ id: 'u2', displayName: '双角色成员', username: 'dual', roles: ['KB_EDITOR', 'KB_REVIEWER'] }]) : undefined, ['ADMIN'])
  await click(view, '管理成员'); await flushPromises()
  const select = document.querySelector<HTMLSelectElement>('#knowledge-member-user')!; select.value = 'u2'; select.dispatchEvent(new Event('change', { bubbles: true }))
  const reason = document.querySelector<HTMLInputElement>('#knowledge-member-reason')!; reason.value = '权限变更'; reason.dispatchEvent(new Event('input', { bubbles: true }))
  document.querySelector<HTMLInputElement>('#knowledge-member-edit')!.click(); document.querySelector<HTMLInputElement>('#knowledge-member-review')!.click(); await flushPromises()
  document.querySelector<HTMLFormElement>('#knowledge-member-form')!.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await flushPromises()
  expect(document.body.textContent).toContain('编辑与审核权限不能同时授予'); expect(requests.some(row => row.init.method === 'PUT')).toBe(false)
  expect(document.querySelector<HTMLInputElement>('#knowledge-member-reason')!.value).toBe('权限变更')
})
it('protects member edits from same-page admin query navigation', async () => {
  const { view, router } = await render('KnowledgeSpaceAdminView', '/admin/knowledge-spaces', undefined, ['ADMIN'])
  await click(view, '管理成员'); await flushPromises()
  const reason = document.querySelector<HTMLInputElement>('#knowledge-member-reason')!; reason.value = '未保存的授权说明'; reason.dispatchEvent(new Event('input', { bubbles: true })); await flushPromises()
  const navigate = router.push('/admin/knowledge-spaces?spaceId=s2'); await flushPromises()
  const cancel = [...document.querySelectorAll<HTMLButtonElement>('.el-message-box button')].find(button => button.textContent?.includes('继续编辑'))
  expect(cancel).toBeDefined(); cancel!.click(); await navigate; await flushPromises()
  expect(document.querySelector<HTMLInputElement>('#knowledge-member-reason')!.value).toBe('未保存的授权说明')
})
it('restores knowledge search and page from the URL and writes searchable history with category state', async () => {
  const { view, router, requests } = await render('KnowledgeView', '/knowledge?spaceId=s1&categoryId=cat1&q=network&page=2', url => url.endsWith('/categories') ? json([{ id: 'cat1', spaceId: 's1', parentId: null, name: '网络', version: 1 }]) : url.includes('/documents?') ? json({ items: [detail], total: 100 }) : undefined, ['KB_EDITOR'])
  expect(view.get('#knowledge-search').element).toHaveProperty('value', 'network')
  expect(requests.some(row => row.url.includes('page=2') && row.url.includes('q=network') && row.url.includes('categoryId=cat1'))).toBe(true)
  await view.get('#knowledge-search').setValue('  printer  '); await view.get('.knowledge-list form').trigger('submit'); await flushPromises()
  expect(router.currentRoute.value.query).toEqual({ spaceId: 's1', categoryId: 'cat1', q: 'printer', page: '0' })
  await click(view, '下一页'); expect(router.currentRoute.value.query).toMatchObject({ q: 'printer', page: '1', categoryId: 'cat1' })
  router.back(); await vi.waitFor(() => expect(router.currentRoute.value.query.page).toBe('0')); await flushPromises()
  expect(view.get('#knowledge-search').element).toHaveProperty('value', 'printer')
  router.back(); await vi.waitFor(() => expect(router.currentRoute.value.query.q).toBe('network')); await flushPromises()
  expect(view.get('#knowledge-search').element).toHaveProperty('value', 'network'); expect(view.text()).toContain('第 3 页')
})
it.each(['-1', '1.5', 'invalid', '9007199254740993'])('normalizes invalid knowledge URL page %s to zero', async page => {
  const { router, requests } = await render('KnowledgeView', `/knowledge?spaceId=s1&q=network&page=${page}`, undefined, ['KB_EDITOR'])
  expect(router.currentRoute.value.query).toMatchObject({ q: 'network', page: '0' })
  expect(requests.filter(row => row.url.includes('/documents?')).every(row => row.url.includes('page=0'))).toBe(true)
})
it.each([{ action: 'SUBMIT_REVIEW', label: '提交审核', suffix: 'review-requests', requested: '' }, { action: 'PUBLISH', label: '发布此版本', suffix: 'publish', requested: 'v1' }])('binds header $action to its exact actionable version', async ({ action, label, suffix, requested }) => {
  const { view, requests } = await render('KnowledgeDocumentView', `/knowledge/documents/d1${requested ? `?versionId=${requested}` : ''}`, url => url.endsWith('/documents/d1') ? json({ ...detail, allowedActions: ['EDIT_DRAFT'], versions: [{ ...version, allowedActions: [action] }, { ...version, id: 'v1', allowedActions: [action] }] }) : undefined, ['KB_EDITOR'])
  const header = view.get('.knowledge-detail > .document-actions')
  const button = header.findAll('button').find(item => item.text() === label); expect(button).toBeDefined(); await button!.trigger('click'); await flushPromises()
  expect(requests.some(row => row.url.endsWith(`/versions/${requested || 'v2'}/${suffix}`) && row.init.method === 'POST')).toBe(true)
  expect(requests.some(row => row.url.includes('/versions/undefined'))).toBe(false)
})
it.each([['KB_EDITOR'], ['KB_EDITOR', 'ADMIN']])('warns an editor without an eligible reviewer while preserving submission: %s', async (...roles) => {
  const { view } = await render('KnowledgeDocumentView', '/knowledge/documents/d1?tab=versions', url => url.endsWith('/documents/d1') ? json({ ...detail, allowedActions: [], versions: [{ ...version, reviewState: 'DRAFT', hasEligibleReviewer: false, allowedActions: ['SUBMIT_REVIEW'] }] }) : undefined, roles)
  expect(view.text()).toContain('当前空间尚无可用的专职审核者；提交后将等待管理员配置审核权限')
  expect(view.findAll('button').some(button => button.text() === '提交审核' && !button.attributes('disabled'))).toBe(true)
  if (roles.includes('ADMIN')) expect(view.get('a[href="/admin/knowledge-spaces?spaceId=s1"]').text()).toContain('管理知识空间')
  else expect(view.text()).toContain('联系管理员')
})
