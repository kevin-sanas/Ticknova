import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import type { Component } from 'vue'
import { api, bindApiSession, createApi } from '../src/api'
import { useSessionStore } from '../src/stores/session'

const space = { id: 's1', name: '运维知识', active: true, canRead: true, canEdit: true, canManageMembers: true, canReview: false }
const summary = { id: 'd1', spaceId: 's1', title: '操作手册', publicationState: 'PUBLISHED', latestVersionId: 'v2', publishedVersionId: 'v1', latestIngestState: 'READY', versionCount: 2, createdAt: '2026-10-20T01:00:00Z', canEdit: true }
const version = { reviewState: 'APPROVED', authorId: 'u2', reviewerId: 'u3', reviewReason: null, reviewedAt: null, canRequestReview: false, canReview: false, canPublish: true, id: 'v2', versionNo: 2, fileName: 'guide.md', mimeType: 'text/markdown', sha256: 'abc', ingestState: 'READY', publicationState: 'DRAFT', errorCode: null, createdAt: summary.createdAt, jobId: 'j1', jobStatus: 'COMPLETED', attemptCount: 1, chunkCount: 2 }
const detail = { ...summary, allowedActions: ['EDIT_DRAFT', 'UPLOAD_VERSION', 'DISABLE', 'ARCHIVE'], versions: [{ ...version, allowedActions: ['PUBLISH'] }] }
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status })
let wrapper: VueWrapper | undefined
Object.defineProperty(Element.prototype, 'scrollIntoView', { configurable: true, value: () => {} })
afterEach(() => { wrapper?.unmount(); document.querySelectorAll('.el-overlay').forEach(element => element.remove()); wrapper = undefined; vi.useRealTimers(); vi.unstubAllGlobals(); document.body.innerHTML = '' })
async function render(roles = ['KB_EDITOR'], renderErrors?: unknown[], reference?: Record<string, string>, destination?: string) {
  const modules = import.meta.glob('../src/views/Knowledge*.vue')
  const load = async (name: string) => (await modules[`../src/views/${name}.vue`]!() as { default: Component }).default
  const [list, detailPage, admin, reviews] = await Promise.all(['KnowledgeView', 'KnowledgeDocumentView', 'KnowledgeSpaceAdminView', 'KnowledgeReviewView'].map(load))
  const pinia = createPinia(); const session = useSessionStore(pinia)
  session.accept({ accessToken: 'kb-token', expiresIn: 1800, user: { id: 'u1', username: 'editor', displayName: '编辑', roles, mustChangePassword: false } })
  bindApiSession({ getToken: () => session.token, onUnauthorized: () => session.clear() })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/knowledge', component: list }, { path: '/knowledge/documents/:documentId', component: detailPage }, { path: '/admin/knowledge-spaces', component: admin }, { path: '/knowledge/reviews', component: reviews }] })
  router.beforeEach(to => to.path === '/knowledge' && typeof to.query.documentId === 'string' ? { path: `/knowledge/documents/${to.query.documentId}`, query: { versionId: to.query.versionId, chunkId: to.query.chunkId, tab: to.query.tab } } : true)
  await router.push({ path: destination || '/knowledge', query: reference || {} }); await router.isReady()
  wrapper = mount({ template: '<router-view />' }, { attachTo: document.body, global: { plugins: [pinia, ElementPlus, router], config: renderErrors ? { errorHandler: error => renderErrors.push(error) } : {} } }); await flushPromises()
  return { view: wrapper, session, router }
}
function stub(extra?: (url: string, init: RequestInit) => Response | Promise<Response> | undefined) {
  vi.spyOn(api, 'upload').mockImplementation((path, body, config) => api.multipart(path, body, config))
  vi.stubGlobal('fetch', (url: string, init: RequestInit) => {
    const response = extra?.(url, init); if (response) return Promise.resolve(response)
    if (url.endsWith('/knowledge/spaces')) return Promise.resolve(json([space]))
    if (url.includes('/s1/documents?')) return Promise.resolve(json({ items: [summary], total: 1, page: 0, size: 20 }))
    if (url.endsWith('/documents/d1')) return Promise.resolve(json(detail))
    if (url.endsWith('/members') || url.endsWith('/member-candidates')) return Promise.resolve(json([]))
    if (url.endsWith('/content')) return Promise.resolve(json(null))
    return Promise.resolve(json([]))
  })
}
async function click(view: VueWrapper, label: string) { const button = view.findAll('button, a, [role=tab]').find(button => button.text() === label); const dialogButton = [...document.querySelectorAll<HTMLButtonElement>('.el-message-box button')].find(button => button.textContent?.trim() === label); expect(button || dialogButton, label).toBeDefined(); if (button) await button.trigger('click'); else dialogButton!.click(); await flushPromises() }
async function clickDocumentMenu(view: VueWrapper, label: string) { const more = view.find('button[aria-label="更多文档操作"]'); expect(more.exists(), '更多文档操作').toBe(true); more.element.dispatchEvent(new MouseEvent('click', { bubbles: true, button: 0 })); await vi.waitFor(() => expect(more.attributes('aria-expanded')).toBe('true')); const item = [...document.querySelectorAll<HTMLElement>('.el-dropdown-menu__item')].find(element => element.textContent?.trim() === label); expect(item, label).toBeDefined(); item!.click(); await flushPromises() }
async function open(view: VueWrapper, tab = 'versions') { await click(view, '操作手册'); const target = view.findAll('[role=tab]').find(item => item.text() === (tab === 'overview' ? '文档概览' : '版本历史')); if (target) { await target.trigger('click'); await flushPromises() } }
async function file(view: VueWrapper, selector = '#knowledge-file', name = 'guide.md') { const input = view.get(selector); Object.defineProperty(input.element, 'files', { value: [new File(['# Guide'], name, { type: 'text/markdown' })], configurable: true }); await input.trigger('change') }

