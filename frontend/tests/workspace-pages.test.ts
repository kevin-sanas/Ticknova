import {afterEach,expect,it,vi} from 'vitest'
import {mount,flushPromises,type VueWrapper} from '@vue/test-utils'
import {createPinia} from 'pinia'
import {createRouter,createMemoryHistory} from 'vue-router'
import ElementPlus from 'element-plus'
import {useSessionStore} from '../src/stores/session'
import {bindApiSession} from '../src/api'
let view:VueWrapper|undefined
afterEach(()=>{view?.unmount();vi.unstubAllGlobals();document.body.innerHTML=''})
async function render(name:string,roles=['REQUESTER'],start='/portal'){
 const pinia=createPinia(),session=useSessionStore(pinia);session.accept({accessToken:'fixture',expiresIn:1800,user:{id:'u',username:'user',displayName:'用户',roles,mustChangePassword:false}});bindApiSession({getToken:()=>session.token,onUnauthorized:()=>session.clear()})
 const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{template:'<div />'}}]});await router.push(start)
 const modules=import.meta.glob('../src/views/*View.vue'),load=modules[`../src/views/${name}View.vue`];expect(load,`${name} page exists`).toBeDefined();const component=(await load!()) as {default:object}
 view=mount(component.default,{attachTo:document.body,global:{plugins:[pinia,router,ElementPlus]}});await flushPromises();return view
}
it('portal searches authorized real API results and offers no administrator actions',async()=>{
 const urls:string[]=[];vi.stubGlobal('fetch',(url:string)=>{urls.push(url);return Promise.resolve(new Response(JSON.stringify({query:new URL(url,'http://localhost').searchParams.get('q')||'',knowledge:[{id:'d',spaceId:'s',title:'VPN手册',snippet:'先检查连接',categoryId:null,viewCount:1}],tickets:[{id:'t',ticketNo:12,title:'网络故障',status:'NEW',priority:'NORMAL',updatedAt:'2026-10-21T00:00:00Z'}],popularDocuments:[],categories:[]})))})
 const page=await render('Portal');expect(page.find('a[href="/admin/users"]').exists()).toBe(false)
 await page.get('#portal-search').setValue('网络 VPN');await page.get('#portal-search-form').trigger('submit');await flushPromises();expect(urls.at(-1)).toContain('q=%E7%BD%91%E7%BB%9C+VPN');expect(page.text()).toContain('VPN手册');expect(page.find('a[href="/tickets/t"]').exists()).toBe(true)
})
it('restores ticket filter/page from the URL and scopes inbox tabs through real queries',async()=>{
 const urls:string[]=[];vi.stubGlobal('fetch',(url:string)=>{urls.push(url);return Promise.resolve(new Response(JSON.stringify(url.includes('/tickets?')?{items:[],total:0,page:1,size:20}:[])))})
 const page=await render('Tickets',['AGENT'],'/tickets?keyword=VPN&status=NEW&page=2&view=unassigned')
 expect(page.get('#ticket-search').element).toHaveProperty('value','VPN');expect(urls.find(x=>x.includes('/tickets?'))).toContain('unassigned=true');expect(urls.find(x=>x.includes('/tickets?'))).toContain('page=1')
 const mine=page.findAll('button').find(x=>x.text()==='分配给我');expect(mine).toBeDefined();await mine!.trigger('click');await flushPromises();expect(urls.at(-1)).toContain('assigneeId=u');expect(urls.at(-1)).not.toContain('unassigned=true')
})
it('analytics describes actual counts and UTC range with accessible tables',async()=>{
 vi.stubGlobal('fetch',()=>Promise.resolve(new Response(JSON.stringify({from:'2026-10-01',to:'2026-10-21',ticketTotal:2,statusCounts:[{key:'NEW',count:2}],priorityCounts:[{key:'NORMAL',count:2}],daily:[{date:'2026-10-21',created:2,resolved:0}],knowledge:{publishedDocuments:1,pendingReviews:0},ai:{completed:1,refused:0,accepted:1,rejected:0}}))))
 const page=await render('Analytics',['ADMIN']);expect(page.text()).toContain('UTC');expect(page.find('table').exists()).toBe(true);expect(page.text()).toContain('2026-10-21');expect(page.text()).toContain('新建')
})
