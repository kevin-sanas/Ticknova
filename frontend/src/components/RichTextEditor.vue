<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { useEditor, EditorContent } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import { DOMParser as ProseMirrorDOMParser, Slice } from '@tiptap/pm/model'
import { normalizeRichDoc, safeHref, type RichDoc } from '../knowledge/rich'
const props = defineProps<{ modelValue: RichDoc; disabled?: boolean }>()
const emit = defineEmits<{ 'update:modelValue': [content: RichDoc]; error: [message: string] }>()
const linkOpen = ref(false); const link = ref(''); const linkError = ref(''); const selectionRevision = ref(0)
const linkButton = ref<HTMLButtonElement | null>(null); const linkInput = ref<HTMLInputElement | null>(null)
const editor = useEditor({
  extensions: [StarterKit.configure({ heading: { levels: [1, 2, 3] }, link: { openOnClick: false, autolink: false, linkOnPaste: false, isAllowedUri: url => !!safeHref(url) }, trailingNode: false })],
  content: normalizeRichDoc(props.modelValue), editable: !props.disabled,
  editorProps: {
    attributes: { role: 'textbox', 'aria-label': '文章正文', 'aria-multiline': 'true', class: 'rich-document' },
    handlePaste(view, event) {
      const html = event.clipboardData?.getData('text/html'); const text = event.clipboardData?.getData('text/plain')
      if (!html && !text) return false
      try {
        let document: RichDoc
        if (html) { const element = new DOMParser().parseFromString(html, 'text/html'); element.querySelectorAll('script,style,iframe,object,embed,img,svg,math').forEach(node => node.remove()); document = normalizeRichDoc(ProseMirrorDOMParser.fromSchema(view.state.schema).parse(element.body).toJSON()) }
        else document = normalizeRichDoc({ type: 'doc', content: text!.split('\n').map(line => ({ type: 'paragraph', content: line ? [{ type: 'text', text: line }] : [] })) })
        view.dispatch(view.state.tr.replaceSelection(new Slice(view.state.schema.nodeFromJSON(document).content, 0, 0)))
        return true
      } catch (failure) { emit('error', (failure as Error).message); return true }
    },
  },
  onUpdate: ({ editor }) => { try { emit('update:modelValue', normalizeRichDoc(editor.getJSON())) } catch (failure) { emit('update:modelValue', editor.getJSON() as RichDoc); emit('error', (failure as Error).message) } },
  onTransaction: () => { selectionRevision.value++ },
})
const formatGroups = [
  { label: '文字样式', actions: [
    { label: '粗体', name: 'bold', action: () => editor.value?.chain().focus().toggleBold().run() },
    { label: '斜体', name: 'italic', action: () => editor.value?.chain().focus().toggleItalic().run() },
    { label: '下划线', name: 'underline', action: () => editor.value?.chain().focus().toggleUnderline().run() },
    { label: '删除线', name: 'strike', action: () => editor.value?.chain().focus().toggleStrike().run() },
    { label: '行内代码', name: 'code', action: () => editor.value?.chain().focus().toggleCode().run() },
  ] },
  { label: '段落样式', actions: [
    { label: '无序列表', name: 'bulletList', action: () => editor.value?.chain().focus().toggleBulletList().run() },
    { label: '有序列表', name: 'orderedList', action: () => editor.value?.chain().focus().toggleOrderedList().run() },
    { label: '引用', name: 'blockquote', action: () => editor.value?.chain().focus().toggleBlockquote().run() },
    { label: '代码块', name: 'codeBlock', action: () => editor.value?.chain().focus().toggleCodeBlock().run() },
  ] },
]
function setHeading(event: Event) { const level = Number((event.target as HTMLSelectElement).value); if (level) editor.value?.chain().focus().setHeading({ level: level as 1 | 2 | 3 }).run(); else editor.value?.chain().focus().setParagraph().run() }
function closeLink(restoreFocus = true) { linkOpen.value = false; linkError.value = ''; if (restoreFocus) void nextTick(() => linkButton.value?.focus()) }
function toggleLink() {
  if (props.disabled) return
  if (linkOpen.value) { closeLink(); return }
  link.value = editor.value?.getAttributes('link').href || ''
  linkError.value = ''
  linkOpen.value = true
  void nextTick(() => linkInput.value?.focus())
}
function applyLink() {
  if (props.disabled) return
  const href = safeHref(link.value)
  if (!href) { linkError.value = '请输入完整的 https://、http:// 或 mailto: 链接。'; linkInput.value?.focus(); return }
  editor.value?.chain().focus().extendMarkRange('link').setLink({ href }).run()
  closeLink()
}
watch(() => props.disabled, value => { editor.value?.setEditable(!value, false); if (value && linkOpen.value) closeLink(false) })
watch(() => props.modelValue, value => { try { if (editor.value && JSON.stringify(editor.value.getJSON()) !== JSON.stringify(value) && JSON.stringify(normalizeRichDoc(editor.value.getJSON())) !== JSON.stringify(value)) editor.value.commands.setContent(normalizeRichDoc(value), { emitUpdate: false }) } catch (failure) { emit('error', (failure as Error).message) } }, { deep: true })
onBeforeUnmount(() => editor.value?.destroy())
</script>
<template>
  <div class="rich-editor" :data-selection-revision="selectionRevision">
    <div class="editor-toolbar" role="toolbar" aria-label="文章格式">
      <div class="editor-toolbar-group" role="group" aria-label="标题与正文"><label class="sr-only" for="article-heading">段落格式</label><select id="article-heading" aria-label="段落格式" :disabled="disabled" :value="editor?.isActive('heading') ? editor.getAttributes('heading').level : 0" @change="setHeading"><option :value="0">正文</option><option :value="1">标题 1</option><option :value="2">标题 2</option><option :value="3">标题 3</option></select></div>
      <div v-for="group in formatGroups" :key="group.label" class="editor-toolbar-group" role="group" :aria-label="group.label"><button v-for="format in group.actions" :key="format.name" type="button" :aria-label="format.label" :aria-pressed="!!editor?.isActive(format.name)" :disabled="disabled" @click="format.action">{{ format.label }}</button></div>
      <div class="editor-toolbar-group" role="group" aria-label="插入内容"><button ref="linkButton" type="button" :disabled="disabled" :aria-expanded="linkOpen" :aria-controls="linkOpen ? 'article-link-form' : undefined" @click="toggleLink">链接</button><button type="button" :disabled="disabled || !editor?.isActive('link')" @click="editor?.chain().focus().unsetLink().run()">移除链接</button><button type="button" :disabled="disabled" @click="editor?.chain().focus().setHorizontalRule().run()">分隔线</button></div>
      <div class="editor-toolbar-group" role="group" aria-label="编辑历史"><button type="button" :disabled="disabled || !editor?.can().undo()" @click="editor?.chain().focus().undo().run()"><span class="editor-tool-icon" aria-hidden="true">↶</span>撤销</button><button type="button" :disabled="disabled || !editor?.can().redo()" @click="editor?.chain().focus().redo().run()"><span class="editor-tool-icon" aria-hidden="true">↷</span>重做</button></div>
    </div>
    <form v-if="linkOpen" id="article-link-form" class="editor-link-form filter-form" novalidate @submit.prevent="applyLink" @keydown.esc.prevent.stop="closeLink()"><div class="field grow"><label for="article-link">链接地址</label><input id="article-link" ref="linkInput" v-model="link" type="text" inputmode="url" maxlength="2048" :disabled="disabled" :aria-invalid="!!linkError" :aria-describedby="linkError ? 'article-link-error' : undefined" placeholder="https://example.com 或 mailto:name@example.com" @input="linkError = ''" /><small v-if="linkError" id="article-link-error" class="error-text" role="alert">{{ linkError }}</small></div><button type="submit" :disabled="disabled">应用链接</button><button type="button" :disabled="disabled" @click="closeLink()">取消</button></form>
    <EditorContent :editor="editor" /><p class="small muted editor-shortcuts">快捷键：Ctrl / ⌘ + B 粗体、I 斜体、U 下划线、Z 撤销。粘贴时自动移除不受支持的内容。</p>
  </div>
</template>
