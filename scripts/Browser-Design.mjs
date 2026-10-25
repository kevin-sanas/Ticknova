import { browserEnvironment } from './Browser-Environment.mjs'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { randomUUID } from 'node:crypto'
import { actorRequest, createEditorFixture, createReviewerFixture, permitReview } from './Browser-Review.mjs'
import { DESIGN_TOKENS, hexToCssRgb } from './Design-Tokens.mjs'

// Real local services only: no response fulfillment, provider changes, or existing
// demo password changes. Fixtures live only in the disposable browser database.
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const { base, envPath, env, evidence } = await browserEnvironment(root)
const origin = new URL(base)
if (origin.hostname !== '127.0.0.1' || origin.protocol !== 'http:' || origin.username || origin.password || origin.pathname !== '/' || origin.search || origin.hash) throw new Error('Requires local http://127.0.0.1 origin')
if (process.env.UI_BASELINE === 'true') throw new Error('Historical before evidence is immutable')
const secrets = Object.entries(env).filter(([key]) => /PASSWORD|PEPPER|TOKEN|KEY|SECRET/.test(key)).map(([, value]) => value).filter(Boolean)
const { chromium } = await import(pathToFileURL(resolve(root, 'frontend/node_modules/playwright/index.mjs')))
const { expect: baseExpect } = await import(pathToFileURL(resolve(root, 'frontend/node_modules/@playwright/test/index.mjs')))
const expect = baseExpect.configure({ timeout: 45_000 })
const checks = [], screenshots = [], contexts = [], pageErrors = [], fixtures = {}, observations = []
const stamp = Date.now(), widths = [1920, 1440, 1280, 768, 390]
let browser, step = 'launch', currentPage, logins = 0, failure
class VerificationError extends Error {}
function ensure(condition, code) { if (!condition) throw new VerificationError(code) }
function checked(name, details) { checks.push({ name, pass: true, ...(details ? { details } : {}) }); console.log(`PASS ${name}`) }
async function phase(name, task) { step = name; await task() }
function redact(value) { let text = String(value); for (const secret of secrets) text = text.replaceAll(secret, '[REDACTED]'); return text.replace(/Bearer\s+\S+/gi, 'Bearer [REDACTED]').replace(/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, '[REDACTED]') }
const api = (actor, method, path, data, key, expected) => actorRequest(actor, base, method, path, data, key, expected)
async function responseFor(page, method, suffix, action, statuses = [200, 201, 202, 204]) {
  const pending = page.waitForResponse(response => new URL(response.url()).pathname.endsWith(suffix) && response.request().method() === method)
  pending.catch(() => {}) // Actionability failures must not leave an unhandled waiter.
  await action(); const response = await pending
  ensure(statuses.includes(response.status()), `HTTP_${method}_${response.status()}`)
  return response.status() === 204 ? null : response.json()
}
async function login(username, password) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1050 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' }); contexts.push(context)
  await context.route('**/*', route => { const url = new URL(route.request().url()); return ['data:', 'blob:'].includes(url.protocol) || url.origin === origin.origin ? route.continue() : route.abort('blockedbyclient') })
  const page = await context.newPage(); currentPage = page
  page.on('pageerror', error => pageErrors.push({ step, name: error.name }))
  await page.goto(`${base}/login`)
  await expect(page.locator('.auth-brand')).toContainText('Ticknova')
  await expect(page.locator('.auth-brand .brand-mark')).toHaveText('t')
  await expect(page).toHaveTitle(/Ticknova/)
  if (!logins) await snapshot(page, 'ticknova-login')
  await page.locator('#username').fill(username); await page.locator('#password').fill(password || env[`DEMO_BROWSER_${username.toUpperCase()}_PASSWORD`] || env.DEMO_INITIAL_PASSWORD)
  const result = await responseFor(page, 'POST', '/auth/login', () => page.getByRole('button', { name: /登录工作台/ }).click(), [200])
  secrets.push(result.accessToken); ensure(!result.user.mustChangePassword, 'DEMO_FIRST_PASSWORD_CHANGE_REQUIRED_USE_EXISTING_SETUP')
  await page.waitForURL(url => !['/login', '/change-password'].includes(url.pathname)); logins++
  await expect(page.locator('.sidebar .brand')).toContainText('Ticknova')
  await expect(page.locator('.workspace-footer')).toContainText('Ticknova')
  await expect(page).toHaveTitle(/· Ticknova$/)
  ensure(!await page.locator('.brand,.workspace-footer,.auth-brand').filter({ hasText: 'KnowDesk' }).count(), 'INCORRECT_PRODUCT_BRAND')
  ensure(await page.evaluate(() => Object.keys(localStorage).length + Object.keys(sessionStorage).length) === 0, 'SESSION_MUST_REMAIN_MEMORY_ONLY')
  return { context, page, user: result.user, token: result.accessToken }
}
async function navigate(actor, path) {
  const page = actor.page; currentPage = page
  let link = page.locator(`a[href="${path}"]:visible`).first()
  if (!await link.count()) { await page.getByRole('button', { name: '打开导航', exact: true }).click(); link = page.getByRole('dialog').locator(`a[href="${path}"]`).first() }
  await link.click(); await page.waitForURL(url => url.pathname === path)
}
async function snapshot(page, tag) {
  currentPage = page
  await page.evaluate(() => window.scrollTo({ top: 0, left: 0, behavior: 'instant' }))
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
  const issues = await page.evaluate(() => {
    const visible = el => { const box = el.getBoundingClientRect(), style = getComputedStyle(el); return box.width > 0 && box.height > 0 && style.visibility !== 'hidden' && style.display !== 'none' }
    const name = el => (el.getAttribute('aria-label') || (el.getAttribute('aria-labelledby') || '').split(' ').map(id => document.getElementById(id)?.textContent || '').join(' ').trim() || el.textContent || el.getAttribute('title') || '').trim()
    const bad = []
    for (const el of document.querySelectorAll('button,[role="button"]')) if (visible(el) && !name(el)) bad.push(`unnamed-button:${el.id || el.tagName}`)
    for (const el of document.querySelectorAll('input:not([type="hidden"]),select,textarea')) if (visible(el) && !el.labels?.length && !el.getAttribute('aria-label') && !el.getAttribute('aria-labelledby')) bad.push(`unlabelled-field:${el.id || el.tagName}`)
    for (const el of document.querySelectorAll('.el-button:not(.is-text),.primary-link,#portal-search,#ticket-title,#article-title')) if (visible(el) && el.getBoundingClientRect().height < 35.5) bad.push(`undersized-control:${el.id || el.tagName}`)
    return bad
  })
  ensure(issues.length === 0, `A11Y_${issues.join(',')}`)
  ensure(!await page.getByRole('button', { name: /切换.*主题|深色模式|暗色模式|dark mode/i }).count(), 'UNIMPLEMENTED_DARK_THEME_ENTRY')
  const file = `ui-v2-${tag}-${page.viewportSize().width}.png`
  const fixedSurface = /drawer|dialog|confirm|tooltip/.test(tag)
  await page.screenshot({ path: resolve(evidence, file), fullPage: !fixedSurface, animations: 'disabled' }); screenshots.push(file)
}
async function matrix(page, tag) {
  for (const width of widths) { await page.setViewportSize({ width, height: width === 390 ? 844 : 1050 }); await snapshot(page, tag) }
  await page.setViewportSize({ width: 1440, height: 1050 }); checked(`${tag}: responsive screenshots, accessible names/labels, control size and no document overflow`, { widths })
}
async function trap(page, surface) {
  await expect(surface).toBeVisible(); await expect.poll(() => surface.evaluate(el => el.contains(document.activeElement))).toBe(true)
  const count = await surface.locator('button,input,select,textarea,a[href],[tabindex="0"]').count()
  for (let index = 0; index < Math.max(count + 2, 4); index++) { await page.keyboard.press('Tab'); ensure(await surface.evaluate(el => el.contains(document.activeElement)), 'MODAL_TAB_ESCAPED') }
  for (let index = 0; index < 3; index++) { await page.keyboard.press('Shift+Tab'); ensure(await surface.evaluate(el => el.contains(document.activeElement)), 'MODAL_SHIFT_TAB_ESCAPED') }
}
async function selectElOption(page, selector, label) {
  await page.locator(selector).click()
  const popper = page.locator('.el-select__popper:visible')
  await expect(popper).toBeVisible()
  await popper.getByRole('option', { name: label, exact: true }).click()
}
async function openDocument(actor, space, id, title) {
  if (actor.user.roles.includes('KB_REVIEWER')) {
    await navigate(actor,'/knowledge/reviews'); await actor.page.getByRole('button',{name:'刷新待办',exact:true}).click(); await actor.page.getByRole('button',{name:new RegExp(`^审核${title}的版本 `)}).first().click(); await expect(actor.page.locator('.knowledge-detail')).toBeVisible(); return
  }
  await navigate(actor, '/knowledge'); await actor.page.getByRole('button', { name: '刷新空间', exact: true }).click(); await expect(actor.page.locator('#knowledge-space')).toBeEnabled()
  await actor.page.locator('#knowledge-space').selectOption(space.id); await actor.page.getByRole('button', { name: title, exact: true }).click()
  await expect(actor.page.locator('.knowledge-detail')).toBeVisible(); await expect.poll(() => new URL(actor.page.url()).pathname).toBe(`/knowledge/documents/${id}`)
}
async function refresh(actor) { await actor.page.getByRole('button', { name: '刷新状态', exact: true }).click() }
async function ready(actor, id, versionId) {
  await expect.poll(async () => (await api(actor, 'GET', `/knowledge/documents/${id}`)).versions.find(row => row.id === versionId)?.ingestState, { timeout: 120_000, intervals: [1000, 2000, 3000] }).toBe('READY')
  await expect(actor.page.locator('.knowledge-detail')).toBeVisible(); await refresh(actor); await actor.page.getByRole('tab',{name:'版本历史',exact:true}).click()
}
async function requestReview(actor) {
  const row = actor.page.locator('.version-list > li').first()
  await row.getByRole('button', { name: '提交审核', exact: true }).click(); await expect(row.getByText('待审核', { exact: true })).toBeVisible()
}
async function approveAndPublish(reviewer, author) {
  const row = reviewer.page.locator('.version-list > li').first()
  await row.getByRole('button', { name: '审核通过', exact: true }).click(); await reviewer.page.waitForURL('**/knowledge/reviews'); await expect(reviewer.page.locator('.knowledge-detail,.knowledge-reader')).toHaveCount(0)
  await refresh(author); await author.page.getByRole('tab',{name:'版本历史',exact:true}).click()
  await author.page.locator('.version-list > li').first().getByRole('button',{name:'发布此版本',exact:true}).click(); await expect(author.page.getByText('版本已发布。',{exact:true})).toBeVisible()
}
async function editDocument(actor) { await actor.page.getByRole('button', { name: '编辑文章草稿', exact: true }).click(); await expect(actor.page.getByRole('textbox', { name: '文章正文', exact: true })).toBeVisible() }
async function commit(actor) { return responseFor(actor.page, 'POST', '/draft/versions', () => actor.page.getByRole('button', { name: '生成待审核版本', exact: true }).click(), [202]) }
async function move(actor, status) {
  await actor.page.locator('#target-status').selectOption(status); await actor.page.locator('#transition-reason').fill(`合成验收状态 ${status}`)
  await responseFor(actor.page, 'POST', '/transitions', () => actor.page.getByRole('button', { name: '确认更新', exact: true }).click(), [200]); await expect(actor.page.getByText('工单状态已更新。', { exact: true })).toBeVisible()
}
async function waitJob(actor, id) {
  let result
  await expect.poll(async () => { result = await api(actor, 'GET', `/jobs/${id}`); return result.status }, { timeout: 60_000, intervals: [250, 500, 1000] }).toMatch(/^(COMPLETED|FAILED|CANCELLED)$/)
  return result
}