describe('knowledge API multipart', () => {
  it('sends native FormData with bearer and browser boundary, preserving 401 handling', async () => {
    const sent: RequestInit[] = []; let expired = 0
    const client = createApi({ getToken: () => 'memory', onUnauthorized: () => expired++, fetcher: async (_, init) => { sent.push(init!); return json({}, 401) } })
    expect(client).toHaveProperty('multipart')
    const body = new FormData(); body.append('title', '手册')
    await expect(client.multipart('/knowledge/spaces/s1/documents', body)).rejects.toMatchObject({ status: 401 })
    expect(sent[0]?.body).toBe(body); expect(new Headers(sent[0]?.headers).get('Content-Type')).toBeNull()
    expect(new Headers(sent[0]?.headers).get('Authorization')).toBe('Bearer memory'); expect(sent[0]?.credentials).toBe('omit'); expect(expired).toBe(1)
  })
})
describe('knowledge management', () => {
  it('opens the pending review queue and keeps approval behind the server capability', async () => {
    const requests: string[] = []
    stub((url, init) => {
      requests.push(url)
      if (url.includes('/knowledge/review-tasks?')) return json({ items: [{ documentId: 'd1', spaceId: 's1', title: '待审核手册', versionId: 'v2', versionNo: 2, fileName: 'guide.md', authorId: 'u2', versionCreatedAt: summary.createdAt }], total: 1, page: 0, size: 20 })
      if (url.endsWith('/documents/d1')) return json({ ...detail, title: '待审核手册', versions: [{ ...version, reviewState: 'PENDING', canReview: true, canPublish: false, allowedActions: ['APPROVE', 'REJECT'] }] })
      if (url.endsWith('/versions/v2/reviews') && init.method === 'POST') return json({})
    })
    const { view } = await render(['KB_REVIEWER'])
    expect(requests.some(url => url.includes('/review-tasks?'))).toBe(false)
    await click(view, '审核待办')
    expect(view.text()).toContain('待审核手册')
    await click(view, '打开审核')
    expect(view.text()).toContain('审核通过')
    await click(view, '审核通过')
    expect(requests.some(url => url.endsWith('/versions/v2/reviews'))).toBe(true)
  })
  it('does not offer the review queue to an administrator without explicit editor role', async () => {
    const requests: string[] = []
    stub(url => { requests.push(url); return undefined })
    const { view } = await render(['ADMIN'])
    expect(view.text()).not.toContain('待我审核')
    expect(requests.some(url => url.includes('/review-tasks?'))).toBe(false)
  })
  it('keeps a pending version without reviewer actions and uses the separate member administration page', async () => {
    stub(url => url.endsWith('/documents/d1') ? json({ ...detail, allowedActions: [], versions: [{ ...version, reviewState: 'PENDING', authorId: 'u1', canReview: false, canPublish: false, hasEligibleReviewer: false, allowedActions: [] }] }) : undefined)
    const { view, router } = await render(['ADMIN']); await open(view)
    expect(view.text()).toContain('待审核'); expect(view.findAll('button').some(button => button.text() === '审核通过')).toBe(false)
    await router.push('/admin/knowledge-spaces'); await flushPromises(); await click(view, '管理成员'); expect(view.find('#knowledge-member-form').exists()).toBe(true)
  })
  it('opens the exact pending version from a reviewer deep link, even with an older published version', async () => {
    stub(url => {
      if (url.includes('/review-tasks?')) return json({ items: [{ documentId: 'd1', spaceId: 's1', title: '待审核手册', versionId: 'v2', versionNo: 2, fileName: 'guide.md', authorId: 'u2', versionCreatedAt: summary.createdAt }], total: 1, page: 0, size: 20 })
      if (url.endsWith('/documents/d1')) return json({ ...detail, versions: [{ ...version, reviewState: 'PENDING', canReview: true, canPublish: false, allowedActions: ['APPROVE', 'REJECT'] }] })
      if (url.endsWith('/v2/chunks')) return json([{ id: 'c2', chunkIndex: 0, pageNo: null, headingPath: null, content: '待审核正文' }])
    })
    const { view, router } = await render(['KB_REVIEWER'], undefined, {})
    await click(view, '审核待办'); await click(view, '打开审核')
    expect(router!.currentRoute.value.path).toBe('/knowledge/documents/d1'); expect(router!.currentRoute.value.query).toMatchObject({ versionId: 'v2', tab: 'versions' })
    await click(view, '解析结果'); expect(view.text()).toContain('待审核正文')
  })
  it('shows an empty reviewer queue and reports a failed queue refresh', async () => {
    let fail = false
    stub(url => url.includes('/review-tasks?') ? fail ? json({ title: '审核待办暂时不可用', status: 503 }, 503) : json({ items: [], total: 0, page: 0, size: 20 }) : undefined)
    const { view } = await render(['KB_REVIEWER'])
    await click(view, '审核待办'); expect(view.text()).toContain('暂无待你审核的版本')
    fail = true; await click(view, '刷新待办'); expect(view.text()).toContain('审核待办暂时不可用')
  })
  it('clears stale review tasks when opening an item reveals revoked document access', async () => {
    stub(url => {
      if (url.includes('/review-tasks?')) return json({ items: [{ documentId: 'd1', spaceId: 's1', title: '待撤权手册', versionId: 'v2', versionNo: 2, fileName: 'guide.md', authorId: 'u2', versionCreatedAt: summary.createdAt }], total: 1, page: 0, size: 20 })
      if (url.endsWith('/documents/d1')) return json({ title: '资源不可访问', status: 404 }, 404)
    })
    const { view } = await render(['KB_REVIEWER'])
    await click(view, '审核待办'); expect(view.text()).toContain('待撤权手册')
    await click(view, '打开审核')
    expect(view.text()).not.toContain('待撤权手册')
    expect(view.find('.knowledge-review-tasks').exists()).toBe(false)
  })
  it('shows readable empty spaces to requesters without editor controls even when server capabilities are true', async () => {
    stub(url => url.includes('/documents?') ? json({ items: [], total: 0, page: 0, size: 20 }) : undefined)
    const { view } = await render(['REQUESTER'])
    expect(view.text()).toContain('运维知识'); expect(view.text()).toContain('此空间暂无文档')
    expect(view.find('#knowledge-file').exists()).toBe(false); expect(view.text()).not.toContain('创建空间')
  })
  it('hides edit controls without server capability and exposes only the current publication to readers', async () => {
    stub(url => url.endsWith('/documents/d1') ? json({ ...detail, allowedActions: [], versions: [{ ...version, id: 'v1', versionNo: 1, allowedActions: [] }] }) : url.endsWith('/knowledge/spaces') ? json([{ ...space, canEdit: false, canManageMembers: false }]) : undefined)
    const { view } = await render(['REQUESTER']); await open(view)
    expect(view.find('#knowledge-version-file').exists()).toBe(false); expect(view.text()).not.toContain('发布此版本'); expect(view.findAll('button').filter(button => button.text() === '查看解析结果')).toHaveLength(1)
  })
  it('lets administrators manage unreadable space configuration without requesting documents', async () => {
    let documentReads = 0
    stub(url => { if (url.includes('/documents')) documentReads++; if (url.endsWith('/knowledge/spaces')) return json([{ ...space, canRead: false }]) })
    const { view } = await render(['ADMIN'], undefined, {}, '/admin/knowledge-spaces'); expect(view.text()).toContain('你未被授予此空间阅读权限'); await click(view, '管理成员'); expect(documentReads).toBe(0); expect(view.find('#knowledge-file').exists()).toBe(false)
  })
  it('retains title and file after 409, uploads multipart and opens processing version while preserving old publication', async () => {
    const uploads: FormData[] = []
    stub((url, init) => {
      if (url.endsWith('/s1/documents') && init.method === 'POST') { uploads.push(init.body as FormData); return uploads.length === 1 ? json({ title: '重复文档', code: 'DUPLICATE_DOCUMENT' }, 409) : json({ documentId: 'd1', versionId: 'v2', jobId: 'j1' }, 202) }
      if (url.endsWith('/documents/d1')) return json({ ...detail, versions: [{ ...version, ingestState: 'EXTRACTING', jobStatus: 'RUNNING' }] })
    })
    const { view } = await render(); await click(view, '新建文档'); await view.get('#knowledge-title').setValue('保留草稿'); await file(view)
    await view.get('#knowledge-upload').trigger('submit'); await flushPromises()
    expect(view.get('#knowledge-title').element).toHaveProperty('value', '保留草稿'); expect(view.text()).toContain('DUPLICATE_DOCUMENT'); expect(view.text()).toContain('guide.md')
    await view.get('#knowledge-upload').trigger('submit'); await flushPromises()
    expect(uploads[1]?.get('title')).toBe('保留草稿'); expect(uploads[1]?.get('file')).toBeInstanceOf(File)
    await click(view, '版本历史'); expect(view.text()).toContain('解析中'); expect(view.text()).toContain('旧版本仍保持发布')
  })
  it('does not show a stale completed progress indicator when retrying a failed upload', async () => {
    stub()
    let attempts = 0; let release!: () => void
    vi.spyOn(api, 'upload').mockImplementation(async (_path, _body, config) => {
      if (++attempts === 1) { config?.onProgress?.(100); throw new TypeError('connection lost') }
      await new Promise<void>(resolve => { release = resolve })
      return { documentId: 'd1', versionId: 'v2', jobId: 'j1' } as never
    })
    const { view } = await render(); await click(view, '新建文档'); await view.get('#knowledge-title').setValue('重试草稿'); await file(view)
    await view.get('#knowledge-upload').trigger('submit'); await flushPromises()
    expect(view.text()).toContain('操作未完成')
    await view.get('#knowledge-upload').trigger('submit'); await flushPromises()
    expect(view.find('.upload-progress').exists()).toBe(false)
    expect(view.get('#knowledge-title').element).toHaveProperty('value', '重试草稿')
    release(); await flushPromises()
  })
  it('rejects unsupported and oversized files before sending uploads', async () => {
    let uploads = 0; stub((_url, init) => { if (init.method === 'POST') uploads++; return undefined })
    const { view } = await render(); await click(view, '新建文档'); await view.get('#knowledge-title').setValue('手册'); await file(view, '#knowledge-file', 'guide.exe')
    await view.get('#knowledge-upload').trigger('submit'); await flushPromises(); expect(view.text()).toContain('仅支持'); expect(uploads).toBe(0)
    const input = view.get('#knowledge-file'); const large = new File(['x'], 'large.txt'); Object.defineProperty(large, 'size', { value: 20 * 1024 * 1024 + 1 }); Object.defineProperty(input.element, 'files', { value: [large], configurable: true }); await input.trigger('change')
    await view.get('#knowledge-upload').trigger('submit'); await flushPromises(); expect(view.text()).toContain('20 MiB'); expect(uploads).toBe(0)
  })
  it('shows stable failure code and retries the failed version', async () => {
    const writes: string[] = []; stub((url, init) => { if (init.method === 'POST') { writes.push(url); return json({ documentId: 'd1', versionId: 'v2', jobId: 'j1' }, 202) }; if (url.endsWith('/documents/d1')) return json({ ...detail, versions: [{ ...version, ingestState: 'FAILED', errorCode: 'PARSER_TIMEOUT', jobStatus: 'FAILED', allowedActions: ['RETRY_PARSE'] }] }) })
    const { view } = await render(); await open(view); expect(view.text()).toContain('PARSER_TIMEOUT'); await click(view, '重试解析'); expect(writes).toContain('/api/v1/knowledge/documents/d1/versions/v2/retry')
  })
  it('shows real failed index state and retries only when the server grants the capability', async () => {
    const writes: string[] = []; let indexState = 'FAILED'
    stub((url, init) => {
      if (url.endsWith('/documents/d1')) return json({ ...detail, versions: [{ ...version, embeddingState: indexState, embeddingErrorCode: indexState === 'FAILED' ? 'AI_PROVIDER_UNAVAILABLE' : null, canRetryIndex: indexState === 'FAILED', allowedActions: indexState === 'FAILED' ? ['RETRY_INDEX'] : [] }] })
      if (url.endsWith('/versions/v2/index/retry') && init.method === 'POST') { writes.push(url); indexState = 'PENDING'; return json({}, 202) }
    })
    const { view } = await render(); await open(view)
    expect(view.text()).toContain('AI_PROVIDER_UNAVAILABLE')
    await click(view, '重试索引')
    expect(writes).toEqual(['/api/v1/knowledge/documents/d1/versions/v2/index/retry'])
    expect(view.text()).toContain('重试已提交')
    expect(view.text()).not.toContain('AI_PROVIDER_UNAVAILABLE')
    expect(view.findAll('button').some(button => button.text() === '重试索引')).toBe(false)
  })
  it('does not offer index retry when the server denies it', async () => {
    stub(url => url.endsWith('/documents/d1') ? json({ ...detail, versions: [{ ...version, embeddingState: 'FAILED', embeddingErrorCode: 'AI_PROVIDER_UNAVAILABLE', canRetryIndex: false }] }) : undefined)
    const { view } = await render(); await open(view)
    expect(view.text()).toContain('AI_PROVIDER_UNAVAILABLE')
    expect(view.findAll('button').some(button => button.text() === '重试索引')).toBe(false)
  })
  it('publishes only READY versions and submits confirmed withdrawal and archive', async () => {
    const writes: string[] = []; stub((url, init) => { if (['POST', 'DELETE'].includes(init.method!)) { writes.push(url); return init.method === 'DELETE' ? new Response(null, { status: 204 }) : json(detail) } })
    const { view } = await render(); await open(view); await click(view, '发布此版本'); expect(writes).toContain('/api/v1/knowledge/documents/d1/versions/v2/publish')
    await click(view, '撤回发布'); expect(writes).not.toContain('/api/v1/knowledge/documents/d1/disable'); await click(view, '确认撤回'); expect(writes).toContain('/api/v1/knowledge/documents/d1/disable')
    await clickDocumentMenu(view, '归档文档'); await click(view, '确认归档'); expect(writes).toContain('/api/v1/knowledge/documents/d1')
  })
  it('uploads a new version without resending a title and explains deduplication', async () => {
    let body: FormData | undefined; stub((url, init) => { if (url.endsWith('/d1/versions') && init.method === 'POST') { body = init.body as FormData; return json({ documentId: 'd1', versionId: 'v2', jobId: 'j1' }, 202) } })
    const { view } = await render(); await open(view); await click(view, '上传新版本'); await file(view, '#knowledge-version-file'); await view.get('#knowledge-version-upload').trigger('submit'); await flushPromises(); expect(body?.get('file')).toBeInstanceOf(File); expect(body?.has('title')).toBe(false); expect(view.text()).toContain('相同文件')
  })
  it('renders parse chunks as inert text and restricts read-only preview to published versions', async () => {
    stub(url => url.endsWith('/chunks') ? json([{ id: 'c1', chunkIndex: 0, pageNo: 1, headingPath: '手册', content: '<img src=x onerror=alert(1)>正文' }]) : undefined)
    const { view } = await render(); await open(view); await click(view, '查看解析结果'); expect(view.text()).toContain('<img src=x onerror=alert(1)>正文'); expect(view.find('.knowledge-chunks img').exists()).toBe(false)
  })
  it('removes an unpublished preview when editor access changes to read only', async () => {
    let revoked = false
    stub(url => url.endsWith('/chunks') ? revoked ? json({ title: '引用不可访问', status: 404 }, 404) : json([{ id: 'c1', chunkIndex: 0, pageNo: null, headingPath: null, content: '未发布内容' }]) : undefined)
    const { view, session } = await render(); await open(view); await click(view, '查看解析结果'); expect(view.text()).toContain('未发布内容'); revoked = true; session.user!.roles = ['REQUESTER']; await flushPromises(); expect(view.text()).not.toContain('未发布内容'); expect(view.find('.knowledge-chunks').exists()).toBe(false)
  })
  it('creates spaces and updates membership using candidates and explicit reason', async () => {
    const writes: { url: string; body: unknown }[] = []
    stub((url, init) => { if (init.method === 'POST') { writes.push({ url, body: JSON.parse(String(init.body)) }); return json(space, 201) }; if (init.method === 'PUT') { writes.push({ url, body: JSON.parse(String(init.body)) }); return new Response(null, { status: 204 }) }; if (url.endsWith('/member-candidates')) return json([{ id: 'u2', username: 'reader', displayName: '阅读成员' }]) })
    const { view } = await render(['ADMIN'], undefined, {}, '/admin/knowledge-spaces'); await click(view, '创建空间'); await view.get('#knowledge-space-name').setValue('新空间'); await view.get('#knowledge-space-create').trigger('submit'); await flushPromises()
    expect(writes[0]).toEqual({ url: '/api/v1/knowledge/spaces', body: { name: '新空间' } })
    await click(view, '管理成员'); await view.get('#knowledge-member-user').setValue('u2'); await view.get('#knowledge-member-reason').setValue('知识访问申请'); await view.get('#knowledge-member-form').trigger('submit'); await flushPromises()
    expect(writes[1]).toEqual({ url: '/api/v1/knowledge/spaces/s1/members/u2', body: { canRead: true, canEdit: false, canReview: false, reason: '知识访问申请' } })
  })
  it('does not show late document details after the space switches', async () => {
    let release!: (value: Response) => void
    stub(url => { if (url.endsWith('/knowledge/spaces')) return json([space, { ...space, id: 's2', name: '其他空间' }]); if (url.endsWith('/documents/d1')) return new Promise<Response>(resolve => { release = resolve }); if (url.includes('/s2/documents?')) return json({ items: [], total: 0, page: 0, size: 20 }) })
    const { view, router } = await render(); await open(view); await router.push({ path: '/knowledge', query: { spaceId: 's2' } }); await flushPromises(); release(json(detail)); await flushPromises(); expect(view.find('.knowledge-detail').exists()).toBe(false); expect(view.text()).toContain('此空间暂无文档')
  })
  it('discards late mutation responses after selecting another document', async () => {
    let release!: (value: Response) => void
    stub((url, init) => { if (init.method === 'POST') return new Promise<Response>(resolve => { release = resolve }); if (url.endsWith('/documents/d2')) return json({ ...detail, id: 'd2', title: '第二篇文档' }) })
    const { view, router } = await render(); await open(view); await click(view, '发布此版本'); await router.push('/knowledge/documents/d2'); await flushPromises(); release(json(detail)); await flushPromises(); expect(view.get('.knowledge-detail h1').text()).toBe('第二篇文档'); expect(view.text()).not.toContain('版本已发布。')
  })
  it('clears previously readable content if refreshed access is denied', async () => {
    let forbidden = false; stub(url => url.endsWith('/documents/d1') && forbidden ? json({ title: '无访问权限', code: 'SPACE_READ_FORBIDDEN' }, 403) : undefined)
    const { view } = await render(); await open(view); await click(view, '查看解析结果'); forbidden = true; await click(view, '刷新状态'); expect(view.find('.knowledge-detail').exists()).toBe(false); expect(view.text()).toContain('SPACE_READ_FORBIDDEN')
  })
  it('clears detail and chunks for the actual hidden-object 404 and stops polling', async () => {
    vi.useFakeTimers(); let revoked = false; let reads = 0
    stub(url => {
      if (url.endsWith('/chunks')) return json([{ id: 'c1', chunkIndex: 0, pageNo: null, headingPath: null, content: '已撤销权限的内容' }])
      if (url.endsWith('/documents/d1')) { reads++; return revoked ? json({ title: '知识不存在', code: 'KNOWLEDGE_NOT_FOUND' }, 404) : json({ ...detail, versions: [version, { ...version, id: 'v3', ingestState: 'EXTRACTING', jobStatus: 'RUNNING' }] }) }
    })
    const { view } = await render(); await open(view); await click(view, '查看解析结果'); expect(view.text()).toContain('已撤销权限的内容'); revoked = true; await click(view, '刷新状态')
    expect(view.find('.knowledge-detail').exists()).toBe(false); expect(view.text()).not.toContain('已撤销权限的内容'); expect(view.text()).toContain('KNOWLEDGE_NOT_FOUND'); const after = reads; await vi.advanceTimersByTimeAsync(9000); expect(reads).toBe(after)
  })
  it('clears cached content and private version upload input on a mutation 404', async () => {
    stub((url, init) => { if (init.method === 'POST') return json({ title: '知识不存在', code: 'KNOWLEDGE_NOT_FOUND' }, 404); if (url.endsWith('/chunks')) return json([{ id: 'c1', chunkIndex: 0, pageNo: null, headingPath: null, content: '私有解析预览' }]) })
    const { view } = await render(); await open(view); await click(view, '查看解析结果'); expect(view.text()).toContain('私有解析预览'); await click(view, '版本历史'); await click(view, '上传新版本'); await file(view, '#knowledge-version-file'); await click(view, '发布此版本')
    expect(view.find('.knowledge-detail').exists()).toBe(false); expect(view.text()).not.toContain('私有解析预览'); expect(view.text()).toContain('KNOWLEDGE_NOT_FOUND'); expect(view.find('#knowledge-version-file').exists()).toBe(false)
  })
  it('clears list metadata after a space document request returns hidden-object 404', async () => {
    let revoked = false; stub(url => url.includes('/s1/documents?') && revoked ? json({ title: '知识不存在', code: 'KNOWLEDGE_NOT_FOUND' }, 404) : undefined)
    const { view } = await render(); revoked = true; await click(view, '刷新文档'); expect(view.find('.knowledge-detail').exists()).toBe(false); expect(view.text()).not.toContain('操作手册'); expect(view.text()).toContain('KNOWLEDGE_NOT_FOUND')
  })
  it('reuses an initial upload key after a lost response and changes it when title or File identity changes', async () => {
    const keys: (string | null)[] = []
    stub((url, init) => { if (url.endsWith('/s1/documents') && init.method === 'POST') { keys.push(new Headers(init.headers).get('Idempotency-Key')); return Promise.reject(new TypeError('response lost')) } })
    const { view } = await render(); await click(view, '新建文档'); await view.get('#knowledge-title').setValue('手册'); await file(view)
    for (let i = 0; i < 2; i++) { await view.get('#knowledge-upload').trigger('submit'); await flushPromises() }
    expect(keys[0]).toBeTruthy(); expect(keys[1]).toBe(keys[0]); await click(view, '新建文档'); await view.get('#knowledge-title').setValue('修订手册'); await view.get('#knowledge-upload').trigger('submit'); await flushPromises(); expect(keys[2]).not.toBe(keys[1])
    await file(view); await view.get('#knowledge-upload').trigger('submit'); await flushPromises(); expect(keys[3]).not.toBe(keys[2])
  })
  it('reuses a space creation key after a lost response and rotates it when the name changes or creation succeeds', async () => {
    const keys: (string | null)[] = []; let succeed = false
    stub((url, init) => { if (url.endsWith('/knowledge/spaces') && init.method === 'POST') { keys.push(new Headers(init.headers).get('Idempotency-Key')); return succeed ? json(space, 201) : Promise.reject(new TypeError('response lost')) } })
    const { view } = await render(['ADMIN'], undefined, {}, '/admin/knowledge-spaces'); await click(view, '创建空间'); await view.get('#knowledge-space-name').setValue('重试空间')
    for (let i = 0; i < 2; i++) { await view.get('#knowledge-space-create').trigger('submit'); await flushPromises() }
    expect(keys[0]).toBeTruthy(); expect(keys[1]).toBe(keys[0]); await view.get('#knowledge-space-name').setValue('改名空间'); await view.get('#knowledge-space-create').trigger('submit'); await flushPromises(); expect(keys[2]).not.toBe(keys[1])
    succeed = true; await view.get('#knowledge-space-create').trigger('submit'); await flushPromises(); expect(keys[3]).toBe(keys[2]); await click(view, '创建空间'); await view.get('#knowledge-space-name').setValue('改名空间'); await view.get('#knowledge-space-create').trigger('submit'); await flushPromises(); expect(keys[4]).not.toBe(keys[3])
  })
  it('resets the initial upload key after success and never carries the old document version file to the new document', async () => {
    const keys: (string | null)[] = []
    stub((url, init) => { if (url.endsWith('/s1/documents') && init.method === 'POST') { keys.push(new Headers(init.headers).get('Idempotency-Key')); return json({ documentId: 'd2', versionId: 'v2', jobId: 'j2' }, 202) }; if (url.endsWith('/documents/d2')) return json({ ...detail, id: 'd2', title: '新文档' }) })
    const { view, router } = await render(); await click(view, '新建文档'); await view.get('#knowledge-title').setValue('新文档'); await file(view); await view.get('#knowledge-upload').trigger('submit'); await flushPromises()
    expect(view.get('.knowledge-detail h1').text()).toBe('新文档'); await click(view, '上传新版本'); await file(view, '#knowledge-version-file', '旧文档草稿.md')
    const navigate = router.push('/knowledge'); await flushPromises(); await click(view, '放弃修改并离开'); await navigate; await flushPromises()
    expect(view.text()).not.toContain('旧文档草稿.md'); await click(view, '新建文档'); await view.get('#knowledge-title').setValue('新文档'); await file(view); await view.get('#knowledge-upload').trigger('submit'); await flushPromises(); expect(keys[0]).toBeTruthy(); expect(keys[1]).not.toBe(keys[0])
  })
  it('limits ACL reasons to the server contract length', async () => {
    stub(); const { view } = await render(['ADMIN'], undefined, {}, '/admin/knowledge-spaces'); await click(view, '管理成员'); expect(view.get('#knowledge-member-reason').attributes('maxlength')).toBe('500')
  })
  it('invalidates a late upload result and clears private content when the session changes', async () => {
    let release!: (value: Response) => void
    stub((url, init) => url.endsWith('/s1/documents') && init.method === 'POST' ? new Promise<Response>(resolve => { release = resolve }) : undefined)
    const { view, session } = await render(); await click(view, '新建文档'); await view.get('#knowledge-title').setValue('私有草稿'); await file(view); await view.get('#knowledge-upload').trigger('submit'); await flushPromises(); session.clear(); await flushPromises(); release(json({ documentId: 'd1', versionId: 'v2', jobId: 'j1' }, 202)); await flushPromises(); expect(view.text()).not.toContain('操作手册'); expect(view.text()).not.toContain('私有草稿'); expect(view.find('.knowledge-detail').exists()).toBe(false)
  })
  it('polls processing detail and stops after unmount', async () => {
    vi.useFakeTimers(); let reads = 0
    stub(url => { if (url.endsWith('/documents/d1')) { reads++; return json({ ...detail, versions: [{ ...version, ingestState: reads === 1 ? 'CHUNKING' : 'READY' }] }) } })
    const { view } = await render(); await open(view); expect(view.text()).toContain('分块中'); await vi.advanceTimersByTimeAsync(3000); await flushPromises(); expect(reads).toBe(2); expect(view.text()).toContain('解析就绪'); view.unmount(); wrapper = undefined; await vi.advanceTimersByTimeAsync(6000); expect(reads).toBe(2)
  })
  it('keeps polling a failed extraction while its job has an automatic retry scheduled', async () => {
    vi.useFakeTimers(); let reads = 0
    stub(url => { if (url.endsWith('/documents/d1')) { reads++; return json({ ...detail, versions: [{ ...version, ingestState: reads === 1 ? 'FAILED' : 'READY', jobStatus: reads === 1 ? 'RETRYING' : 'COMPLETED' }] }) } })
    const { view } = await render(); await open(view); await vi.advanceTimersByTimeAsync(3000); await flushPromises(); expect(reads).toBe(2); expect(view.text()).toContain('解析就绪')
  })
})


