import { readFile, writeFile, mkdir } from 'node:fs/promises'
import { resolve, dirname } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { randomBytes } from 'node:crypto'
import { signInDemo, changeDemoPassword } from './Demo-Credentials.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const envPath = resolve(root, '.env')
const base = process.env.TICKNOVA_URL || 'http://127.0.0.1'
const origin = new URL(base)
if (origin.protocol !== 'http:' || origin.hostname !== '127.0.0.1' || origin.pathname !== '/') throw Error('Only the isolated local Ticknova browser stack is allowed')
const env = Object.fromEntries((await readFile(resolve(root, '.env'), 'utf8')).split(/\r?\n/).filter(line => line && !line.startsWith('#')).map(line => { const index = line.indexOf('='); return [line.slice(0, index), line.slice(index + 1)] }))
const { chromium } = await import(pathToFileURL(resolve(root, 'frontend/node_modules/playwright/index.mjs')))
const { expect: baseExpect } = await import(pathToFileURL(resolve(root, 'frontend/node_modules/@playwright/test/index.mjs')))
const expect = baseExpect.configure({ timeout: 30_000 })
const evidence = resolve(root, 'docs/evidence')
await mkdir(evidence, { recursive: true })
const prefix = 'galaxy-batch1-2026-10-22'
const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 960 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' })
const page = await context.newPage()
const checks = [], screenshots = [], errors = []
const secrets = [env.DEMO_INITIAL_PASSWORD].filter(Boolean)
let phase = 'bootstrap'
page.on('pageerror', error => errors.push(error.name))
function checked(name) { checks.push(name); console.log(`PASS ${name}`) }
async function snapshot(tag, target = page) {
  for (const width of [1440, 390]) {
    await target.setViewportSize({ width, height: width === 390 ? 844 : 960 })
    await expect.poll(() => target.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
    const name = `${prefix}-${tag}-${width}.png`
    await target.screenshot({ path: resolve(evidence, name), fullPage: true, animations: 'disabled' })
    screenshots.push(name)
  }
  await target.setViewportSize({ width: 1440, height: 960 })
}
async function style(selector) {
  return page.locator(selector).first().evaluate(element => {
    const css = getComputedStyle(element)
    return { border: css.borderColor, borderLeftWidth: css.borderLeftWidth, outline: css.outlineStyle, outlineWidth: css.outlineWidth, color: css.color, background: css.backgroundColor, minHeight: css.minHeight }
  })
}
try {
  const adminSignIn = await signInDemo(context.request, base, envPath, 'admin')
  let adminPassword = adminSignIn.password
  const initial = adminSignIn.login; secrets.push(initial.accessToken, adminPassword)
  if (initial.user.mustChangePassword) {
    const changed = `Galaxy-${randomBytes(20).toString('hex')}!`; secrets.push(changed)
    adminPassword = await changeDemoPassword(context.request, base, envPath, 'admin', adminPassword, initial.accessToken, changed)
  }
  phase = 'login controls'
  await page.goto(`${base}/login`)
  await expect(page.getByRole('heading', { name: '欢迎回来' })).toBeVisible()
  await snapshot('login')
  await page.locator('#username').focus()
  expect((await style('#username')).outlineWidth).toBe('2px')
  await page.locator('#username').fill('admin')
  await page.locator('#password').fill('wrong-password')
  const failedLogin = page.waitForResponse(response => response.url().endsWith('/auth/login') && response.request().method() === 'POST')
  await page.getByRole('button', { name: /登录工作台/ }).click()
  expect((await failedLogin).status()).toBe(401)
  await expect(page.locator('.error-notice[role=alert]')).toBeVisible()
  await expect(page.locator('#username')).toHaveValue('admin')
  expect((await style('.error-notice')).borderLeftWidth).toBe('3px')
  checked('input focus, real login error feedback and preserved username')
  await page.locator('#password').fill(adminPassword)
  const goodLogin = page.waitForResponse(response => response.url().endsWith('/auth/login') && response.request().method() === 'POST')
  await page.getByRole('button', { name: /登录工作台/ }).click()
  expect((await goodLogin).status()).toBe(200)
  await page.waitForURL(url => url.pathname !== '/login')
  checked('actual request controls the login loading and success transition')

  phase = 'affected pages'
  for (const [path, name] of [['/portal', 'portal'], ['/knowledge', 'knowledge'], ['/admin/users', 'users']]) {
    await page.locator(`.sidebar a[href="${path}"]`).click()
    await page.waitForURL(url => url.pathname === path)
    await expect(page.locator('main')).toBeVisible()
    await snapshot(name)
  }
  checked('portal, knowledge and users have no horizontal overflow at 1440 and 390 px')

  phase = 'admin dialog controls'
  await page.getByRole('button', { name: /创建用户/ }).click()
  const dialog = page.getByRole('dialog', { name: '创建用户' })
  await expect(dialog).toBeVisible()
  const displayName = dialog.locator('#user-name')
  const quietBorder = await displayName.evaluate(element => getComputedStyle(element).borderColor)
  await displayName.hover()
  await expect.poll(() => displayName.evaluate(element => getComputedStyle(element).borderColor)).not.toBe(quietBorder)
  await dialog.locator('#new-username').focus()
  expect((await style('#new-username')).outlineWidth).toBe('2px')
  const checkbox = dialog.locator('input[type=checkbox]').first()
  await checkbox.focus()
  expect(parseFloat((await style('input[type=checkbox]')).outlineWidth)).toBeGreaterThanOrEqual(2)
  await snapshot('user-dialog')
  const closeButton = dialog.getByRole('button', { name: '关闭', exact: true })
  const quietButton = await closeButton.evaluate(element => getComputedStyle(element).backgroundColor)
  await closeButton.hover()
  await expect.poll(() => closeButton.evaluate(element => getComputedStyle(element).backgroundColor)).not.toBe(quietButton)
  await dialog.getByRole('button', { name: '创建用户', exact: true }).click()
  expect(await dialog.locator('#new-username').evaluate(element => element.matches(':user-invalid'))).toBe(true)
  await expect.poll(async () => (await style('#new-username')).border).toBe('rgb(180, 35, 24)')
  await closeButton.click()
  await expect(dialog).toBeHidden()
  checked('dialog input and checkbox keyboard focus; closing leaves no overlay')

  phase = 'network failure recovery'
  let releaseRead
  const readGate = new Promise(resolve => { releaseRead = resolve })
  await page.route('**/api/v1/users?*', async route => { await readGate; await route.continue() })
  const refresh = page.getByRole('button', { name: '刷新', exact: true })
  await refresh.click()
  await expect(refresh).toHaveClass(/is-loading/)
  releaseRead()
  await expect(refresh).not.toHaveClass(/is-loading/)
  await page.unroute('**/api/v1/users?*')
  checked('button loading state follows a delayed real users request')
  await page.route('**/api/v1/users?*', route => route.abort('failed'))
  await refresh.click()
  await expect(page.locator('.error-notice[role=alert]')).toBeVisible()
  await snapshot('users-error')
  await page.unroute('**/api/v1/users?*')
  await page.getByRole('button', { name: '重试' }).click()
  await expect(page.locator('.error-notice[role=alert]')).toHaveCount(0)
  await expect(page.locator('table tbody tr')).not.toHaveCount(0)
  checked('simulated request failure is visible; real retry restores server data')
  phase = 'engineer ticket page'
  const agentSignIn = await signInDemo(context.request, base, envPath, 'agent')
  let agentPassword = agentSignIn.password
  const agentInitial = agentSignIn.login; secrets.push(agentInitial.accessToken, agentPassword)
  if (agentInitial.user.mustChangePassword) {
    const changed = `Galaxy-Agent-${randomBytes(20).toString('hex')}!`; secrets.push(changed)
    agentPassword = await changeDemoPassword(context.request, base, envPath, 'agent', agentPassword, agentInitial.accessToken, changed)
  }
  const agentPage = await context.newPage()
  agentPage.on('pageerror', error => errors.push(error.name))
  await agentPage.goto(`${base}/login`)
  await agentPage.locator('#username').fill('agent')
  await agentPage.locator('#password').fill(agentPassword)
  const agentLogin = agentPage.waitForResponse(response => response.url().endsWith('/auth/login') && response.request().method() === 'POST')
  await agentPage.getByRole('button', { name: /登录工作台/ }).click()
  expect((await agentLogin).status()).toBe(200)
  await agentPage.locator('.sidebar a[href="/tickets"]').click()
  await agentPage.waitForURL(url => url.pathname === '/tickets')
  await snapshot('tickets', agentPage)
  expect(await agentPage.locator('.el-pagination button:disabled').count()).toBeGreaterThan(0)
  checked('engineer ticket list buttons and filters have no horizontal overflow at 1440 and 390 px')
  expect(errors).toEqual([])
  await writeFile(resolve(evidence, `${prefix}.json`), JSON.stringify({ recordedAt: new Date().toISOString(), status: 'PASS', checks, screenshots, pageErrors: errors, scope: 'isolated ticknova_browser database; Chromium only; no existing demo database' }, null, 2) + '\n')
} catch (error) {
  let message = `Galaxy first batch browser failure at ${phase}: ${error.message}`
  for (const secret of secrets) message = message.replaceAll(secret, '[REDACTED]')
  console.error(message)
  process.exitCode = 1
  try { await page.screenshot({ path: resolve(evidence, `${prefix}-failure.png`), fullPage: true, animations: 'disabled' }) } catch {}
} finally {
  await context.close()
  await browser.close()
}
