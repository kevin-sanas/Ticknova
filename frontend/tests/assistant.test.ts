import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus, { ElSelect } from 'element-plus'
import type { Component } from 'vue'
import { bindApiSession } from '../src/api'
import { useSessionStore } from '../src/stores/session'
import TicketDetailView from '../src/views/TicketDetailView.vue'

const space = { id: 's1', name: '运维知识', active: true, canRead: true, canEdit: false, canManageMembers: false }
const citation = { documentId: 'd1', versionId: 'v2', version: 2, title: '网络手册', page: 3, chunkId: 'c1', snippet: '<img src=x onerror=alert(1)>重启设备' }
const result = { suggestionId: 'a1', kind: 'ANSWER', mode: 'mock', profile: 'mock:hash-v1:1024', outcome: 'ANSWERED', answer: '请按照网络手册重启设备。', summary: null, serviceId: null, priority: null, tags: [], citations: [citation], requiresHumanReview: true, feedback: null }
const ticket = {
  ticket: { id: 't1', ticketNo: 'TK-001', title: '网络故障', priority: 'NORMAL', status: 'IN_PROGRESS', requesterId: 'u1', requesterName: '申请人', queueId: 'q1', queueName: '受理队列', assigneeId: 'a1', assigneeName: '工程师', serviceId: null, serviceName: null, version: 1, createdAt: '2026-10-20T01:00:00Z', updatedAt: '2026-10-20T01:00:00Z', tags: [] },
  description: '网络无法连接', resolutionNote: null, publicReplies: [], internalNotes: [], transitions: [],
  capabilities: { canReadInternal: false, canCommentPublic: true, canCommentInternal: false, canAssign: false, canEdit: false }, allowedTransitions: [],
}
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
const completed = (value: unknown = result) => json({ id: 'j1', status: 'COMPLETED', errorCode: null, attemptCount: 1, result: value })
let wrapper: VueWrapper | undefined
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.useRealTimers(); vi.unstubAllGlobals(); document.body.innerHTML = '' })
function stub(extra?: (url: string, init: RequestInit) => Response | Promise<Response> | undefined) {
  vi.stubGlobal('fetch', (url: string, init: RequestInit) => {
    const response = extra?.(url, init); if (response) return Promise.resolve(response)
    if (url.endsWith('/ai/config')) return Promise.resolve(json({ mode: 'mock', profile: 'mock:hash-v1:1024', externalProvider: false }))
    if (url.endsWith('/knowledge/spaces')) return Promise.resolve(json([space, { ...space, id: 'secret', name: '未授权空间', canRead: false }]))
    if (url.endsWith('/services')) return Promise.resolve(json([{ id: 'svc1', code: 'NET', name: '网络服务', active: true }]))
    if (url.endsWith('/ai/answers') || url.endsWith('/ai-drafts')) return Promise.resolve(json({ jobId: 'j1' }, 202))
    if (url.endsWith('/jobs/j1')) return Promise.resolve(completed())
    if (url.endsWith('/feedback')) return Promise.resolve(new Response(null, { status: 204 }))
    if (url.endsWith('/tickets/t1')) return Promise.resolve(json(ticket))
    return Promise.resolve(json([]))
  })
}
async function render(component?: Component) {
  if (!component) {
    const modules = import.meta.glob('../src/views/AssistantView.vue')
    expect(modules['../src/views/AssistantView.vue'], 'assistant page is implemented').toBeDefined()
    component = ((await modules['../src/views/AssistantView.vue']!()) as { default: Component }).default
  }
  const pinia = createPinia(); const session = useSessionStore(pinia)
  session.accept({ accessToken: 'ai-memory', expiresIn: 1800, user: { id: 'u1', username: 'requester', displayName: '申请人', roles: ['REQUESTER'], mustChangePassword: false } })
  bindApiSession({ getToken: () => session.token, onUnauthorized: () => session.clear() })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/:pathMatch(.*)*', component: { template: '<div />' } }, { path: '/tickets/:id', component }] }); await router.push('/tickets/t1'); await router.isReady()
  wrapper = mount({ template: '<router-view />' }, { attachTo: document.body, global: { plugins: [pinia, router, ElementPlus] } }); await flushPromises()
  return { view: wrapper, session }
}
async function click(view: VueWrapper, label: string) { const button = view.findAll('button').find(button => button.text() === label); expect(button, label).toBeDefined(); await button!.trigger('click'); await flushPromises() }
async function ask(view: VueWrapper, question = '网络故障如何处理？') { await view.get('#assistant-question').setValue(question); await view.get('#assistant-form').trigger('submit'); await flushPromises() }
async function generate(view: VueWrapper, kind = 'REPLY') { await view.get('#ai-kind').setValue(kind); await click(view, '生成建议') }

