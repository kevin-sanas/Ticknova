// Browser-only regression: serves a built frontend and synthetic responses in-memory.
// No network, database, or real user session is used.
const {chromium} = require('../frontend/node_modules/@playwright/test')
const fs = require('fs')
const path = require('path')
const root = path.resolve(process.env.TICKNOVA_DIST || path.join(__dirname, '../frontend/dist'))
const origin = 'https://ticknova-fixture.invalid'
const answer = ('【离线合成】请先检查网络连接、服务状态和错误信息，再按步骤核对配置。\n\n').repeat(45) + '正文结束标记。\nhttps://example.invalid/' + 'long-segment-'.repeat(100) + '链接结束标记'
const citation = { documentId:'fixture-doc', versionId:'fixture-version', version:1, title:'【离线合成知识来源】' + '超长连续标题'.repeat(35), page:1, chunkId:'fixture-chunk', snippet: '【离线合成引用片段】' + '检查网络连接和服务配置。'.repeat(100) + '引用结束标记' }
const result = { suggestionId:'fixture-answer', kind:'ANSWER', mode:'mock', profile:'synthetic-fixture', outcome:'ANSWERED', answer, summary:null, serviceId:null, priority:null, tags:[], citations:[citation], requiresHumanReview:true, feedback:null }
const user = {id:'fixture-user',username:'fixture',displayName:'模拟账号',roles:['REQUESTER'],mustChangePassword:false,active:true}
const contentTypes = {'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8','.css':'text/css; charset=utf-8','.png':'image/png','.svg':'image/svg+xml','.woff2':'font/woff2','.woff':'font/woff'}
async function run(viewport) {
 const browser=await chromium.launch({headless:true,...(process.env.TICKNOVA_BROWSER_EXECUTABLE ? {executablePath:process.env.TICKNOVA_BROWSER_EXECUTABLE} : {})})
 const context=await browser.newContext({viewport,offline:true,reducedMotion:'reduce'})
 const page=await context.newPage(); const unexpected=[]; const calls=[]
 await context.route('**/*',async route=>{
  const request=route.request(); const u=new URL(request.url())
  if(u.origin!==origin){unexpected.push(u.origin); return route.abort()}
  if(u.pathname.startsWith('/api/')) {
   const p=u.pathname, method=request.method(); calls.push(method+' '+p); let body, status=200
   if(p==='/api/v1/auth/login'&&method==='POST')body={accessToken:'synthetic-token',expiresIn:1800,user}
   else if(p==='/api/v1/auth/me'&&method==='GET')body=user
   else if(p==='/api/v1/ai/config'&&method==='GET')body={mode:'mock',profile:'synthetic-fixture',externalProvider:false}
   else if(p==='/api/v1/knowledge/spaces'&&method==='GET')body=[]
   else if(p==='/api/v1/workspace/portal'&&method==='GET')body={query:'',knowledge:[],popularDocuments:[],categories:[],tickets:[]}
   else if(p==='/api/v1/ai/answers'&&method==='POST'){body={jobId:'fixture-job'};status=202}
   else if(p==='/api/v1/jobs/fixture-job'&&method==='GET')body={id:'fixture-job',status:'COMPLETED',errorCode:null,attemptCount:1,result}
   else {unexpected.push(method+' '+p);return route.fulfill({status:404,contentType:'application/json',body:JSON.stringify({status:404,title:'离线测试未配置'})})}
   return route.fulfill({status,contentType:'application/json; charset=utf-8',body:JSON.stringify(body)})
  }
  const name=(u.pathname==='/'||!path.extname(u.pathname))?'index.html':decodeURIComponent(u.pathname).replace(/^\/+/, '')
  const f=path.resolve(root,name); if(!f.startsWith(root+path.sep))return route.abort()
  if(!fs.existsSync(f)){unexpected.push('missing '+name);return route.fulfill({status:404,body:'not found'})}
  return route.fulfill({status:200,contentType:contentTypes[path.extname(f)]||'application/octet-stream',body:fs.readFileSync(f)})
 })
 try {
  await page.goto(origin+'/login',{waitUntil:'domcontentloaded'});
  await page.locator('#username').fill('fixture'); await page.locator('#password').fill('synthetic-password');
  await page.getByRole('button',{name:'登录工作台'}).click();
  await page.waitForURL('**/portal',{timeout:15000});
  const mobileMenu=page.getByRole('button',{name:'打开导航'}); if(await mobileMenu.isVisible()) await mobileMenu.click();
  await page.getByRole('button',{name:'知识协作'}).click(); await page.getByRole('link',{name:'知识助手'}).click();
  const input=page.locator('#assistant-question');await input.waitFor({state:'visible'});await input.fill('离线长回复验收问题');
  await page.locator('#assistant-submit').waitFor({state:'visible'});await page.waitForFunction(()=>!document.querySelector('#assistant-submit')?.disabled);
  await page.locator('#assistant-submit').click();await page.locator('.ai-answer').waitFor({state:'attached',timeout:10000});
  await page.locator('.ai-citations summary').click();
  const metrics=await page.evaluate(()=>{
   const answer=document.querySelector('.ai-answer');const citations=document.querySelector('.ai-citations');const result=document.querySelector('.ai-result');const link=document.querySelector('.citation-link');
   const size=e=>e?{client:e.clientWidth,scroll:e.scrollWidth,box:Math.round(e.getBoundingClientRect().width),height:Math.round(e.getBoundingClientRect().height),display:getComputedStyle(e).display,visibility:getComputedStyle(e).visibility}:null;
   return {viewport:innerWidth,pageWidth:document.documentElement.scrollWidth,answer:size(answer),citations:size(citations),result:size(result),answerComplete:answer?.textContent.includes('链接结束标记'),citationComplete:citations?.querySelector('details[open] p')?.innerText.includes('引用结束标记'),sourceHref:link?.getAttribute('href'),hasSourceLink:!!link,citationExpanded:!!citations?.querySelector('details[open]'),ancestors:answer?(()=>{let e=answer;const a=[];while(e&&a.length<8){const r=e.getBoundingClientRect();const s=getComputedStyle(e);a.push({name:e.className,display:s.display,visibility:s.visibility,width:r.width,height:r.height});e=e.parentElement}return a})():[]}
  });
  if(process.env.TICKNOVA_SCREENSHOT_DIR){const file=path.resolve(process.env.TICKNOVA_SCREENSHOT_DIR,`long-ai-offline-${viewport.width}.png`);await page.screenshot({path:file,fullPage:true})}
  console.log(JSON.stringify({viewport,metrics,unexpected,synthetic:true}));
  if(!metrics.answer?.height || metrics.answer.client < 120 || !metrics.citationExpanded || unexpected.length || !metrics.answerComplete || !metrics.citationComplete || !metrics.hasSourceLink || metrics.sourceHref !== '/knowledge/documents/fixture-doc?versionId=fixture-version&chunkId=fixture-chunk' || metrics.pageWidth>viewport.width+1 || metrics.answer.scroll>metrics.answer.client+1 || metrics.citations.scroll>metrics.citations.client+1)process.exitCode=1
 } finally {await browser.close()}
}
;(async()=>{await run({width:1280,height:800});await run({width:390,height:844})})().catch(e=>{console.error(e.stack);process.exitCode=1})
