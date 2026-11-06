import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import AuditView from '../src/views/AuditView.vue'

let view: VueWrapper | undefined
afterEach(() => { view?.unmount(); view = undefined; vi.unstubAllGlobals() })
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
const empty = { items: [], total: 0, page: 0, size: 20 }
const filters = { actions: [], actors: [] }
const unavailable = () => json({ title: 'Unavailable' }, 503)
async function render(fetcher: (url: string) => Promise<Response>) {
  vi.stubGlobal('fetch', fetcher)
  view = mount(AuditView, { global: { plugins: [ElementPlus] } })
  await flushPromises()
}
function expectUnknownResults() {
  expect(view!.find('.error-notice').exists()).toBe(true)
  expect(view!.find('.panel-header h2 .small').exists()).toBe(false)
  expect(view!.find('.desktop-table').exists()).toBe(false)
  expect(view!.find('.mobile-cards').exists()).toBe(false)
  expect(view!.find('.pagination').exists()).toBe(false)
  expect(view!.find('.empty-state').exists()).toBe(false)
}
it('hides unknown results on initial failure and shows an empty result after retry', async () => {
  let calls = 0
  await render(async url => url.endsWith('/filters') ? json(filters) : ++calls === 1 ? unavailable() : json(empty))
  expectUnknownResults()
  await view!.get('.error-notice button').trigger('click'); await flushPromises()
  expect(calls).toBe(2)
  expect(view!.find('.error-notice').exists()).toBe(false)
  expect(view!.find('.empty-state').exists()).toBe(true)
  expect(view!.find('.pagination').text()).toContain('0')
})
it('hides previously successful results after a later failed refresh until retry succeeds', async () => {
  let calls = 0
  const event = { id: 'a1', action: 'AUTH_LOGIN', actorName: 'Fixture', subjectType: 'USER', createdAt: '2026-10-21T00:00:00Z', metadata: {} }
  await render(async url => url.endsWith('/filters') ? json(filters) : ++calls === 2 ? unavailable() : json({ ...empty, items: [event], total: 1 }))
  expect(view!.find('.desktop-table').text()).toContain('AUTH_LOGIN')
  await view!.get('.filter-form').trigger('submit'); await flushPromises()
  expectUnknownResults()
  await view!.get('.error-notice button').trigger('click'); await flushPromises()
  expect(view!.find('.desktop-table').text()).toContain('AUTH_LOGIN')
  expect(view!.find('.pagination').text()).toContain('1')
})
it('keeps successfully loaded empty results when only filter options fail', async () => {
  await render(async url => url.endsWith('/filters') ? unavailable() : json(empty))
  expect(view!.find('.error-notice').exists()).toBe(true)
  expect(view!.find('.empty-state').exists()).toBe(true)
  expect(view!.find('.pagination').text()).toContain('0')
})