describe('knowledge assistant', () => {
  it('cancels the actual server job before showing user-stopped and stops polling', async () => {
    vi.useFakeTimers(); let reads = 0; let cancellations = 0; let release!: (value: Response) => void
    stub(url => {
      if (url.endsWith('/jobs/j1')) { reads++; return json({ id: 'j1', status: 'RUNNING', errorCode: null, attemptCount: 1, result: null }) }
      if (url.endsWith('/ai/jobs/j1/cancel')) { cancellations++; return new Promise<Response>(resolve => { release = resolve }) }
    })
    const { view } = await render(); await ask(view); await click(view, '中止生成')
    expect(cancellations).toBe(1); expect(view.text()).not.toContain('已中止')
    release(new Response(null, { status: 204 })); await flushPromises(); expect(view.text()).toContain('已中止')
    const before = reads; await vi.advanceTimersByTimeAsync(60000); expect(reads).toBe(before); expect(view.find('.ai-answer').exists()).toBe(false)
  })
  it('renders cited plain text with provenance and only readable spaces; empty selection means all readable', async () => {
    let body: unknown
    stub((url, init) => { if (url.endsWith('/ai/answers')) { body = JSON.parse(String(init.body)); return json({ jobId: 'j1' }, 202) } })
    const { view } = await render(); expect(view.text()).toContain('本地演示'); expect(view.text()).not.toContain('未授权空间')
    await ask(view); expect(body).toEqual({ question: '网络故障如何处理？', spaceIds: [] }); expect(view.text()).toContain(result.answer)
    expect(view.text()).toContain('网络手册'); expect(view.text()).toContain('v2'); expect(view.text()).toContain('第 3 页'); expect(view.text()).toContain(citation.snippet)
    expect(view.find('.ai-citations img').exists()).toBe(false); expect(view.text()).toContain(result.profile)
    expect(view.get('.ai-citations a').attributes('href')).toBe('/knowledge/documents/d1?versionId=v2&chunkId=c1')
    expect(view.text()).toContain('访问时重新校验权限')
  })
  it('submits readable spaces selected from the tag-style multiselect', async () => {
    let body: unknown
    stub((url, init) => { if (url.endsWith('/ai/answers')) { body = JSON.parse(String(init.body)); return json({ jobId: 'j1' }, 202) } })
    const { view } = await render()
    const select = view.getComponent(ElSelect)
    expect(select.classes()).toContain('assistant-space-select')
    expect(select.props('multiple')).toBe(true)
    expect(view.find('select#assistant-spaces[multiple]').exists()).toBe(false)
    await select.vm.$emit('update:modelValue', ['s1'])
    await flushPromises()
    await ask(view)
    expect(body).toEqual({ question: '网络故障如何处理？', spaceIds: ['s1'] })
  })
  it('does not submit blank questions and retains the question on conflict', async () => {
    let calls = 0; stub((url) => { if (url.endsWith('/ai/answers')) { calls++; return json({ title: '请求冲突', code: 'IDEMPOTENCY_CONFLICT' }, 409) } })
    const { view } = await render(); await ask(view, '   '); expect(calls).toBe(0); await ask(view); expect(view.text()).toContain('IDEMPOTENCY_CONFLICT'); expect(view.get('#assistant-question').element).toHaveProperty('value', '网络故障如何处理？')
  })
  it('keeps a request key for ambiguous failure and rotates it after question, space or session changes', async () => {
    const keys: (string | null)[] = []
    stub((url, init) => { if (url.endsWith('/ai/answers')) { keys.push(new Headers(init.headers).get('Idempotency-Key')); return Promise.reject(new TypeError('response lost')) } })
    const { view, session } = await render(); await ask(view); await view.get('#assistant-form').trigger('submit'); await flushPromises()
    expect(keys[0]).toBeTruthy(); expect(keys[1]).toBe(keys[0]); await ask(view, '另一问题'); expect(keys[2]).not.toBe(keys[1])
    await view.getComponent(ElSelect).vm.$emit('update:modelValue', ['s1']); await flushPromises(); await view.get('#assistant-form').trigger('submit'); await flushPromises(); expect(keys[3]).not.toBe(keys[2])
    session.token = 'another-session'; await flushPromises(); await ask(view, '另一问题'); expect(keys[4]).not.toBe(keys[3])
  })
  it('refuses evidence-free answers without displaying ungrounded model text', async () => {
    stub(url => url.endsWith('/jobs/j1') ? completed({ ...result, outcome: 'INSUFFICIENT_EVIDENCE', answer: '不得显示的模型文本', citations: [] }) : undefined)
    const { view } = await render(); await ask(view); expect(view.text()).toContain('证据不足'); expect(view.text()).not.toContain('不得显示的模型文本'); expect(view.find('.ai-citations').exists()).toBe(false)
  })
  it('fails closed for an answered knowledge result with no citations', async () => {
    stub(url => url.endsWith('/jobs/j1') ? completed({ ...result, citations: [] }) : undefined)
    const { view } = await render(); await ask(view); expect(view.text()).toContain('证据不足'); expect(view.text()).not.toContain(result.answer)
  })
  it('polls queued/running/retrying jobs with backoff, shows a stable terminal failure and can regenerate', async () => {
    vi.useFakeTimers(); let reads = 0
    stub(url => { if (url.endsWith('/jobs/j1')) { reads++; return json({ id: 'j1', status: reads === 1 ? 'QUEUED' : reads === 2 ? 'RUNNING' : reads === 3 ? 'RETRYING' : 'FAILED', errorCode: reads >= 3 ? 'AI_PROVIDER_UNAVAILABLE' : null, attemptCount: reads, result: null }) } })
    const { view } = await render(); await ask(view); expect(view.text()).toContain('排队中'); await vi.advanceTimersByTimeAsync(1000); await flushPromises(); expect(view.text()).toContain('生成中')
    await vi.advanceTimersByTimeAsync(2000); await flushPromises(); expect(view.text()).toContain('自动重试'); await vi.advanceTimersByTimeAsync(4000); await flushPromises(); expect(view.text()).toContain('AI_PROVIDER_UNAVAILABLE')
    const count = reads; await vi.advanceTimersByTimeAsync(10000); expect(reads).toBe(count); expect(view.get('#assistant-question').element).toHaveProperty('value', '网络故障如何处理？')
  })
  it.each([403, 404])('clears an existing answer and ignores a delayed poll after authoritative %s feedback', async status => {
    let release!: (value: Response) => void
    let reads = 0
    stub(url => { if (url.endsWith('/jobs/j1')) { reads++; return reads === 1 ? completed() : new Promise<Response>(resolve => { release = resolve }) }; if (url.endsWith('/feedback')) return json({ title: '来源已不可用', code: 'SOURCE_UNAVAILABLE' }, status) })
    const { view } = await render(); await ask(view); await click(view, '刷新结果'); await click(view, '采纳建议'); expect(view.text()).not.toContain(result.answer); expect(view.text()).toContain('SOURCE_UNAVAILABLE')
    release(completed()); await flushPromises(); expect(view.text()).not.toContain(result.answer)
  })
  it('ignores a prior session response and clears private content on logout', async () => {
    let release!: (value: Response) => void; stub(url => url.endsWith('/jobs/j1') ? new Promise<Response>(resolve => { release = resolve }) : undefined)
    const { view, session } = await render(); await ask(view); session.clear(); await flushPromises(); release(completed()); await flushPromises(); expect(view.text()).not.toContain(result.answer); expect(view.get('#assistant-question').element).toHaveProperty('value', '')
  })
  it('cancels stale jobs when the question changes and stops polls after unmount', async () => {
    vi.useFakeTimers(); let reads = 0; stub(url => { if (url.endsWith('/jobs/j1')) { reads++; return json({ id: 'j1', status: 'RUNNING', errorCode: null, attemptCount: 1, result: null }) } })
    const { view } = await render(); await ask(view); await view.get('#assistant-question').setValue('改问其他问题'); await vi.advanceTimersByTimeAsync(20000); expect(reads).toBe(1)
    await view.get('#assistant-form').trigger('submit'); await flushPromises(); view.unmount(); wrapper = undefined; const count = reads; await vi.advanceTimersByTimeAsync(20000); expect(reads).toBe(count)
  })
  it('shows external-provider disclosure before submitting and records feedback without storing answer bodies', async () => {
    let feedback: unknown; stub((url, init) => { if (url.endsWith('/ai/config')) return json({ mode: 'openai', profile: 'provider:configured', externalProvider: true }); if (url.endsWith('/feedback')) { feedback = JSON.parse(String(init.body)); return new Response(null, { status: 204 }) } })
    const { view } = await render(); expect(view.text()).toContain('第三方'); await ask(view); await click(view, '拒绝建议'); expect(feedback).toEqual({ feedback: 'REJECTED' }); expect(view.text()).toContain('已拒绝'); expect(localStorage.length).toBe(0); expect(sessionStorage.length).toBe(0)
  })
  it('bounds polling and offers explicit resume without resubmitting a job', async () => {
    vi.useFakeTimers(); let submissions = 0; let reads = 0
    stub((url) => { if (url.endsWith('/ai/answers')) { submissions++; return json({ jobId: 'j1' }, 202) }; if (url.endsWith('/jobs/j1')) { reads++; return json({ id: 'j1', status: 'RUNNING', errorCode: null, attemptCount: 1, result: null }) } })
    const { view } = await render(); await ask(view); await vi.advanceTimersByTimeAsync(600000); await flushPromises(); expect(view.text()).toContain('等待时间较长'); const count = reads; await vi.advanceTimersByTimeAsync(60000); expect(reads).toBe(count)
    await click(view, '继续查询'); expect(reads).toBe(count + 1); expect(submissions).toBe(1)
  })
  it('times out a hung job read and resumes the same job without another submission', async () => {
    vi.useFakeTimers(); let reads = 0; let submissions = 0
    stub(url => { if (url.endsWith('/ai/answers')) { submissions++; return json({ jobId: 'j1' }, 202) }; if (url.endsWith('/jobs/j1')) { reads++; return reads === 1 ? new Promise<Response>(() => {}) : completed() } })
    const { view } = await render(); await ask(view); await vi.advanceTimersByTimeAsync(20000); await flushPromises(); expect(view.text()).toContain('AI_REQUEST_TIMEOUT')
    await vi.advanceTimersByTimeAsync(1000); await flushPromises(); expect(view.text()).toContain(result.answer); expect(submissions).toBe(1)
  })
})