describe('knowledge editorial workflow', () => {
  it('locates an authorized citation only inside its exact requested version', async () => {
    const scroll = vi.spyOn(Element.prototype, 'scrollIntoView')
    stub(url => url.endsWith('/documents/d1') ? json({ ...detail, versions: [{ ...version, id: 'v1', versionNo: 1 }] }) : url.endsWith('/chunks') ? json([{ id: 'c1', chunkIndex: 0, pageNo: 1, headingPath: '引用章节', content: '引用来源内容' }]) : undefined)
    const { view } = await render(['REQUESTER'], undefined, { documentId: 'd1', versionId: 'v1', chunkId: 'c1' }); const chunk = view.get('#knowledge-source-c1'); expect(view.get('.knowledge-chunks').isVisible()).toBe(true); expect(document.activeElement).toBe(chunk.element); expect(scroll).toHaveBeenCalled(); expect(view.text()).toContain('已定位引用片段')
  })
  it.each([{ versionId: 'not-authorized', chunkId: 'c1' }, { versionId: 'v1', chunkId: 'missing' }])('does not substitute another source when the citation is unavailable: %s', async reference => {
    stub(url => url.endsWith('/documents/d1') ? json({ ...detail, versions: [{ ...version, id: 'v1', versionNo: 1 }] }) : url.endsWith('/chunks') ? json([{ id: 'c1', chunkIndex: 0, pageNo: 1, headingPath: null, content: '不能冒充目标引用的正文' }]) : undefined)
    const { view } = await render(['REQUESTER'], undefined, { documentId: 'd1', ...reference }); expect(view.text()).toContain('引用已不可用'); expect(view.text()).toContain('重新生成'); expect(view.find('.knowledge-reader').exists()).toBe(false); expect(view.text()).not.toContain('不能冒充目标引用的正文')
  })
  it('removes the previous citation preview if the requested source later returns hidden-object denial', async () => {
    let denied = false
    stub(url => url.endsWith('/documents/d1') ? json({ ...detail, versions: [{ ...version, id: 'v1', versionNo: 1 }] }) : url.endsWith('/chunks') ? denied ? json({ title: '知识不存在', code: 'KNOWLEDGE_NOT_FOUND' }, 404) : json([{ id: 'c1', chunkIndex: 0, pageNo: 1, headingPath: null, content: '此前授权引用内容' }]) : undefined)
    const { view, router } = await render(['REQUESTER'], undefined, { documentId: 'd1', versionId: 'v1', chunkId: 'c1' }); expect(view.text()).toContain('此前授权引用内容'); denied = true; await router!.push({ query: { documentId: 'd1', versionId: 'v1', chunkId: 'c2' } }); await flushPromises(); expect(view.text()).toContain('引用已不可用'); expect(view.text()).not.toContain('此前授权引用内容'); expect(view.find('.knowledge-reader').exists()).toBe(false)
  })
  it('reads an authorized uploaded file longer than the article authoring cap without render errors', async () => {
    const text = '完整文件正文'.repeat(18000); const errors: unknown[] = []
    stub(url => url.endsWith('/documents/d1') ? json({ ...detail, versions: [{ ...version, id: 'v1' }] }) : url.endsWith('/chunks') ? json([{ id: 'c1', chunkIndex: 0, pageNo: 1, headingPath: '长文件', content: text }]) : undefined)
    const { view } = await render(['KB_EDITOR'], errors); await open(view, 'overview'); expect(errors).toEqual([]); expect(view.get('.knowledge-reader').text()).toContain(text); await click(view, '解析结果'); expect(view.get('.knowledge-chunks').text()).toContain(text)
  })
  it.each([403, 404])('purges category tree and selected server-derived edit form after document denial %s', async status => {
    let denied = false
    stub(url => url.endsWith('/categories') ? json([{ id: 'c1', spaceId: 's1', parentId: null, name: '受限分类名称', version: 1 }]) : url.includes('/s1/documents?') && denied ? json({ title: '访问已撤销', code: 'KNOWLEDGE_NOT_FOUND' }, status) : undefined)
    const { view } = await render(); await click(view, '管理分类'); await view.get('#category-edit-id').setValue('c1'); expect(view.get('#category-name').element).toHaveProperty('value', '受限分类名称'); denied = true; await click(view, '刷新文档'); expect(view.find('.knowledge-categories').exists()).toBe(false); expect(view.find('#category-name').exists()).toBe(false); expect(view.text()).not.toContain('受限分类名称')
  })
  it('fences a category read that settles after a fresh document access denial', async () => {
    let release!: (value: Response) => void; let denied = false
    stub(url => url.endsWith('/categories') ? new Promise(resolve => { release = resolve }) : url.includes('/s1/documents?') && denied ? json({ title: '访问已撤销', code: 'KNOWLEDGE_NOT_FOUND' }, 404) : undefined)
    const { view } = await render(); denied = true; await click(view, '刷新文档'); release(json([{ id: 'c1', spaceId: 's1', parentId: null, name: '迟到的私有分类', version: 1 }])); await flushPromises(); expect(view.find('.knowledge-categories').exists()).toBe(false); expect(view.text()).not.toContain('迟到的私有分类')
  })
  it('purges selected category form metadata after its own category mutation denial', async () => {
    stub((url, init) => url.endsWith('/categories') ? json([{ id: 'c1', spaceId: 's1', parentId: null, name: '不可再编辑分类', version: 1 }]) : init.method === 'PATCH' ? json({ title: '分类不存在', code: 'KNOWLEDGE_NOT_FOUND' }, 404) : undefined)
    const { view } = await render(); await click(view, '新建文档'); await view.get('#knowledge-title').setValue('独立未上传草稿'); await file(view); await click(view, '管理分类'); await view.get('#category-edit-id').setValue('c1'); await view.get('#category-form').trigger('submit'); await flushPromises(); expect(view.find('.knowledge-categories').exists()).toBe(false); expect(view.find('#category-name').exists()).toBe(false); expect(view.text()).not.toContain('不可再编辑分类'); expect(view.find('#knowledge-title').exists()).toBe(false); expect(view.text()).not.toContain('独立未上传草稿')
  })
  it('reads the immutable rich version with the same safe renderer as editor preview', async () => {
    stub(url => url.endsWith('/documents/d1') ? json({ ...detail, versions: [{ ...version, id: 'v1' }] }) : url.endsWith('/content') ? json({ type: 'doc', content: [{ type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: '准确目录' }] }, { type: 'paragraph', content: [{ type: 'text', text: '带下划线的原稿', marks: [{ type: 'underline' }] }] }] }) : url.endsWith('/chunks') ? json([{ id: 'c1', chunkIndex: 0, pageNo: null, headingPath: null, content: '不同的纯文本来源' }]) : undefined)
    const { view } = await render(); await open(view, 'overview'); expect(view.get('.knowledge-reader u').text()).toBe('带下划线的原稿'); expect(view.get('.article-outline a').text()).toBe('准确目录'); expect(view.get('.knowledge-reader').text()).not.toContain('不同的纯文本来源')
  })
  it('clears a previously loaded rich reader when the version content access is revoked', async () => {
    let revoked = false
    stub(url => url.endsWith('/documents/d1') ? json({ ...detail, versions: [{ ...version, id: 'v1' }] }) : url.endsWith('/content') ? revoked ? json({ title: '知识不存在', code: 'KNOWLEDGE_NOT_FOUND' }, 404) : json({ type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: '授权富文本' }] }] }) : undefined)
    const { view } = await render(); await open(view, 'overview'); expect(view.text()).toContain('授权富文本'); revoked = true; await click(view, '刷新状态'); expect(view.find('.knowledge-reader').exists()).toBe(false); expect(view.text()).toContain('KNOWLEDGE_NOT_FOUND')
  })
  it('sends the selected category version when confirming its deletion', async () => {
    let deleted: unknown
    stub((url, init) => { if (url.endsWith('/categories')) return json([{ id: 'c1', spaceId: 's1', parentId: null, name: '待删除分类', version: 3 }]); if (init.method === 'DELETE') { deleted = init.body ? JSON.parse(String(init.body)) : null; return new Response(null, { status: 204 }) } })
    const { view } = await render(); await click(view, '管理分类'); await view.get('#category-edit-id').setValue('c1'); await click(view, '删除分类'); await click(view, '确认删除分类'); expect(deleted).toEqual({ version: 3 })
  })
  it('filters by nested category and performs versioned category rename', async () => {
    const paths: string[] = []; const writes: unknown[] = []
    stub((url, init) => { paths.push(url); if (url.endsWith('/categories')) return json([{ id: 'c1', spaceId: 's1', parentId: null, name: '设备帮助', version: 2 }, { id: 'c2', spaceId: 's1', parentId: 'c1', name: '网络', version: 1 }]); if (init.method === 'PATCH') { writes.push(JSON.parse(String(init.body))); return json({}) } })
    const { view } = await render(); await click(view, '网络'); expect(paths.some(path => path.includes('categoryId=c2'))).toBe(true)
    await click(view, '管理分类'); await view.get('#category-edit-id').setValue('c1'); await view.get('#category-name').setValue('设备与网络'); await view.get('#category-form').trigger('submit'); await flushPromises(); expect(writes).toEqual([{ name: '设备与网络', parentId: null, version: 2 }])
  })
  it('requires server review capabilities and sends a real rejection reason', async () => {
    const writes: unknown[] = []
    stub((url, init) => { if (init.method === 'POST') { writes.push({ url, body: init.body ? JSON.parse(String(init.body)) : undefined }); return json(detail) }; if (url.endsWith('/documents/d1')) return json({ ...detail, versions: [{ ...version, reviewState: 'PENDING', canPublish: false, canReview: true, allowedActions: ['APPROVE', 'REJECT'] }] }) })
    const { view } = await render(); await open(view); expect(view.text()).not.toContain('发布此版本'); await click(view, '审核不通过'); await view.get('#review-reason').setValue('缺少关键排查步骤'); await view.get('#review-reject-form').trigger('submit'); await flushPromises(); expect(writes).toContainEqual({ url: '/api/v1/knowledge/documents/d1/versions/v2/reviews', body: { decision: 'REJECTED', reason: '缺少关键排查步骤' } })
  })
  it('renders an authorized publication as a readable article with a navigable outline', async () => {
    stub(url => url.endsWith('/documents/d1') ? json({ ...detail, versions: [{ ...version, id: 'v1', versionNo: 1 }] }) : url.endsWith('/chunks') ? json([{ id: 'c1', chunkIndex: 0, pageNo: null, headingPath: '连接设置', content: '可以阅读的正文' }]) : undefined)
    const { view } = await render(); await open(view, 'overview'); expect(view.get('.knowledge-reader').text()).toContain('可以阅读的正文'); expect(view.get('.article-outline a').attributes('href')).toBe('#article-heading-0')
  })
})

