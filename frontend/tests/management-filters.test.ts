import { afterEach, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import ElementPlus, { ElPagination, ElSelect } from 'element-plus'
import { createPinia } from 'pinia'
import { bindApiSession } from '../src/api'
import { useSessionStore } from '../src/stores/session'
import UsersView from '../src/views/UsersView.vue'
import CatalogView from '../src/views/CatalogView.vue'

let view: VueWrapper | undefined
afterEach(() => { view?.unmount(); view = undefined; vi.unstubAllGlobals(); document.body.innerHTML = '' })
const json = (value: unknown) => new Response(JSON.stringify(value))
function mountAdmin(component: object, props = {}) {
  const pinia = createPinia(); const session = useSessionStore(pinia)
  session.accept({ accessToken: 'admin-token', expiresIn: 1800, user: { id: 'admin', username: 'admin', displayName: '管理员', roles: ['ADMIN'], mustChangePassword: false } })
  bindApiSession({ getToken: () => session.token, onUnauthorized: () => session.clear() })
  view = mount(component, { attachTo: document.body, props, global: { plugins: [pinia, ElementPlus] } })
  return view
}

it('queries users by text, active status and explicit role', async () => {
  const calls: string[] = []
  vi.stubGlobal('fetch', (url: string) => {
    calls.push(url)
    return Promise.resolve(json(url.endsWith('/roles') ? ['ADMIN', 'AGENT'] : { items: [], total: 0, page: 0, size: 20 }))
  })
  const page = mountAdmin(UsersView); await flushPromises()
  await page.get('#user-query').setValue('网络 工程师')
  const [status, role] = page.findAllComponents(ElSelect)
  expect(status!.classes()).toContain('user-status-filter')
  expect(role!.classes()).toContain('user-role-filter')
  await status!.vm.$emit('update:modelValue', 'true')
  await role!.vm.$emit('update:modelValue', 'AGENT')
  await page.get('#user-filter-form').trigger('submit'); await flushPromises()
  const request = new URL(calls.filter(url => url.includes('/users?')).at(-1)!, 'http://ticknova.local')
  expect(request.searchParams.get('q')).toBe('网络 工程师')
  expect(request.searchParams.get('active')).toBe('true')
  expect(request.searchParams.get('role')).toBe('AGENT')
  expect(request.searchParams.get('page')).toBe('0')
})

it('queries and paginates the admin catalog without changing public catalog calls', async () => {
  const calls: string[] = []
  vi.stubGlobal('fetch', (url: string) => { calls.push(url); return Promise.resolve(json({ items: [], total: 45, page: 0, size: 20 })) })
  const page = mountAdmin(CatalogView, { kind: 'services' }); await flushPromises()
  await page.get('#catalog-query').setValue('邮箱')
  const status = page.getComponent(ElSelect)
  expect(status.classes()).toContain('catalog-status-filter')
  await status.vm.$emit('update:modelValue', 'false')
  await page.get('#catalog-filter-form').trigger('submit'); await flushPromises()
  let request = new URL(calls.at(-1)!, 'http://ticknova.local')
  expect(request.pathname).toBe('/api/v1/admin/services')
  expect(request.searchParams.get('q')).toBe('邮箱')
  expect(request.searchParams.get('active')).toBe('false')
  await page.getComponent(ElPagination).vm.$emit('current-change', 2); await flushPromises()
  request = new URL(calls.at(-1)!, 'http://ticknova.local')
  expect(request.searchParams.get('page')).toBe('1')
})
