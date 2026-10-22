import {afterEach, expect, it, vi} from 'vitest'
import {mount, flushPromises, type VueWrapper} from '@vue/test-utils'
import {createPinia} from 'pinia'
import {createRouter, createMemoryHistory} from 'vue-router'
import ElementPlus, {ElMessageBox} from 'element-plus'
import type {Component} from 'vue'
import {bindApiSession} from '../src/api'
import {useSessionStore} from '../src/stores/session'
import NewTicketView from '../src/views/NewTicketView.vue'
import TicketDetailView from '../src/views/TicketDetailView.vue'
import CatalogView from '../src/views/CatalogView.vue'
import UsersView from '../src/views/UsersView.vue'
import TicketsView from '../src/views/TicketsView.vue'
import App from '../src/App.vue'

let wrapper:VueWrapper|undefined
afterEach(()=>{ElMessageBox.close(); wrapper?.unmount(); wrapper=undefined; vi.unstubAllGlobals(); document.body.innerHTML=''})
const json=(value:unknown)=>new Response(JSON.stringify(value))
const ticket={id:'t1',ticketNo:'TK-1',title:'测试工单',priority:'NORMAL',status:'IN_PROGRESS',requesterId:'u1',requesterName:'申请人',queueId:'q1',queueName:'受理队列',assigneeId:null,assigneeName:null,serviceId:null,serviceName:null,version:1,createdAt:'2026-10-21T00:00:00Z',updatedAt:'2026-10-21T00:00:00Z',tags:[]}
const detail={ticket,description:'问题描述',resolutionNote:null,publicReplies:[],internalNotes:[],transitions:[],capabilities:{canReadInternal:false,canCommentPublic:true,canCommentInternal:false,canAssign:false,canEdit:false},allowedTransitions:[]}
async function render(component:Component,path:string,props={},start?:string,shell=false){
 const pinia=createPinia(), session=useSessionStore(pinia)
 session.accept({accessToken:'local-fixture',expiresIn:1800,user:{id:'u1',username:'user',displayName:'用户',roles:['REQUESTER','AGENT','ADMIN'],mustChangePassword:false}})
 bindApiSession({getToken:()=>session.token,onUnauthorized:()=>session.clear()})
 const routes=[{path,component,props:()=>props},...([{path:'/tickets',component:TicketsView},{path:'/tickets/:id',component:TicketDetailView},{path:'/tickets/new',component:NewTicketView}].filter(x=>x.path!==path)),{path:'/:pathMatch(.*)*',component:{template:'<p>目标页面</p>'}}]
 const router=createRouter({history:createMemoryHistory(),routes})
 await router.push(start||(path.includes(':id')?path.replace(':id','t1'):path));await router.isReady()
 wrapper=mount(shell?App:{template:'<router-view />'},{attachTo:document.body,global:{plugins:[pinia,router,ElementPlus]}})
 await flushPromises();return{view:wrapper,router,session}
}
function shown(element:Element){for(let current:Element|null=element;current;current=current.parentElement) if((current as HTMLElement).style.display==='none')return false;return true}
function activeMessage(){return Array.from(document.querySelectorAll('.el-message-box')).find(shown)}
function button(label:string){return Array.from(document.querySelectorAll<HTMLButtonElement>('button')).find(x=>shown(x)&&x.textContent?.trim()===label)}
async function choose(label:string){const target=button(label);expect(target,label).toBeDefined();target!.click();await flushPromises();if(['继续编辑','放弃修改并离开'].includes(label)) await vi.waitFor(()=>expect(activeMessage()).toBeUndefined())}

