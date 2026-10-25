import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import PortalView from '../src/views/PortalView.vue'
import { bindApiSession } from '../src/api'
import { useSessionStore } from '../src/stores/session'
let page: VueWrapper | undefined
const document = { id: 'd1', spaceId: 's1', title: 'VPN 连接指南', snippet: '检查网络，再重新连接。'.repeat(30), categoryId: 'c1', viewCount: 7, publishedAt: '2026-10-20T12:00:00Z' }
const payload = { query: '', knowledge: [], popularDocuments: [document], categories: [{ id: 'c1', spaceId: 's1', parentId: null, name: '网络支持', spaceName: 'IT 服务', documentCount: 3 }], tickets: [{ id: 't1', ticketNo: 12, title: '连接中断', status: 'NEW', priority: 'NORMAL', updatedAt: '2026-10-20T12:00:00Z' }] }
afterEach(() => { page?.unmount(); vi.unstubAllGlobals() })
async function render(roles = ['REQUESTER'], start = '/portal') {
  const pinia = createPinia(), session = useSessionStore(pinia)
  session.accept({ accessToken: 'fixture', expiresIn: 1800, user: { id: 'u', username: 'u', displayName: '员工', roles, mustChangePassword: false } })
  bindApiSession({ getToken: () => session.token, onUnauthorized: () => session.clear() })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push(start)
  page = mount(PortalView, { global: { plugins: [pinia, router, ElementPlus] } }); await flushPromises()
  return { page, router, session }
}
it('shows real publication metadata, category counts, independent document links and own ticket panel', async () => {
  vi.stubGlobal('fetch', () => Promise.resolve(Response.json(payload)))
  const { page } = await render()
  expect(page.get('h1').text()).toBe('员工服务门户')
  expect(page.get('a[href="/tickets/new"]').text()).toContain('提交工单')
  expect(page.get('.portal-tickets h2').text()).toBe('我的工单')
  expect(page.find('a[href="/knowledge/documents/d1"]').exists()).toBe(true)
  expect(page.get('.document-card').text()).toContain('网络支持')
  expect(page.get('.document-card time').attributes('datetime')).toBe(document.publishedAt)
  expect(page.get('.document-card').text()).toContain('7 次阅读')
  expect(page.get('.category-chips').text()).toContain('网络支持 3')
  expect(page.get('.document-snippet').text()).toBe(document.snippet)
})
it('limits the ticket summary panel to the three latest API results', async () => {
  const tickets = Array.from({ length: 4 }, (_, index) => ({ ...payload.tickets[0], id: `t${index + 1}`, ticketNo: index + 1, title: `工单 ${index + 1}` }))
  vi.stubGlobal('fetch', () => Promise.resolve(Response.json({ ...payload, tickets })))
  const { page } = await render()
  expect(page.findAll('.portal-ticket')).toHaveLength(3)
  expect(page.text()).toContain('工单 3')
  expect(page.text()).not.toContain('工单 4')
  expect(page.get('.portal-tickets .panel-header a').text()).toContain('查看全部')
})
it('labels queue-visible tickets honestly for agents including dual-role agents', async () => {
  vi.stubGlobal('fetch', () => Promise.resolve(Response.json(payload)))
  const { page } = await render(['AGENT', 'REQUESTER'])
  expect(page.get('.portal-tickets h2').text()).toBe('可访问工单')
  expect(page.text()).not.toContain('我的工单')
})
it('searches using real document shortcuts, and assistant mode sends the entered question', async () => {
  const urls: string[] = []
  vi.stubGlobal('fetch', (url: string) => { urls.push(url); return Promise.resolve(Response.json({ ...payload, query: new URL(url, 'http://local').searchParams.get('q') || '', knowledge: [document] })) })
  const { page, router } = await render()
  await page.get('.search-shortcuts button').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.query.q).toBe(document.title)
  expect(urls.at(-1)).toContain(encodeURIComponent('VPN'))
  await page.get('[data-mode="assistant"]').trigger('click')
  await page.get('#portal-search').setValue('如何连接？')
  await page.get('#portal-search-form').trigger('submit'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/assistant')
  expect(router.currentRoute.value.query.q).toBe('如何连接？')
})
it('follows URL searches and fences stale results after a later request', async () => {
  const pending: ((value: Response) => void)[] = []
  vi.stubGlobal('fetch', () => new Promise<Response>(resolve => pending.push(resolve)))
  const { page, router } = await render()
  expect(page.find('[role="status"]').exists()).toBe(true)
  await router.push('/portal?q=new'); await flushPromises()
  pending[1]!(Response.json({ ...payload, query: 'new', popularDocuments: [{ ...document, title: '最新指南' }] })); await flushPromises()
  pending[0]!(Response.json(payload)); await flushPromises()
  expect(page.text()).toContain('最新指南'); expect(page.text()).not.toContain(document.title)
})
it('renders empty and error states separately and purges private content on session change', async () => {
  vi.stubGlobal('fetch', () => Promise.resolve(Response.json({ ...payload, popularDocuments: [], tickets: [], categories: [] })))
  const { page, router, session } = await render()
  expect(page.text()).toContain('还没有你可阅读的已发布文档')
  vi.stubGlobal('fetch', () => Promise.resolve(Response.json({ title: '服务暂不可用', status: 503 }, { status: 503 })))
  await router.push('/portal?q=VPN'); await flushPromises()
  expect(page.find('[role="alert"]').exists()).toBe(true)
  expect(page.find('.document-card').exists()).toBe(false)
  session.clear(); await flushPromises()
  expect(page.find('.portal-tickets').exists()).toBe(false)
})
it('does not issue duplicate same-query requests and ignores private results after logout', async () => {
  let resolve: (response: Response) => void = () => {}
  const fetch = vi.fn(() => new Promise<Response>(done => { resolve = done }))
  vi.stubGlobal('fetch', fetch)
  const { page, session } = await render()
  await page.get('#portal-search-form').trigger('submit')
  expect(fetch).toHaveBeenCalledTimes(1)
  session.clear(); resolve(Response.json(payload)); await flushPromises()
  expect(page.find('.document-card').exists()).toBe(false)
  expect(page.find('.portal-tickets').exists()).toBe(false)
})
