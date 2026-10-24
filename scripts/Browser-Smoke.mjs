import { browserEnvironment } from './Browser-Environment.mjs'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { createHmac, randomBytes } from 'node:crypto'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const { base, envPath, env: settings, evidence } = await browserEnvironment(root)
process.env.PLAYWRIGHT_BROWSERS_PATH ||= resolve(root, '.cache/ms-playwright')
const { chromium } = await import(pathToFileURL(resolve(root, 'frontend/node_modules/playwright/index.mjs')))
const { expect } = await import(pathToFileURL(resolve(root, 'frontend/node_modules/@playwright/test/index.mjs')))
let envText = await readFile(envPath, 'utf8')
const secrets = new Set(Object.entries(settings).filter(([key]) => /PASSWORD|PEPPER/.test(key)).map(([,value]) => value).filter(Boolean))
const browser = await chromium.launch({ headless: true })
const contexts = [], pageErrors = [], checks = []
let step = 'initialization'
function checked(name) { checks.push(name); console.log(`PASS ${name}`) }
async function login(username) {
  step = `${username}: sign in`
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' })
  contexts.push(context)
  const page = await context.newPage()
  page.on('pageerror', error => pageErrors.push(error.message))
  const key = `DEMO_BROWSER_${username.toUpperCase()}_PASSWORD`
  let password = settings[key] || settings.DEMO_INITIAL_PASSWORD
  await page.goto(`${base}/login`)
  await page.locator('#username').fill(username)
  await page.locator('#password').fill(password)
  await page.getByRole('button', { name: /登录工作台/ }).click()
  await page.waitForURL(url => url.pathname !== '/login')
  if (new URL(page.url()).pathname === '/change-password') {
    const next = `Verified-${createHmac('sha256',settings.DEMO_INITIAL_PASSWORD).update(username).digest('hex').slice(0,40)}!`
    secrets.add(next)
    // Preserve the new local demo password before changing it, so interrupted tests cannot lose it.
    settings[key] = next
    envText = envText.split(/\r?\n/).filter(line => line && !line.startsWith(`${key}=`)).join('\n') + `\n${key}=${next}\n`
    await writeFile(envPath, envText, 'utf8')
    await page.locator('#old-password').fill(password)
    await page.locator('#new-password').fill(next)
    await page.locator('#confirmation').fill(next)
    await page.getByRole('button', { name: '保存并重新登录' }).click()
    await page.waitForURL('**/login')
    password = next
    await page.locator('#username').fill(username)
    await page.locator('#password').fill(password)
    await page.getByRole('button', { name: /登录工作台/ }).click()
    await page.waitForURL(url => !['/login','/change-password'].includes(url.pathname))
    checked(`${username} first-login password change and re-login`)
  }
  expect(await page.evaluate(() => Object.keys(localStorage).length + Object.keys(sessionStorage).length)).toBe(0)
  return page
}
async function navigate(page,path) {
  // SPA navigation preserves the intentionally in-memory bearer session.
  await page.locator(`a[href="${path}"]`).first().click()
  await page.waitForURL(`**${path}`)
}
async function move(page,status,reason) {
  step = `transition ${status}`
  await page.locator('#target-status').selectOption(status)
  await page.locator('#transition-reason').fill(reason)
  const done = page.waitForResponse(r => r.url().endsWith('/transitions') && r.request().method() === 'POST')
  await page.getByRole('button',{ name:'确认更新', exact:true }).click()
  expect((await done).status()).toBe(200)
  await expect(page.getByText('工单状态已更新。',{ exact:true })).toBeVisible()
}
try {
  const admin = await login('admin')
  const requester = await login('requester')
  const agent = await login('agent')
  step = 'create ticket'
  await navigate(requester,'/tickets/new')
  const title = `浏览器验收：网关故障 ${Date.now()}`
  await requester.locator('#ticket-title').fill(title)
  await requester.locator('#ticket-description').fill('虚构演示：访问网关出现502，请检查测试配置。')
  const created = requester.waitForResponse(r=>r.url().endsWith('/api/v1/tickets')&&r.request().method()==='POST')
  await requester.getByRole('button',{ name:'提交工单', exact:true }).click()
  expect((await created).status()).toBe(201)
  await requester.waitForURL(/\/tickets\/[a-f0-9-]{36}$/)
  const ticketPath = new URL(requester.url()).pathname
  await expect(requester.getByRole('heading',{name:title})).toBeVisible()
  checked('requester creates ticket through the browser')

  step = 'engineer opens and claims'
  await agent.getByRole('button',{name:'查询',exact:true}).click()
  await agent.getByRole('link',{name:title,exact:true}).first().click()
  await agent.waitForURL(`**${ticketPath}`)
  const claimed = agent.waitForResponse(r => r.url().endsWith('/assignment') && r.request().method()==='PUT')
  await agent.getByRole('button',{name:'领取工单',exact:true}).click()
  expect((await claimed).status()).toBe(200)
  checked('engineer claims an authorized queue ticket')
  await agent.getByRole('tab',{name:/内部备注/}).click()
  const internal = `仅内部验收信息-${Date.now()}`
  await agent.locator('#comment-body').fill(internal)
  await agent.getByRole('button',{name:'保存备注',exact:true}).click()
  await expect(agent.getByText(internal,{exact:true})).toBeVisible()
  await requester.getByRole('button',{name:'刷新工单',exact:true}).click()
  await expect(requester.getByRole('tab',{name:/内部备注/})).toHaveCount(0)
  await expect(requester.getByText(internal,{exact:true})).toHaveCount(0)
  checked('internal note is hidden from requester')
  await agent.getByRole('tab',{name:/公开回复/}).click()
  await agent.locator('#comment-body').fill('已收到，正在排查配置。')
  await agent.getByRole('button',{name:'发送回复',exact:true}).click()
  await expect(agent.getByText('已收到，正在排查配置。',{exact:true})).toBeVisible()
  await move(agent,'TRIAGED','已确认影响范围')
  await move(agent,'IN_PROGRESS','开始处理')
  await move(agent,'RESOLVED','已恢复测试配置')
  await requester.getByRole('button',{name:'刷新工单',exact:true}).click()
  await expect(requester.getByRole('heading',{name:'解决说明',exact:true})).toBeVisible()
  await move(requester,'IN_PROGRESS','仍需复核')
  await agent.getByRole('button',{name:'刷新工单',exact:true}).click()
  await move(agent,'RESOLVED','复核完成，测试正常')
  await requester.getByRole('button',{name:'刷新工单',exact:true}).click()
  await move(requester,'CLOSED','确认解决')
  checked('triage → progress → resolve → requester reopen → resolve → confirm close')
  await requester.screenshot({path:resolve(evidence,'ticket-desktop.png'),fullPage:true})
  await requester.setViewportSize({width:375,height:812})
  await expect.poll(async()=>{const box=await requester.locator('.sidebar').boundingBox();return box?box.x+box.width:0}).toBeLessThanOrEqual(0.5)
  expect(await requester.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
  await requester.screenshot({path:resolve(evidence,'ticket-mobile.png'),fullPage:true,animations:'disabled'})
  checked('375px ticket layout has no horizontal document overflow')

  step = 'administrator creates service and queue'
  await navigate(admin,'/admin/services')
  await admin.getByRole('button',{name:/新建服务/}).click()
  const code = `DEMO_BROWSER_${Date.now()}`
  await admin.locator('#catalog-code').fill(code)
  await admin.locator('#catalog-name').fill('浏览器验收服务')
  await admin.getByRole('dialog').getByRole('button',{name:'保存',exact:true}).click()
  await expect(admin.getByRole('cell',{name:code,exact:true})).toBeVisible()
  await navigate(admin,'/admin/queues')
  await admin.getByRole('button',{name:/新建队列/}).click()
  await admin.locator('#catalog-code').fill(code)
  await admin.locator('#catalog-name').fill('浏览器验收队列')
  await admin.getByRole('dialog').getByRole('button',{name:'保存',exact:true}).click()
  const row=admin.getByRole('row').filter({has:admin.getByRole('cell',{name:code,exact:true})})
  await row.getByRole('button',{name:'管理成员',exact:true}).click()
  await admin.locator('#member-user').selectOption({label:'支持工程师（agent）'})
  await admin.getByRole('button',{name:'保存成员',exact:true}).click()
  await expect(admin.getByText('队列成员权限已保存。',{exact:true})).toBeVisible()
  await admin.getByRole('dialog').locator('.el-dialog__headerbtn').click()
  checked('administrator configures service, queue and membership')

  step = 'administrator creates and disables user'
  await navigate(admin,'/admin/users')
  await admin.getByRole('button',{name:/创建用户/}).click()
  const username=`verify_${Date.now()}`, fakePassword=`Test-only-${randomBytes(18).toString('hex')}!`
  secrets.add(fakePassword)
  await admin.locator('#new-username').fill(username)
  await admin.locator('#user-name').fill('验收停用账号')
  await admin.locator('#initial-password').fill(fakePassword)
  await admin.getByRole('dialog').locator('input[type=checkbox][value=REQUESTER]').check()
  await admin.getByRole('dialog').getByRole('button',{name:'创建用户',exact:true}).click()
  const userRow=admin.getByRole('row').filter({hasText:username})
  await userRow.getByRole('button',{name:'管理',exact:true}).click()
  await admin.getByRole('dialog').getByLabel('允许此账号登录').uncheck()
  await admin.getByRole('button',{name:'保存基本信息',exact:true}).click()
  await expect(admin.getByText('基本信息已保存。',{exact:true})).toBeVisible()
  await admin.getByRole('dialog').locator('.form-actions').getByRole('button',{name:'关闭',exact:true}).click()
  await expect(userRow.getByText('停用',{exact:true})).toBeVisible()
  checked('administrator creates and disables an account')
  await navigate(admin,'/admin/audit')
  await expect(admin.getByRole('heading',{name:'审计记录',exact:true})).toBeVisible()
  await admin.screenshot({path:resolve(evidence,'admin-audit.png'),fullPage:true})
  checked('administrator can inspect audit records')
  step = 'memory-only session refresh'
  await requester.reload()
  await requester.waitForURL('**/login')
  checked('page refresh clears the in-memory session')
  expect(pageErrors).toEqual([])
  await writeFile(resolve(evidence,'browser-verification.json'),JSON.stringify({date:new Date().toISOString(),base,checks,uncaughtPageErrors:pageErrors.length,status:'PASS'},null,2)+'\n')
  console.log(`Browser verification passed: ${checks.length} checks.`)
} catch(error) {
  let message=`Browser verification failed at ${step}: ${error.message}`
  if(pageErrors.length)message+=`\nPage errors: ${pageErrors.join('; ')}`
  for(const secret of secrets) message=message.replaceAll(secret,'[REDACTED]')
  console.error(message)
  process.exitCode=1
} finally {
  for(const context of contexts)await context.close()
  await browser.close()
}