it('protects a new ticket draft on leave, retains it on cancel and does not block successful submission',async()=>{
 vi.stubGlobal('fetch',(url:string,init:RequestInit)=>Promise.resolve(json(url.endsWith('/services')?[]:init.method==='POST'?ticket:detail)))
 const {view,router}=await render(NewTicketView,'/tickets/new')
 await view.get('#ticket-title').setValue('尚未保存');await view.get('#ticket-description').setValue('草稿正文')
 const navigation=router.push('/portal');await flushPromises()
 expect(activeMessage()).toBeDefined()
 await choose('继续编辑');await navigation
 expect(router.currentRoute.value.path).toBe('/tickets/new');expect(view.get('#ticket-title').element).toHaveProperty('value','尚未保存')
 await view.get('form').trigger('submit');await flushPromises()
 expect(router.currentRoute.value.path).toBe('/tickets/t1');expect(activeMessage()).toBeUndefined()
})
it('protects an unsent public reply but lets an expired session leave immediately',async()=>{
 vi.stubGlobal('fetch',()=>Promise.resolve(json(detail)))
 const {view,router,session}=await render(TicketDetailView,'/tickets/:id')
 await view.get('#comment-body').setValue('不要丢失我的回复')
 const navigation=router.push('/portal');await flushPromises();expect(activeMessage()).toBeDefined()
 await choose('继续编辑');await navigation;expect(view.get('#comment-body').element).toHaveProperty('value','不要丢失我的回复')
 session.clear();await router.push('/login');await flushPromises();expect(router.currentRoute.value.path).toBe('/login')
})
it.each(['catalog','users'])('asks before closing the dirty %s dialog and allows untouched close',async kind=>{
 vi.stubGlobal('fetch',(url:string)=>Promise.resolve(json(url.includes('/users?')?{items:[],total:0,page:0,size:20}:url.endsWith('/roles')?['REQUESTER']:[])))
 await render(kind==='catalog'?CatalogView:UsersView,'/admin',kind==='catalog'?{kind:'services'}:{})
 await choose(kind==='catalog'?'＋ 新建服务':'＋ 创建用户')
 const input=document.querySelector<HTMLInputElement>(kind==='catalog'?'#catalog-name':'#user-name')!;input.value='未保存的名称';input.dispatchEvent(new Event('input',{bubbles:true}))
 await choose(kind==='catalog'?'取消':'关闭')
 expect(activeMessage()).toBeDefined()
 await choose('继续编辑');expect(input.value).toBe('未保存的名称')
 await choose(kind==='catalog'?'取消':'关闭');await choose('放弃修改并离开')
 await vi.waitFor(()=>expect((input.closest('.el-overlay') as HTMLElement).style.display).toBe('none'))
 await choose(kind==='catalog'?'＋ 新建服务':'＋ 创建用户');await choose(kind==='catalog'?'取消':'关闭')
 expect(activeMessage()).toBeUndefined()
})
it('restores filters when browser navigation changes the inbox query without remounting',async()=>{
 const urls:string[]=[];vi.stubGlobal('fetch',(url:string)=>{urls.push(url);return Promise.resolve(json(url.includes('/tickets?')?{items:[],total:0,page:0,size:20}:[]))})
 const {view,router}=await render(TicketsView,'/tickets')
 await view.get('#ticket-search').setValue('VPN');await view.get('form').trigger('submit');await flushPromises()
 expect(router.currentRoute.value.query.keyword).toBe('VPN')
 await router.push('/tickets?keyword=邮箱&status=NEW&view=unassigned');await flushPromises()
 expect(view.get('#ticket-search').element).toHaveProperty('value','邮箱');expect(urls.at(-1)).toContain('unassigned=true');expect(urls.at(-1)).toContain('status=NEW')
 router.back();await flushPromises();expect(view.get('#ticket-search').element).toHaveProperty('value','VPN')
})
it('loads the latest inbox selection while its earlier read is pending and keeps catalog options',async()=>{
 let release!:(value:Response)=>void;let reads=0;const urls:string[]=[]
 vi.stubGlobal('fetch',(url:string)=>{urls.push(url);if(url.includes('/tickets?')){reads++;return reads===1?new Promise<Response>(resolve=>{release=resolve}):Promise.resolve(json({items:[{...ticket,title:'最新待分配结果'}],total:1,page:0,size:20}))}return Promise.resolve(json(url.endsWith('/services')?[{id:'s1',code:'VPN',name:'VPN服务',active:true}]:[]))})
 const {view}=await render(TicketsView,'/tickets');await choose('待分配');release(json({items:[{...ticket,title:'过期的全部结果'}],total:1,page:0,size:20}));await flushPromises()
 expect(urls.filter(x=>x.includes('/tickets?')).at(-1)).toContain('unassigned=true')
 expect(view.text()).toContain('最新待分配结果');expect(view.text()).not.toContain('过期的全部结果');expect(view.get('#service-filter').text()).toContain('VPN服务')
})
it('uses the preserved inbox destination for visible detail return and new-ticket cancel links',async()=>{
 const scroll=vi.fn();vi.stubGlobal('scrollTo',scroll);vi.stubGlobal('scrollY',456)
 vi.stubGlobal('fetch',(url:string)=>Promise.resolve(json(url.includes('/tickets?')?{items:[ticket],total:21,page:1,size:20}:url.endsWith('/tickets/t1')?detail:[])))
 const {view,router}=await render(TicketsView,'/tickets',{},'/tickets?keyword=VPN&page=2&view=mine')
 await view.get('.desktop-table a.ticket-title').trigger('click');await flushPromises()
 expect(view.get('a.back-link').attributes('href')).toBe('/tickets?keyword=VPN&page=2&view=mine')
 await view.get('a.back-link').trigger('click');await flushPromises();expect(router.currentRoute.value.query.page).toBe('2');expect(view.get('#ticket-search').element).toHaveProperty('value','VPN')
 await vi.waitFor(()=>expect(scroll).toHaveBeenCalledWith({top:456,behavior:'instant'}))
 await view.get('a[href="/tickets/new"]').trigger('click');await flushPromises()
 expect(view.findAll('a').filter(x=>x.text()==='取消')[0]!.attributes('href')).toBe('/tickets?keyword=VPN&page=2&view=mine')
})
it.each(['public','internal'])('retains %s text typed after a comment send begins',async kind=>{
 let release!:(value:Response)=>void;const sent:unknown[]=[]
 vi.stubGlobal('fetch',(url:string,init:RequestInit)=>{if(url.endsWith('/comments')){sent.push(JSON.parse(String(init.body)));return new Promise<Response>(resolve=>{release=resolve})}return Promise.resolve(json({...detail,capabilities:{...detail.capabilities,canReadInternal:true,canCommentInternal:true}}))})
 const {view}=await render(TicketDetailView,'/tickets/:id');if(kind==='internal')await view.get('#internal-tab').trigger('click')
 await view.get('#comment-body').setValue('已提交的原稿');await view.get('.reply-form').trigger('submit');await flushPromises();await view.get('#comment-body').setValue('发送期间继续补写的草稿')
 release(new Response(null,{status:204}));await flushPromises()
 expect(view.get('#comment-body').element).toHaveProperty('value','发送期间继续补写的草稿');expect(sent).toEqual([{visibility:kind==='internal'?'INTERNAL_NOTE':'PUBLIC_REPLY',body:'已提交的原稿'}])
})
it('confirms explicit logout before clearing a dirty draft or sending logout',async()=>{
 let logouts=0;vi.stubGlobal('fetch',(url:string)=>{if(url.endsWith('/auth/logout')){logouts++;return Promise.resolve(new Response(null,{status:204}))}return Promise.resolve(json([]))})
 const {view,session,router}=await render(NewTicketView,'/tickets/new',{},undefined,true)
 await view.get('#ticket-title').setValue('需要保留的草稿');await choose('退出');expect(logouts).toBe(0);expect(activeMessage()).toBeDefined()
 await choose('继续编辑');expect(session.authenticated).toBe(true);expect(view.get('#ticket-title').element).toHaveProperty('value','需要保留的草稿')
 await choose('退出');await choose('放弃修改并离开');expect(logouts).toBe(1);expect(session.authenticated).toBe(false);expect(router.currentRoute.value.path).toBe('/login')
})
it('offers compact detail drawers and preserves copilot choices across closing',async()=>{
 vi.stubGlobal('matchMedia',()=>({matches:true,addEventListener:vi.fn(),removeEventListener:vi.fn()}))
 vi.stubGlobal('fetch',(url:string)=>Promise.resolve(json(url.endsWith('/ai/config')?{mode:'mock',profile:'mock',externalProvider:false}:url.endsWith('/knowledge/spaces')?[]:detail)))
 await render(TicketDetailView,'/tickets/:id')
 await choose('工单信息与操作');expect(document.querySelector('.ticket-properties-drawer .meta-list')?.textContent).toContain('受理队列')
 ;(document.querySelector('.ticket-properties-drawer .el-drawer__close-btn') as HTMLButtonElement).click();await flushPromises()
 await choose('AI Copilot');const input=document.querySelector<HTMLSelectElement>('.ticket-copilot-drawer #ai-kind')!;expect(input).not.toBeNull();input.value='SUMMARY';input.dispatchEvent(new Event('change',{bubbles:true}));await flushPromises()
 ;(document.querySelector('.ticket-copilot-drawer .el-drawer__close-btn') as HTMLButtonElement).click();await flushPromises();await choose('AI Copilot');expect(document.querySelector<HTMLSelectElement>('.ticket-copilot-drawer #ai-kind')!.value).toBe('SUMMARY')
})
it.each([201,400])('locks a new ticket during a deferred write and retains failed input (%s)',async status=>{
 let release!:(value:Response)=>void
 vi.stubGlobal('fetch',(url:string,init:RequestInit)=>init?.method==='POST'&&url.endsWith('/tickets')?new Promise<Response>(resolve=>{release=resolve}):Promise.resolve(json(url.endsWith('/services')?[]:detail)))
 const {view,router}=await render(NewTicketView,'/tickets/new')
 await view.get('#ticket-title').setValue('提交的标题');await view.get('#ticket-description').setValue('提交的正文');await view.get('form').trigger('submit');await flushPromises()
 for(const id of ['ticket-title','ticket-description','ticket-service','ticket-priority'])expect(view.get(`#${id}`).element,`${id} must be disabled during submit`).toHaveProperty('disabled',true)
 release(new Response(JSON.stringify(status===201?ticket:{title:'无法创建',status}),{status}));await flushPromises()
 if(status===201)expect(router.currentRoute.value.path).toBe('/tickets/t1')
 else {expect(router.currentRoute.value.path).toBe('/tickets/new');expect(view.get('#ticket-title').element).toHaveProperty('value','提交的标题');expect(view.get('#ticket-description').element).toHaveProperty('value','提交的正文');expect(view.get('#ticket-title').element).toHaveProperty('disabled',false)}
})
it.each([201,400])('locks status fields during a deferred transition and retains failed input (%s)',async status=>{
 let release!:(value:Response)=>void
 vi.stubGlobal('fetch',(url:string,init:RequestInit)=>url.endsWith('/transitions')&&init?.method==='POST'?new Promise<Response>(resolve=>{release=resolve}):Promise.resolve(json({...detail,allowedTransitions:['RESOLVED']})))
 const {view}=await render(TicketDetailView,'/tickets/:id')
 await view.get('#target-status').setValue('RESOLVED');await view.get('#transition-reason').setValue('已排除故障');await view.get('#target-status').element.closest('form')!.dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));await flushPromises()
 expect(view.get('#target-status').element).toHaveProperty('disabled',true);expect(view.get('#transition-reason').element).toHaveProperty('disabled',true)
 release(new Response(JSON.stringify(status===201?ticket:{title:'无法流转',status}),{status}));await flushPromises()
 expect(view.get('#target-status').element).toHaveProperty('disabled',false);expect(view.get('#transition-reason').element).toHaveProperty('value',status===201?'':'已排除故障');expect(view.get('#target-status').element).toHaveProperty('value',status===201?'':'RESOLVED')
})
it.each([['catalog',201],['catalog',400],['users',201],['users',400]] as const)('locks %s creation fields during a deferred write and retains failures (%s)',async(kind,status)=>{
 let release!:(value:Response)=>void
 vi.stubGlobal('fetch',(url:string,init:RequestInit)=>init?.method==='POST'?new Promise<Response>(resolve=>{release=resolve}):Promise.resolve(json(url.includes('/users?')?{items:[],total:0,page:0,size:20}:url.endsWith('/roles')?['REQUESTER','AGENT']:[])))
 const {view}=await render(kind==='catalog'?CatalogView:UsersView,'/admin',kind==='catalog'?{kind:'services'}:{})
 await choose(kind==='catalog'?'＋ 新建服务':'＋ 创建用户')
 const ids=kind==='catalog'?['catalog-code','catalog-name']:['new-username','user-name','initial-password']
 for(const id of ids){const input=view.get(`#${id}`);await input.setValue(id==='initial-password'?'Local-fixture-123':id==='catalog-code'?'SUPPORT':'fixture')}
 if(kind==='users')await view.get('fieldset input').setValue(true)
 await view.get('.dialog-form form,form.dialog-form').trigger('submit');await flushPromises()
 for(const id of ids)expect(view.get(`#${id}`).element,`${id} locked during write`).toHaveProperty('disabled',true)
 if(kind==='users')for(const checkbox of view.findAll('fieldset input'))expect(checkbox.element).toHaveProperty('disabled',true)
 release(new Response(JSON.stringify(status===201?{}:{title:'无法保存',status}),{status}));await flushPromises()
 if(status===201)await vi.waitFor(()=>expect((view.get(`#${ids[0]}`).element.closest('.el-overlay') as HTMLElement).style.display).toBe('none'))
 else for(const id of ids){expect(view.get(`#${id}`).element).toHaveProperty('disabled',false);expect((view.get(`#${id}`).element as HTMLInputElement).value).not.toBe('')}
})
it.each(['profile','roles'])('locks every user editor during deferred %s changes without losing failed input',async kind=>{
 let release!:(value:Response)=>void
 const user={id:'u2',username:'another',displayName:'原名称',active:true,roles:['REQUESTER'],mustChangePassword:false}
 vi.stubGlobal('fetch',(url:string,init:RequestInit)=>['PATCH','PUT'].includes(String(init?.method))?new Promise<Response>(resolve=>{release=resolve}):Promise.resolve(json(url.includes('/users?')?{items:[user],total:1,page:0,size:20}:['REQUESTER','AGENT'])))
 const {view}=await render(UsersView,'/admin/users');await choose('管理');await view.get('#user-name').setValue('待保存名称');await view.findAll('fieldset input')[1]!.setValue(true)
 const form=kind==='profile'?view.get('.dialog-form form'):view.get('.settings-form');await form.trigger('submit');await flushPromises()
 for(const input of view.findAll('.dialog-form input'))expect(input.element).toHaveProperty('disabled',true)
 for(const submit of view.findAll('.dialog-form button[type="submit"]'))expect(submit.element).toHaveProperty('disabled',true)
 release(new Response(JSON.stringify({title:'未保存',status:400}),{status:400}));await flushPromises();expect(view.get('#user-name').element).toHaveProperty('value','待保存名称');expect(view.get('#user-name').element).toHaveProperty('disabled',false);expect(view.findAll('fieldset input')[1]!.element).toHaveProperty('checked',true)
})
