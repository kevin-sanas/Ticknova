export interface RichMark { type: string; attrs?: Record<string, unknown> }
export interface RichNode { type: string; text?: string; attrs?: Record<string, unknown>; marks?: RichMark[]; content?: RichNode[] }
export interface RichDoc extends RichNode { type: 'doc' }
export const emptyRichDoc = (): RichDoc => ({ type: 'doc', content: [{ type: 'paragraph' }] })
const nodes = new Set(['doc', 'paragraph', 'heading', 'text', 'bulletList', 'orderedList', 'listItem', 'blockquote', 'codeBlock', 'hardBreak', 'horizontalRule'])
const marks = new Set(['bold', 'italic', 'strike', 'underline', 'code', 'link'])
export function safeHref(value: unknown): string | null {
  if (typeof value !== 'string' || /[\u0000-\u0020\u007f<>"\\]/.test(value) || value.length > 2048) return null
  try { const url = new URL(value); if (url.protocol === 'mailto:') return value.slice(7).trim() ? value : null; return /^https?:\/\//i.test(value) && !!url.hostname && !url.username && !url.password ? value : null } catch { return null }
}
export function normalizeRichDoc(input: unknown): RichDoc { return normalizeDocument(input, false) }
// File ingestion accepts 4 MiB plus chunk overlap and repeated source headings.
// Reading keeps the same closed schema, with separate bounds from draft writes.
export function normalizeReaderDoc(input: unknown): RichDoc { return normalizeDocument(input, true) }
function normalizeDocument(input: unknown, reader: boolean): RichDoc {
  let count = 0; let length = 0
  if (JSON.stringify(input).length > (reader ? 128 : 1) * 1024 * 1024) throw new Error(reader ? '阅读内容超过安全显示上限，请查看来源片段。' : '文章 JSON 不能超过 1 MiB。')
  function clean(value: unknown, depth: number): RichNode | null {
    if (depth > 32) throw new Error('文章嵌套层级不能超过 32。')
    if (++count > (reader ? 100000 : 20000)) throw new Error(reader ? '阅读内容节点过多，请查看来源片段。' : '文章节点不能超过 20000。')
    if (!value || typeof value !== 'object') return null
    const source = value as RichNode
    if (!nodes.has(source.type)) return null
    const node: RichNode = { type: source.type }
    if (node.type === 'text') {
      if (typeof source.text !== 'string' || !source.text) return null
      length += source.text.length; if (length > (reader ? 32 * 1024 * 1024 : 100000)) throw new Error(reader ? '阅读内容超过安全显示上限，请查看来源片段。' : '文章正文不能超过 100000 个字符。')
      node.text = source.text
      const normalized = (source.marks || []).flatMap(mark => {
        if (!marks.has(mark.type)) return []
        if (mark.type !== 'link') return [{ type: mark.type }]
        const href = safeHref(mark.attrs?.href); return href ? [{ type: 'link', attrs: { href } }] : []
      })
      if (normalized.length) node.marks = normalized
      return node
    }
    if (node.type === 'heading') node.attrs = { level: [1, 2, 3].includes(Number(source.attrs?.level)) ? Number(source.attrs?.level) : 2 }
    if (node.type === 'orderedList' && Number.isInteger(source.attrs?.start) && Number(source.attrs?.start) > 1) node.attrs = { start: Number(source.attrs?.start) }
    if (node.type === 'codeBlock' && typeof source.attrs?.language === 'string' && /^[a-zA-Z0-9_+-]{1,32}$/.test(source.attrs.language)) node.attrs = { language: source.attrs.language }
    if (Array.isArray(source.content)) { const content = source.content.map(child => clean(child, depth + 1)).filter((child): child is RichNode => !!child); if (content.length) node.content = content }
    return node
  }
  const root = clean(input, 0)
  return root?.type === 'doc' ? { type: 'doc', content: root.content?.length ? root.content : [{ type: 'paragraph' }] } : emptyRichDoc()
}
export function richText(node: RichNode): string { return node.text || (node.content || []).map(richText).join(node.type === 'doc' ? '\n' : '') }
export function richHeadings(document: RichDoc) {
  const headings: { id: string; text: string; level: number }[] = []
  function visit(node: RichNode) { if (node.type === 'heading') headings.push({ id: `article-heading-${headings.length}`, text: richText(node), level: Number(node.attrs?.level || 2) }); node.content?.forEach(visit) }
  visit(document); return headings
}
export function chunksRichDoc(chunks: { headingPath: string | null; content: string }[]): RichDoc {
  return normalizeReaderDoc({ type: 'doc', content: chunks.flatMap(chunk => [...(chunk.headingPath ? [{ type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: chunk.headingPath }] }] : []), { type: 'paragraph', content: [{ type: 'text', text: chunk.content }] }]) })
}