try {
  browser = await chromium.launch({ headless: true })
  let admin, requester, agent, author, reviewer, concurrentAuthor, expiringReviewer, space, category, article, publishedVersion, upload
  const articleTitle = `V2合成网关502手册 ${stamp}`, categoryName = `V2网络分类 ${stamp}`
  const sourceText = '网关502错误时，先检查上游服务健康检查结果，再对照请求时间查看upstream连接日志。核对测试服务地址与端口，修复后重新访问验证。'
  await phase('seven isolated UI logins and dedicated fixture setup', async () => {
    admin = await login('admin'); requester = await login('requester'); agent = await login('agent')
    const config = await api(admin, 'GET', '/ai/config'); ensure(config.mode === 'mock' && !config.externalProvider, 'LOCAL_MOCK_REQUIRED')
    const openapi = await api(admin, 'GET', '/openapi')
    ensure(['/api/v1/workspace/portal', '/api/v1/workspace/analytics', '/api/v1/knowledge/documents/{id}/draft', '/api/v1/ai/jobs/{id}/cancel'].every(path => openapi.paths?.[path]), 'CURRENT_OPENAPI_PATHS_MISSING')
    await writeFile(resolve(evidence, 'ui-v2-openapi.json'), JSON.stringify(openapi, null, 2) + '\n')
    const authorFixture = await createEditorFixture(admin, base, secret => secrets.push(secret)), reviewerFixture = await createReviewerFixture(admin, base, secret => secrets.push(secret))
    author = await login(authorFixture.username, authorFixture.password); reviewer = await login(reviewerFixture.username, reviewerFixture.password)
    concurrentAuthor = await login(authorFixture.username, authorFixture.password); expiringReviewer = await login(reviewerFixture.username, reviewerFixture.password)
    fixtures.authorId = author.user.id; fixtures.reviewerId = reviewer.user.id
    ensure(author.user.id !== reviewer.user.id && logins === 7, 'SEVEN_LOGINS_AND_INDEPENDENT_IDENTITIES_REQUIRED')
    checked('seven UI sign-ins; synthetic editor and KB_REVIEWER complete first password change; existing demo credentials preserved')
    checked('Ticknova login, workspace navigation, footer and document title across all seven sessions')
    const designTokens = await requester.page.evaluate(() => ({
      primary: getComputedStyle(document.documentElement).getPropertyValue('--color-primary').trim().toLowerCase(),
      canvas: getComputedStyle(document.documentElement).getPropertyValue('--color-canvas').trim().toLowerCase(),
      canvasRgb: getComputedStyle(document.documentElement).backgroundColor
    }))
    ensure(designTokens.primary === DESIGN_TOKENS.primary, 'BRAND_GREEN_TOKEN')
    ensure(designTokens.canvas === DESIGN_TOKENS.canvas && designTokens.canvasRgb === hexToCssRgb(DESIGN_TOKENS.canvas), 'WARM_CANVAS_TOKEN')
    await expect(requester.page).toHaveURL(`${base}/portal`); await expect(requester.page.locator('a[href^="/admin/"]')).toHaveCount(0)
    await matrix(requester.page, 'portal'); checked('requester defaults to search-first portal without administrative navigation')
  })
  await phase('real category CRUD and empty knowledge states', async () => {
    await navigate(admin, '/admin/knowledge-spaces'); await admin.page.getByRole('button', { name: '创建空间', exact: true }).click(); await admin.page.locator('#knowledge-space-name').fill(`V2合成知识空间 ${stamp}`)
    space = await responseFor(admin.page, 'POST', '/knowledge/spaces', () => admin.page.getByRole('button', { name: '保存空间', exact: true }).click(), [201]); fixtures.spaceId = space.id
    await api(admin,'PUT',`/knowledge/spaces/${space.id}/members/${author.user.id}`,{canRead:true,canEdit:true,canReview:false,reason:'V2 合成编辑授权'}); await permitReview(admin, reviewer, base, space.id)
    await navigate(author,'/knowledge'); await author.page.getByRole('button',{name:'刷新空间',exact:true}).click(); await author.page.locator('#knowledge-space').selectOption(space.id)
    for (const actor of [requester, agent]) await api(admin, 'PUT', `/knowledge/spaces/${space.id}/members/${actor.user.id}`, { canRead: true, canEdit: false, canReview: false, reason: 'V2合成浏览器验收授权' })
    await expect(author.page.getByText('此空间暂无文档。', { exact: true })).toBeVisible(); await snapshot(author.page, 'knowledge-empty')
    await author.page.getByRole('button', { name: '管理分类', exact: true }).click(); await author.page.locator('#category-name').fill(categoryName)
    category = await responseFor(author.page, 'POST', `/spaces/${space.id}/categories`, () => author.page.getByRole('button', { name: '保存分类', exact: true }).click(), [201])
    await author.page.locator('#category-edit-id').selectOption(category.id); await author.page.locator('#category-name').fill(`${categoryName}修订`)
    await responseFor(author.page, 'PATCH', `/categories/${category.id}`, () => author.page.getByRole('button', { name: '保存分类', exact: true }).click(), [200])
    await expect(author.page.getByRole('button', { name: `${categoryName}修订`, exact: true })).toBeVisible(); await author.page.locator('#category-name').fill('V2可删除空分类')
    const removable = await responseFor(author.page, 'POST', `/spaces/${space.id}/categories`, () => author.page.getByRole('button', { name: '保存分类', exact: true }).click(), [201])
    await author.page.locator('#category-edit-id').selectOption(removable.id); await author.page.getByRole('button', { name: '删除分类', exact: true }).click(); await snapshot(author.page, 'category-confirm')
    await responseFor(author.page, 'DELETE', `/categories/${removable.id}`, () => author.page.getByRole('button', { name: '确认删除分类', exact: true }).click(), [204])
    await expect(author.page.locator('#category-edit-id option').filter({ hasText: 'V2可删除空分类' })).toHaveCount(0)
    await author.page.getByRole('button', { name: `${categoryName}修订`, exact: true }).click()
    checked('actual UI category create, rename, selection, empty state and confirmed empty-category deletion')
  })
  await phase('rich authoring toolbar preview and real debounced autosave', async () => {
    await author.page.getByRole('button',{name:'新建文档',exact:true}).click(); await author.page.getByRole('button', { name: '撰写文章', exact: true }).click(); await author.page.locator('#article-create-title').fill(articleTitle)
    article = await responseFor(author.page, 'POST', `/spaces/${space.id}/articles`, () => author.page.getByRole('button', { name: '创建文章草稿', exact: true }).click(), [201]); fixtures.documentId = article.id
    const editor = author.page.getByRole('textbox', { name: '文章正文', exact: true }); await expect(editor).toBeVisible()
    await editor.fill('网关502排查'); await author.page.locator('#article-heading').selectOption('2'); await editor.press('End'); await editor.press('Enter'); await author.page.locator('#article-heading').selectOption('0')
    await author.page.getByRole('button', { name: '粗体', exact: true }).click(); await expect(author.page.getByRole('button', { name: '粗体', exact: true })).toHaveAttribute('aria-pressed', 'true')
    await editor.pressSequentially(sourceText, { delay: 2 }); await author.page.getByRole('button', { name: '粗体', exact: true }).click(); await author.page.locator('#article-category').selectOption(category.id)
    await expect(author.page.locator('.save-status')).toHaveText('已保存')
    const saveEvents = []; const onSave = request => { if (request.method() === 'PUT' && request.url().endsWith(`/documents/${article.id}/draft`)) saveEvents.push(Date.now()) }; author.page.on('request', onSave)
    await editor.press('End'); const started = Date.now(); await editor.pressSequentially(' 合成验收。', { delay: 30 }); const ended = Date.now()
    await expect(author.page.locator('.save-status')).toHaveText('已保存'); author.page.off('request', onSave)
    ensure(saveEvents.some(time => time >= ended + 650), 'AUTOSAVE_NOT_DEBOUNCED_800MS'); ensure(!saveEvents.some(time => time > started && time < ended), 'AUTOSAVE_DURING_TYPING')
    await author.page.locator('#article-preview').click(); await expect(author.page.locator('.article-preview strong')).toContainText('网关502错误'); await expect(author.page.locator('.article-outline a')).toHaveCount(1)
    await matrix(author.page, 'article-preview'); await author.page.locator('#article-preview').click(); await matrix(author.page, 'article-editor')
    checked('toolbar selected state, heading outline, matching rich preview and approximately 800ms real autosave')
  })
  await phase('real concurrent-editor CAS conflict retains local text and explicit reload', async () => {
    const before = await api(author, 'GET', `/knowledge/documents/${article.id}/draft`)
    await openDocument(concurrentAuthor, space, article.id, articleTitle); await editDocument(concurrentAuthor)
    const serverTitle = `${articleTitle}服务器修订`; await concurrentAuthor.page.locator('#article-title').fill(serverTitle); await expect(concurrentAuthor.page.locator('.save-status')).toHaveText('已保存')
    ensure((await api(concurrentAuthor, 'GET', `/knowledge/documents/${article.id}/draft`)).version > before.version, 'SECOND_EDITOR_SAVE_REQUIRED')
    const localTitle = `${articleTitle}本地未合并`, conflict = author.page.waitForResponse(response => response.request().method() === 'PUT' && response.url().endsWith(`/documents/${article.id}/draft`))
    await author.page.locator('#article-title').fill(localTitle); ensure((await conflict).status() === 409, 'CAS_REQUIRES_REAL_409')
    await expect(author.page.locator('.save-status')).toHaveText('保存冲突，修改已保留'); await expect(author.page.locator('#article-title')).toHaveValue(localTitle); await expect(author.page.locator('#article-save')).toBeDisabled(); await snapshot(author.page, 'article-conflict')
    await author.page.locator('#article-reload').click(); let confirm = author.page.getByRole('dialog', { name: '确认离开', exact: true }); await trap(author.page, confirm)
    await confirm.getByRole('button', { name: '继续编辑', exact: true }).click(); await expect(author.page.locator('#article-title')).toHaveValue(localTitle); await expect(author.page.locator('#article-reload')).toBeFocused()
    await author.page.locator('#article-reload').click(); await author.page.getByRole('button', { name: '放弃修改并离开', exact: true }).click(); await expect(author.page.locator('#article-title')).toHaveValue(serverTitle)
    await author.page.locator('#article-title').fill(articleTitle); await expect(author.page.locator('.save-status')).toHaveText('已保存')
    checked('two live editors produce server CAS 409; local draft survives cancel; confirmed explicit reload uses server revision')
  })
  await phase('independent review reject reason then a new approved publication', async () => {
    const first = await commit(author); await ready(author, article.id, first.versionId); await requestReview(author)
    await expect(author.page.getByRole('button', { name: '审核通过', exact: true })).toHaveCount(0)
    await api(author, 'POST', `/knowledge/documents/${article.id}/versions/${first.versionId}/reviews`, { decision: 'APPROVED' }, undefined, [403])
    await openDocument(reviewer, space, article.id, articleTitle); const row = reviewer.page.locator('.version-list > li').first(); await row.getByRole('button', { name: '审核不通过', exact: true }).click()
    await expect(reviewer.page.getByRole('button', { name: '确认审核不通过', exact: true })).toBeDisabled(); await reviewer.page.locator('#review-reason').fill('合成审核：补充复核步骤后重新提交。')
    await reviewer.page.getByRole('button', { name: '确认审核不通过', exact: true }).click(); await reviewer.page.waitForURL('**/knowledge/reviews'); await expect(reviewer.page.locator('.knowledge-detail,.knowledge-reader')).toHaveCount(0); await refresh(author); await expect(author.page.locator('.version-list')).toContainText('补充复核步骤'); await snapshot(author.page, 'review-rejected')
    await refresh(author); await editDocument(author); const editor = author.page.getByRole('textbox', { name: '文章正文', exact: true }); await editor.press('Control+End'); await editor.pressSequentially(' 复核健康检查和路由配置。')
    await expect(author.page.locator('.save-status')).toHaveText('已保存'); const next = await commit(author); ensure(next.versionId !== first.versionId, 'REJECTION_REQUIRES_NEW_VERSION')
    await ready(author, article.id, next.versionId); await requestReview(author); await openDocument(reviewer,space,article.id,articleTitle); await approveAndPublish(reviewer,author); publishedVersion = next.versionId
    checked('self-review denied 403; independent KB_REVIEWER rejection requires reason; distinct new version approved and published')
    await refresh(author); await editDocument(author); await editor.press('Control+End'); await editor.pressSequentially(' 下一版仅为待审核草稿。'); await expect(author.page.locator('.save-status')).toHaveText('已保存')
    const pending = await commit(author); await ready(author, article.id, pending.versionId); await requestReview(author)
    ensure((await api(author, 'GET', `/knowledge/documents/${article.id}`)).publishedVersionId === publishedVersion, 'PUBLICATION_CHANGED_PREMATURELY')
    await expect(author.page.getByText('新版本处理或待发布期间，旧版本仍保持发布。', { exact: true })).toBeVisible()
    await openDocument(requester, space, article.id, articleTitle); await expect(requester.page.locator('.knowledge-reader')).toContainText('复核健康检查'); await expect(requester.page.locator('.knowledge-reader')).not.toContainText('下一版仅为待审核草稿')
    await matrix(requester.page, 'knowledge-reader'); checked('old published source stays readable while a later version awaits review')
  })
  await phase('small Markdown real Docling upload review and reader', async () => {
    await navigate(author,'/knowledge'); await author.page.locator('#knowledge-space').selectOption(space.id); await author.page.getByRole('button',{name:'新建文档',exact:true}).click()
    await author.page.locator('#knowledge-title').fill(`V2文件导入 ${stamp}`)
    await author.page.locator('#knowledge-file').setInputFiles({ name: 'v2-docling.md', mimeType: 'text/markdown', buffer: Buffer.from(`# 文件导入验收\n\n${sourceText}\n`, 'utf8') })
    upload = await responseFor(author.page, 'POST', `/spaces/${space.id}/documents`, () => author.page.getByRole('button', { name: '上传并解析', exact: true }).click(), [202]); fixtures.uploadDocumentId = upload.documentId
    await ready(author, upload.documentId, upload.versionId); await requestReview(author); await openDocument(reviewer, space, upload.documentId, `V2文件导入 ${stamp}`); await approveAndPublish(reviewer,author)
    await openDocument(requester, space, upload.documentId, `V2文件导入 ${stamp}`); await expect(requester.page.locator('.knowledge-reader')).toContainText('上游服务健康检查')
    checked('browser upload reaches actual parse/chunk pipeline, independent review, publication and authorized reader')
  })
  await phase('portal search category routing empty offline recovery keyboard tooltip', async () => {
    await navigate(requester, '/portal')
    let release, arrived
    const reached = new Promise(resolve => { arrived = resolve }), gate = new Promise(resolve => { release = resolve })
    const delayed = async route => { arrived(); await gate; await route.continue() }
    const pattern = `${base}/api/v1/workspace/portal?*`
    await requester.context.route(pattern, delayed)
    try {
      await requester.page.locator('#portal-search').fill(articleTitle)
      await requester.page.locator('#portal-search-form').getByRole('button', { name: '搜索', exact: true }).click(); await reached
      await expect(requester.page.getByRole('status', { name: '正在搜索服务内容', exact: true })).toBeVisible()
      await expect(requester.page.locator('#portal-search-form button')).toBeDisabled(); await snapshot(requester.page, 'portal-loading')
    } finally { release(); await requester.context.unroute(pattern, delayed) }
    await expect(requester.page.locator('.portal-main')).toBeVisible()
    checked('loading state and disabled duplicate-submit control while an actual network request is held then released')
    await navigate(requester, '/portal'); await requester.page.locator('#portal-search').fill(articleTitle); await requester.page.locator('#portal-search-form').getByRole('button', { name: '搜索', exact: true }).click()
    await requester.page.locator('.portal-main').getByRole('link', { name: articleTitle, exact: true }).click(); await expect.poll(() => new URL(requester.page.url()).pathname).toBe(`/knowledge/documents/${article.id}`)
    await navigate(requester, '/portal'); await requester.page.locator('.category-chips').getByRole('link', { name: new RegExp(`${categoryName}修订`) }).click()
    await expect(requester.page.locator('#knowledge-space')).toHaveValue(space.id); await expect(requester.page.locator('.category-tree button[aria-current="true"]')).toHaveText(`${categoryName}修订`); ensure(new URL(requester.page.url()).searchParams.get('spaceId') === space.id, 'CATEGORY_WRONG_SPACE')
    await navigate(requester, '/portal'); await requester.page.locator('#portal-search').fill(`no-results-${randomUUID()}`); await requester.page.locator('#portal-search-form').getByRole('button', { name: '搜索', exact: true }).click(); await expect(requester.page.locator('.portal-main .empty-state')).toBeVisible(); await snapshot(requester.page, 'portal-empty')
    await requester.context.setOffline(true)
    try { await requester.page.locator('#portal-search-form').getByRole('button', { name: '搜索', exact: true }).click(); await expect(requester.page.getByRole('alert')).toBeVisible(); await snapshot(requester.page, 'portal-offline') } finally { await requester.context.setOffline(false) }
    await requester.page.getByRole('button', { name: '重试', exact: true }).click(); await expect(requester.page.getByRole('alert')).toHaveCount(0); await expect(requester.page.locator('.portal-main .empty-state')).toBeVisible()
    await requester.page.getByRole('button', { name: '收起导航', exact: true }).click(); await requester.page.locator('.sidebar a[href="/knowledge"]').focus(); await expect(requester.page.getByRole('tooltip', { name: '知识协同', exact: true })).toBeVisible(); await snapshot(requester.page, 'navigation-keyboard-tooltip')
    await requester.page.getByRole('button', { name: '展开导航', exact: true }).click()
    checked('real portal search/category correct-space navigation; empty/offline/error/retry and collapsed-nav keyboard tooltip')
  })
  let ticket
  await phase('ticket create claim internal isolation human AI adoption', async () => {
    await navigate(requester, '/tickets/new'); await expect(requester.page.getByRole('button', { name: '提交工单', exact: true })).toBeDisabled(); await requester.page.locator('#ticket-title').fill(`V2合成网关502 ${stamp}`); await requester.page.locator('#ticket-description').fill(`合成故障：${sourceText}`); await matrix(requester.page, 'new-ticket')
    ticket = await responseFor(requester.page, 'POST', '/api/v1/tickets', () => requester.page.getByRole('button', { name: '提交工单', exact: true }).click(), [201]); fixtures.ticketId = ticket.id
    await navigate(agent, '/tickets'); await agent.page.locator('#ticket-search').fill(`V2合成网关502 ${stamp}`)
    const unassigned = agent.page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/tickets') && new URL(response.url()).searchParams.get('unassigned') === 'true')
    await agent.page.getByRole('button', { name: '待分配', exact: true }).click(); ensure((await unassigned).status() === 200, 'REAL_UNASSIGNED_FILTER'); await matrix(agent.page, 'ticket-inbox')
    await agent.page.locator(`a[href="/tickets/${ticket.id}"]:visible`).first().click(); await agent.page.waitForURL(`**/tickets/${ticket.id}`); await responseFor(agent.page, 'PUT', '/assignment', () => agent.page.getByRole('button', { name: '领取工单', exact: true }).click(), [200])
    await agent.page.getByRole('tab', { name: /内部备注/ }).click(); await agent.page.locator('#comment-body').fill(`V2内部隔离-${stamp}`); await agent.page.getByRole('button', { name: '保存备注', exact: true }).click(); await expect(agent.page.getByText(`V2内部隔离-${stamp}`, { exact: true })).toBeVisible()
    await requester.page.getByRole('button', { name: '刷新工单', exact: true }).click(); await expect(requester.page.getByRole('tab', { name: /内部备注/ })).toHaveCount(0); await expect(requester.page.getByText(`V2内部隔离-${stamp}`, { exact: true })).toHaveCount(0)
    await agent.page.getByRole('tab', { name: /公开回复/ }).click(); await agent.page.locator('#comment-body').fill('合成原始公开草稿')
    let posts = 0; const listener = request => { if (request.method() === 'POST' && request.url().endsWith(`/tickets/${ticket.id}/comments`)) posts++ }; agent.page.on('request', listener)
    await agent.page.locator('#ai-spaces').selectOption([space.id]); await agent.page.locator('#ai-kind').selectOption('REPLY'); await agent.page.locator('#ai-generate').click(); await agent.page.getByRole('button', { name: '采纳建议', exact: true }).click()
    await expect(agent.page.getByRole('alertdialog', { name: '确认替换现有草稿', exact: true })).toBeVisible(); await snapshot(agent.page, 'ai-human-confirm'); await agent.page.getByRole('button', { name: '确认替换', exact: true }).click()
    await expect(agent.page.locator('#comment-body')).not.toHaveValue('合成原始公开草稿'); ensure(posts === 0, 'AI_SENT_REPLY_WITHOUT_HUMAN'); await agent.page.getByRole('button', { name: '发送回复', exact: true }).click(); await expect.poll(() => posts).toBe(1); agent.page.off('request', listener)
    checked('requester creates, agent claims actual unassigned ticket, internal notes stay private and AI adoption never auto-sends')
    await matrix(agent.page, 'ticket-detail')
    for (const width of [768, 390]) {
      await agent.page.setViewportSize({ width, height: 900 })
      for (const [name, title] of [['工单信息与操作', '工单信息与操作'], ['AI Copilot', 'AI Copilot 与相关知识']]) {
        const trigger = agent.page.getByRole('button', { name, exact: true }); await trigger.click(); const drawer = agent.page.getByRole('dialog', { name: title, exact: true }); await trap(agent.page, drawer); await snapshot(agent.page, `ticket-drawer-${name === 'AI Copilot' ? 'copilot' : 'properties'}`); await agent.page.keyboard.press('Escape'); await expect(drawer).not.toBeVisible(); await expect(trigger).toBeFocused()
      }
    }
    await agent.page.setViewportSize({ width: 1440, height: 1050 }); await move(agent, 'TRIAGED'); await move(agent, 'IN_PROGRESS'); await move(agent, 'RESOLVED'); await requester.page.getByRole('button', { name: '刷新工单', exact: true }).click(); await move(requester, 'IN_PROGRESS')
    await agent.page.getByRole('button', { name: '刷新工单', exact: true }).click(); await move(agent, 'RESOLVED'); await requester.page.getByRole('button', { name: '刷新工单', exact: true }).click(); await move(requester, 'CLOSED')
    await agent.page.locator('.back-link').click(); await agent.page.waitForURL(url => url.pathname === '/tickets'); await expect(agent.page.locator('#ticket-search')).toHaveValue(`V2合成网关502 ${stamp}`)
    checked('compact drawers trap/restore focus; resolve/reopen/resolve/close and inbox filter return')
  })
  await phase('real cited answer exact source location refusal and server cancel', async () => {
    await expect.poll(async () => { const created = await api(requester, 'POST', '/ai/answers', { question: '网关502错误先检查什么？', spaceIds: [space.id] }, randomUUID()); return (await waitJob(requester, created.jobId)).result?.outcome }, { timeout: 90_000, intervals: [1000, 3000] }).toBe('ANSWERED')
    await navigate(requester, '/assistant'); await requester.page.locator('#assistant-question').fill('网关502错误先检查什么？'); await selectElOption(requester.page, '.assistant-space-select', space.name); await requester.page.locator('#assistant-submit').click()
    const link = requester.page.locator('.ai-citations a').first(); await expect(link).toBeVisible(); const citation = new URL(await link.getAttribute('href'), base)
    ensure(/^\/knowledge\/documents\/[a-f0-9-]{36}$/.test(citation.pathname) && ['versionId', 'chunkId'].every(key => /^[a-f0-9-]{36}$/.test(citation.searchParams.get(key) || '')), 'CITATION_REQUIRES_DOCUMENT_VERSION_CHUNK')
    await matrix(requester.page, 'assistant-cited'); await link.click(); await expect(requester.page.locator('.citation-located')).toBeVisible(); await expect(requester.page.locator('.knowledge-source-chunk.cited')).toHaveAttribute('id', `knowledge-source-${citation.searchParams.get('chunkId')}`); await expect(requester.page.locator('.knowledge-source-chunk.cited')).toBeFocused(); await snapshot(requester.page, 'citation-location')
    await navigate(requester, '/assistant'); await requester.page.locator('#assistant-question').fill('火星食堂今天午餐菜单是什么？'); await selectElOption(requester.page, '.assistant-space-select', space.name); await requester.page.locator('#assistant-submit').click(); await expect(requester.page.getByText('证据不足，暂时无法确认', { exact: true })).toBeVisible(); await expect(requester.page.locator('.ai-citations')).toHaveCount(0); await snapshot(requester.page, 'assistant-refusal')
    let cancelled = false, attempts = 0
    for (; attempts < 4 && !cancelled; attempts++) {
      await requester.page.locator('#assistant-question').fill(`网关502错误先检查什么？ 合成取消验收 ${attempts}`)
      const created = await responseFor(requester.page, 'POST', '/ai/answers', () => requester.page.locator('#assistant-submit').click(), [202]), stop = requester.page.getByRole('button', { name: '中止生成', exact: true })
      // Fast mock races are observations, never a successful cancellation. Only a
      // real server 204 and persisted CANCELLED satisfy this mandatory gate.
      const stopVisible = await stop.waitFor({ state: 'visible', timeout: 1000 }).then(() => true, () => false)
      if (stopVisible) {
        const result = await responseFor(requester.page, 'POST', `/ai/jobs/${created.jobId}/cancel`, () => stop.click(), [204, 409])
        if (result === null) { const persisted = await waitJob(requester, created.jobId); ensure(persisted.status === 'CANCELLED' && !persisted.result, 'CANCEL_NOT_PERSISTED'); await expect(requester.page.getByText('已中止，可重新生成', { exact: true })).toBeVisible(); cancelled = true }
      }
      if (!cancelled) { await waitJob(requester, created.jobId); observations.push({ name: 'mock cancellation race', attempt: attempts + 1 }); await expect(requester.page.locator('#assistant-submit')).toBeEnabled() }
    }
    ensure(cancelled, 'SERVER_CANCEL_NOT_OBSERVED_AFTER_FOUR_UI_ATTEMPTS'); await snapshot(requester.page, 'assistant-cancelled')
    checked('real citation exact document/version/chunk; explicit unsupported-question refusal; persisted server cancellation', { cancellationAttempts: attempts })
  })
  await phase('admin services queues membership popconfirm dirty modal and focus', async () => {
    await navigate(admin, '/admin/services'); const trigger = admin.page.getByRole('button', { name: /新建服务/ }); await trigger.click(); let dialog = admin.page.getByRole('dialog', { name: '新建服务', exact: true }); await trap(admin.page, dialog)
    await admin.page.locator('#catalog-code').fill(`V2_${stamp}`); await admin.page.locator('#catalog-name').fill('V2合成服务'); await matrix(admin.page, 'service-dialog'); await dialog.getByRole('button', { name: '取消', exact: true }).click()
    const confirm = admin.page.getByRole('dialog', { name: '确认离开', exact: true }); await trap(admin.page, confirm); await snapshot(admin.page, 'dirty-modal-confirm'); await confirm.getByRole('button', { name: '继续编辑', exact: true }).click(); await expect(admin.page.locator('#catalog-name')).toHaveValue('V2合成服务')
    await dialog.getByRole('button', { name: '保存', exact: true }).click(); await expect(dialog).not.toBeVisible(); await expect(trigger).toBeFocused()
    await admin.page.locator('#catalog-query').fill(`V2_${stamp}`); await responseFor(admin.page, 'GET', '/admin/services', () => admin.page.getByRole('button', { name: '查询', exact: true }).click(), [200]); await expect(admin.page.getByRole('cell', { name: `V2_${stamp}`, exact: true })).toBeVisible(); await matrix(admin.page, 'admin-services')
    await navigate(admin, '/admin/queues'); await admin.page.getByRole('button', { name: /新建队列/ }).click(); dialog = admin.page.getByRole('dialog', { name: '新建队列', exact: true }); await admin.page.locator('#catalog-code').fill(`V2_${stamp}`); await admin.page.locator('#catalog-name').fill('V2合成队列'); await dialog.getByRole('button', { name: '保存', exact: true }).click()
    await admin.page.locator('#catalog-query').fill(`V2_${stamp}`); await responseFor(admin.page, 'GET', '/admin/queues', () => admin.page.getByRole('button', { name: '查询', exact: true }).click(), [200])
    const row = admin.page.getByRole('row').filter({ has: admin.page.getByRole('cell', { name: `V2_${stamp}`, exact: true }) }); await row.getByRole('button', { name: '管理成员', exact: true }).click(); dialog = admin.page.getByRole('dialog', { name: '队列成员 · V2合成队列', exact: true }); await trap(admin.page, dialog); await matrix(admin.page, 'queue-member-dialog')
    await admin.page.locator('#member-user').selectOption(agent.user.id); await dialog.getByRole('button', { name: '保存成员', exact: true }).click(); await expect(dialog.getByText('队列成员权限已保存。', { exact: true })).toBeVisible()
    await dialog.getByRole('button', { name: '移出', exact: true }).click(); const popover = admin.page.locator('.el-popconfirm:visible'); await expect(popover).toBeVisible(); await snapshot(admin.page, 'queue-member-popconfirm'); await popover.getByRole('button', { name: '移出', exact: true }).click(); await expect(dialog.getByText('成员已移出此队列。', { exact: true })).toBeVisible()
    await admin.page.keyboard.press('Escape'); await expect(dialog).not.toBeVisible(); await matrix(admin.page, 'admin-queues')
    checked('synthetic service/queue UI creation; modal trap, dirty cancel retention, focus restoration and real member removal popconfirm')
  })
  await phase('admin analytics audit and users responsive real data', async () => {
    await navigate(admin, '/admin/analytics'); await expect(admin.page.locator('[data-kpi="created"]')).toBeVisible(); await matrix(admin.page, 'analytics')
    for (const days of [7,14,30]) {
      const control=admin.page.locator(`[data-days="${days}"]`); let data
      if(await control.getAttribute('aria-pressed')==='true') { await admin.page.locator('[data-days="14"]').click(); await expect(admin.page.locator('[data-kpi="created"]')).toBeVisible() }
      data=await responseFor(admin.page,'GET','/workspace/analytics',()=>control.click(),[200]); await expect(control).toHaveAttribute('aria-pressed','true'); await expect.poll(()=>new URL(admin.page.url()).searchParams.get('range')).toBe(String(days))
      await expect(admin.page.locator('[data-kpi="created"] strong')).toHaveText(String(data.ticketTotal))
      const completed=data.statusCounts.filter(row=>['RESOLVED','CLOSED'].includes(row.key)).reduce((total,row)=>total+row.count,0)
      await expect(admin.page.locator('[data-kpi="open"] strong')).toHaveText(String(data.ticketTotal-completed))
      await expect(admin.page.locator('[data-kpi="urgent"] strong')).toHaveText(String(data.priorityCounts.find(row=>row.key==='URGENT')?.count||0))
      await expect(admin.page.locator('[data-kpi="completion"] strong')).toHaveText(data.ticketTotal?`${Math.round(completed/data.ticketTotal*100)}%`:'—')
      await expect(admin.page.locator('.daily-chart[role="img"]')).toBeVisible()
      await expect(admin.page.locator('.daily-chart svg')).toBeVisible()
      await admin.page.locator('.analytics-table summary').click(); await expect(admin.page.locator('.analytics-table tbody tr')).toHaveCount(data.daily.length)
      for(let index=0;index<data.daily.length;index++){const row=admin.page.locator('.analytics-table tbody tr').nth(index),day=data.daily[index]; await expect(row.locator('th')).toHaveText(day.date); await expect(row.locator('td')).toHaveText([String(day.created),String(day.resolved)])}
      if(!data.ticketTotal) await expect(admin.page.getByText('所选时间范围没有新建工单。',{exact:true})).toBeVisible()
    }
    await snapshot(admin.page,'analytics-real-ranges')
    await navigate(admin, '/admin/audit'); await expect(admin.page.getByRole('button', { name: '查看记录', exact: true }).first()).toBeVisible(); await selectElOption(admin.page, '.audit-action-select', 'SERVICE_CREATED'); await responseFor(admin.page, 'GET', '/audit-events', () => admin.page.getByRole('button', { name: '查询', exact: true }).click(), [200]); await expect(admin.page.getByRole('cell', { name: 'SERVICE_CREATED', exact: true }).first()).toBeVisible(); await matrix(admin.page, 'admin-audit')
    const auditTrigger = admin.page.locator('button:visible').filter({ hasText: '查看记录' }).first(); await auditTrigger.click(); const dialog = admin.page.getByRole('dialog', { name: '审计记录详情', exact: true }); await trap(admin.page, dialog); await matrix(admin.page, 'audit-dialog'); await admin.page.keyboard.press('Escape'); await expect(auditTrigger).toBeFocused()
    await navigate(admin, '/admin/users'); await admin.page.locator('#user-query').fill('admin'); await selectElOption(admin.page, '.user-status-filter', '启用'); await selectElOption(admin.page, '.user-role-filter', '管理员'); await responseFor(admin.page, 'GET', '/users', () => admin.page.getByRole('button', { name: '查询', exact: true }).click(), [200]); await expect(admin.page.locator('.desktop-table .ticket-number', { hasText: 'admin' }).first()).toHaveText('admin'); await matrix(admin.page, 'admin-users'); await responseFor(admin.page, 'GET', '/users', () => admin.page.getByRole('button', { name: '重置', exact: true }).click(), [200])
    checked('actual UTC analytics with real 7/14/30 range data; audit real-value filtering and drawer; user and catalog server filtering responsive on all target widths')
  })
  await phase('fresh ACL denial purges displayed private content', async () => {
    await openDocument(requester, space, article.id, articleTitle); await expect(requester.page.locator('.knowledge-reader')).toBeVisible(); await api(admin, 'PUT', `/knowledge/spaces/${space.id}/members/${requester.user.id}`, { canRead: false, canEdit: false, canReview: false, reason: 'V2合成验收撤销授权' })
    const denied = requester.page.waitForResponse(response => response.url().endsWith(`/knowledge/documents/${article.id}`)); await refresh(requester); ensure([403, 404].includes((await denied).status()), 'REAL_FRESH_ACL_DENIAL_REQUIRED'); await expect(requester.page.locator('.knowledge-detail,.knowledge-reader,.knowledge-chunks,.knowledge-categories')).toHaveCount(0); await snapshot(requester.page, 'knowledge-access-denied')
    checked('fresh real ACL denial immediately clears document, reader, chunks and categories')
  })
  await phase('only synthetic fixture disabled and existing server session rejected', async () => {
    ensure(reviewer.user.id === fixtures.reviewerId && /^ui_reviewer_/.test(reviewer.user.username), 'REFUSE_DISABLE_NON_FIXTURE')
    if (new URL(expiringReviewer.page.url()).pathname !== '/knowledge/reviews') await navigate(expiringReviewer, '/knowledge/reviews')
    await expect(expiringReviewer.page.getByRole('button', { name: '刷新待办', exact: true })).toBeVisible()
    const fixtureRow = admin.page.getByRole('row').filter({ hasText: reviewer.user.username })
    // The directory is genuinely paginated by username; do not assume fixtures
    // are on page one in an already-used local demo.
    for (let page = 0; !await fixtureRow.count(); page++) {
      ensure(page < 200, 'FIXTURE_NOT_FOUND_IN_USER_DIRECTORY')
      const next = admin.page.locator('.el-pagination .btn-next'); await expect(next).toBeEnabled()
      await responseFor(admin.page, 'GET', '/users', () => next.click(), [200])
      await expect(admin.page.locator('.loading-panel')).toHaveCount(0)
    }
    await fixtureRow.getByRole('button', { name: '管理', exact: true }).click()
    const dialog = admin.page.getByRole('dialog', { name: `管理用户 · ${reviewer.user.username}`, exact: true }); await trap(admin.page, dialog)
    await matrix(admin.page, 'user-dialog')
    await dialog.getByLabel('允许此账号登录', { exact: true }).uncheck()
    await responseFor(admin.page, 'PATCH', `/users/${reviewer.user.id}`, () => dialog.getByRole('button', { name: '保存基本信息', exact: true }).click(), [200])
    await expect(dialog.getByText('基本信息已保存。', { exact: true })).toBeVisible(); await snapshot(admin.page, 'synthetic-user-disabled')
    await dialog.getByRole('button', { name: '关闭', exact: true }).click()
    currentPage = expiringReviewer.page
    // Exercise the reviewer's actual page rather than bypassing the UI. The
    // disabled account must lose both its in-memory browser session and token.
    const expiredRead = expiringReviewer.page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/knowledge/review-tasks')
    expiredRead.catch(() => {})
    await expiringReviewer.page.getByRole('button', { name: '刷新待办', exact: true }).click()
    ensure((await expiredRead).status() === 401, 'DISABLED_FIXTURE_REFRESH_MUST_BE_401')
    await expect(expiringReviewer.page).toHaveURL(`${base}/login`); await expect(expiringReviewer.page.locator('#password')).toHaveValue('')
    const denied = await expiringReviewer.page.request.get(`${base}/api/v1/knowledge/spaces`, { headers: { Authorization: `Bearer ${expiringReviewer.token}` } }); ensure(denied.status() === 401, 'DISABLED_FIXTURE_TOKEN_STILL_VALID'); await snapshot(expiringReviewer.page, 'session-invalidated')
    checked('only synthetic reviewer disabled; existing browser session and original bearer session invalidated')
  })
  ensure(pageErrors.length === 0, 'UNCAUGHT_PAGE_ERRORS'); ensure(logins === 7, 'LOGIN_COUNT_CHANGED')
} catch (error) {
  failure = { step, code: error instanceof VerificationError ? redact(error.message) : `${error.name || 'Error'}: browser assertion or operation failed (business text and call logs omitted)`, location: String(error.stack || '').split('\n').filter(line => /Browser-Design\.mjs:\d+/.test(line)).join('\n') }
  if (currentPage && !currentPage.isClosed()) { try { const file = `ui-v2-failure-${screenshots.length}.png`; await currentPage.screenshot({ path: resolve(evidence, file), fullPage: true, animations: 'disabled', timeout: 10_000 }); screenshots.push(file) } catch { /* Preserve JSON even if the browser is gone. */ } }
  console.error(`FAIL ${redact(step)}: ${failure.code}`)
  if (failure.location) console.error(`FAIL location: ${failure.location}`)
  process.exitCode = 1
} finally {
  await writeFile(resolve(evidence, 'ui-v2-design-verification.json'), JSON.stringify({ recordedAt: new Date().toISOString(), base, status: failure || pageErrors.length ? 'FAILED' : 'PASS', mode: 'mock', step, checks, screenshots, fixtures, logins, observations, uncaughtPageErrors: pageErrors.length, ...(failure ? { failure } : {}), limitations: ['Automated DOM and keyboard checks supplement human screenshot inspection.', 'Synthetic fixtures retained as audit evidence; existing demo credentials unchanged.'] }, null, 2) + '\n')
  for (const context of contexts) await context.close().catch(() => {})
  await browser?.close()
}
