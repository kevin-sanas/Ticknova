import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { randomBytes } from 'node:crypto'
import { createEditorFixture, permitReview, actorRequest } from './Browser-Review.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const { chromium } = await import(pathToFileURL(resolve(root, 'frontend/node_modules/playwright/index.mjs')))
const { expect: baseExpect } = await import(pathToFileURL(resolve(root, 'frontend/node_modules/@playwright/test/index.mjs')))
const expect = baseExpect.configure({ timeout: 45000 })
const base = process.env.TICKNOVA_URL || 'http://127.0.0.1:5173'
const prefix = 'knowledge-recovery-2026-10-22'
const origin = new URL(base)
if (origin.protocol !== 'http:' || origin.hostname !== '127.0.0.1' || origin.pathname !== '/') throw Error('Ticknova local test origin required')
const env = Object.fromEntries((await readFile(resolve(root, '.env'), 'utf8')).split(/\r?\n/).filter(line => line && !line.startsWith('#')).map(line => { const i = line.indexOf('='); return [line.slice(0, i), line.slice(i + 1)] }))
const evidence = resolve(root, 'docs/evidence'); await mkdir(evidence, { recursive: true })
const browser = await chromium.launch({ headless: true })
const contexts = [], errors = [], checks = [], screenshots = [], secrets = []
let phase = 'login', fixture = {}
async function login(username, password) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1050 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' }); contexts.push(context)
  const page = await context.newPage(); page.on('pageerror', error => errors.push(error.name))
  await page.goto(`${base}/login`)
  await page.locator('#username').fill(username); await page.locator('#password').fill(password)
  const response = page.waitForResponse(r => r.url().endsWith('/auth/login') && r.request().method() === 'POST')
  await page.getByRole('button', { name: /登录工作台/ }).click(); const result = await response; expect(result.status()).toBe(200)
  const signed = await result.json(); secrets.push(signed.accessToken)
  if (signed.user.mustChangePassword) throw Error('Existing demo account still requires password change')
  await page.waitForURL(url => url.pathname !== '/login'); await page.locator('a[href="/knowledge"]').first().click(); await page.waitForURL('**/knowledge')
  return { page, user: signed.user, token: signed.accessToken }
}
function checked(name) { checks.push(name); console.log(`PASS ${name}`) }
async function snapshot(page, tag) {
  for (const width of [1920, 1440, 1280, 768, 390]) {
    await page.setViewportSize({ width, height: width === 390 ? 844 : 1050 })
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
    const name = `${prefix}-${tag}-${width}.png`
    await page.screenshot({ path: resolve(evidence, name), fullPage: true, animations: 'disabled' }); screenshots.push(name)
  }
  await page.setViewportSize({ width: 1440, height: 1050 })
}
try {
  let adminPassword = env.DEMO_INITIAL_PASSWORD
  const bootstrap = await browser.newContext()
  const first = await bootstrap.request.post(`${base}/api/v1/auth/login`, { data: { username: 'admin', password: adminPassword } })
  expect(first.status()).toBe(200)
  const firstResult = await first.json(); secrets.push(firstResult.accessToken)
  if (firstResult.user.mustChangePassword) {
    const changed = `Browser-${randomBytes(20).toString('hex')}!`; secrets.push(changed)
    const result = await bootstrap.request.post(`${base}/api/v1/auth/change-password`, { headers: { Authorization: `Bearer ${firstResult.accessToken}` }, data: { oldPassword: adminPassword, newPassword: changed } })
    expect(result.status()).toBe(204); adminPassword = changed
  }
  await bootstrap.close()
  const admin = await login('admin', adminPassword)
  const created = await createEditorFixture(admin, base, secret => secrets.push(secret))
  const reviewer = await login(created.username, created.password)
  phase = 'private fixture'
  const space = await actorRequest(admin, base, 'POST', '/knowledge/spaces', { name: `草稿审核回归 ${Date.now()}` })
  fixture.spaceId = space.id; await permitReview(admin, reviewer, base, space.id)
  await admin.page.getByRole('button', { name: '刷新空间', exact: true }).click(); await admin.page.locator('#knowledge-space').selectOption(space.id)
  await expect(admin.page.locator('#knowledge-space')).toHaveValue(space.id)
  await expect(admin.page.locator('#knowledge-upload')).toBeVisible()
  phase = 'upload synthetic source'
  const body = '# 虚构操作手册\n\n仅用于本地回归：先核对虚构网关的健康状态。'
  await admin.page.locator('#knowledge-title').fill('草稿与审核回归手册')
  await admin.page.locator('#knowledge-file').setInputFiles({ name: 'recovery.md', mimeType: 'text/markdown', buffer: Buffer.from(body) })
  await expect(admin.page.getByRole('button', { name: '上传并解析', exact: true })).toBeEnabled()
  const uploadResponse = admin.page.waitForResponse(r => r.url().endsWith(`/spaces/${space.id}/documents`) && r.request().method() === 'POST')
  await admin.page.getByRole('button', { name: '上传并解析', exact: true }).click()
  const uploaded = await uploadResponse; expect(uploaded.status()).toBe(202); const initial = await uploaded.json(); fixture.documentId = initial.documentId
  await expect(admin.page.locator('.knowledge-versions > li').first().getByText('解析就绪', { exact: true })).toBeVisible()
  checked('real parser completes an unpublished Markdown file')

  phase = 'existing blank draft recovery'
  const draftPath = `/knowledge/documents/${initial.documentId}/draft`
  const blank = { type: 'doc', content: [{ type: 'paragraph' }] }
  const savedBlank = await actorRequest(admin, base, 'PUT', draftPath, { title: '草稿与审核回归手册', categoryId: null, content: blank, version: 0 })
  await admin.page.getByRole('button', { name: '编辑文章草稿', exact: true }).click()
  await expect(admin.page.getByText('已保存的空白草稿', { exact: true })).toBeVisible()
  await admin.page.getByRole('button', { name: '预览解析正文', exact: true }).click()
  await expect(admin.page.locator('.article-source-preview')).toContainText('虚构网关的健康状态')
  expect((await actorRequest(admin, base, 'GET', draftPath)).version).toBe(savedBlank.version)
  await snapshot(admin.page, 'import-preview'); checked('existing saved blank draft remains unchanged until source preview and explicit confirmation')
  await admin.page.getByRole('button', { name: '取消', exact: true }).click()
  expect((await actorRequest(admin, base, 'GET', draftPath)).version).toBe(savedBlank.version)
  await admin.page.getByRole('button', { name: '预览解析正文', exact: true }).click()
  const confirmImport = admin.page.getByRole('button', { name: '确认导入正文', exact: true })
  await confirmImport.focus(); expect(await confirmImport.evaluate(button => button === document.activeElement)).toBe(true)
  await admin.page.keyboard.press('Enter')
  await expect(admin.page.getByText('解析正文已导入并保存，可继续编辑。')).toBeVisible()
  const imported = await actorRequest(admin, base, 'GET', draftPath)
  expect(imported.version).toBe(savedBlank.version + 1); expect(JSON.stringify(imported.content)).toContain('虚构网关的健康状态')
  checked('confirmed import saves through the draft version lock')
  await admin.page.getByRole('button', { name: '返回知识中心', exact: true }).click()
  await expect(admin.page.getByRole('button', { name: '提交审核', exact: true })).toBeVisible()

  phase = 'review rejection'
  await admin.page.getByRole('button', { name: '提交审核', exact: true }).click()
  await reviewer.page.getByRole('button', { name: '刷新空间', exact: true }).click()
  await reviewer.page.getByRole('button', { name: '待我审核', exact: true }).click()
  const openTask = reviewer.page.getByRole('button', { name: '打开草稿与审核回归手册' })
  await expect(openTask).toBeVisible()
  await snapshot(reviewer.page, 'review-queue')
  await openTask.focus(); expect(await openTask.evaluate(button => button === document.activeElement)).toBe(true)
  await reviewer.page.keyboard.press('Enter')
  await expect(reviewer.page.locator('.knowledge-reader')).toContainText('虚构网关的健康状态')
  await reviewer.page.getByRole('button', { name: '审核不通过', exact: true }).click()
  await reviewer.page.locator('#review-reason').fill('请补充虚构网关的处理步骤')
  await reviewer.page.getByRole('button', { name: '确认审核不通过', exact: true }).click()
  await expect(reviewer.page.getByText('审核意见：请补充虚构网关的处理步骤')).toBeVisible()
  await admin.page.getByRole('button', { name: '刷新状态', exact: true }).click()
  await expect(admin.page.getByText('审核意见：请补充虚构网关的处理步骤')).toBeVisible()
  checked('another current KB_EDITOR opens the exact pending version by keyboard, rejects with reason, and author sees it')

  phase = 'new version approval'
  await admin.page.locator('#knowledge-version-file').setInputFiles({ name: 'recovery-v2.md', mimeType: 'text/markdown', buffer: Buffer.from(`${body}\n\n修订：再核对虚构路由配置。`) })
  await admin.page.getByRole('button', { name: '上传新版本并解析', exact: true }).click()
  await expect(admin.page.locator('.knowledge-versions > li').first().getByText('解析就绪', { exact: true })).toBeVisible()
  await admin.page.locator('.knowledge-versions > li').first().getByRole('button', { name: '提交审核' }).click()
  await reviewer.page.getByRole('button', { name: '刷新待办' }).click()
  await reviewer.page.getByRole('button', { name: '打开草稿与审核回归手册' }).click()
  await expect(reviewer.page.locator('.knowledge-reader')).toContainText('虚构路由配置')
  await reviewer.page.locator('.knowledge-versions > li').first().getByRole('button', { name: '审核通过' }).click()
  await admin.page.getByRole('button', { name: '刷新状态', exact: true }).click()
  await expect(admin.page.locator('.knowledge-versions > li').first().getByRole('button', { name: '发布此版本' })).toBeVisible()
  checked('second eligible editor approves a revised version and author may publish')
  expect(errors).toEqual([])
  await writeFile(resolve(evidence, `${prefix}.json`), JSON.stringify({ date: new Date().toISOString(), status: 'PASS', checks, screenshots, fixture, pageErrors: errors.length, limitations: ['Synthetic data is confined to the dedicated ticknova_browser test database; cleanup is separate.', 'Chromium only; screenshots require visual inspection.'] }, null, 2) + '\n')
} catch (failure) {
  let message = `Knowledge recovery browser failure at ${phase}: ${failure.message}`
  for (const secret of secrets) message = message.replaceAll(secret, '[REDACTED]')
  console.error(message); process.exitCode = 1
  try { const path = resolve(evidence, `${prefix}-failure.png`); await contexts[0]?.pages()[0]?.screenshot({ path, fullPage: true, animations: 'disabled' }); screenshots.push(`${prefix}-failure.png`) } catch {}
} finally { for (const context of contexts) await context.close(); await browser.close() }
