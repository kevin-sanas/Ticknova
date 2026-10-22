import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import type { Component } from 'vue'
import { bindApiSession } from '../src/api'
import { useSessionStore } from '../src/stores/session'
import type { TicketDetail, User } from '../src/types'
import LoginView from '../src/views/LoginView.vue'
import NewTicketView from '../src/views/NewTicketView.vue'
import TicketDetailView from '../src/views/TicketDetailView.vue'

const requester: User = { id: 'r1', username: 'requester', displayName: '申请人', roles: ['REQUESTER'], mustChangePassword: false }
const agent: User = { id: 'a1', username: 'agent', displayName: '工程师', roles: ['AGENT'], mustChangePassword: false }
const detailFixture: TicketDetail = {
  ticket: { id: 't1', ticketNo: 'TK-001', title: '打印机故障', priority: 'NORMAL', status: 'IN_PROGRESS', requesterId: 'r1', requesterName: '申请人', queueId: 'q1', queueName: '受理队列', assigneeId: 'a1', assigneeName: '工程师', serviceId: null, serviceName: null, version: 1, createdAt: '2026-10-20T01:00:00Z', updatedAt: '2026-10-20T01:00:00Z', tags: [] },
  description: '打印机无法工作', resolutionNote: null,
  publicReplies: [], internalNotes: [{ id: 'c1', ticketId: 't1', authorId: 'a1', authorName: '工程师', visibility: 'INTERNAL_NOTE', body: '秘密排查记录', createdAt: '2026-10-20T02:00:00Z' }], transitions: [],
  capabilities: { canReadInternal: false, canCommentPublic: true, canCommentInternal: false, canAssign: false, canEdit: false }, allowedTransitions: [],
}
function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } }) }
const wrappers: VueWrapper[] = []
beforeEach(() => vi.unstubAllGlobals())
afterEach(() => { wrappers.forEach(wrapper => wrapper.unmount()); wrappers.length = 0; vi.unstubAllGlobals() })
async function render(component: Component, user: User = requester, path = '/tickets/t1') {
  const pinia = createPinia()
  const session = useSessionStore(pinia)
  session.accept({ accessToken: 'memory-only', expiresIn: 1800, user })
  bindApiSession({ getToken: () => session.token, onUnauthorized: () => session.clear() })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div>目标页面</div>' } }] })
  await router.push(path)
  const wrapper = mount(component, { global: { plugins: [pinia, router, ElementPlus] } })
  wrappers.push(wrapper)
  await flushPromises()
  return { wrapper, router, session }
}

