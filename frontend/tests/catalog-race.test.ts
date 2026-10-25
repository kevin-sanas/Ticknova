import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElDialog } from 'element-plus'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { bindApiSession } from '../src/api'
import { useSessionStore } from '../src/stores/session'
import CatalogView from '../src/views/CatalogView.vue'

function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status }) }
const queues = [
  { id: 'qa', code: 'A', name: '队列 A', active: true, canProcess: true },
  { id: 'qb', code: 'B', name: '队列 B', active: true, canProcess: true },
]
const memberA = { userId: 'ua', username: 'member-a', displayName: '仅属于 A 的成员', active: true, canProcess: true }
const memberB = { userId: 'ub', username: 'member-b', displayName: '仅属于 B 的成员', active: true, canProcess: false }
let wrapper: VueWrapper | undefined
afterEach(() => { wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = ''; vi.unstubAllGlobals() })
async function render() {
  const pinia = createPinia()
  const session = useSessionStore(pinia)
  session.accept({ accessToken: 'admin-token', expiresIn: 1800, user: { id: 'admin', username: 'admin', displayName: '管理员', roles: ['ADMIN'], mustChangePassword: false } })
  bindApiSession({ getToken: () => session.token, onUnauthorized: () => session.clear() })
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/admin/queues', component: CatalogView, props: { kind: 'queues' } }] })
  await router.push('/admin/queues'); await router.isReady()
  wrapper = mount({ template: '<router-view />' }, { attachTo: document.body, global: { plugins: [pinia, ElementPlus, router] } })
  await flushPromises()
  return wrapper
}
function memberDialog() { return document.body.querySelector('.el-dialog')! }
async function openQueue(view: VueWrapper, index: number) {
  const buttons = view.get('.desktop-table').findAll('button').filter(button => button.text() === '管理成员')
  await buttons[index]!.trigger('click'); await flushPromises()
}
async function closeMemberDialog(view: VueWrapper) {
  const dialog = view.findAllComponents(ElDialog)[1]!
  await dialog.get('.el-dialog__headerbtn').trigger('click'); await flushPromises()
}

describe('queue member request ownership', () => {
  it('loads B while A is pending and never shows A members or targets A members under B after A finishes', async () => {
    let releaseA!: (response: Response) => void
    let releaseB!: (response: Response) => void
    let bReads = 0
    const mutations: string[] = []
    vi.stubGlobal('fetch', (url: string, init: RequestInit) => {
      if (init.method === 'PUT' || init.method === 'DELETE') { mutations.push(url); return Promise.resolve(new Response(null, { status: 204 })) }
      if (url.includes('/api/v1/admin/queues?')) return Promise.resolve(json({ items: queues, total: queues.length, page: 0, size: 20 }))
      if (url.includes('/users?')) return Promise.resolve(json({ items: [], total: 0, page: 0, size: 100 }))
      if (url.endsWith('/qa/members')) return new Promise<Response>(resolve => { releaseA = resolve })
      if (url.endsWith('/qb/members')) { bReads++; return bReads === 1 ? new Promise<Response>(resolve => { releaseB = resolve }) : Promise.resolve(json([memberB])) }
      throw new Error(`Unexpected request ${url}`)
    })
    const view = await render()
    await openQueue(view, 0)
    await closeMemberDialog(view)
    await openQueue(view, 1)
    expect(memberDialog().textContent).not.toContain(memberA.displayName)
    const submit = memberDialog().querySelector('button[type=submit]') as HTMLButtonElement
    expect(submit.disabled).toBe(true)
    expect(releaseB).toBeTypeOf('function')
    releaseB(json([memberB])); await flushPromises()
    expect(memberDialog().textContent).toContain(memberB.displayName)
    releaseA(json([memberA])); await flushPromises()
    expect(memberDialog().textContent).not.toContain(memberA.displayName)
    expect(memberDialog().textContent).toContain(memberB.displayName)
    const grant = Array.from(memberDialog().querySelectorAll('button')).find(button => button.textContent?.trim() === '授予处理权限')!
    grant.click(); await flushPromises()
    expect(mutations).toEqual(['/api/v1/queues/qb/members/ub'])
  })

  it('clears already-loaded rows when another queue opens and keeps writes disabled until that queue loads', async () => {
    let releaseB!: (response: Response) => void
    vi.stubGlobal('fetch', (url: string) => {
      if (url.includes('/api/v1/admin/queues?')) return Promise.resolve(json({ items: queues, total: queues.length, page: 0, size: 20 }))
      if (url.includes('/users?')) return Promise.resolve(json({ items: [], total: 0, page: 0, size: 100 }))
      if (url.endsWith('/qa/members')) return Promise.resolve(json([memberA]))
      if (url.endsWith('/qb/members')) return new Promise<Response>(resolve => { releaseB = resolve })
      throw new Error(`Unexpected request ${url}`)
    })
    const view = await render()
    await openQueue(view, 0)
    expect(memberDialog().textContent).toContain(memberA.displayName)
    await closeMemberDialog(view)
    await openQueue(view, 1)
    expect(memberDialog().textContent).not.toContain(memberA.displayName)
    expect((memberDialog().querySelector('#member-user') as HTMLSelectElement).disabled).toBe(true)
    releaseB(json([memberB])); await flushPromises()
    expect(memberDialog().textContent).toContain(memberB.displayName)
    expect((memberDialog().querySelector('#member-user') as HTMLSelectElement).disabled).toBe(false)
  })

  it('keeps the member dialog open while a save is pending and allows closing after it completes', async () => {
    let releaseSave!: (response: Response) => void
    vi.stubGlobal('fetch', (url: string, init: RequestInit) => {
      if (init.method === 'PUT') return new Promise<Response>(resolve => { releaseSave = resolve })
      if (url.includes('/api/v1/admin/queues?')) return Promise.resolve(json({ items: queues, total: queues.length, page: 0, size: 20 }))
      if (url.includes('/users?')) return Promise.resolve(json({ items: [], total: 0, page: 0, size: 100 }))
      return Promise.resolve(json([memberB]))
    })
    const view = await render()
    await openQueue(view, 1)
    const grant = Array.from(memberDialog().querySelectorAll('button')).find(button => button.textContent?.trim() === '授予处理权限')!
    grant.click(); await flushPromises()
    await closeMemberDialog(view)
    expect((memberDialog().closest('.el-overlay') as HTMLElement).style.display).not.toBe('none')
    releaseSave(new Response(null, { status: 204 })); await flushPromises()
    await closeMemberDialog(view)
    expect((memberDialog().closest('.el-overlay') as HTMLElement).style.display).toBe('none')
  })
})
