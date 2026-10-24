import { browserEnvironment } from './Browser-Environment.mjs'
import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import {createEditorFixture,permitReview,approveVersion} from './Browser-Review.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const { base, envPath, env, evidence } = await browserEnvironment(root)
const { chromium } = await import(pathToFileURL(resolve(root, 'frontend/node_modules/playwright/index.mjs')))
const { expect: baseExpect } = await import(pathToFileURL(resolve(root, 'frontend/node_modules/@playwright/test/index.mjs')))
const expect = baseExpect.configure({ timeout: 30_000 })
const secrets = Object.entries(env).filter(([key]) => /PASSWORD|PEPPER|TOKEN|API_KEY/.test(key)).map(([, value]) => value).filter(Boolean)
const evidenceTag = process.env.TICKNOVA_EVIDENCE_TAG || 'g4'
if (!/^[a-z0-9-]+$/.test(evidenceTag)) throw new Error('Invalid evidence tag')
const screenshotPrefix = evidenceTag === 'g4' ? 'assistant' : `${evidenceTag}-assistant`
const browser = await chromium.launch({ headless: true }); const contexts = [], checks = [], errors = []; let step = 'login'
const checked = name => { checks.push(name); console.log(`PASS ${name}`) }
async function login(username, password) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1050 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' }); contexts.push(context)
  const page = await context.newPage(); page.on('pageerror', error => errors.push(error.message))
  await page.goto(`${base}/login`); await page.locator('#username').fill(username); await page.locator('#password').fill(password || env[`DEMO_BROWSER_${username.toUpperCase()}_PASSWORD`] || env.DEMO_INITIAL_PASSWORD)
  const pending = page.waitForResponse(response => response.url().endsWith('/auth/login') && response.request().method() === 'POST')
  await page.getByRole('button', { name: /登录工作台/ }).click(); const response = await pending; expect(response.status()).toBe(200)
  const body = await response.json(); if (body.user.mustChangePassword) throw new Error('Run full browser verification to finish demo first-login flow')
  await page.waitForURL(url => !['/login', '/change-password'].includes(url.pathname)); return { page, token: body.accessToken, user: body.user }
}
async function api(actor, method, path, data, key) {
  const response = await actor.page.request.fetch(`${base}/api/v1${path}`, { method, headers: { Authorization: `Bearer ${actor.token}`, ...(key ? { 'Idempotency-Key': key } : {}) }, ...(data === undefined ? {} : { data }) })
  if (![200, 201, 202, 204].includes(response.status())) throw new Error(`${method} ${path}: HTTP ${response.status()}`)
  return response.status() === 204 ? null : response.json()
}
async function job(actor, id) {
  let result
  await expect.poll(async () => { result = await api(actor, 'GET', `/jobs/${id}`); return result.status }, { timeout: 45_000 }).toMatch(/^(COMPLETED|FAILED)$/)
  return result
}
async function assistant(actor, question, space) {
  await actor.page.locator('a[href="/assistant"]').first().click(); await actor.page.waitForURL('**/assistant')
  await actor.page.locator('#assistant-question').fill(question); if (space) await actor.page.locator('#assistant-spaces').selectOption([space])
  await actor.page.locator('#assistant-submit').click()
}
try {
  const admin = await login('admin'), reader = await login('requester'), agent = await login('agent')
  const reviewerFixture=await createEditorFixture(admin,base,secret=>secrets.push(secret)),reviewer=await login(reviewerFixture.username,reviewerFixture.password)
  const config = await api(admin, 'GET', '/ai/config'); expect(config.mode).toBe('mock')
  step = 'create synthetic published knowledge source'
  const suffix = Date.now(), title = `G4虚构网关手册 ${suffix}`, question = '网关502错误先检查什么？'
  const space = await api(admin, 'POST', '/knowledge/spaces', { name: `G4验收 ${suffix}` }, crypto.randomUUID())
  await permitReview(admin,reviewer,base,space.id)
  for (const member of [reader, agent]) await api(admin, 'PUT', `/knowledge/spaces/${space.id}/members/${member.user.id}`, { canRead: true, canEdit: false, reason: '虚构G4浏览器验收' })
  const uploadedResponse = await admin.page.request.post(`${base}/api/v1/knowledge/spaces/${space.id}/documents`, { headers: { Authorization: `Bearer ${admin.token}`, 'Idempotency-Key': crypto.randomUUID() }, multipart: { title, file: { name: 'g4-gateway.md', mimeType: 'text/markdown', buffer: Buffer.from('# 网关502排查\n网关出现502错误时，先检查上游服务健康检查结果，再对照请求时间查看upstream连接日志。核对测试服务地址和端口，修复后重新访问验证。') } } })
  expect(uploadedResponse.status()).toBe(202); const uploaded = await uploadedResponse.json()
  await expect.poll(async () => (await api(admin, 'GET', `/knowledge/documents/${uploaded.documentId}`)).versions.find(version => version.id === uploaded.versionId)?.ingestState, { timeout: 60_000 }).toBe('READY')
  await approveVersion(admin,reviewer,base,uploaded.documentId,uploaded.versionId)
  await api(admin, 'POST', `/knowledge/documents/${uploaded.documentId}/versions/${uploaded.versionId}/publish`)
  // A tiny real pipeline fixture should index promptly; each poll is a distinct request, never a replay of an old refusal.
  await expect.poll(async () => { const created = await api(reader, 'POST', '/ai/answers', { question, spaceIds: [space.id] }, crypto.randomUUID()); return (await job(reader, created.jobId)).result?.outcome }, { timeout: 45_000, intervals: [1000, 2000] }).toBe('ANSWERED')
  checked('published Docling source is indexed and retrieved through the actual async AI API')
  step = 'assistant answer and citations'
  await assistant(reader, question, space.id)
  await expect(reader.page.getByText(title, { exact: true }).first()).toBeVisible()
  await expect(reader.page.getByText(/上游服务健康检查/).first()).toBeVisible()
  await expect(reader.page.getByText(/本地.*演示|mock/i).first()).toBeVisible()
  checked('Chinese assistant displays a cited answer and explicit local demonstration mode')
  await reader.page.evaluate(() => window.scrollTo(0, 0)); await reader.page.screenshot({ path: resolve(evidence, `${screenshotPrefix}-desktop.png`), fullPage: true, animations: 'disabled' })
  await reader.page.setViewportSize({ width: 390, height: 844 }); await expect.poll(() => reader.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await reader.page.evaluate(() => window.scrollTo(0, 0)); await reader.page.screenshot({ path: resolve(evidence, `${screenshotPrefix}-mobile.png`), fullPage: true, animations: 'disabled' }); await reader.page.setViewportSize({ width: 1440, height: 1050 })
  checked('390px assistant layout has no horizontal overflow')
  step = 'ticket suggestions stay human controlled'
  const ticketTitle = `G4验收网关502 ${suffix}`
  const ticket = await api(reader, 'POST', '/tickets', { title: ticketTitle, description: '网关502错误，需要检查上游服务健康检查及upstream连接日志。', priority: 'NORMAL', serviceId: null }, crypto.randomUUID())
  await agent.page.locator('a[href="/tickets"]').first().click(); await agent.page.getByRole('button', { name: '查询', exact: true }).click(); await agent.page.getByRole('link', { name: ticketTitle, exact: true }).click()
  await agent.page.waitForURL(`**/tickets/${ticket.id}`)
  await agent.page.locator('#comment-body').fill('我的原始公开草稿')
  let commentPosts = 0; agent.page.on('request', request => { if (request.method() === 'POST' && request.url().endsWith(`/tickets/${ticket.id}/comments`)) commentPosts++ })
  await agent.page.locator('#ai-kind').selectOption('REPLY'); await agent.page.locator('#ai-generate').click()
  await agent.page.getByRole('button', { name: '采纳建议', exact: true }).click()
  await agent.page.getByRole('button', { name: '确认替换', exact: true }).click()
  await expect(agent.page.locator('#comment-body')).not.toHaveValue('我的原始公开草稿'); expect(commentPosts).toBe(0)
  checked('accepting a reply confirms replacement and fills the public draft without sending')
  await agent.page.getByRole('button', { name: '发送回复', exact: true }).click(); await expect.poll(() => commentPosts).toBe(1)
  await expect(agent.page.getByText('公开回复已发送。', { exact: true })).toBeVisible()
  checked('only the existing manual send action creates a public ticket reply')
  step = 'withdrawal invalidates an already rendered answer'
  await api(admin, 'POST', `/knowledge/documents/${uploaded.documentId}/disable`)
  await reader.page.getByRole('button', { name: '拒绝建议', exact: true }).click()
  await expect(reader.page.getByText(title, { exact: true })).toHaveCount(0)
  checked('feedback reauthorization clears the cached answer and citations after withdrawal')
  step = 'explicit no-evidence refusal'
  await reader.page.locator('#assistant-question').fill('今天食堂午餐菜单是什么？'); await reader.page.locator('#assistant-submit').click()
  await expect(reader.page.getByText(/无可信来源|证据不足|无法从知识库确认|没有足够/).first()).toBeVisible()
  checked('unrelated knowledge question produces explicit refusal without fabricated citations')
  await api(admin, 'DELETE', `/knowledge/documents/${uploaded.documentId}`)
  expect(errors).toEqual([])
  await writeFile(resolve(evidence, `${evidenceTag}-browser-verification.json`), JSON.stringify({ date: new Date().toISOString(), base, status: 'PASS', mode: 'mock', checks, uncaughtPageErrors: errors.length, fixtureTicketId: ticket.id }, null, 2) + '\n')
} catch (error) {
  let message = `step=${step}; ${String(error.message || error)}`
  for (const secret of secrets) message = message.split(secret).join('[REDACTED]')
  await writeFile(resolve(evidence, `${evidenceTag}-browser-verification.json`), JSON.stringify({ date: new Date().toISOString(), status: 'FAILED', checks, error: message, uncaughtPageErrors: errors.length }, null, 2) + '\n')
  console.error(message); process.exitCode = 1
} finally { for (const context of contexts) await context.close(); await browser.close() }
