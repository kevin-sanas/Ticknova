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
 expect(view.get('.brand').text()).toContain('Ticknova');expect(view.find('.brand-mark svg').exists()).toBe(true);expect(view.get('.workspace-footer').text()).toContain('Ticknova');expect(view.text()).not.toContain('KnowDesk AI');expect(view.find('a[href="/portal"]').text()).toContain('服务门户');expect(view.find('a[href="/admin/users"]').exists()).toBe(false)
 await view.get('button[aria-label="打开导航"]').trigger('click');await flushPromises();expect(document.querySelector('.navigation-drawer .brand')?.textContent).toBe('Ticknova')
 expect(view.find('button[aria-label="打开个人菜单"]').exists()).toBe(true)
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
 const pinia=createPinia();const router=createRouter({history:createMemoryHistory(),routes:[{path:`/${path}`,component}]});await router.push(`/${path}`)
 view=mount(App,{attachTo:document.body,global:{plugins:[pinia,router,ElementPlus]}});await flushPromises();await flushPromises()
 expect(view.get('.auth-brand').text()).toBe('Ticknova');expect(view.text()).not.toContain('KnowDesk AI');expect(view.find(path==='login'?'#username':'#old-password').exists()).toBe(true)
})
it('groups role-aware navigation into drawers on desktop and mobile',async()=>{
 const pinia=createPinia(),session=useSessionStore(pinia);session.accept({accessToken:'fixture',expiresIn:1800,user:{id:'u',username:'reader',displayName:'成员',roles:['REQUESTER'],mustChangePassword:false}})
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]});await router.push('/portal')
 view=mount(App,{attachTo:document.body,global:{plugins:[pinia,router,ElementPlus]}});await flushPromises()
 expect(view.findAll('.sidebar .group-toggle').map(heading=>heading.text())).toEqual(['服务与工单','知识协作'])
 expect(view.find('.sidebar a[href="/admin/users"]').exists()).toBe(false)
 session.user!.roles=['KB_REVIEWER','ADMIN'];await flushPromises()
 expect(view.findAll('.sidebar .group-toggle').map(heading=>heading.text())).toEqual(['服务与工单','知识协作','运营与配置','账号与权限'])
 expect(view.find('.sidebar a[href="/knowledge/reviews"]').exists()).toBe(true)
 expect(view.find('.sidebar a[href="/admin/knowledge-spaces"]').exists()).toBe(true)
 expect(view.find('.sidebar a[href="/admin/users"]').exists()).toBe(true)
 const knowledge=view.findAll('.sidebar .group-toggle').find(item=>item.text()==='知识协作')!
 await knowledge.trigger('click');expect(knowledge.attributes('aria-expanded')).toBe('true')
 await knowledge.trigger('click');expect(knowledge.attributes('aria-expanded')).toBe('false')
 await view.get('button[aria-label="打开导航"]').trigger('click');await flushPromises()
 expect([...document.querySelectorAll('.navigation-drawer .group-toggle')].map(heading=>heading.textContent?.trim())).toEqual(['服务与工单','知识协作','运营与配置','账号与权限'])
})