describe('portal category route receiver', () => {
  const otherSpace = { ...space, id: 's2', name: '员工支持知识' }
  const hiddenSpace = { ...space, id: 's-private', name: '受限空间', canRead: false, canEdit: false, canManageMembers: false }
  const categoryOne = { id: 'cat-one', spaceId: 's1', parentId: null, name: '办公设备', version: 1 }
  const categoryTwo = { id: 'cat-two', spaceId: 's2', parentId: null, name: '网络常见问题', version: 1 }
  const firstDocument = { ...summary, title: '第一空间分类文档', categoryId: 'cat-one' }
  const secondDocument = { ...summary, id: 'd2', spaceId: 's2', title: '第二空间分类文档', categoryId: 'cat-two' }
  function portalFixture() {
    const reads: string[] = []
    stub((url, init) => {
      if (init.method === 'GET') reads.push(url)
      if (url.endsWith('/knowledge/spaces')) return json([space, otherSpace, hiddenSpace])
      if (url.endsWith('/s1/categories')) return json([categoryOne])
      if (url.endsWith('/s2/categories')) return json([categoryTwo])
      if (url.includes('/s-private/')) return json({ title: '拒绝访问', code: 'KNOWLEDGE_NOT_FOUND' }, 404)
      if (url.includes('/s1/documents?')) return json({ items: [firstDocument], total: 1, page: 0, size: 20 })
      if (url.includes('/s2/documents?')) return json({ items: [url.includes('categoryId=cat-two') ? secondDocument : { ...secondDocument, title: '第二空间未筛选结果' }], total: 1, page: 0, size: 20 })
      if (url.endsWith('/documents/d1')) return json({ ...detail, versions: [{ ...version, id: 'v1', versionNo: 1 }] })
      if (url.endsWith('/chunks')) return json([{ id: 'citation-one', chunkIndex: 0, pageNo: 1, headingPath: '准确引用章节', content: '需要清理的引用来源正文' }])
      return undefined
    })
    return reads
  }
  it('mounts a portal category link in the second readable space and applies that category before listing', async () => {
    const reads = portalFixture()
    const { view } = await render(['KB_EDITOR'], undefined, { spaceId: 's2', categoryId: 'cat-two' })
    expect(view.get('#knowledge-space').element).toHaveProperty('value', 's2')
    expect(view.get('.category-tree [aria-current=true]').text()).toBe('网络常见问题')
    expect(view.text()).toContain('第二空间分类文档'); expect(view.text()).not.toContain('第二空间未筛选结果'); expect(view.text()).not.toContain('第一空间分类文档')
    expect(reads.filter(url => url.includes('/documents?'))).toEqual(['/api/v1/knowledge/spaces/s2/documents?page=0&size=20&categoryId=cat-two'])
  })
  it('consumes category route changes on the mounted receiver and replaces the previous space/filter', async () => {
    const reads = portalFixture()
    const { view, router } = await render(['KB_EDITOR'], undefined, { spaceId: 's1', categoryId: 'cat-one' })
    await router!.push({ path: '/knowledge', query: { spaceId: 's2', categoryId: 'cat-two' } }); await flushPromises()
    expect(view.get('#knowledge-space').element).toHaveProperty('value', 's2'); expect(view.get('.category-tree [aria-current=true]').text()).toBe('网络常见问题'); expect(view.text()).toContain('第二空间分类文档'); expect(view.text()).not.toContain('第一空间分类文档')
    expect(reads.filter(url => url.includes('/documents?')).at(-1)).toBe('/api/v1/knowledge/spaces/s2/documents?page=0&size=20&categoryId=cat-two')
  })
  it.each([
    { spaceId: 'not-a-space', categoryId: 'cat-two' },
    { spaceId: 's-private', categoryId: 'private-category' },
    { spaceId: 's2', categoryId: 'not-a-category' },
    { spaceId: 's2', categoryId: 'cat-one' },
  ])('rejects an invalid or unreadable category destination without listing a substitute, then recovers: %s', async destination => {
    const reads = portalFixture(); const { view, router } = await render(['KB_EDITOR'], undefined, destination)
    expect(view.text()).toContain('分类入口不可用'); expect(view.find('.knowledge-reader').exists()).toBe(false); expect(view.text()).not.toContain('第一空间分类文档'); expect(view.text()).not.toContain('第二空间未筛选结果')
    expect(reads.filter(url => url.includes('/documents?'))).toEqual([]); expect(reads.some(url => url.includes('/s-private/'))).toBe(false)
    await router!.push({ path: '/knowledge', query: { spaceId: 's2', categoryId: 'cat-two' } }); await flushPromises(); expect(view.text()).not.toContain('分类入口不可用'); expect(view.text()).toContain('第二空间分类文档'); expect(view.get('#knowledge-space').element).toHaveProperty('value', 's2')
  })
  it('gives an exact document citation precedence over unrelated category route keys', async () => {
    const reads = portalFixture()
    const { view } = await render(['KB_EDITOR'], undefined, { documentId: 'd1', versionId: 'v1', chunkId: 'citation-one', spaceId: 's2', categoryId: 'cat-two' })
    expect(view.find('#knowledge-space').exists()).toBe(false); expect(view.get('.knowledge-reader').text()).toContain('需要清理的引用来源正文'); expect(view.find('#knowledge-source-citation-one').exists()).toBe(true); expect(view.text()).toContain('已定位引用片段'); expect(reads.some(url => url.includes('/s2/documents?'))).toBe(false)
  })
  it('recovers an invalid portal destination through explicit readable-space selection and updates the route', async () => {
    portalFixture()
    const { view, router } = await render(['KB_EDITOR'], undefined, { spaceId: 'not-a-space', categoryId: 'cat-two' })
    expect(view.text()).toContain('分类入口不可用'); await view.get('#knowledge-space').setValue('s2'); await flushPromises(); expect(router!.currentRoute.value.query).toEqual({ spaceId: 's2' }); expect(view.text()).not.toContain('分类入口不可用'); expect(view.text()).toContain('第二空间未筛选结果')
  })
  it('clears the exact citation reader when same-route navigation enters a category list', async () => {
    portalFixture()
    const { view, router } = await render(['KB_EDITOR'], undefined, { documentId: 'd1', versionId: 'v1', chunkId: 'citation-one' }); expect(view.text()).toContain('需要清理的引用来源正文')
    await router!.push({ path: '/knowledge', query: { spaceId: 's2', categoryId: 'cat-two' } }); await flushPromises(); expect(view.find('.knowledge-reader').exists()).toBe(false); expect(view.find('.knowledge-chunks').exists()).toBe(false); expect(view.text()).not.toContain('需要清理的引用来源正文'); expect(view.text()).not.toContain('已定位引用片段'); expect(view.text()).toContain('第二空间分类文档')
  })
  it('clears a category filter before entering an exact citation in the same space', async () => {
    portalFixture()
    const { view, router } = await render(['KB_EDITOR'], undefined, { spaceId: 's1', categoryId: 'cat-one' })
    expect(view.get('.category-tree [aria-current=true]').text()).toBe('办公设备')
    await router!.push({ query: { documentId: 'd1', versionId: 'v1', chunkId: 'citation-one' } }); await flushPromises(); expect(view.find('.knowledge-categories').exists()).toBe(false); expect(view.get('.knowledge-reader').text()).toContain('需要清理的引用来源正文'); expect(view.text()).toContain('已定位引用片段')
  })
})

