import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import AnalyticsView from '../src/views/AnalyticsView.vue'
import { bindApiSession } from '../src/api'
import { useSessionStore } from '../src/stores/session'
vi.mock('../src/components/analytics/DailyChart.vue', () => ({ __esModule: true, default: { props: ['daily'], template: '<div data-test="daily-chart" />' } }))
let page: VueWrapper | undefined
const payload = { from: '2026-10-18', to: '2026-10-24', ticketTotal: 10, statusCounts: [{ key: 'NEW', count: 2 }, { key: 'IN_PROGRESS', count: 3 }, { key: 'RESOLVED', count: 4 }, { key: 'CLOSED', count: 1 }], priorityCounts: [{ key: 'URGENT', count: 2 }, { key: 'NORMAL', count: 8 }], daily: [{ date: '2026-10-24', created: 10, resolved: 20 }], knowledge: { publishedDocuments: 12, pendingReviews: 3 }, ai: { completed: 8, refused: 2, accepted: 0, rejected: 0 } }
afterEach(() => { page?.unmount(); vi.unstubAllGlobals() })
async function render(start = '/analytics') {
  const pinia = createPinia(), session = useSessionStore(pinia)
  session.accept({ accessToken: 'fixture', expiresIn: 1800, user: { id: 'u', username: 'u', displayName: '管理员', roles: ['ADMIN'], mustChangePassword: false } })
  bindApiSession({ getToken: () => session.token, onUnauthorized: () => session.clear() })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }] })
  await router.push(start); page = mount(AnalyticsView, { global: { plugins: [pinia, router, ElementPlus] } }); await flushPromises()
  return { page, router, session }
}
it('uses URL-backed 7/14/30 ranges with default 30 and inclusive UTC dates', async () => {
  const urls: string[] = []
  vi.stubGlobal('fetch', (url: string) => { urls.push(url); return Promise.resolve(Response.json(payload)) })
  const { page, router } = await render()
  expect(page.get('[data-days="30"]').attributes('aria-pressed')).toBe('true')
  await page.get('[data-days="7"]').trigger('click'); await flushPromises()
  expect(router.currentRoute.value.query.range).toBe('7')
  const params = new URL(urls.at(-1)!, 'http://local').searchParams
  expect((Date.parse(params.get('to')!) - Date.parse(params.get('from')!)) / 86400000).toBe(6)
  await router.push('/analytics?range=14'); await flushPromises()
  expect(page.get('[data-days="14"]').attributes('aria-pressed')).toBe('true')
})
it('derives cohort KPIs without using daily resolved transitions as a completion rate', async () => {
  vi.stubGlobal('fetch', () => Promise.resolve(Response.json(payload)))
  const { page } = await render('/analytics?range=7')
  expect(page.get('[data-kpi="created"]').text()).toContain('10')
  expect(page.get('[data-kpi="open"]').text()).toContain('5')
  expect(page.get('[data-kpi="urgent"]').text()).toContain('2')
  expect(page.get('[data-kpi="completion"]').text()).toContain('50%')
  expect(page.get('[data-ai-acceptance]').text()).toContain('暂无反馈')
  expect(page.text()).toContain('不同工单')
  expect(page.find('[data-test="daily-chart"]').exists()).toBe(true)
  expect(page.find('details table').exists()).toBe(true)
})
it('shows real feedback denominator and a meaningful empty state', async () => {
  vi.stubGlobal('fetch', () => Promise.resolve(Response.json({ ...payload, ticketTotal: 0, statusCounts: [], priorityCounts: [], daily: [], ai: { ...payload.ai, accepted: 1, rejected: 3 } })))
  const { page } = await render()
  expect(page.get('[data-ai-acceptance]').text()).toContain('25%')
  expect(page.text()).toContain('所选时间范围没有新建工单')
  expect(page.get('[data-kpi="completion"]').text()).toContain('—')
  expect(page.text()).toContain('暂无每日数据')
})
it('fences stale URL requests and clears data when access changes', async () => {
  const pending: ((value: Response) => void)[] = []
  vi.stubGlobal('fetch', () => new Promise<Response>(resolve => pending.push(resolve)))
  const { page, router, session } = await render()
  expect(page.find('[role="status"]').exists()).toBe(true)
  await router.push('/analytics?range=7'); await flushPromises()
  pending[1]!(Response.json({ ...payload, ticketTotal: 99 })); await flushPromises()
  pending[0]!(Response.json(payload)); await flushPromises()
  expect(page.get('[data-kpi="created"]').text()).toContain('99')
  session.clear(); await flushPromises()
  expect(page.find('[data-kpi]').exists()).toBe(false)
})
it('reports request failure without showing stale KPIs', async () => {
  vi.stubGlobal('fetch', () => Promise.resolve(Response.json(payload)))
  const { page, router } = await render()
  vi.stubGlobal('fetch', () => Promise.resolve(Response.json({ title: '统计不可用', status: 503 }, { status: 503 })))
  await router.push('/analytics?range=7'); await flushPromises()
  expect(page.find('[role="alert"]').exists()).toBe(true)
  expect(page.find('[data-kpi]').exists()).toBe(false)
})
it('builds chart values from the continuous daily series and disables motion on request', async () => {
  const { dailyChartOption } = await import('../src/components/analytics/daily-chart-options')
  const option = dailyChartOption(payload.daily, { primary: 'green', secondary: 'purple', text: 'ink', muted: 'muted', border: 'border' }, true)
  expect(option.animation).toBe(false)
  expect(option.xAxis.data).toEqual(['2026-10-24'])
  expect(option.series.map(series => series.data)).toEqual([[10], [20]])
  expect(option.series.map(series => series.itemStyle.color)).toEqual(['green', 'purple'])
  expect(option.aria.enabled).toBe(true)
})
it('does not convert malformed analytics responses into plausible zero KPIs', async () => {
  vi.stubGlobal('fetch', () => Promise.resolve(Response.json({ ...payload, ai: {} })))
  const { page } = await render()
  expect(page.find('[role="alert"]').exists()).toBe(true)
  expect(page.find('[data-kpi]').exists()).toBe(false)
})
