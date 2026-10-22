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
it.each([['login',LoginView],['change-password',ChangePasswordView]] as const)('uses Ticknova on the %s form',async(path,component)=>{
 const pinia=createPinia();const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]});await router.push(`/${path}`)
 view=mount(component,{attachTo:document.body,global:{plugins:[pinia,router,ElementPlus]}});await flushPromises()
 expect(view.get('.auth-brand').text()).toBe('tTicknova');expect(view.text()).not.toContain('KnowDesk AI')
})
