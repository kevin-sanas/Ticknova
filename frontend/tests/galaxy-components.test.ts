import { afterEach, describe, expect, it, vi } from 'vitest'
import { nextTick } from 'vue'
import { mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElTooltip } from 'element-plus'
import { createMemoryHistory, createRouter } from 'vue-router'
import KnowledgeFilePicker from '../src/components/KnowledgeFilePicker.vue'
import AiRequestStatus from '../src/components/AiRequestStatus.vue'
import AppNavigation from '../src/components/AppNavigation.vue'
import type { AiJob } from '../src/types'

let view: VueWrapper | undefined
afterEach(() => { view?.unmount(); view = undefined; vi.unstubAllGlobals(); document.body.innerHTML = '' })

it('disables a focused collapsed navigation tooltip when the desktop navigation disappears', async () => {
  let viewportChanged: ((event: { matches: boolean }) => void) | undefined
  vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: (_name: string, listener: (event: { matches: boolean }) => void) => { viewportChanged = listener }, removeEventListener: () => {} }))
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/portal', component: { template: '<p>门户</p>' } }] })
  await router.push('/portal'); await router.isReady()
  view = mount(AppNavigation, { props: { items: [{ path: '/portal', label: '员工服务门户', icon: 'search' }], collapsed: true }, global: { plugins: [router, ElementPlus] } })
  expect(view.get('a').attributes('aria-label')).toBe('员工服务门户')
  expect(view.getComponent(ElTooltip).props('disabled')).toBe(false)
  viewportChanged?.({ matches: true }); await nextTick()
  expect(view.getComponent(ElTooltip).props('disabled')).toBe(true)
})

describe('knowledge file picker', () => {
  it('shows the real selected filename, permits keyboard clearing, and reports invalid selection', async () => {
    view = mount(KnowledgeFilePicker, { props: { id: 'file', label: '原始文件', modelValue: null, issue: '' }, attachTo: document.body })
    const selected = new File(['body'], 'a-very-long-guide.md', { type: 'text/markdown' })
    const input = view.get('#file')
    Object.defineProperty(input.element, 'files', { value: [selected], configurable: true })
    await input.trigger('change')
    expect(view.emitted('update:modelValue')?.[0]).toEqual([selected])
    await view.setProps({ modelValue: selected, issue: '文件不能超过 20 MiB。' })
    expect(view.get('.file-picker-name').text()).toContain('a-very-long-guide.md')
    expect(view.get('[role="alert"]').text()).toContain('20 MiB')
    await view.get('[aria-label^="清除已选文件"]').trigger('click')
    expect(view.emitted('update:modelValue')?.at(-1)).toEqual([null])
    expect((input.element as HTMLInputElement).value).toBe('')
  })

  it('blocks file and clear operations while disabled', async () => {
    const selected = new File(['body'], 'guide.md')
    view = mount(KnowledgeFilePicker, { props: { id: 'file', label: '原始文件', modelValue: selected, disabled: true }, attachTo: document.body })
    expect(view.get('.file-picker-trigger').attributes('disabled')).toBeDefined()
    expect(view.get('.file-picker-clear').attributes('disabled')).toBeDefined()
    await view.get('.file-picker-clear').trigger('click')
    expect(view.emitted('update:modelValue')).toBeUndefined()
  })
})

describe('AI request status', () => {
  const job = (status: AiJob['status']): AiJob => ({ id: 'j1', status, attemptCount: 1, errorCode: null, result: null })
  it.each(['QUEUED', 'RUNNING', 'RETRYING', 'COMPLETED', 'FAILED', 'CANCELLED'] as const)('marks the real %s job state and only offers allowed controls', status => {
    view = mount(AiRequestStatus, { props: { error: null, job: job(status), submitting: false, waiting: false, pollPending: false }, global: { plugins: [ElementPlus] } })
    expect(view.get('.ai-status').classes()).toContain(`ai-status-${status.toLowerCase()}`)
    const buttons = view.findAll('button').map(button => button.text())
    expect(buttons.includes('中止生成')).toBe(['QUEUED', 'RUNNING', 'RETRYING'].includes(status))
    expect(buttons.includes('刷新结果')).toBe(status !== 'CANCELLED')
  })

  it('keeps refresh disabled during a pending poll', () => {
    view = mount(AiRequestStatus, { props: { error: null, job: job('RUNNING'), submitting: false, waiting: false, pollPending: true }, global: { plugins: [ElementPlus] } })
    expect(view.findAll('button').find(button => button.text() === '刷新结果')?.attributes('disabled')).toBeDefined()
  })
})