describe('ticket AI suggestions', () => {
  it('keeps conversation central with independently collapsible properties and copilot', async () => {
    stub(); const { view } = await render(TicketDetailView)
    expect(view.find('.detail-main').exists()).toBe(true)
    expect(view.find('.detail-properties-column').exists()).toBe(true)
    expect(view.find('.detail-copilot-column').exists()).toBe(true)
    await click(view, '收起工单信息'); expect(view.get('.detail-grid').classes()).toContain('properties-collapsed')
    await click(view, '展开工单信息'); expect(view.get('.detail-grid').classes()).not.toContain('properties-collapsed')
  })
  it.each([
    { kind: 'REPLY', length: 10001 }, { kind: 'REPLY', length: 11000 },
    { kind: 'SUMMARY', length: 10001 }, { kind: 'SUMMARY', length: 11000 },
  ])('blocks legacy $kind text of $length characters before feedback or replacement', async ({ kind, length }) => {
    const text = '文'.repeat(length); const writes: string[] = []
    stub((url, init) => { if (init.method !== 'GET') writes.push(url); if (url.endsWith('/jobs/j1')) return completed({ ...result, kind, answer: kind === 'REPLY' ? text : null, summary: kind === 'SUMMARY' ? text : null, citations: kind === 'REPLY' ? [citation] : [] }) })
    const { view } = await render(TicketDetailView); await view.get('#comment-body').setValue('保留我的原稿'); await generate(view, kind)
    expect(view.get('.ai-answer').text()).toBe(text)
    await click(view, '采纳建议')
    expect(view.text()).toContain('超过公开回复的 10,000 字符上限，请重新生成')
    expect(view.find('[role="alertdialog"]').exists()).toBe(false)
    expect(view.get('#comment-body').element).toHaveProperty('value', '保留我的原稿')
    expect(writes).toEqual(['/api/v1/tickets/t1/ai-drafts'])
    await click(view, '拒绝建议')
    expect(view.text()).toContain('已拒绝')
    expect(writes).toEqual(['/api/v1/tickets/t1/ai-drafts', '/api/v1/ai/suggestions/a1/feedback'])
  })
  it.each(['REPLY', 'SUMMARY'])('accepts a 10000-character %s intact and sends only after manual submit', async kind => {
    const text = '文'.repeat(10000); const writes: Array<{ url: string; body: unknown }> = []
    stub((url, init) => {
      if (init.method !== 'GET') writes.push({ url, body: init.body ? JSON.parse(String(init.body)) : null })
      if (url.endsWith('/jobs/j1')) return completed({ ...result, kind, answer: kind === 'REPLY' ? text : null, summary: kind === 'SUMMARY' ? text : null, citations: kind === 'REPLY' ? [citation] : [] })
      if (url.endsWith('/comments')) return json({}, 201)
    })
    const { view } = await render(TicketDetailView); await view.get('#comment-body').setValue('原稿'); await generate(view, kind); await click(view, '采纳建议'); await click(view, '确认替换')
    expect(view.get('#comment-body').element).toHaveProperty('value', text)
    expect(writes.map(write => write.url)).toEqual(['/api/v1/tickets/t1/ai-drafts', '/api/v1/ai/suggestions/a1/feedback'])
    await view.get('.reply-form').trigger('submit'); await flushPromises()
    expect(writes.at(-1)).toEqual({ url: '/api/v1/tickets/t1/comments', body: { visibility: 'PUBLIC_REPLY', body: text } })
    expect(view.text()).toContain('公开回复已发送。')
  })
  it('records acceptance before filling the public reply draft and never sends a ticket mutation automatically', async () => {
    const writes: string[] = []; let release!: (value: Response) => void
    stub((url, init) => { if (init.method !== 'GET') writes.push(url); if (url.endsWith('/jobs/j1')) return completed({ ...result, kind: 'REPLY' }); if (url.endsWith('/feedback')) return new Promise<Response>(resolve => { release = resolve }) })
    const { view } = await render(TicketDetailView); await generate(view); await click(view, '采纳建议'); expect(view.get('#comment-body').element).toHaveProperty('value', '')
    release(new Response(null, { status: 204 })); await flushPromises(); expect(view.get('#comment-body').element).toHaveProperty('value', result.answer); expect(writes).toEqual(['/api/v1/tickets/t1/ai-drafts', '/api/v1/ai/suggestions/a1/feedback'])
  })
  it('confirms replacement of a nonempty public draft, including after the user typed while generating', async () => {
    stub(url => url.endsWith('/jobs/j1') ? completed({ ...result, kind: 'SUMMARY', answer: null, summary: '公开工单摘要', citations: [] }) : undefined)
    const { view } = await render(TicketDetailView); await generate(view, 'SUMMARY'); await view.get('#comment-body').setValue('我的原稿'); await click(view, '采纳建议')
    expect(view.get('#comment-body').element).toHaveProperty('value', '我的原稿'); expect(view.text()).toContain('确认替换'); await click(view, '保留原稿'); expect(view.get('#comment-body').element).toHaveProperty('value', '我的原稿')
    await click(view, '采纳建议'); await click(view, '确认替换'); expect(view.get('#comment-body').element).toHaveProperty('value', '公开工单摘要'); expect(view.text()).toContain('仅依据工单公开内容，未引用知识库')
  })
  it('prefills classification only with canEdit, confirms current values and leaves the service as a suggestion', async () => {
    const writes: string[] = []
    stub((url, init) => { if (init.method !== 'GET') writes.push(url); if (url.endsWith('/tickets/t1')) return json({ ...ticket, capabilities: { ...ticket.capabilities, canEdit: true } }); if (url.endsWith('/jobs/j1')) return completed({ ...result, kind: 'CLASSIFICATION', answer: null, serviceId: 'svc1', priority: 'HIGH', tags: ['网络'], citations: [] }) })
    const { view } = await render(TicketDetailView); await generate(view, 'CLASSIFICATION'); expect(view.text()).toContain('网络服务'); await click(view, '采纳建议'); expect(view.get('#edit-priority').element).toHaveProperty('value', 'NORMAL'); await click(view, '确认替换')
    expect(view.get('#edit-priority').element).toHaveProperty('value', 'HIGH'); expect(view.get('#edit-tags').element).toHaveProperty('value', '网络'); expect(writes.some(url => !url.endsWith('ai-drafts') && !url.endsWith('feedback'))).toBe(false)
  })
  it('allows classification review without edit permission but does not create editable fields', async () => {
    stub(url => url.endsWith('/jobs/j1') ? completed({ ...result, kind: 'CLASSIFICATION', answer: null, priority: 'HIGH', tags: ['网络'], citations: [] }) : undefined)
    const { view } = await render(TicketDetailView); await generate(view, 'CLASSIFICATION'); await click(view, '采纳建议'); expect(view.text()).toContain('已采纳'); expect(view.find('#edit-priority').exists()).toBe(false); expect(view.get('#comment-body').element).toHaveProperty('value', '')
  })
  it('preserves an existing public draft if feedback fails or conflicts', async () => {
    stub(url => url.endsWith('/jobs/j1') ? completed({ ...result, kind: 'REPLY' }) : url.endsWith('/feedback') ? json({ title: '反馈冲突', code: 'FEEDBACK_CONFLICT' }, 409) : undefined)
    const { view } = await render(TicketDetailView); await view.get('#comment-body').setValue('保留我的回复'); await generate(view); await click(view, '采纳建议'); await click(view, '确认替换'); expect(view.get('#comment-body').element).toHaveProperty('value', '保留我的回复'); expect(view.text()).toContain('FEEDBACK_CONFLICT')
  })
  it('hides suggestions when public comment permission is absent', async () => {
    stub(url => url.endsWith('/tickets/t1') ? json({ ...ticket, capabilities: { ...ticket.capabilities, canCommentPublic: false } }) : undefined)
    const { view } = await render(TicketDetailView); expect(view.find('#ai-kind').exists()).toBe(false)
  })
  it('clears AI content after the parent ticket refresh returns hidden-object 404', async () => {
    let revoked = false; stub(url => url.endsWith('/jobs/j1') ? completed({ ...result, kind: 'REPLY' }) : url.endsWith('/tickets/t1') && revoked ? json({ title: '工单不存在', code: 'TICKET_NOT_FOUND' }, 404) : undefined)
    const { view } = await render(TicketDetailView); await generate(view); expect(view.text()).toContain(result.answer); revoked = true; await click(view, '刷新工单'); expect(view.text()).not.toContain(result.answer)
  })
  it('clears cached AI content if the ticket public-comment endpoint authoritatively denies access', async () => {
    stub((url, init) => url.endsWith('/jobs/j1') ? completed({ ...result, kind: 'REPLY' }) : url.endsWith('/comments') && init.method === 'POST' ? json({ title: '工单不存在', code: 'TICKET_NOT_FOUND' }, 404) : undefined)
    const { view } = await render(TicketDetailView); await generate(view); await view.get('#comment-body').setValue('公开回复'); await view.get('.reply-form').trigger('submit'); await flushPromises(); expect(view.text()).not.toContain(result.answer)
  })
  it('does not resurrect a prior-session ticket or AI panel after its delayed read completes', async () => {
    let release!: (value: Response) => void; stub(url => url.endsWith('/tickets/t1') ? new Promise<Response>(resolve => { release = resolve }) : undefined)
    const { view, session } = await render(TicketDetailView); session.clear(); await flushPromises(); release(json(ticket)); await flushPromises(); expect(view.find('#ai-kind').exists()).toBe(false); expect(view.text()).not.toContain(ticket.description)
  })
  it.each([
    { first: 'REPLY', second: 'CLASSIFICATION', status: 403, manualReply: '', manualPriority: '', manualTags: '' },
    { first: 'CLASSIFICATION', second: 'REPLY', status: 404, manualReply: '', manualPriority: '', manualTags: '' },
    { first: 'CLASSIFICATION', second: 'REPLY', status: 403, manualReply: '人工回复', manualPriority: '', manualTags: '人工标签' },
    { first: 'REPLY', second: 'CLASSIFICATION', status: 404, manualReply: '', manualPriority: 'URGENT', manualTags: '' },
  ])('clears independently accepted $first/$second destinations after $status, preserving independently edited inputs', async ({ first, second, status, manualReply, manualPriority, manualTags }) => {
    let kind = 'REPLY'; let revoked = false
    stub((url, init) => {
      if (url.endsWith('/tickets/t1')) return json({ ...ticket, capabilities: { ...ticket.capabilities, canEdit: true } })
      if (url.endsWith('/ai-drafts')) { kind = JSON.parse(String(init.body)).kind; return json({ jobId: 'j1' }, 202) }
      if (url.endsWith('/jobs/j1')) return revoked ? json({ title: '来源已不可用', code: 'SOURCE_UNAVAILABLE' }, status) : completed(kind === 'CLASSIFICATION' ? { ...result, suggestionId: 'classification', kind, answer: null, priority: 'HIGH', tags: ['网络'], citations: [] } : { ...result, suggestionId: 'reply', kind })
    })
    const { view } = await render(TicketDetailView)
    for (const selected of [first, second]) { await generate(view, selected); await click(view, '采纳建议'); if (selected === 'CLASSIFICATION') await click(view, '确认替换') }
    expect(view.get('#comment-body').element).toHaveProperty('value', result.answer); expect(view.get('#edit-priority').element).toHaveProperty('value', 'HIGH'); expect(view.get('#edit-tags').element).toHaveProperty('value', '网络')
    if (manualReply) await view.get('#comment-body').setValue(manualReply)
    if (manualPriority) await view.get('#edit-priority').setValue(manualPriority)
    if (manualTags) await view.get('#edit-tags').setValue(manualTags)
    revoked = true; await click(view, '刷新结果')
    expect(view.find('.ai-result').exists()).toBe(false); expect(view.find('.ai-citations').exists()).toBe(false)
    expect(view.get('#comment-body').element).toHaveProperty('value', manualReply)
    expect(view.get('#edit-priority').element).toHaveProperty('value', manualPriority || 'NORMAL')
    expect(view.get('#edit-tags').element).toHaveProperty('value', manualTags)
  })
})
