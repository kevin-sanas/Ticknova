import { afterEach, expect, it, vi } from 'vitest'
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus from 'element-plus'
import RichTextEditor from '../src/components/RichTextEditor.vue'
import { bindApiSession } from '../src/api'
import { normalizeRichDoc } from '../src/knowledge/rich'
import { useSessionStore } from '../src/stores/session'
import type { Component } from 'vue'
let wrapper: VueWrapper | undefined
// jsdom has no layout geometry; Tiptap still runs its real selection commands.
Object.defineProperty(Range.prototype, 'getClientRects', { configurable: true, value: () => [] })
Object.defineProperty(Range.prototype, 'getBoundingClientRect', { configurable: true, value: () => new DOMRect() })
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.unstubAllGlobals(); vi.useRealTimers(); document.body.innerHTML = '' })
const draft = { id: 'd1', spaceId: 's1', title: '原始标题', categoryId: null, content: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: '文章正文' }] }] }, version: 1, updatedAt: '2026-10-21T01:00:00Z', updatedBy: 'u1' }
const emptyDraft = { ...draft, version: 14, content: { type: 'doc', content: [{ type: 'paragraph' }] } }
const parsedSource = { versionId: 'v1', versionNo: 1, importable: true, reason: null, content: draft.content }
async function render(extra?: (url: string, init: RequestInit) => Response | Promise<Response> | undefined) {
  const modules = import.meta.glob('../src/views/ArticleEditorView.vue'); expect(modules['../src/views/ArticleEditorView.vue'], 'working article editor exists').toBeDefined()
  const component = (await modules['../src/views/ArticleEditorView.vue']!() as { default: Component }).default
  vi.stubGlobal('fetch', (url: string, init: RequestInit) => Promise.resolve(extra?.(url, init) || new Response(JSON.stringify(url.endsWith('/draft') ? draft : []))))
  const pinia = createPinia(); const session = useSessionStore(pinia); session.accept({ accessToken: 'editor-token', expiresIn: 1800, user: { id: 'u1', username: 'editor', displayName: '编辑', roles: ['KB_EDITOR'], mustChangePassword: false } }); bindApiSession({ getToken: () => session.token, onUnauthorized: () => session.clear() })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/knowledge/articles/:id/edit', component }, { path: '/knowledge/documents/:documentId', component: { template: '<p>独立文档详情</p>' } }, { path: '/knowledge', component: { template: '<p>知识</p>' } }] }); await router.push('/knowledge/articles/d1/edit'); await router.isReady()
  wrapper = mount({ template: '<router-view />' }, { attachTo: document.body, global: { plugins: [pinia, router, ElementPlus] } }); await flushPromises(); return { view: wrapper, session, router }
}
it('saves actual title changes with version and retains edits with explicit conflict recovery', async () => {
  let body: unknown
  const { view } = await render((url, init) => { if (url.endsWith('/draft') && init.method === 'PUT') { body = JSON.parse(String(init.body)); return new Response(JSON.stringify({ title: '版本冲突', status: 409 }), { status: 409 }) } })
  await view.get('#article-title').setValue('我的标题'); await view.get('#article-save').trigger('click'); await flushPromises()
  expect(body).toMatchObject({ title: '我的标题', version: 1 }); expect(view.text()).toContain('保存冲突'); expect(view.get('#article-title').element).toHaveProperty('value', '我的标题'); expect(view.find('#article-reload').exists()).toBe(true)
})
it('previews a parsed version and imports an existing empty draft only after confirmation', async () => {
  const writes: unknown[] = []
  const { view } = await render((url, init) => {
    if (url.endsWith('/draft') && init.method === 'GET') return new Response(JSON.stringify(emptyDraft))
    if (url.endsWith('/documents/d1')) return new Response(JSON.stringify({ id: 'd1', latestVersionId: 'v1', versions: [{ id: 'v1', versionNo: 1, ingestState: 'READY', chunkCount: 1 }] }))
    if (url.endsWith('/draft-source')) return new Response(JSON.stringify(parsedSource))
    if (url.endsWith('/draft') && init.method === 'PUT') { writes.push(JSON.parse(String(init.body))); return new Response(JSON.stringify({ ...emptyDraft, content: draft.content, version: 15 })) }
  })
  expect(view.text()).toContain('已保存的空白草稿')
  await view.findAll('button').find(button => button.text() === '预览解析正文')!.trigger('click'); await flushPromises()
  expect(view.text()).toContain('文章正文'); expect(writes).toHaveLength(0)
  await view.findAll('button').find(button => button.text() === '确认导入正文')!.trigger('click'); await flushPromises()
  expect(writes).toEqual([{ title: '原始标题', categoryId: null, content: draft.content, version: 14 }])
  expect(view.get('[contenteditable]').text()).toContain('文章正文')
})
it('keeps the selected source in the editor when import conflicts with another save', async () => {
  const { view } = await render((url, init) => {
    if (url.endsWith('/draft') && init.method === 'GET') return new Response(JSON.stringify(emptyDraft))
    if (url.endsWith('/documents/d1')) return new Response(JSON.stringify({ id: 'd1', latestVersionId: 'v1', versions: [{ id: 'v1', versionNo: 1, ingestState: 'READY', chunkCount: 1 }] }))
    if (url.endsWith('/draft-source')) return new Response(JSON.stringify(parsedSource))
    if (url.endsWith('/draft') && init.method === 'PUT') return new Response(JSON.stringify({ status: 409, title: '版本冲突' }), { status: 409 })
  })
  await view.findAll('button').find(button => button.text() === '预览解析正文')!.trigger('click'); await flushPromises()
  await view.findAll('button').find(button => button.text() === '确认导入正文')!.trigger('click'); await flushPromises()
  expect(view.text()).toContain('保存冲突'); expect(view.get('[contenteditable]').text()).toContain('文章正文')
})
it('shows a retryable error when an empty draft cannot load its parsed source metadata', async () => {
  const { view } = await render((url, init) => {
    if (url.endsWith('/draft') && init.method === 'GET') return new Response(JSON.stringify(emptyDraft))
    if (url.endsWith('/documents/d1')) return new Response(JSON.stringify({ status: 503, title: '解析版本暂时不可用' }), { status: 503 })
  })
  expect(view.text()).toContain('解析版本暂时不可用')
  expect(view.findAll('button').some(button => button.text() === '重新加载')).toBe(true)
  expect(view.get('[contenteditable]').text()).toBe('')
})
it('clears editor and preview immediately on session replacement', async () => {
  const { view, session } = await render(); expect(view.text()).toContain('文章正文'); session.clear(); await flushPromises(); expect(view.text()).not.toContain('文章正文'); expect(view.find('[contenteditable]').exists()).toBe(false)
})
it('returns to the independent document page after committing a version', async () => {
  const { view, router } = await render((_url, init) => init.method === 'POST' ? new Response(JSON.stringify({ documentId: 'd1', versionId: 'v2', jobId: 'j2' }), { status: 202 }) : undefined)
  await view.findAll('button').find(button => button.text() === '生成待审核版本')!.trigger('click'); await flushPromises()
  expect(router.currentRoute.value.path).toBe('/knowledge/documents/d1'); expect(view.text()).toContain('独立文档详情')
})
it.each([403, 404])('purges the private editor after source preview is denied with %s', async status => {
  const { view } = await render((url, init) => {
    if (url.endsWith('/draft') && init.method === 'GET') return new Response(JSON.stringify(emptyDraft))
    if (url.endsWith('/documents/d1')) return new Response(JSON.stringify({ id: 'd1', latestVersionId: 'v1', versions: [{ id: 'v1', versionNo: 1, ingestState: 'READY', chunkCount: 1 }] }))
    if (url.endsWith('/draft-source')) return new Response(JSON.stringify({ status, title: '来源访问已撤销' }), { status })
  })
  await view.findAll('button').find(button => button.text() === '预览解析正文')!.trigger('click'); await flushPromises()
  expect(view.find('#article-title').exists()).toBe(false); expect(view.find('[contenteditable]').exists()).toBe(false)
})
it('shows accessible editable selection controls and a safe matching preview', async () => {
  const { view } = await render(); expect(view.get('[role=toolbar]').attributes('aria-label')).toBe('文章格式'); expect(view.get('[aria-label="粗体"]').attributes('aria-pressed')).toBe('false'); await view.get('#article-preview').trigger('click'); expect(view.get('.article-preview').text()).toContain('文章正文')
})
it('keeps toolbar selection state current and sanitizes pasted HTML through the closed schema', async () => {
  const { view } = await render(); await view.get('[aria-label="粗体"]').trigger('click'); expect(view.get('[aria-label="粗体"]').attributes('aria-pressed')).toBe('true')
  const event = new Event('paste', { bubbles: true, cancelable: true }); Object.defineProperty(event, 'clipboardData', { value: { getData: (type: string) => type === 'text/html' ? '<p><strong>安全文字</strong><img src=x onerror=alert(1)><script>alert(1)</script><a href="javascript:alert(1)">不可执行链接</a></p>' : '' } }); view.get('[contenteditable]').element.dispatchEvent(event); await flushPromises()
  expect(view.get('[contenteditable]').text()).toContain('安全文字'); expect(view.find('[contenteditable] img').exists()).toBe(false); expect(view.find('[contenteditable] script').exists()).toBe(false); expect(view.find('[contenteditable] a').exists()).toBe(false); await view.get('#article-preview').trigger('click'); expect(view.get('.article-preview strong').text()).toContain('安全文字')
})
it('groups editor actions and keeps unsafe links out of the draft', async () => {
  const { view } = await render()
  expect(view.findAll('.editor-toolbar [role=group]').map(group => group.attributes('aria-label'))).toEqual(['标题与正文', '文字样式', '段落样式', '插入内容', '编辑历史'])
  const linkButton = view.get('.editor-toolbar [aria-expanded]')
  await linkButton.trigger('click'); await flushPromises()
  expect(linkButton.attributes('aria-expanded')).toBe('true')
  expect(document.activeElement).toBe(view.get('#article-link').element)
  await view.get('#article-link').setValue('javascript:alert(1)')
  await view.get('#article-link-form').trigger('submit'); await flushPromises()
  expect(view.get('#article-link-error').text()).toContain('https://')
  expect(view.get('#article-link').attributes('aria-invalid')).toBe('true')
  expect(view.get('[contenteditable]').text()).toBe('文章正文')
  expect(view.find('[contenteditable] a').exists()).toBe(false)
  await view.get('#article-link').trigger('keydown.esc'); await flushPromises()
  expect(view.find('#article-link-form').exists()).toBe(false)
  expect(document.activeElement).toBe(linkButton.element)
})
it('closes an open link form when editing becomes read-only', async () => {
  wrapper = mount(RichTextEditor, { props: { modelValue: normalizeRichDoc(draft.content) }, attachTo: document.body })
  await wrapper.get('.editor-toolbar [aria-expanded]').trigger('click'); await flushPromises()
  await wrapper.get('#article-link').setValue('https://example.com')
  await wrapper.setProps({ disabled: true }); await flushPromises()
  expect(wrapper.find('#article-link-form').exists()).toBe(false)
  expect(wrapper.get('.editor-toolbar [aria-expanded]').attributes('disabled')).toBeDefined()
  expect(wrapper.get('[role=textbox]').attributes('contenteditable')).toBe('false')
  expect(wrapper.emitted('update:modelValue')).toBeUndefined()
})
it('confirms unsaved changes when switching article IDs and keeps the draft when cancelled', async () => {
  const { view, router } = await render(); await view.get('#article-title').setValue('未保存的标题'); const navigation = router.push('/knowledge/articles/d2/edit'); await flushPromises()
  const cancel = Array.from(document.querySelectorAll<HTMLButtonElement>('.el-message-box button')).find(button => button.textContent?.includes('继续编辑')); expect(cancel, 'same-route article switch asks before discarding edits').toBeDefined(); cancel!.click(); await navigation; await flushPromises(); expect(router.currentRoute.value.params.id).toBe('d1'); expect(view.get('#article-title').element).toHaveProperty('value', '未保存的标题')
})
it('does not commit another article after a delayed prerequisite save and confirmed navigation', async () => {
  let release!: (value: Response) => void; const commits: string[] = []
  const { view, router } = await render((url, init) => {
    if (init.method === 'PUT') return new Promise(resolve => { release = resolve })
    if (url.endsWith('/d2/draft')) return new Response(JSON.stringify({ ...draft, id: 'd2', title: '第二篇草稿' }))
    if (init.method === 'POST') { commits.push(url); return new Response(JSON.stringify({ documentId: 'd2', versionId: 'v1', jobId: 'j1' })) }
  })
  await view.get('#article-title').setValue('第一篇修改'); await view.findAll('button').find(button => button.text() === '生成待审核版本')!.trigger('click'); await flushPromises()
  const navigation = router.push('/knowledge/articles/d2/edit'); await flushPromises(); const discard = Array.from(document.querySelectorAll<HTMLButtonElement>('.el-message-box button')).find(button => button.textContent?.includes('放弃修改')); expect(discard).toBeDefined(); discard!.click(); await navigation; await flushPromises()
  release(new Response(JSON.stringify({ ...draft, title: '第一篇修改', version: 2 }))); await flushPromises(); expect(commits).toEqual([]); expect(router.currentRoute.value.path).toBe('/knowledge/articles/d2/edit'); expect(view.get('#article-title').element).toHaveProperty('value', '第二篇草稿')
})
it('does not commit with a replacement session after its old prerequisite save settles', async () => {
  let release!: (value: Response) => void; const commits: string[] = []
  const { view, session, router } = await render((url, init) => { if (init.method === 'PUT') return new Promise(resolve => { release = resolve }); if (init.method === 'POST') { commits.push(url); return new Response(JSON.stringify({ documentId: 'd1', versionId: 'v1', jobId: 'j1' })) } })
  await view.get('#article-title').setValue('旧会话修改'); await view.findAll('button').find(button => button.text() === '生成待审核版本')!.trigger('click'); await flushPromises()
  session.accept({ accessToken: 'replacement-token', expiresIn: 1800, user: { id: 'u2', username: 'other-editor', displayName: '另一编辑', roles: ['KB_EDITOR'], mustChangePassword: false } }); await flushPromises(); release(new Response(JSON.stringify({ ...draft, version: 2 }))); await flushPromises(); expect(commits).toEqual([]); expect(router.currentRoute.value.path).toBe('/knowledge/articles/d1/edit')
})
it('claims the commit guard while its prerequisite save is still pending', async () => {
  let release!: (value: Response) => void; const commits: string[] = []
  const { view } = await render((url, init) => { if (init.method === 'PUT') return new Promise(resolve => { release = resolve }); if (init.method === 'POST') { commits.push(url); return new Response(JSON.stringify({ documentId: 'd1', versionId: 'v1', jobId: 'j1' })) } })
  await view.get('#article-title').setValue('同一份草稿'); const button = view.findAll('button').find(button => button.text() === '生成待审核版本')!; await button.trigger('click'); await button.trigger('click'); await flushPromises(); expect(button.attributes('aria-disabled')).toBe('true'); release(new Response(JSON.stringify({ ...draft, title: '同一份草稿', version: 2 }))); await flushPromises(); expect(commits).toEqual(['/api/v1/knowledge/documents/d1/draft/versions'])
})
it('keeps preview headings aligned with its outline when an autosave response replaces the document', async () => {
  let release!: (value: Response) => void
  const richDraft = { ...draft, content: { type: 'doc', content: [{ type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: '保存中的标题' }] }, ...draft.content.content] } }
  const { view } = await render((url, init) => { if (init.method === 'PUT') return new Promise(resolve => { release = resolve }); if (url.endsWith('/draft')) return new Response(JSON.stringify(richDraft)) })
  await view.get('#article-title').setValue('等待保存'); await view.get('#article-save').trigger('click'); await view.get('#article-preview').trigger('click'); expect(view.get('.article-preview h2').attributes('id')).toBe('article-heading-0'); release(new Response(JSON.stringify({ ...richDraft, title: '等待保存', version: 2 }))); await flushPromises(); expect(view.get('.article-preview h2').attributes('id')).toBe('article-heading-0'); expect(view.get('.article-outline a').attributes('href')).toBe('#article-heading-0')
})
