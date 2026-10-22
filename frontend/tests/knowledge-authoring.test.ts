import { afterEach, describe, expect, it, vi } from 'vitest'

afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })
async function rich() {
  const modules = import.meta.glob('../src/knowledge/rich.ts')
  expect(modules['../src/knowledge/rich.ts'], 'safe rich document boundary exists').toBeDefined()
  return await modules['../src/knowledge/rich.ts']!() as typeof import('../src/knowledge/rich')
}
describe('knowledge rich document boundary', () => {
  it('rejects ambiguous HTTP addresses, credential URLs and empty mailto links', async () => {
    const { safeHref } = await rich()
    for (const href of ['http:relative', 'https://user:pass@example.com', 'https://example.com/\\evil', 'https://example.com/<tag>', 'mailto:']) expect(safeHref(href)).toBeNull()
    expect(safeHref('https://example.com/guide?q=1')).toBe('https://example.com/guide?q=1')
  })
  it('removes executable links and unsupported embedded content without losing text', async () => {
    const { normalizeRichDoc } = await rich()
    expect(normalizeRichDoc({ type: 'doc', content: [{ type: 'paragraph', attrs: { onclick: 'alert(1)' }, content: [{ type: 'text', text: '<script>safe text</script>', marks: [{ type: 'link', attrs: { href: 'javascript:alert(1)' } }, { type: 'bold' }] }, { type: 'image', attrs: { src: 'https://x' } }] }] })).toEqual({ type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: '<script>safe text</script>', marks: [{ type: 'bold' }] }] }] })
  })
  it('uses stable unique heading anchors and keeps safe canonical attributes', async () => {
    const { normalizeRichDoc, richHeadings } = await rich()
    const document = normalizeRichDoc({ type: 'doc', content: [{ type: 'heading', attrs: { level: 2, id: 'evil' }, content: [{ type: 'text', text: '同名' }] }, { type: 'heading', attrs: { level: 1 }, content: [{ type: 'text', text: '同名' }] }, { type: 'orderedList', attrs: { start: 3 }, content: [{ type: 'listItem', content: [{ type: 'paragraph' }] }] }] })
    expect(richHeadings(document)).toEqual([{ id: 'article-heading-0', text: '同名', level: 2 }, { id: 'article-heading-1', text: '同名', level: 1 }])
    expect(document.content?.[2]?.attrs).toEqual({ start: 3 })
    expect(document.content?.[0]?.attrs).toEqual({ level: 2 })
  })
  it('rejects oversized/deep documents before saving instead of truncating user work', async () => {
    const { normalizeRichDoc } = await rich()
    expect(() => normalizeRichDoc({ type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text: 'x'.repeat(100001) }] }] })).toThrow(/100000/)
    let node: unknown = { type: 'paragraph' }; for (let i = 0; i < 35; i++) node = { type: 'blockquote', content: [node] }
    expect(() => normalizeRichDoc({ type: 'doc', content: [node] })).toThrow(/32/)
  })
})

describe('versioned article autosave', () => {
  async function controller(save: (payload: unknown) => Promise<unknown>) {
    const modules = import.meta.glob('../src/knowledge/autosave.ts')
    expect(modules['../src/knowledge/autosave.ts'], 'versioned autosave exists').toBeDefined()
    const module = await modules['../src/knowledge/autosave.ts']!() as typeof import('../src/knowledge/autosave')
    return module.createDraftAutosave(save as never)
  }
  const draft = { id: 'd1', spaceId: 's1', title: '标题', categoryId: null, content: { type: 'doc' as const, content: [{ type: 'paragraph' as const }] }, version: 1, updatedAt: '2026-10-21T01:00:00Z', updatedBy: 'u1' }
  it('debounces 800 ms and serializes saves with the returned optimistic version', async () => {
    vi.useFakeTimers(); const payloads: unknown[] = []
    const saver = await controller(async payload => { payloads.push(payload); return { ...draft, ...(payload as object), version: payloads.length + 1 } })
    saver.reset(draft); saver.edit({ ...draft, title: '第一次' }); await vi.advanceTimersByTimeAsync(500); saver.edit({ ...draft, title: '最后一次' }); await vi.advanceTimersByTimeAsync(799); expect(payloads).toHaveLength(0)
    await vi.advanceTimersByTimeAsync(1); expect(payloads).toEqual([{ title: '最后一次', categoryId: null, content: draft.content, version: 1 }]); expect(saver.state.value.status).toBe('saved')
    saver.edit({ ...draft, title: '下一次' }); await saver.flush(); expect(payloads[1]).toMatchObject({ version: 2 }); saver.dispose()
  })
  it('keeps manual changes on conflict and never retries until explicit reload', async () => {
    vi.useFakeTimers(); let writes = 0
    const saver = await controller(async () => { writes++; throw Object.assign(new Error('版本冲突'), { status: 409 }) })
    saver.reset(draft); saver.edit({ ...draft, title: '我的编辑' }); await vi.advanceTimersByTimeAsync(800)
    expect(saver.state.value).toMatchObject({ status: 'conflict', draft: { title: '我的编辑' }, dirty: true }); saver.edit({ ...draft, title: '继续手动编辑' }); await vi.advanceTimersByTimeAsync(5000); await saver.flush(); expect(writes).toBe(1); expect(saver.state.value.draft?.title).toBe('继续手动编辑'); saver.dispose()
  })
  it('fences late saves after reset/dispose so content cannot enter another document', async () => {
    let release!: (draft: unknown) => void
    const saver = await controller(() => new Promise(resolve => { release = resolve }))
    saver.reset(draft); saver.edit({ ...draft, title: '旧文档' }); const pending = saver.flush(); saver.reset({ ...draft, id: 'd2', title: '新文档' }); release({ ...draft, title: '旧文档', version: 2 }); await pending
    expect(saver.state.value.draft).toMatchObject({ id: 'd2', title: '新文档', version: 1 }); saver.dispose(); expect(saver.state.value.draft).toBeNull()
  })
  it('preserves edits made during an in-flight save and saves them with its returned version', async () => {
    vi.useFakeTimers(); let release!: (value: unknown) => void; const payloads: unknown[] = []
    const saver = await controller(payload => { payloads.push(payload); return payloads.length === 1 ? new Promise(resolve => { release = resolve }) : Promise.resolve({ ...draft, ...(payload as object), version: 3 }) })
    saver.reset(draft); saver.edit({ ...draft, title: '先保存' }); const pending = saver.flush(); saver.edit({ ...draft, title: '保存中继续编辑' }); release({ ...draft, title: '先保存', version: 2 }); await pending; expect(saver.state.value.draft?.title).toBe('保存中继续编辑'); expect(saver.state.value.dirty).toBe(true)
    await vi.advanceTimersByTimeAsync(800); expect(payloads[1]).toMatchObject({ title: '保存中继续编辑', version: 2 }); expect(saver.state.value.dirty).toBe(false); saver.dispose()
  })
})
