import {afterEach,expect,it,vi} from 'vitest'
import {mount,flushPromises,type VueWrapper} from '@vue/test-utils'
import {createPinia} from 'pinia'
import {createRouter,createMemoryHistory} from 'vue-router'
import ElementPlus from 'element-plus'
import App from '../src/App.vue'
import {useSessionStore} from '../src/stores/session'
import {destinationFor} from '../src/access'
import LoginView from '../src/views/LoginView.vue'
import ChangePasswordView from '../src/views/ChangePasswordView.vue'
let view:VueWrapper|undefined
afterEach(()=>{view?.unmount();vi.unstubAllGlobals();document.body.innerHTML=''})
it('routes requesters to their search-first portal while keeping engineer work entry',()=>{
 const user={id:'u',username:'requester',displayName:'申请人',roles:['REQUESTER'],mustChangePassword:false}
 expect(destinationFor(user)).toBe('/portal');expect(destinationFor({...user,roles:['AGENT']})).toBe('/tickets')
})
it('shows Ticknova throughout the shell without exposing administrator navigation to requesters',async()=>{
 const pinia=createPinia(),session=useSessionStore(pinia);session.accept({accessToken:'fixture',expiresIn:1800,user:{id:'u',username:'requester',displayName:'申请人',roles:['REQUESTER'],mustChangePassword:false}})
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]});await router.push('/portal')
 view=mount(App,{attachTo:document.body,global:{plugins:[pinia,router,ElementPlus]}});await flushPromises()
 expect(view.get('.brand').text()).toContain('Ticknova');expect(view.get('.brand-mark').text()).toBe('t');expect(view.get('.workspace-footer').text()).toContain('Ticknova');expect(view.text()).not.toContain('KnowDesk AI');expect(view.find('a[href="/portal"]').text()).toContain('员工服务门户');expect(view.find('a[href="/admin/users"]').exists()).toBe(false)
 await view.get('button[aria-label="打开导航"]').trigger('click');await flushPromises();expect(document.querySelector('.navigation-drawer .brand')?.textContent).toBe('tTicknova')
 const collapse=view.findAll('button').find(x=>x.attributes('aria-label')==='收起导航');expect(collapse).toBeDefined();await collapse!.trigger('click');expect(view.classes()).toBeDefined();expect(view.find('.workspace').classes()).toContain('nav-collapsed')
})
it('gives dedicated reviewers a distinct entry while hiding it from editors and highlights the current knowledge route',async()=>{
 const pinia=createPinia(),session=useSessionStore(pinia);session.accept({accessToken:'fixture',expiresIn:1800,user:{id:'u',username:'editor',displayName:'编辑',roles:['KB_EDITOR'],mustChangePassword:false}})
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]});await router.push('/knowledge/reviews')
 view=mount(App,{attachTo:document.body,global:{plugins:[pinia,router,ElementPlus]}});await flushPromises()
 expect(view.find('.sidebar a[href="/knowledge/reviews"]').exists()).toBe(false)
 session.user!.roles=['KB_REVIEWER'];await flushPromises()
 expect(view.get('.sidebar a[href="/knowledge/reviews"]').text()).toContain('审核待办')
 expect(view.get('.sidebar a[href="/knowledge/reviews"]').attributes('aria-current')).toBe('page')
 expect(view.get('.sidebar a[href="/knowledge"]').attributes('aria-current')).toBeUndefined()
 await router.push('/knowledge/articles/d1/edit');await flushPromises()
 expect(view.get('.sidebar a[href="/knowledge"]').attributes('aria-current')).toBe('page')
 session.accept({accessToken:'admin',expiresIn:1800,user:{id:'a',username:'admin',displayName:'管理员',roles:['ADMIN'],mustChangePassword:false}});await flushPromises()
 expect(view.find('.sidebar a[href="/knowledge/reviews"]').exists()).toBe(false)
 expect(view.get('.sidebar a[href="/admin/knowledge-spaces"]').text()).toContain('知识空间')
})
it.each([['login',LoginView],['change-password',ChangePasswordView]] as const)('uses Ticknova on the %s form',async(path,component)=>{
 const pinia=createPinia();const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]});await router.push(`/${path}`)
 view=mount(component,{attachTo:document.body,global:{plugins:[pinia,router,ElementPlus]}});await flushPromises()
 expect(view.get('.auth-brand').text()).toBe('tTicknova');expect(view.text()).not.toContain('KnowDesk AI')
})
it('groups role-aware navigation on desktop and mobile and hides headings when collapsed',async()=>{
 const pinia=createPinia(),session=useSessionStore(pinia);session.accept({accessToken:'fixture',expiresIn:1800,user:{id:'u',username:'reader',displayName:'成员',roles:['REQUESTER'],mustChangePassword:false}})
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]});await router.push('/portal')
 view=mount(App,{attachTo:document.body,global:{plugins:[pinia,router,ElementPlus]}});await flushPromises()
 expect(view.findAll('.sidebar .nav-group-title').map(heading=>heading.text())).toEqual(['工作台','知识'])
 expect(view.find('.sidebar .navigation-group[aria-label="管理"]').exists()).toBe(false)
 session.user!.roles=['KB_REVIEWER','ADMIN'];await flushPromises()
 expect(view.findAll('.sidebar .nav-group-title').map(heading=>heading.text())).toEqual(['工作台','知识','管理'])
 expect(view.find('.sidebar .navigation-group[aria-label="知识"] a[href="/knowledge/reviews"]').exists()).toBe(true)
 expect(view.find('.sidebar .navigation-group[aria-label="管理"] a[href="/admin/knowledge-spaces"]').exists()).toBe(true)
 await view.get('button[aria-label="收起导航"]').trigger('click');expect(view.findAll('.sidebar .nav-group-title')).toHaveLength(0)
 expect(view.get('.sidebar a[href="/knowledge/reviews"]').attributes('aria-label')).toBe('审核待办')
 await view.get('button[aria-label="打开导航"]').trigger('click');await flushPromises()
 expect([...document.querySelectorAll('.navigation-drawer .nav-group-title')].map(heading=>heading.textContent)).toEqual(['工作台','知识','管理'])
})
