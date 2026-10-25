import { browserEnvironment } from './Browser-Environment.mjs'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { randomBytes } from 'node:crypto'
import { actorRequest } from './Browser-Review.mjs'
import { signInDemo, changeDemoPassword } from './Demo-Credentials.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const { base, envPath, env, evidence } = await browserEnvironment(root)
const origin = new URL(base)
if (origin.protocol !== 'http:' || origin.hostname !== '127.0.0.1' || origin.pathname !== '/') throw Error('Only the isolated local Ticknova browser stack is allowed')
const { chromium } = await import(pathToFileURL(resolve(root, 'frontend/node_modules/playwright/index.mjs')))
const { expect: baseExpect } = await import(pathToFileURL(resolve(root, 'frontend/node_modules/@playwright/test/index.mjs')))
const expect = baseExpect.configure({ timeout: 45000 })
const prefix = 'galaxy-batch2-2026-10-22'
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' })
const page = await context.newPage()
const checks = [], screenshots = [], errors = []
const secrets = [env.DEMO_INITIAL_PASSWORD].filter(Boolean)
let phase = 'bootstrap'
page.on('pageerror', error => errors.push(error.name))
function checked(name) { checks.push(name); console.log(`PASS ${name}`) }
async function snapshot(tag) {
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: width === 390 ? 844 : 960 })
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
    const name = `${prefix}-${tag}-${width}.png`
    await page.screenshot({ path: resolve(evidence, name), fullPage: true, animations: 'disabled' })
    screenshots.push(name)
  }
  await page.setViewportSize({ width: 1440, height: 960 })
}
try {
  let managerSignIn=await signInDemo(context.request,base,envPath,'admin')
  if(managerSignIn.login.user.mustChangePassword){const next=`Galaxy-Admin-${randomBytes(20).toString('hex')}!`; secrets.push(next); await changeDemoPassword(context.request,base,envPath,'admin',managerSignIn.password,managerSignIn.login.accessToken,next); managerSignIn=await signInDemo(context.request,base,envPath,'admin')}
  secrets.push(managerSignIn.login.accessToken,managerSignIn.password); const manager={page,token:managerSignIn.login.accessToken,user:managerSignIn.login.user}
  const editorSignIn = await signInDemo(context.request, base, envPath, 'editor')
  let password = editorSignIn.password
  const initial = editorSignIn.login; secrets.push(initial.accessToken, password)
  if (initial.user.mustChangePassword) {
    const changed = `Galaxy-${randomBytes(20).toString('hex')}!`; secrets.push(changed)
    password = await changeDemoPassword(context.request, base, envPath, 'editor', password, initial.accessToken, changed)
  }
  await page.goto(`${base}/login`)
  await page.locator('#username').fill('editor'); await page.locator('#password').fill(password)
  await page.getByRole('button', { name: /登录工作台/ }).click()
  await page.waitForURL(url => url.pathname !== '/login')
  await page.locator('a[href="/knowledge"]').first().click()
  await page.waitForURL('**/knowledge')
  phase = 'knowledge file picker'
  const space=await actorRequest(manager,base,'POST','/knowledge/spaces',{name:`Galaxy 文件回归 ${Date.now()}`})
  await actorRequest(manager,base,'PUT',`/knowledge/spaces/${space.id}/members/${initial.user.id}`,{canRead:true,canEdit:true,canReview:false,reason:'Galaxy 合成文件编辑授权'})
  await page.getByRole('button',{name:'刷新空间',exact:true}).click(); await page.locator('#knowledge-space').selectOption(space.id); await page.getByRole('button',{name:'新建文档',exact:true}).click()
  await expect(page.locator('#knowledge-upload')).toBeVisible()
  await page.locator('#knowledge-title').fill('文件控件回归手册')
  await page.locator('#knowledge-file').setInputFiles({ name: 'unsupported.exe', mimeType: 'application/octet-stream', buffer: Buffer.from('invalid') })
  await expect(page.locator('#knowledge-file-issue')).toContainText('仅支持')
  await expect(page.getByRole('button', { name: '上传并解析', exact: true })).toBeDisabled()
  await snapshot('file-invalid')
  await page.getByRole('button', { name: /清除已选文件 unsupported.exe/ }).click()
  await expect(page.locator('#knowledge-upload .file-picker-name')).toHaveText('尚未选择文件')
  await expect(page.locator('#knowledge-title')).toHaveValue('文件控件回归手册')
  checked('invalid file is explained without network upload, and clearing preserves title')

  const body = '# 虚构知识手册\n\n仅供隔离回归：核对请求和解析状态。'
  await page.locator('#knowledge-file').setInputFiles({ name: 'galaxy-knowledge.md', mimeType: 'text/markdown', buffer: Buffer.from(body) })
  await expect(page.locator('#knowledge-upload .file-picker-name')).toContainText('galaxy-knowledge.md')
  await expect(page.getByRole('button', { name: '上传并解析', exact: true })).toBeEnabled()
  await snapshot('file-selected')
  const uploadPath = '**/api/v1/knowledge/spaces/*/documents'
  await page.route(uploadPath, route => route.abort('failed'))
  await page.getByRole('button', { name: '上传并解析', exact: true }).click()
  await expect(page.locator('.error-notice')).toBeVisible()
  await expect(page.locator('#knowledge-title')).toHaveValue('文件控件回归手册')
  await expect(page.locator('#knowledge-upload .file-picker-name')).toContainText('galaxy-knowledge.md')
  await page.unroute(uploadPath)
  const response = page.waitForResponse(r => r.url().includes('/knowledge/spaces/') && r.url().endsWith('/documents') && r.request().method() === 'POST')
  await page.getByRole('button', { name: '上传并解析', exact: true }).click()
  expect((await response).status()).toBe(202)
  await page.getByRole('tab',{name:'版本历史',exact:true}).click(); await expect(page.locator('.version-list > li').first().getByText('解析就绪', { exact: true })).toBeVisible()
  await page.getByRole('button',{name:'上传新版本',exact:true}).click(); await expect(page.locator('#knowledge-version-file')).toHaveCount(1)
  await page.locator('#knowledge-version-file').setInputFiles({ name: 'bad.exe', mimeType: 'application/octet-stream', buffer: Buffer.from('invalid') })
  await expect(page.locator('#knowledge-version-file-issue')).toContainText('仅支持')
  await expect(page.getByRole('button', { name: '上传新版本文件' })).toBeDisabled()
  await snapshot('file-version')
  checked('failed upload retains the file; retry receives 202, parser reaches READY, version picker rejects invalid file')
  await page.getByRole('button',{name:/清除已选文件 bad.exe/}).click()

  phase = 'assistant status'
  await page.locator('a[href="/assistant"]').first().click()
  await page.waitForURL('**/assistant')
  await page.locator('#assistant-question').fill('隔离知识手册是否已有可引用的发布内容？')
  const aiResponse = page.waitForResponse(r => r.url().endsWith('/ai/answers') && r.request().method() === 'POST')
  await page.getByRole('button', { name: '查找答案' }).click()
  expect((await aiResponse).status()).toBe(202)
  await expect(page.locator('.ai-status-queued')).toBeVisible()
  await snapshot('ai-queued')
  const cancelResponse = page.waitForResponse(r => r.url().includes('/ai/jobs/') && r.url().endsWith('/cancel') && r.request().method() === 'POST')
  await page.getByRole('button', { name: '中止生成' }).click()
  expect((await cancelResponse).status()).toBe(204)
  await expect(page.locator('.ai-status-cancelled')).toBeVisible()
  await expect(page.getByRole('button', { name: '中止生成' })).toHaveCount(0)
  await snapshot('ai-cancelled')
  checked('AI status follows a real QUEUED job and only shows CANCELLED after server-confirmed cancellation')
  expect(errors).toEqual([])
  const output = { environment: 'isolated ticknova-test browser DB, Chromium 1440/390, local mock AI with worker disabled', checks, screenshots, pageErrors: errors, status: 'PASS' }
  await writeFile(resolve(evidence, `${prefix}.json`), JSON.stringify(output, null, 2))
} catch (failure) {
  const message = secrets.reduce((value, secret) => value.split(secret).join('[REDACTED]'), String(failure?.stack || failure))
  await writeFile(resolve(evidence, `${prefix}.json`), JSON.stringify({ phase, checks, screenshots, pageErrors: errors, status: 'FAIL', message }, null, 2))
  console.error(`FAIL ${phase}: ${message}`)
  process.exitCode = 1
} finally {
  await context.close()
  await browser.close()
}