describe('user-facing forms', () => {
  it('retains login input on rejected credentials and sends a successful first login to password change', async () => {
    let valid = false
    vi.stubGlobal('fetch', async () => valid ? json({ accessToken: 'new-memory-only', expiresIn: 1800, user: { ...requester, mustChangePassword: true } }) : json({ title: '账号或密码错误', code: 'INVALID_CREDENTIALS' }, 401))
    const { wrapper, router } = await render(LoginView, requester, '/login')
    await wrapper.get('#username').setValue('requester')
    await wrapper.get('#password').setValue('entered-by-user')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(wrapper.get('#password').element).toHaveProperty('value', 'entered-by-user')
    expect(wrapper.text()).toContain('账号或密码错误')
    valid = true
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/change-password')
  })

  it('keeps a new ticket draft and reuses its idempotency key after a network failure', async () => {
    const requests: { key: string | null; body: unknown }[] = []
    vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
      if (url.endsWith('/services')) return json([])
      requests.push({ key: new Headers(init.headers).get('Idempotency-Key'), body: JSON.parse(String(init.body)) })
      if (requests.length === 1) throw new TypeError('Network unavailable')
      return json(detailFixture.ticket, 201)
    })
    const { wrapper, router } = await render(NewTicketView, requester, '/tickets/new')
    await wrapper.get('#ticket-title').setValue('打印机故障')
    await wrapper.get('#ticket-description').setValue('打印机无法工作')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(wrapper.get('#ticket-title').element).toHaveProperty('value', '打印机故障')
    expect(wrapper.get('#ticket-description').element).toHaveProperty('value', '打印机无法工作')
    expect(wrapper.text()).toContain('无法连接服务器')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(requests).toHaveLength(2)
    expect(requests[0]?.key).toBeTruthy()
    expect(requests[1]?.key).toBe(requests[0]?.key)
    expect(requests[1]?.body).toEqual({ title: '打印机故障', description: '打印机无法工作', serviceId: null, priority: 'NORMAL' })
    expect(router.currentRoute.value.path).toBe('/tickets/t1')
  })

  it('never renders internal notes for a requester even if the response contains unexpected internal data', async () => {
    vi.stubGlobal('fetch', async () => json({ ...detailFixture, capabilities: { ...detailFixture.capabilities, canReadInternal: true, canCommentInternal: true } }))
    const { wrapper } = await render(TicketDetailView)
    expect(wrapper.text()).not.toContain('秘密排查记录')
    expect(wrapper.find('#internal-tab').exists()).toBe(false)
    expect(wrapper.find('#comment-body').exists()).toBe(true)
  })

  it('shows internal notes without a composer to an agent with read-only permission', async () => {
    vi.stubGlobal('fetch', async () => json({ ...detailFixture, capabilities: { ...detailFixture.capabilities, canReadInternal: true, canCommentInternal: false } }))
    const { wrapper } = await render(TicketDetailView, agent)
    await wrapper.get('#internal-tab').trigger('click')
    expect(wrapper.text()).toContain('秘密排查记录')
    expect(wrapper.find('#comment-body').exists()).toBe(false)
  })

  it('moves conversation tabs using arrow keys with one keyboard tab stop', async () => {
    vi.stubGlobal('fetch', async () => json({ ...detailFixture, capabilities: { ...detailFixture.capabilities, canReadInternal: true } }))
    const { wrapper } = await render(TicketDetailView, agent)
    expect(wrapper.get('#public-tab').attributes('tabindex')).toBe('0')
    expect(wrapper.get('#internal-tab').attributes('tabindex')).toBe('-1')
    await wrapper.get('#public-tab').trigger('keydown', { key: 'ArrowRight' })
    expect(wrapper.get('#internal-tab').attributes('aria-selected')).toBe('true')
    expect(wrapper.get('#internal-tab').attributes('tabindex')).toBe('0')
    await wrapper.get('#internal-tab').trigger('keydown', { key: 'End' })
    expect(wrapper.get('#history-tab').attributes('aria-selected')).toBe('true')
  })

  it('retains transition input on 409 and submits the latest version only after explicit reload', async () => {
    let version = 1
    const submitted: { reason: string; version: number }[] = []
    vi.stubGlobal('fetch', async (url: string, init: RequestInit) => {
      if (url.endsWith('/transitions')) {
        submitted.push(JSON.parse(String(init.body)))
        if (submitted.length === 1) { version = 2; return json({ title: '版本冲突', code: 'VERSION_CONFLICT' }, 409) }
        return json(detailFixture.ticket)
      }
      return json({ ...detailFixture, ticket: { ...detailFixture.ticket, version }, allowedTransitions: ['RESOLVED'] })
    })
    const { wrapper } = await render(TicketDetailView, agent)
    await wrapper.get('#target-status').setValue('RESOLVED')
    await wrapper.get('#transition-reason').setValue('已重启打印服务')
    const form = wrapper.get('#target-status').element.closest('form')!
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await flushPromises()
    expect(wrapper.get('#transition-reason').element).toHaveProperty('value', '已重启打印服务')
    expect(wrapper.text()).toContain('载入最新版本')
    const reload = wrapper.findAll('button').find(button => button.text() === '载入最新版本')!
    await reload.trigger('click'); await flushPromises()
    expect(wrapper.get('#transition-reason').element).toHaveProperty('value', '已重启打印服务')
    form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true })); await flushPromises()
    expect(submitted).toEqual([{ toStatus: 'RESOLVED', reason: '已重启打印服务', version: 1 }, { toStatus: 'RESOLVED', reason: '已重启打印服务', version: 2 }])
  })

  it('loads eligible members for the new queue when selection changes during an in-flight member request', async () => {
    let release!: (response: Response) => void
    vi.stubGlobal('fetch', async (url: string) => {
      if (url.endsWith('/queues')) return json([{ id: 'q1', name: '受理队列', code: 'GENERAL', active: true, canProcess: true }, { id: 'q2', name: '网络队列', code: 'NETWORK', active: true, canProcess: true }])
      if (url.endsWith('/q1/members')) return new Promise<Response>(resolve => { release = resolve })
      if (url.endsWith('/q2/members')) return json([{ userId: 'a2', username: 'agent2', displayName: '网络工程师', active: true, canProcess: true }, { userId: 'a3', username: 'inactive', displayName: '停用工程师', active: false, canProcess: true }])
      return json({ ...detailFixture, capabilities: { ...detailFixture.capabilities, canAssign: true } })
    })
    const { wrapper } = await render(TicketDetailView, agent)
    await wrapper.get('#assignment-queue').setValue('q2')
    release(json([])); await flushPromises()
    expect(wrapper.get('#assignment-person').text()).toContain('网络工程师')
    expect(wrapper.get('#assignment-person').text()).not.toContain('停用工程师')
  })
})
