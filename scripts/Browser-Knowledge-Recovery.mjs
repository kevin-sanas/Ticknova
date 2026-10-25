import { browserEnvironment } from './Browser-Environment.mjs'
import { readFile, mkdir, writeFile } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { randomBytes } from 'node:crypto'
import { createEditorFixture, createReviewerFixture, actorRequest } from './Browser-Review.mjs'
import { signInDemo, changeDemoPassword } from './Demo-Credentials.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const { base, envPath, env, evidence } = await browserEnvironment(root)
const { chromium } = await import(pathToFileURL(resolve(root, 'frontend/node_modules/playwright/index.mjs')))
const { expect: baseExpect } = await import(pathToFileURL(resolve(root, 'frontend/node_modules/@playwright/test/index.mjs')))
const expect = baseExpect.configure({ timeout: 45000 })
const prefix = 'knowledge-recovery-2026-10-22'
const origin = new URL(base)
if (origin.protocol !== 'http:' || origin.hostname !== '127.0.0.1' || origin.pathname !== '/') throw Error('Ticknova local test origin required')
const browser = await chromium.launch({ headless: true })
const contexts = [], errors = [], checks = [], screenshots = [], secrets = []
let phase = 'login', fixture = {}, reviewerPage
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
  const bootstrap = await browser.newContext()
  const adminSignIn = await signInDemo(bootstrap.request, base, envPath, 'admin')
  let adminPassword = adminSignIn.password
  const firstResult = adminSignIn.login; secrets.push(firstResult.accessToken, adminPassword)
  if (firstResult.user.mustChangePassword) {
    const changed = `Browser-${randomBytes(20).toString('hex')}!`; secrets.push(changed)
    adminPassword = await changeDemoPassword(bootstrap.request, base, envPath, 'admin', adminPassword, firstResult.accessToken, changed)
  }
  await bootstrap.close()
  const admin = await login('admin', adminPassword)
  const authorFixture=await createEditorFixture(admin,base,secret=>secrets.push(secret)); const author=await login(authorFixture.username,authorFixture.password)
  const created = await createReviewerFixture(admin, base, secret => secrets.push(secret))
  const reviewer = await login(created.username, created.password)
  reviewerPage = reviewer.page
  phase = 'private fixture'
  const space = await actorRequest(admin, base, 'POST', '/knowledge/spaces', { name: `草稿审核回归 ${Date.now()}` })
  fixture.spaceId = space.id
  await actorRequest(admin,base,'PUT',`/knowledge/spaces/${space.id}/members/${author.user.id}`,{canRead:true,canEdit:true,canReview:false,reason:'草稿回归编辑授权'})
  await author.page.getByRole('button', { name: '刷新空间', exact: true }).click(); await author.page.locator('#knowledge-space').selectOption(space.id)
  await expect(author.page.locator('#knowledge-space')).toHaveValue(space.id)
  await author.page.getByRole('button',{name:'新建文档',exact:true}).click(); await expect(author.page.locator('#knowledge-upload')).toBeVisible()
  phase = 'upload synthetic source'
  const body = '# 虚构操作手册\n\n仅用于本地回归：先核对虚构网关的健康状态。'
  await author.page.locator('#knowledge-title').fill('草稿与审核回归手册')
  await author.page.locator('#knowledge-file').setInputFiles({ name: 'recovery.md', mimeType: 'text/markdown', buffer: Buffer.from(body) })
  await expect(author.page.getByRole('button', { name: '上传并解析', exact: true })).toBeEnabled()
  const uploadResponse = author.page.waitForResponse(r => r.url().endsWith(`/spaces/${space.id}/documents`) && r.request().method() === 'POST')
  await author.page.getByRole('button', { name: '上传并解析', exact: true }).click()
  const uploaded = await uploadResponse; expect(uploaded.status()).toBe(202); const initial = await uploaded.json(); fixture.documentId = initial.documentId
  await author.page.getByRole('tab',{name:'版本历史',exact:true}).click(); await expect(author.page.locator('.version-list > li').first().getByText('解析就绪', { exact: true })).toBeVisible()
  checked('real parser completes an unpublished Markdown file')

  phase = 'existing blank draft recovery'
  const draftPath = `/knowledge/documents/${initial.documentId}/draft`
  const blank = { type: 'doc', content: [{ type: 'paragraph' }] }
  const savedBlank = await actorRequest(author, base, 'PUT', draftPath, { title: '草稿与审核回归手册', categoryId: null, content: blank, version: 0 })
  await author.page.getByRole('button', { name: '编辑文章草稿', exact: true }).click()
  await expect(author.page.getByText('已保存的空白草稿', { exact: true })).toBeVisible()
  await author.page.getByRole('button', { name: '预览解析正文', exact: true }).click()
  await expect(author.page.locator('.article-source-preview')).toContainText('虚构网关的健康状态')
  expect((await actorRequest(author, base, 'GET', draftPath)).version).toBe(savedBlank.version)
  await snapshot(author.page, 'import-preview'); checked('existing saved blank draft remains unchanged until source preview and explicit confirmation')
  await author.page.getByRole('button', { name: '取消', exact: true }).click()
  expect((await actorRequest(author, base, 'GET', draftPath)).version).toBe(savedBlank.version)
  await author.page.getByRole('button', { name: '预览解析正文', exact: true }).click()
  const confirmImport = author.page.getByRole('button', { name: '确认导入正文', exact: true })
  await confirmImport.focus(); expect(await confirmImport.evaluate(button => button === document.activeElement)).toBe(true)
  await author.page.keyboard.press('Enter')
  await expect(author.page.getByText('解析正文已导入并保存，可继续编辑。')).toBeVisible()
  const imported = await actorRequest(author, base, 'GET', draftPath)
  expect(imported.version).toBe(savedBlank.version + 1); expect(JSON.stringify(imported.content)).toContain('虚构网关的健康状态')
  checked('confirmed import saves through the draft version lock')
  await author.page.getByRole('button', { name: '返回知识中心', exact: true }).click()
  await author.page.getByRole('tab',{name:'版本历史',exact:true}).click(); await expect(author.page.locator('.version-list > li').first().getByRole('button', { name: '提交审核', exact: true })).toBeVisible()

  phase = 'review rejection'
  await expect(author.page.getByText('当前空间尚无可用的专职审核者；提交后将等待管理员配置审核权限。',{exact:true})).toBeVisible(); await expect(author.page.getByText('请联系管理员配置此空间的审核权限。',{exact:true})).toBeVisible()
  expect((await actorRequest(author,base,'GET',`/knowledge/documents/${initial.documentId}`)).versions.find(row=>row.id===initial.versionId).hasEligibleReviewer).toBe(false)
  await admin.page.locator('a[href="/admin/knowledge-spaces"]').first().click(); await admin.page.waitForURL('**/admin/knowledge-spaces'); await admin.page.locator('#knowledge-space').selectOption(space.id)
  await admin.page.getByRole('button',{name:'管理成员',exact:true}).click(); const memberDialog=admin.page.getByRole('dialog',{name:/空间成员/})
  await expect(memberDialog.locator('#knowledge-member-user')).toBeEnabled(); await memberDialog.locator('#knowledge-member-user').selectOption(created.user.id)
  await memberDialog.getByLabel('允许阅读',{exact:true}).check(); await memberDialog.getByLabel('允许编辑',{exact:true}).uncheck(); await memberDialog.getByLabel('允许审核',{exact:true}).check()
  await memberDialog.locator('#knowledge-member-reason').fill('专职 KB_REVIEWER 审核验收授权')
  const grantResponse=admin.page.waitForResponse(r=>r.url().endsWith(`/spaces/${space.id}/members/${created.user.id}`)&&r.request().method()==='PUT')
  await memberDialog.getByRole('button',{name:'保存成员权限',exact:true}).click(); expect((await grantResponse).status()).toBe(204)
  await author.page.getByRole('button',{name:'刷新状态',exact:true}).click(); expect((await actorRequest(author,base,'GET',`/knowledge/documents/${initial.documentId}`)).versions.find(row=>row.id===initial.versionId).hasEligibleReviewer).toBe(true)
  await expect(author.page.getByText('当前空间尚无可用的专职审核者；提交后将等待管理员配置审核权限。',{exact:true})).toHaveCount(0); await author.page.locator('.version-list > li').first().getByRole('button',{name:'提交审核',exact:true}).click()
  checked('missing eligible reviewer is verified against real detail, then ADMIN grants dedicated review permission in the members dialog')
  await expect(reviewer.page.locator('a[href="/knowledge/reviews"]').first()).toBeVisible()
  await reviewer.page.locator('a[href="/knowledge/reviews"]').first().click()
  await reviewer.page.waitForURL('**/knowledge/reviews')
  const openTask = reviewer.page.getByRole('button', { name: '审核草稿与审核回归手册的版本 1' })
  await expect(openTask).toBeVisible()
  await snapshot(reviewer.page, 'review-queue')
  await openTask.focus(); expect(await openTask.evaluate(button => button === document.activeElement)).toBe(true)
  await reviewer.page.keyboard.press('Enter')
  await reviewer.page.getByRole('tab',{name:'文档概览',exact:true}).click(); await expect(reviewer.page.locator('.knowledge-reader')).toContainText('虚构网关的健康状态'); await reviewer.page.getByRole('tab',{name:'版本历史',exact:true}).click()
  await reviewer.page.locator('.version-list > li').first().getByRole('button', { name: '审核不通过', exact: true }).click()
  await reviewer.page.locator('#review-reason').fill('请补充虚构网关的处理步骤')
  await reviewer.page.getByRole('button', { name: '确认审核不通过', exact: true }).click()
  await reviewer.page.waitForURL('**/knowledge/reviews'); await expect(reviewer.page.locator('.knowledge-detail,.knowledge-reader')).toHaveCount(0)
  await author.page.getByRole('button', { name: '刷新状态', exact: true }).click()
  await expect(author.page.getByText('审核意见：请补充虚构网关的处理步骤')).toBeVisible()
  checked('dedicated KB_REVIEWER opens the exact pending version by keyboard, rejects with reason, and author sees it')

  phase = 'new version approval'
  await author.page.getByRole('button',{name:'上传新版本',exact:true}).click(); await author.page.locator('#knowledge-version-file').setInputFiles({ name: 'recovery-v2.md', mimeType: 'text/markdown', buffer: Buffer.from(`${body}\n\n修订：再核对虚构路由配置。`) })
  await author.page.getByRole('button', { name: '上传新版本文件', exact: true }).click()
  await expect(author.page.locator('.version-list > li').first().getByText('解析就绪', { exact: true })).toBeVisible()
  await author.page.locator('.version-list > li').first().getByRole('button', { name: '提交审核' }).click()
  await reviewer.page.locator('a[href="/knowledge/reviews"]').first().click()
  await reviewer.page.waitForURL('**/knowledge/reviews')
  await reviewer.page.getByRole('button', { name: '刷新待办' }).click()
  await reviewer.page.getByRole('button', { name: '审核草稿与审核回归手册的版本 2' }).click()
  await reviewer.page.getByRole('tab',{name:'文档概览',exact:true}).click(); await expect(reviewer.page.locator('.knowledge-reader')).toContainText('虚构路由配置'); await reviewer.page.getByRole('tab',{name:'版本历史',exact:true}).click()
  await reviewer.page.locator('.version-list > li').first().getByRole('button', { name: '审核通过' }).click()
  await reviewer.page.waitForURL('**/knowledge/reviews'); await expect(reviewer.page.locator('.knowledge-detail,.knowledge-reader')).toHaveCount(0)
  await author.page.getByRole('button', { name: '刷新状态', exact: true }).click()
  await expect(author.page.locator('.version-list > li').first().getByRole('button', { name: '发布此版本' })).toBeVisible()
  await author.page.locator('.version-list > li').first().getByRole('button', { name: '发布此版本' }).click()
  await expect(author.page.getByText('版本已发布。',{exact:true})).toBeVisible()
  await reviewer.page.locator('a[href="/knowledge/reviews"]').first().click()
  await reviewer.page.waitForURL('**/knowledge/reviews')
  await expect(reviewer.page.getByRole('button', { name: '审核草稿与审核回归手册的版本 2' })).toHaveCount(0)
  checked('dedicated KB_REVIEWER approves a revised version, author publishes it, and review task disappears')
  expect(errors).toEqual([])
  await writeFile(resolve(evidence, `${prefix}.json`), JSON.stringify({ date: new Date().toISOString(), status: 'PASS', checks, screenshots, fixture, pageErrors: errors.length, limitations: ['Synthetic data is confined to the dedicated ticknova_browser test database; cleanup is separate.', 'Chromium only; screenshots require visual inspection.'] }, null, 2) + '\n')
} catch (failure) {
  let message = `Knowledge recovery browser failure at ${phase}: ${failure.message}`
  for (const secret of secrets) message = message.replaceAll(secret, '[REDACTED]')
  console.error(message); process.exitCode = 1
  if (reviewerPage) console.error(`Reviewer page: ${reviewerPage.url()} | ${((await reviewerPage.locator('#main-content').allInnerTexts()).join(' ') || '').slice(0, 500)}`)
  if (errors.length) console.error(`Page errors: ${errors.join(' | ').slice(0, 500)}`)
  try { const path = resolve(evidence, `${prefix}-failure.png`); await contexts[0]?.pages()[0]?.screenshot({ path, fullPage: true, animations: 'disabled' }); screenshots.push(`${prefix}-failure.png`) } catch {}
} finally { for (const context of contexts) await context.close(); await browser.close() }
