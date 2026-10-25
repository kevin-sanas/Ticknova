import { browserEnvironment } from './Browser-Environment.mjs'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import {actorRequest,createEditorFixture,createReviewerFixture,permitReview,approveVersion} from './Browser-Review.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const { base, envPath, env, evidence } = await browserEnvironment(root)
const { chromium } = await import(pathToFileURL(resolve(root, 'frontend/node_modules/playwright/index.mjs')))
const { expect: baseExpect } = await import(pathToFileURL(resolve(root, 'frontend/node_modules/@playwright/test/index.mjs')))
const expect = baseExpect.configure({ timeout: 45_000 })
const secrets = Object.entries(env).filter(([key]) => /PASSWORD|PEPPER|TOKEN/.test(key)).map(([, value]) => value).filter(Boolean)
const browser = await chromium.launch({ headless: true }); const contexts = []; const checks = []; const errors = []; let step = 'login'
function checked(name) { checks.push(name); console.log(`PASS ${name}`) }
async function login(username,password) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1050 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' }); contexts.push(context)
  const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message))
  await page.goto(`${base}/login`); await page.locator('#username').fill(username); await page.locator('#password').fill(password || env[`DEMO_BROWSER_${username.toUpperCase()}_PASSWORD`] || env.DEMO_INITIAL_PASSWORD)
  const response = page.waitForResponse(r => r.url().endsWith('/auth/login') && r.request().method() === 'POST')
  await page.getByRole('button', { name: /登录工作台/ }).click(); const result = await response; expect(result.status()).toBe(200); const loginResult = await result.json(); const user = loginResult.user
  if (user.mustChangePassword) throw new Error('Run the full Test-Browser.ps1 first to complete the initial demo-account password flow.')
  await page.waitForURL(url => url.pathname !== '/login'); await page.locator('a[href="/knowledge"]').first().click(); await page.waitForURL('**/knowledge')
  return { page, userId: user.id, user, token:loginResult.accessToken }
}
async function grant(page, spaceId, userId, read) {
  await page.locator('a[href="/admin/knowledge-spaces"]').first().click(); await page.waitForURL('**/admin/knowledge-spaces'); await page.locator('#knowledge-space').selectOption(spaceId)
  if (!(await page.locator('#knowledge-member-form').isVisible())) await page.getByRole('button', { name: '管理成员', exact: true }).click()
  await expect(page.locator('#knowledge-member-user')).toBeEnabled(); await page.locator('#knowledge-member-user').selectOption(userId)
  const checkbox = page.getByLabel('允许阅读', { exact: true }); if (read) await checkbox.check(); else await checkbox.uncheck()
  await page.locator('#knowledge-member-reason').fill(read ? '虚构演示知识阅读授权' : '虚构演示撤销知识阅读授权')
  const response = page.waitForResponse(r => r.url().includes(`/members/${userId}`) && r.request().method() === 'PUT')
  await page.getByRole('button', { name: '保存成员权限', exact: true }).click(); expect((await response).status()).toBe(204)
  await expect(page.getByText('成员权限已保存。', { exact: true })).toBeVisible(); await page.getByRole('dialog',{name:/空间成员/}).locator('.el-dialog__headerbtn').click()
}
async function uploadVersion(page, name, mimeType, text) {
  await page.getByRole('button',{name:'上传新版本',exact:true}).click()
  await page.locator('#knowledge-version-file').setInputFiles({ name, mimeType, buffer: Buffer.from(text, 'utf8') })
  const response = page.waitForResponse(r => /\/versions$/.test(new URL(r.url()).pathname) && r.request().method() === 'POST')
  await page.locator('#knowledge-version-upload').getByRole('button', { name: '上传新版本文件', exact: true }).click()
  const result = await response; expect(result.status()).toBe(202); return result.json()
}
try {
  const manager = await login('admin'); const authorFixture=await createEditorFixture(manager,base,secret=>secrets.push(secret)); const author = await login(authorFixture.username,authorFixture.password); const editorPage=author.page; const { page: reader, userId: readerId } = await login('requester')
  const reviewerFixture=await createReviewerFixture(manager,base,secret=>secrets.push(secret)); const reviewer=await login(reviewerFixture.username,reviewerFixture.password)
  const stamp = Date.now(); const spaceName = `G3知识验收 ${stamp}`; const title = `虚构网关手册 ${stamp}`
  step = 'create space'
  const managerPage=manager.page; await managerPage.locator('a[href="/admin/knowledge-spaces"]').first().click(); await managerPage.waitForURL('**/admin/knowledge-spaces')
  await managerPage.getByRole('button', { name: '创建空间', exact: true }).click(); await managerPage.locator('#knowledge-space-name').fill(spaceName)
  const created = managerPage.waitForResponse(r => r.url().endsWith('/knowledge/spaces') && r.request().method() === 'POST')
  await managerPage.getByRole('button', { name: '保存空间', exact: true }).click(); const spaceResponse = await created; expect(spaceResponse.status()).toBe(201); const space = await spaceResponse.json()
  await expect(managerPage.locator('#knowledge-space')).toHaveValue(space.id);
  await actorRequest(manager,base,'PUT',`/knowledge/spaces/${space.id}/members/${author.user.id}`,{canRead:true,canEdit:true,canReview:false,reason:'合成编辑授权'}); checked('administrator creates an explicitly authorized knowledge space')
  await permitReview(manager,reviewer,base,space.id)
  await editorPage.getByRole('button', {name:'刷新空间',exact:true}).click(); await editorPage.locator('#knowledge-space').selectOption(space.id); await editorPage.getByRole('button',{name:'新建文档',exact:true}).click()
  step = 'upload and parse Markdown'
  await editorPage.locator('#knowledge-title').fill(title); await editorPage.locator('#knowledge-file').setInputFiles({ name: 'gateway.md', mimeType: 'text/markdown', buffer: Buffer.from('# 网关排障\n\n虚构第一版知识：先检查测试网关的健康状态。', 'utf8') })
  const accepted = editorPage.waitForResponse(r => r.url().endsWith(`/spaces/${space.id}/documents`) && r.request().method() === 'POST')
  await editorPage.locator('#knowledge-upload').getByRole('button', { name: '上传并解析', exact: true }).click(); const acceptedResponse = await accepted; expect(acceptedResponse.status()).toBe(202); const initial = await acceptedResponse.json()
  await editorPage.getByRole('tab',{name:'版本历史',exact:true}).click(); await expect(editorPage.locator('.version-list').getByText('解析就绪', { exact: true })).toBeVisible(); checked('browser upload reaches real Docling and versioned chunks')
  step = 'publish and grant reading'
  await approveVersion(author,reviewer,base,initial.documentId,initial.versionId)
  await editorPage.getByRole('button', { name: '刷新状态', exact: true }).click()
  await editorPage.locator('.version-list > li').first().getByRole('button', { name: '发布此版本', exact: true }).click(); await expect(editorPage.getByText('版本已发布。',{exact:true})).toBeVisible()
  await grant(managerPage, space.id, readerId, true); await reader.getByRole('button', { name: '刷新空间', exact: true }).click(); await reader.locator('#knowledge-space').selectOption(space.id)
  await reader.getByRole('button', { name: title, exact: true }).click(); await reader.getByRole('tab',{name:'版本历史',exact:true}).click(); await reader.getByRole('button', { name: '查看解析结果', exact: true }).click()
  await expect(reader.locator('.knowledge-chunks')).toContainText('虚构第一版知识'); await expect(reader.getByRole('button', { name: '发布此版本', exact: true })).toHaveCount(0)
  checked('read-only ACL member can read the current published text and cannot edit')
  // Legacy bookmarks are a compatibility regression only; normal document links use the dedicated route.
  await reader.locator('a[href="/knowledge"]').first().click(); await reader.waitForURL('**/knowledge')
  await reader.evaluate(path=>{history.pushState(history.state,'',path);dispatchEvent(new PopStateEvent('popstate',{state:history.state}))},`/knowledge?documentId=${initial.documentId}&versionId=${initial.versionId}&tab=parse`)
  await expect.poll(()=>new URL(reader.url()).pathname).toBe(`/knowledge/documents/${initial.documentId}`); await expect(reader.locator('.knowledge-chunks')).toContainText('虚构第一版知识'); checked('legacy document bookmark redirects to the dedicated document route with exact version')
  step = 'new version preserves old publication'
  await expect(editorPage).toHaveURL(new RegExp(`/knowledge/documents/${initial.documentId}`))
  const nextVersion=await uploadVersion(editorPage, 'gateway.md', 'text/markdown', '# 网关排障\n\n虚构第二版知识：先检查健康状态，再核对路由配置。')
  await expect(editorPage.getByText('新版本处理或待发布期间，旧版本仍保持发布。', { exact: true })).toBeVisible()
  await expect(editorPage.locator('.version-list > li').first().getByText('解析就绪', { exact: true })).toBeVisible()
  await approveVersion(author,reviewer,base,nextVersion.documentId,nextVersion.versionId)
  await editorPage.getByRole('button', { name: '刷新状态', exact: true }).click()
  await editorPage.locator('.version-list > li').first().getByRole('button', { name: '发布此版本', exact: true }).click()
  await reader.getByRole('button', { name: '刷新状态', exact: true }).click(); await reader.getByRole('tab',{name:'版本历史',exact:true}).click(); await reader.locator('.version-list > li').first().getByRole('button', { name: '查看解析结果', exact: true }).click()
  await expect(reader.locator('.knowledge-chunks')).toContainText('虚构第二版知识'); checked('new version parsing retains the old publication until atomic publish')
  step = 'failed parse and manual retry'
  const broken = await uploadVersion(editorPage, 'broken.pdf', 'application/pdf', '%PDF-broken test fixture')
  const newest = editorPage.locator('.version-list > li').first(); await expect(newest.getByText('解析失败', { exact: true })).toBeVisible(); await expect(newest).toContainText('INVALID_DOCUMENT')
  const retried = editorPage.waitForResponse(r => r.url().endsWith(`/versions/${broken.versionId}/retry`) && r.request().method() === 'POST')
  await newest.getByRole('button', { name: '重试解析', exact: true }).click(); const retry = await retried; expect(retry.status()).toBe(202); expect((await retry.json()).jobId).not.toBe(broken.jobId)
  await expect(newest.getByText('解析失败', { exact: true })).toBeVisible(); await expect(editorPage.getByText('新版本处理或待发布期间，旧版本仍保持发布。', { exact: true })).toBeVisible()
  checked('failed PDF exposes a stable error and manual retry creates a new job')
  await editorPage.evaluate(() => window.scrollTo(0, 0)); await expect.poll(() => editorPage.evaluate(() => window.scrollY)).toBe(0)
  await editorPage.screenshot({ path: resolve(evidence, 'knowledge-desktop.png'), fullPage: true, animations: 'disabled' }); await editorPage.setViewportSize({ width: 390, height: 844 })
  await expect.poll(() => editorPage.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await editorPage.evaluate(() => window.scrollTo(0, 0)); await expect.poll(() => editorPage.evaluate(() => window.scrollY)).toBe(0)
  await editorPage.screenshot({ path: resolve(evidence, 'knowledge-mobile.png'), fullPage: true, animations: 'disabled' }); await editorPage.setViewportSize({ width: 1440, height: 1050 }); checked('390px real knowledge page has no horizontal overflow')
  step = 'revoke reading while content is open'
  await grant(managerPage, space.id, readerId, false); await reader.getByRole('button', { name: '刷新状态', exact: true }).click()
  await expect(reader.locator('.knowledge-chunks')).toHaveCount(0); await expect(reader.locator('.knowledge-detail')).toHaveCount(0); checked('actual authorization-hiding 404 clears previously displayed private content')
  step = 'withdraw and archive'
  await editorPage.getByRole('button', { name: '撤回发布', exact: true }).click(); await editorPage.getByRole('button', { name: '确认撤回', exact: true }).click(); await expect(editorPage.getByText('已撤回发布。', { exact: true })).toBeVisible()
  await editorPage.getByRole('button', { name: '更多文档操作', exact: true }).click(); await editorPage.getByRole('menuitem', { name: '归档文档', exact: true }).click(); await editorPage.getByRole('button', { name: '确认归档', exact: true }).click(); await expect(editorPage.getByRole('button', { name: title, exact: true })).toHaveCount(0)
  checked('withdrawal and archive complete through the browser'); expect(errors).toEqual([])
  await writeFile(resolve(evidence, 'g3-browser-verification.json'), JSON.stringify({ date: new Date().toISOString(), base, status: 'PASS', checks, uncaughtPageErrors: errors.length, fixtureDocumentId: initial.documentId }, null, 2) + '\n')
} catch (error) {
  let message = `G3 browser failure at ${step}: ${error.message}`; for (const secret of secrets) message = message.replaceAll(secret, '[REDACTED]'); console.error(message); process.exitCode = 1
} finally { for (const context of contexts) await context.close(); await browser.close() }
