import { expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import RichDocument from '../src/components/RichDocument.vue'
import type { RichDoc } from '../src/knowledge/rich'

it('starts heading anchors at zero on every complete render traversal', async () => {
  const content: RichDoc = { type: 'doc', content: [{ type: 'heading', attrs: { level: 2 }, content: [{ type: 'text', text: '第一节' }] }, { type: 'heading', attrs: { level: 3 }, content: [{ type: 'text', text: '第二节' }] }] }
  const view = mount(RichDocument, { props: { content } })
  try {
    expect(view.findAll('h2,h3').map(node => node.attributes('id'))).toEqual(['article-heading-0', 'article-heading-1'])
    await view.setProps({ content: structuredClone(content) }); expect(view.findAll('h2,h3').map(node => node.attributes('id'))).toEqual(['article-heading-0', 'article-heading-1'])
    await view.setProps({ content: { type: 'doc', content: [{ type: 'heading', attrs: { level: 1 }, content: [{ type: 'text', text: '新标题' }] }] } }); expect(view.get('h1').attributes('id')).toBe('article-heading-0')
  } finally { view.unmount() }
})
