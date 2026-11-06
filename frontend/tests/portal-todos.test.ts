import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import PortalView from '../src/views/PortalView.vue'
import { bindApiSession } from '../src/api'
import { useSessionStore } from '../src/stores/session'

let page: VueWrapper | undefined
const portal = { query: '', knowledge: [], popularDocuments: [], categories: [], tickets: [] }
const todo = { id: 't1', ticketNo: 10, title: '办公软件安装失败', status: 'WAITING_CUSTOMER', priority: 'HIGH', updatedAt: '2026-10-20T12:00:00Z', reason: 'WAITING_CUSTOMER' }
afterEach(() => { page?.unmount(); vi.unstubAllGlobals() })
async function render() {
  const pinia = createPinia(), session = useSessionStore(pinia)
  session.accept({ accessToken: 'fixture', expiresIn: 1800, user: { id: 'u', username: 'u', displayName: '用户', roles: ['REQUESTER'], mustChangePassword: false } })
  bindApiSession({ getToken: () => session.token, onUnauthorized: () => session.clear() })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push('/portal')
  page = mount(PortalView, { global: { plugins: [pinia, router, ElementPlus] }, attachTo: document.body })
  await flushPromises()
  return { page, session }
}
it('opens current-user tasks on demand, links to the real ticket and closes with Escape', async () => {
  const fetch = vi.fn((url: string) => Promise.resolve(Response.json(url.includes('/workspace/todos') ? [todo] : portal)))
  vi.stubGlobal('fetch', fetch)
  const { page } = await render()
  expect(fetch).toHaveBeenCalledTimes(1)
  await page.get('[aria-label="打开待处理工单"]').trigger('click'); await flushPromises()
  expect(page.get('[aria-label="打开待处理工单"]').attributes('aria-expanded')).toBe('true')
  expect(page.get('[data-testid="todo-panel"]').text()).toContain('等待申请人')
  expect(page.get('[data-testid="todo-panel"] a').attributes('href')).toBe('/tickets/t1')
  await page.get('[data-testid="todo-panel"]').trigger('keydown', { key: 'Escape' })
  expect(page.get('[aria-label="打开待处理工单"]').attributes('aria-expanded')).toBe('false')
})
it('shows loading, empty and retryable failure states without inventing tasks', async () => {
  let resolve: (response: Response) => void = () => {}
  const todoFetch = vi.fn(() => new Promise<Response>(done => { resolve = done }))
  vi.stubGlobal('fetch', (url: string) => url.includes('/workspace/todos') ? todoFetch() : Promise.resolve(Response.json(portal)))
  const { page } = await render()
  await page.get('[aria-label="打开待处理工单"]').trigger('click')
  expect(page.get('[data-testid="todo-panel"]').text()).toContain('正在加载')
  resolve(Response.json([])); await flushPromises()
  expect(page.get('[data-testid="todo-panel"]').text()).toContain('暂无待处理工单')
  await page.get('[aria-label="打开待处理工单"]').trigger('click')
  await page.get('[aria-label="打开待处理工单"]').trigger('click')
  resolve(Response.json({ title: '服务不可用', status: 503 }, { status: 503 })); await flushPromises()
  expect(page.get('[data-testid="todo-panel"]').text()).toContain('无法加载')
  expect(page.get('[data-testid="todo-panel"]').text()).toContain('重试')
})
it('discards pending private tasks when the session changes', async () => {
  let resolve: (response: Response) => void = () => {}
  vi.stubGlobal('fetch', (url: string) => url.includes('/workspace/todos') ? new Promise<Response>(done => { resolve = done }) : Promise.resolve(Response.json(portal)))
  const { page, session } = await render()
  await page.get('[aria-label="打开待处理工单"]').trigger('click')
  session.clear(); resolve(Response.json([todo])); await flushPromises()
  expect(page.text()).not.toContain(todo.title)
})