describe('configuration-only route', () => {
  const configSpace = { ...space, id: 's-config', name: '仅管理成员空间', canRead: false, canEdit: false, canManageMembers: true, canReview: false }
  function configFixture() {
    const requests: { url: string; method: string; body: unknown }[] = []
    let member = { userId: 'u2', username: 'reader', displayName: '待授权成员', canRead: false, canEdit: false }
    stub((url, init) => {
      requests.push({ url, method: init.method || '', body: init.body ? JSON.parse(String(init.body)) : undefined })
      if (url.endsWith('/knowledge/spaces')) return json([space, configSpace])
      if (url.endsWith('/s-config/members')) return json([member])
      if (url.endsWith('/s-config/member-candidates')) return json([{ id: 'u2', username: 'reader', displayName: '待授权成员' }])
      if (url.endsWith('/s-config/members/u2') && init.method === 'PUT') { member = { ...member, canRead: true }; return new Response(null, { status: 204 }) }
      return undefined
    })
    return requests
  }
  function contentRequests(requests: { url: string }[]) { return requests.filter(request => /\/s-config\/(categories|documents)|\/(chunks|content)$/.test(request.url)) }
  it('keeps explicit routed manage-only selection and lets an authorized manager save ACL without content reads', async () => {
    const requests = configFixture(); const { view, router } = await render(['ADMIN'], undefined, {}, '/admin/knowledge-spaces')
    await view.get('#knowledge-space').setValue('s-config'); await flushPromises(); expect(router!.currentRoute.value.query).toEqual({ spaceId: 's-config' }); expect(view.get('#knowledge-space').element).toHaveProperty('value', 's-config'); expect(view.text()).not.toContain('分类入口不可用')
    await click(view, '管理成员'); expect(view.text()).toContain('待授权成员'); await view.get('#knowledge-member-user').setValue('u2'); await view.get('#knowledge-member-reason').setValue('授权空间阅读'); await view.get('#knowledge-member-form').trigger('submit'); await flushPromises()
    expect(requests).toContainEqual({ url: '/api/v1/knowledge/spaces/s-config/members/u2', method: 'PUT', body: { canRead: true, canEdit: false, canReview: false, reason: '授权空间阅读' } }); expect(contentRequests(requests)).toEqual([]); expect(view.find('#knowledge-file').exists()).toBe(false)
  })
  it('restores a direct space-only management route on mount and refresh without requesting categories or documents', async () => {
    const requests = configFixture(); const { view } = await render(['ADMIN'], undefined, { spaceId: 's-config' }, '/admin/knowledge-spaces'); expect(view.get('#knowledge-space').element).toHaveProperty('value', 's-config'); await click(view, '刷新空间'); expect(view.get('#knowledge-space').element).toHaveProperty('value', 's-config'); await click(view, '管理成员'); expect(view.text()).toContain('待授权成员'); expect(contentRequests(requests)).toEqual([]); expect(view.find('.knowledge-categories').exists()).toBe(false); expect(view.find('.knowledge-reader').exists()).toBe(false)
  })
  it('still refuses category content navigation to a manage-only space without reading any content endpoints', async () => {
    const requests = configFixture(); const { view } = await render(['ADMIN'], undefined, { spaceId: 's-config', categoryId: 'private-category' }); expect(view.text()).toContain('分类入口不可用'); expect(view.find('.knowledge-categories').exists()).toBe(false); expect(view.find('.knowledge-reader').exists()).toBe(false); expect(contentRequests(requests)).toEqual([])
  })
})
