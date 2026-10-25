import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import ElementPlus, { ElMessageBox, ElSelect } from 'element-plus'
import type { Component } from 'vue'
import { bindApiSession } from '../src/api'
import { useSessionStore } from '../src/stores/session'
import CatalogView from '../src/views/CatalogView.vue'
import UsersView from '../src/views/UsersView.vue'
import AuditView from '../src/views/AuditView.vue'

let view: VueWrapper | undefined
afterEach(() => { ElMessageBox.close(); view?.unmount(); view = undefined; vi.unstubAllGlobals(); document.body.innerHTML = '' })
const json = (value: unknown) => new Response(JSON.stringify(value))
const visible = (element: Element) => { for (let node: Element | null = element; node; node = node.parentElement) if ((node as HTMLElement).style.display === 'none') return false; return true }
const button = (label: string) => Array.from(document.querySelectorAll<HTMLButtonElement>('button')).find(node => visible(node) && node.textContent?.trim() === label)!
async function render(component: Component, props = {}) {
  const pinia = createPinia(), session = useSessionStore(pinia)
  session.accept({ accessToken: 'local-fixture', expiresIn: 1800, user: { id: 'u1', username: 'admin', displayName: '管理员', roles: ['ADMIN'], mustChangePassword: false } })
  bindApiSession({ getToken: () => session.token, onUnauthorized: () => session.clear() })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/admin', component, props: () => props }] })
  await router.push('/admin'); view = mount({ template: '<router-view />' }, { attachTo: document.body, global: { plugins: [pinia, router, ElementPlus], stubs: { transition: false } } }); await flushPromises()
}
it.each(['catalog', 'users'])('restores the real %s opener after cancelling a nested dirty confirmation then saving', async kind => {
  vi.stubGlobal('fetch', (url: string) => Promise.resolve(json(url.includes('/admin/services?') ? { items: [], total: 0, page: 0, size: 20 } : url.includes('/users?') ? { items: [], total: 0, page: 0, size: 20 } : url.endsWith('/roles') ? ['REQUESTER'] : [])))
  await render(kind === 'catalog' ? CatalogView : UsersView, kind === 'catalog' ? { kind: 'services' } : {})
  const opener = button(kind === 'catalog' ? '＋ 新建服务' : '＋ 创建用户')
  // Pointer activation can leave focus on the preceding control; restore the
  // actual event trigger rather than relying only on activeElement at open.
  const precedingControl = document.createElement('button'); precedingControl.textContent = '前一控件'; document.body.append(precedingControl); precedingControl.focus()
  opener.click(); await flushPromises()
  const input = document.querySelector<HTMLInputElement>(kind === 'catalog' ? '#catalog-name' : '#user-name')!
  input.focus(); input.value = '保存焦点回归'; input.dispatchEvent(new Event('input', { bubbles: true }))
  button(kind === 'catalog' ? '取消' : '关闭').click(); await flushPromises()
  button('继续编辑').click(); await flushPromises()
  await vi.waitFor(() => expect(Array.from(document.querySelectorAll('.el-message-box')).filter(visible)).toHaveLength(0))
  if (kind === 'users') {
    for (const [id, value] of [['new-username', 'focus.fixture'], ['initial-password', 'Local-fixture-123']]) { const field = document.getElementById(id!) as HTMLInputElement; field.value = value!; field.dispatchEvent(new Event('input', { bubbles: true })) }
    const role = document.querySelector<HTMLInputElement>('fieldset input')!; role.checked = true; role.dispatchEvent(new Event('change', { bubbles: true })); await flushPromises()
  } else { const code = document.getElementById('catalog-code') as HTMLInputElement; code.value = 'FOCUS_TEST'; code.dispatchEvent(new Event('input', { bubbles: true })); await flushPromises() }
  const save = button(kind === 'catalog' ? '保存' : '创建用户'); save.focus(); save.click(); await flushPromises()
  await vi.waitFor(() => expect((input.closest('.el-overlay') as HTMLElement).style.display).toBe('none'))
  await vi.waitFor(() => expect(document.activeElement).toBe(opener))
})
it.each(['members', 'audit'])('restores the %s opener after a real dialog close', async kind => {
  const event = { id: 'a1', action: 'TICKET_CREATED', actorName: '用户', subjectType: 'TICKET', subjectId: 't1', traceId: 'trace', createdAt: '2026-10-21T00:00:00Z', metadata: {} }
  vi.stubGlobal('fetch', (url: string) => Promise.resolve(json(url.endsWith('/audit-events/filters') ? { actions: ['TICKET_CREATED'], actors: [{ value: 'u1', label: '用户' }] } : url.includes('/audit-events?') ? { items: [event], total: 1, page: 0, size: 20 } : url.includes('/users?') ? { items: [], total: 0, page: 0, size: 100 } : url.includes('/admin/queues?') ? { items: [{ id: 'q1', code: 'GENERAL', name: '通用队列', active: true }], total: 1, page: 0, size: 20 } : [])))
  await render(kind === 'members' ? CatalogView : AuditView, kind === 'members' ? { kind: 'queues' } : {})
  const opener = button(kind === 'members' ? '管理成员' : '查看记录'); opener.focus(); opener.click(); await flushPromises()
  const dialog = Array.from(document.querySelectorAll<HTMLElement>(kind === 'audit' ? '.el-drawer' : '.el-dialog')).find(visible)!
  const close = dialog.querySelector<HTMLButtonElement>(kind === 'audit' ? '.el-drawer__close-btn' : '.el-dialog__headerbtn')!; close.focus(); close.click(); await flushPromises()
  await vi.waitFor(() => expect((dialog.closest('.el-overlay') as HTMLElement).style.display).toBe('none'))
  await vi.waitFor(() => expect(document.activeElement).toBe(opener))
})
it('filters audit rows with actual action, actor and inclusive local date choices', async () => {
  const calls: string[] = []
  vi.stubGlobal('fetch', (url: string) => {
    calls.push(url)
    if (url.endsWith('/audit-events/filters')) return Promise.resolve(json({ actions: ['AUTH_LOGIN'], actors: [{ value: 'u1', label: '管理员' }] }))
    return Promise.resolve(json({ items: [], total: 0, page: 0, size: 20 }))
  })
  await render(AuditView)
  const [action, actor] = view!.findAllComponents(ElSelect)
  expect(action!.classes()).toContain('audit-action-select')
  expect(actor!.classes()).toContain('audit-actor-select')
  await action!.vm.$emit('update:modelValue', 'AUTH_LOGIN')
  await actor!.vm.$emit('update:modelValue', 'u1')
  await view!.get('#audit-from').setValue('2026-10-01')
  await view!.get('#audit-to').setValue('2026-10-02')
  await view!.get('.filter-form').trigger('submit')
  await flushPromises()
  const request = new URL(calls.filter(url => url.includes('/audit-events?')).at(-1)!, 'http://ticknova.local')
  expect(request.searchParams.get('action')).toBe('AUTH_LOGIN')
  expect(request.searchParams.get('actor')).toBe('u1')
  const from = new Date(request.searchParams.get('from')!).getTime()
  const to = new Date(request.searchParams.get('to')!).getTime()
  expect(Number.isFinite(from)).toBe(true)
  expect(to - from).toBe(2 * 24 * 60 * 60 * 1000)
})
it.each(['remove', 'grant'])('keeps member dialog focus and Escape after a real %s refresh', async kind => {
  let memberExists = true; let canProcess = false; let deleteBeforePopupClosed = false
  const member = () => ({ userId: 'u2', username: 'engineer', displayName: '工程师', active: true, canProcess })
  vi.stubGlobal('fetch', (url: string, init: RequestInit) => {
    if (init?.method === 'DELETE') { const popup = document.querySelector('.el-popconfirm'); deleteBeforePopupClosed = !!popup && visible(popup); memberExists = false; return Promise.resolve(new Response(null, { status: 204 })) }
    if (init?.method === 'PUT') { canProcess = true; return Promise.resolve(new Response(null, { status: 204 })) }
    return Promise.resolve(json(url.includes('/admin/queues?') ? { items: [{ id: 'q1', code: 'GENERAL', name: '通用队列', active: true }], total: 1, page: 0, size: 20 } : url.includes('/users?') ? { items: [], total: 0, page: 0, size: 100 } : memberExists ? [member()] : []))
  })
  await render(CatalogView, { kind: 'queues' })
  const opener = button('管理成员'); opener.focus(); opener.click(); await flushPromises()
  const dialog = Array.from(document.querySelectorAll<HTMLElement>('.el-dialog')).find(visible)!
  const trigger = button(kind === 'remove' ? '移出' : '授予处理权限'); trigger.focus(); trigger.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })); trigger.click(); await flushPromises()
  if (kind === 'remove') {
    await vi.waitFor(() => expect(Array.from(document.querySelectorAll('.el-popconfirm')).filter(visible)).toHaveLength(1))
    const confirm = Array.from(document.querySelectorAll<HTMLButtonElement>('.el-popconfirm__action button')).find(node => node.textContent?.trim() === '移出')!
    confirm.focus(); confirm.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })); confirm.click(); await flushPromises()
    await vi.waitFor(() => expect(Array.from(document.querySelectorAll('.el-popconfirm')).filter(visible)).toHaveLength(0))
    await vi.waitFor(() => expect(dialog.textContent).toContain('成员已移出此队列。'))
    expect(deleteBeforePopupClosed).toBe(false)
  } else await vi.waitFor(() => expect(dialog.textContent).toContain('队列成员权限已保存。'))
  await vi.waitFor(() => expect(dialog.contains(document.activeElement)).toBe(true))
  document.activeElement!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }))
  await vi.waitFor(() => expect((dialog.closest('.el-overlay') as HTMLElement).style.display).toBe('none'))
  await vi.waitFor(() => expect(document.activeElement).toBe(opener))
})
it.each(['remove', 'grant'])('reports %s completion only after the member refresh and focus are ready', async kind => {
  let wrote = false; let release: ((value: Response) => void) | undefined
  const member = { userId: 'u2', username: 'engineer', displayName: '工程师', active: true, canProcess: false }
  vi.stubGlobal('fetch', (url: string, init: RequestInit) => {
    if (['DELETE', 'PUT'].includes(String(init?.method))) { wrote = true; return Promise.resolve(new Response(null, { status: 204 })) }
    if (url.endsWith('/q1/members') && wrote) return new Promise<Response>(resolve => { release = resolve })
    return Promise.resolve(json(url.includes('/admin/queues?') ? { items: [{ id: 'q1', code: 'GENERAL', name: '通用队列', active: true }], total: 1, page: 0, size: 20 } : url.includes('/users?') ? { items: [], total: 0, page: 0, size: 100 } : [member]))
  })
  await render(CatalogView, { kind: 'queues' }); const opener = button('管理成员'); opener.focus(); opener.click(); await flushPromises()
  const dialog = Array.from(document.querySelectorAll<HTMLElement>('.el-dialog')).find(visible)!
  const trigger = button(kind === 'remove' ? '移出' : '授予处理权限'); trigger.focus(); trigger.click(); await flushPromises()
  if (kind === 'remove') {
    await vi.waitFor(() => expect(Array.from(document.querySelectorAll('.el-popconfirm')).filter(visible)).toHaveLength(1))
    const confirm = Array.from(document.querySelectorAll<HTMLButtonElement>('.el-popconfirm__action button')).find(node => node.textContent?.trim() === '移出')!; confirm.focus(); confirm.click()
  }
  await vi.waitFor(() => expect(release).toBeDefined())
  const completed = kind === 'remove' ? '成员已移出此队列。' : '队列成员权限已保存。'
  expect(dialog.textContent).not.toContain(completed); expect(dialog.querySelector('#member-user')).toHaveProperty('disabled', true)
  if (kind === 'grant') expect(trigger.isConnected).toBe(true)
  release!(json(kind === 'remove' ? [] : [{ ...member, canProcess: true }]))
  await vi.waitFor(() => expect(dialog.textContent).toContain(completed))
  expect(dialog.contains(document.activeElement)).toBe(true)
  document.activeElement!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape', code: 'Escape', bubbles: true }))
  await vi.waitFor(() => expect((dialog.closest('.el-overlay') as HTMLElement).style.display).toBe('none'))
  await vi.waitFor(() => expect(document.activeElement).toBe(opener))
})
