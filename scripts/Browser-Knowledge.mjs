import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import {createEditorFixture,permitReview,approveVersion} from './Browser-Review.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const { chromium } = await import(pathToFileURL(resolve(root, 'frontend/node_modules/playwright/index.mjs')))
const { expect: baseExpect } = await import(pathToFileURL(resolve(root, 'frontend/node_modules/@playwright/test/index.mjs')))
const expect = baseExpect.configure({ timeout: 45_000 })
const base = process.env.TICKNOVA_URL || 'http://127.0.0.1:5173'
const env = Object.fromEntries((await readFile(resolve(root, '.env'), 'utf8')).split(/\r?\n/).filter(line => line && !line.startsWith('#')).map(line => { const i = line.indexOf('='); return [line.slice(0, i), line.slice(i + 1)] }))
const secrets = Object.entries(env).filter(([key]) => /PASSWORD|PEPPER|TOKEN/.test(key)).map(([, value]) => value).filter(Boolean)
const evidence = resolve(root, 'docs/evidence'); await mkdir(evidence, { recursive: true })
const browser = await chromium.launch({ headless: true }); const contexts = []; const checks = []; const errors = []; let step = 'login'
function checked(name) { checks.push(name); console.log(`PASS ${name}`) }
async function login(username,password) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1050 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' }); contexts.push(context)
  const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message))
  await page.goto(`${base}/login`); await page.locator('#username').fill(username); await page.locator('#password').fill(password || env[`DEMO_${username.toUpperCase()}_PASSWORD`] || env.DEMO_INITIAL_PASSWORD)
  const response = page.waitForResponse(r => r.url().endsWith('/auth/login') && r.request().method() === 'POST')
  await page.getByRole('button', { name: /登录工作台/ }).click(); const result = await response; expect(result.status()).toBe(200); const loginResult = await result.json(); const user = loginResult.user
  if (user.mustChangePassword) throw new Error('Run the full Test-Browser.ps1 first to complete the initial demo-account password flow.')
  await page.waitForURL(url => url.pathname !== '/login'); await page.locator('a[href="/knowledge"]').first().click(); await page.waitForURL('**/knowledge')
  return { page, userId: user.id, user, token:loginResult.accessToken }
}
async function grant(page, userId, read) {
  if (!(await page.locator('#knowledge-member-form').isVisible())) await page.getByRole('button', { name: '管理成员', exact: true }).click()
  await expect(page.locator('#knowledge-member-user')).toBeEnabled(); await page.locator('#knowledge-member-user').selectOption(userId)
  const checkbox = page.getByLabel('允许阅读', { exact: true }); if (read) await checkbox.check(); else await checkbox.uncheck()
  await page.locator('#knowledge-member-reason').fill(read ? '虚构演示知识阅读授权' : '虚构演示撤销知识阅读授权')
  const response = page.waitForResponse(r => r.url().includes(`/members/${userId}`) && r.request().method() === 'PUT')
  await page.getByRole('button', { name: '保存成员权限', exact: true }).click(); expect((await response).status()).toBe(204)
  await expect(page.getByText('成员权限已保存。', { exact: true })).toBeVisible()
}
async function uploadVersion(page, name, mimeType, text) {
  await page.locator('#knowledge-version-file').setInputFiles({ name, mimeType, buffer: Buffer.from(text, 'utf8') })
  const response = page.waitForResponse(r => /\/versions$/.test(new URL(r.url()).pathname) && r.request().method() === 'POST')
  await page.locator('#knowledge-version-upload').getByRole('button', { name: '上传新版本并解析', exact: true }).click()
  const result = await response; expect(result.status()).toBe(202); return result.json()
}
try {
  const author = await login('admin'); const admin=author.page; const { page: reader, userId: readerId } = await login('requester')
  const reviewerFixture=await createEditorFixture(author,base,secret=>secrets.push(secret)); const reviewer=await login(reviewerFixture.username,reviewerFixture.password)
  const stamp = Date.now(); const spaceName = `G3知识验收 ${stamp}`; const title = `虚构网关手册 ${stamp}`
  step = 'create space'
  await admin.getByRole('button', { name: '创建空间', exact: true }).click(); await admin.locator('#knowledge-space-name').fill(spaceName)
  const created = admin.waitForResponse(r => r.url().endsWith('/knowledge/spaces') && r.request().method() === 'POST')
  await admin.getByRole('button', { name: '保存空间', exact: true }).click(); const spaceResponse = await created; expect(spaceResponse.status()).toBe(201); const space = await spaceResponse.json()
  await expect(admin.locator('#knowledge-space')).toHaveValue(space.id); checked('administrator creates an explicitly authorized knowledge space')
  await permitReview(author,reviewer,base,space.id)
  step = 'upload and parse Markdown'
  await admin.locator('#knowledge-title').fill(title); await admin.locator('#knowledge-file').setInputFiles({ name: 'gateway.md', mimeType: 'text/markdown', buffer: Buffer.from('# 网关排障\n\n虚构第一版知识：先检查测试网关的健康状态。', 'utf8') })
  const accepted = admin.waitForResponse(r => r.url().endsWith(`/spaces/${space.id}/documents`) && r.request().method() === 'POST')
  await admin.locator('#knowledge-upload').getByRole('button', { name: '上传并解析', exact: true }).click(); const acceptedResponse = await accepted; expect(acceptedResponse.status()).toBe(202); const initial = await acceptedResponse.json()
  await expect(admin.locator('.knowledge-versions').getByText('解析就绪', { exact: true })).toBeVisible(); checked('browser upload reaches real Docling and versioned chunks')
  step = 'publish and grant reading'
  await approveVersion(author,reviewer,base,initial.documentId,initial.versionId)
  await admin.getByRole('button', { name: '刷新状态', exact: true }).click()
  await admin.getByRole('button', { name: '发布此版本', exact: true }).click(); await expect(admin.locator('.knowledge-versions').getByText('当前发布', { exact: true })).toBeVisible()
  await grant(admin, readerId, true); await reader.getByRole('button', { name: '刷新空间', exact: true }).click(); await reader.locator('#knowledge-space').selectOption(space.id)
  await reader.getByRole('button', { name: title, exact: true }).click(); await reader.getByRole('button', { name: '查看解析结果', exact: true }).click()
  await expect(reader.locator('.knowledge-chunks')).toContainText('虚构第一版知识'); await expect(reader.getByRole('button', { name: '发布此版本', exact: true })).toHaveCount(0)
  checked('read-only ACL member can read the current published text and cannot edit')
  step = 'new version preserves old publication'
  const nextVersion=await uploadVersion(admin, 'gateway.md', 'text/markdown', '# 网关排障\n\n虚构第二版知识：先检查健康状态，再核对路由配置。')
  await expect(admin.getByText('新版本处理或待发布期间，旧版本仍保持发布。', { exact: true })).toBeVisible()
  await expect(admin.locator('.knowledge-versions > li').first().getByText('解析就绪', { exact: true })).toBeVisible()
  await approveVersion(author,reviewer,base,nextVersion.documentId,nextVersion.versionId)
  await admin.getByRole('button', { name: '刷新状态', exact: true }).click()
  await admin.locator('.knowledge-versions > li').first().getByRole('button', { name: '发布此版本', exact: true }).click()
  await reader.getByRole('button', { name: '刷新状态', exact: true }).click(); await reader.locator('.knowledge-versions > li').first().getByRole('button', { name: '查看解析结果', exact: true }).click()
  await expect(reader.locator('.knowledge-chunks')).toContainText('虚构第二版知识'); checked('new version parsing retains the old publication until atomic publish')
  step = 'failed parse and manual retry'
  const broken = await uploadVersion(admin, 'broken.pdf', 'application/pdf', '%PDF-broken test fixture')
  const newest = admin.locator('.knowledge-versions > li').first(); await expect(newest.getByText('解析失败', { exact: true })).toBeVisible(); await expect(newest).toContainText('INVALID_DOCUMENT')
  const retried = admin.waitForResponse(r => r.url().endsWith(`/versions/${broken.versionId}/retry`) && r.request().method() === 'POST')
  await newest.getByRole('button', { name: '重试解析', exact: true }).click(); const retry = await retried; expect(retry.status()).toBe(202); expect((await retry.json()).jobId).not.toBe(broken.jobId)
  await expect(newest.getByText('解析失败', { exact: true })).toBeVisible(); await expect(admin.getByText('新版本处理或待发布期间，旧版本仍保持发布。', { exact: true })).toBeVisible()
  checked('failed PDF exposes a stable error and manual retry creates a new job')
  await admin.evaluate(() => window.scrollTo(0, 0)); await expect.poll(() => admin.evaluate(() => window.scrollY)).toBe(0)
  await admin.screenshot({ path: resolve(evidence, 'knowledge-desktop.png'), fullPage: true, animations: 'disabled' }); await admin.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => admin.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await admin.evaluate(() => window.scrollTo(0, 0)); await expect.poll(() => admin.evaluate(() => window.scrollY)).toBe(0)
  await admin.screenshot({ path: resolve(evidence, 'knowledge-mobile.png'), fullPage: true, animations: 'disabled' }); await admin.setViewportSize({ width: 1440, height: 1050 }); checked('390px real knowledge page has no horizontal overflow')
  step = 'revoke reading while content is open'
  await grant(admin, readerId, false); await reader.getByRole('button', { name: '刷新状态', exact: true }).click()
  await expect(reader.locator('.knowledge-chunks')).toHaveCount(0); await expect(reader.locator('.knowledge-detail')).toHaveCount(0); checked('actual authorization-hiding 404 clears previously displayed private content')
  step = 'withdraw and archive'
  await admin.getByRole('button', { name: '撤回发布', exact: true }).click(); await admin.getByRole('button', { name: '确认撤回', exact: true }).click(); await expect(admin.getByText('已撤回发布。', { exact: true })).toBeVisible()
  await admin.getByRole('button', { name: '归档文档', exact: true }).click(); await admin.getByRole('button', { name: '确认归档', exact: true }).click(); await expect(admin.getByRole('button', { name: title, exact: true })).toHaveCount(0)
  checked('withdrawal and archive complete through the browser'); expect(errors).toEqual([])
  await writeFile(resolve(evidence, 'g3-browser-verification.json'), JSON.stringify({ date: new Date().toISOString(), base, status: 'PASS', checks, uncaughtPageErrors: errors.length, fixtureDocumentId: initial.documentId }, null, 2) + '\n')
} catch (error) {
  let message = `G3 browser failure at ${step}: ${error.message}`; for (const secret of secrets) message = message.replaceAll(secret, '[REDACTED]'); console.error(message); process.exitCode = 1
} finally { for (const context of contexts) await context.close(); await browser.close() }
