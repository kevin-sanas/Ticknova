import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Component } from 'vue'
import { bindApiSession } from '../src/api'
import { useSessionStore } from '../src/stores/session'

const page = { items: [{ documentId: 'd1', spaceId: 's1', title: '待审核手册', versionId: 'v2', versionNo: 2, fileName: 'guide.md', authorId: 'u2', versionCreatedAt: '2026-10-21T01:00:00Z' }], total: 1, page: 0, size: 20 }
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
let view: VueWrapper | undefined
afterEach(() => { view?.unmount(); view = undefined; vi.unstubAllGlobals(); document.body.innerHTML = '' })

async function render(fetcher: (url: string) => Response | Promise<Response>) {
  const modules = import.meta.glob('../src/views/KnowledgeReviewView.vue')
  expect(modules['../src/views/KnowledgeReviewView.vue'], 'dedicated review page exists').toBeDefined()
  const component = (await modules['../src/views/KnowledgeReviewView.vue']!() as { default: Component }).default
  const pinia = createPinia(); const session = useSessionStore(pinia)
  session.accept({ accessToken: 'review-token', expiresIn: 1800, user: { id: 'u1', username: 'reviewer', displayName: '审核者', roles: ['KB_EDITOR'], mustChangePassword: false } })
  bindApiSession({ getToken: () => session.token, onUnauthorized: () => session.clear() })
  vi.stubGlobal('fetch', (url: string) => Promise.resolve(fetcher(url)))
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/knowledge/reviews', component }, { path: '/knowledge', component: { template: '<main>知识文档</main>' } },
  ] })
  await router.push('/knowledge/reviews'); await router.isReady()
  view = mount(component, { attachTo: document.body, global: { plugins: [pinia, ElementPlus, router] } }); await flushPromises()
  return { view, router, session }
}

it('shows a discoverable reviewer task and opens the exact pending document version', async () => {
  const urls: string[] = []
  const { view, router } = await render(url => { urls.push(url); return json(page) })
  expect(view.get('h1').text()).toBe('审核待办')
  expect(view.text()).toContain('待审核手册')
  expect(urls.some(url => url.includes('/knowledge/review-tasks?page=0&size=20'))).toBe(true)
  await view.get('button[aria-label="审核待审核手册的版本 2"]').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/knowledge')
  expect(router.currentRoute.value.query).toEqual({ documentId: 'd1', versionId: 'v2', fromReview: '1' })
})

it('distinguishes empty tasks from a failed refresh and permits retry', async () => {
  let fail = false
  const { view } = await render(() => fail ? json({ status: 503, title: '审核服务暂时不可用' }, 503) : json({ items: [], total: 0, page: 0, size: 20 }))
  expect(view.text()).toContain('暂无待你审核的版本')
  fail = true; await view.get('button[aria-label="刷新待办"]').trigger('click'); await flushPromises()
  expect(view.text()).toContain('审核服务暂时不可用')
  expect(view.text()).not.toContain('暂无待你审核的版本')
  fail = false; await view.get('button[aria-label="刷新待办"]').trigger('click'); await flushPromises()
  expect(view.text()).toContain('暂无待你审核的版本')
})
