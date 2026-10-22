<script setup lang="ts">
import { h, type VNode } from 'vue'
import { normalizeReaderDoc, type RichDoc, type RichNode } from '../knowledge/rich'
const props = defineProps<{ content: RichDoc }>()
const Render = { render: () => {
  let heading = 0
  function render(node: RichNode): VNode | string {
    if (node.type === 'text') {
      let child: VNode | string = node.text || ''
      for (const mark of node.marks || []) { const tag = ({ bold: 'strong', italic: 'em', strike: 's', underline: 'u', code: 'code', link: 'a' } as Record<string, string>)[mark.type]; if (tag) child = h(tag, mark.type === 'link' ? { href: mark.attrs?.href, rel: 'noopener noreferrer', target: '_blank' } : {}, [child]) }
      return child
    }
    const tags: Record<string, string> = { doc: 'div', paragraph: 'p', heading: `h${node.attrs?.level || 2}`, bulletList: 'ul', orderedList: 'ol', listItem: 'li', blockquote: 'blockquote', codeBlock: 'pre', hardBreak: 'br', horizontalRule: 'hr' }
    return h(tags[node.type] || 'div', node.type === 'heading' ? { id: `article-heading-${heading++}`, tabindex: -1 } : node.type === 'orderedList' ? { start: node.attrs?.start } : {}, node.type === 'codeBlock' ? [h('code', {}, (node.content || []).map(render))] : (node.content || []).map(render))
  }
  return render(normalizeReaderDoc(props.content))
} }
</script>
<template><component :is="Render" class="rich-document" /></template>
