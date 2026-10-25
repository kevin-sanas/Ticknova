import { browserEnvironment } from './Browser-Environment.mjs'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
const root = resolve('.'), { base, env, evidence } = await browserEnvironment(root)
const { chromium } = await import(pathToFileURL(resolve(root,'frontend/node_modules/playwright/index.mjs')))
const { expect: rawExpect } = await import(pathToFileURL(resolve(root,'frontend/node_modules/@playwright/test/index.mjs')))
const expect = rawExpect.configure({ timeout: 45000 }), browser = await chromium.launch({ headless: true }), contexts = [], errors = []
const polluted = /浏览器验收|V2合成|验收审核人|时光机|歌词/
async function login(username) {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1050 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' }); contexts.push(context)
  const page = await context.newPage(); page.on('pageerror', error => errors.push(error.name))
  await page.goto(`${base}/login`); await page.locator('#username').fill(username); await page.locator('#password').fill(env[`DEMO_BROWSER_${username.toUpperCase()}_PASSWORD`])
  const response=page.waitForResponse(r=>r.url().endsWith('/auth/login')&&r.request().method()==='POST'); await page.getByRole('button', { name: /登录工作台/ }).click(); const signed=await(await response).json(); expect(signed.user.roles).toEqual([({admin:'ADMIN',agent:'AGENT',requester:'REQUESTER',editor:'KB_EDITOR',reviewer:'KB_REVIEWER'})[username]]); await page.waitForURL(url => !['/login','/change-password'].includes(url.pathname))
  return page
}
async function navigate(page, path) { await page.locator(`a[href="${path}"]:visible`).first().click(); await page.waitForURL(url => url.pathname === path) }
try {
  const reader = await login('requester'); await expect(reader.locator('.portal-main .document-card')).not.toHaveCount(0)
  for (const width of [1920,1440,1366,1280,768,390]) {
    await reader.setViewportSize({ width, height: width === 390 ? 844 : 1050 })
    expect(await reader.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(true)
    const snippets = await reader.locator('.portal-main .document-snippet').evaluateAll(rows => rows.map(row => ({ height: row.getBoundingClientRect().height, line: parseFloat(getComputedStyle(row).lineHeight), clamp: getComputedStyle(row).webkitLineClamp })))
    expect(snippets.length).toBeGreaterThan(0)
    for (const snippet of snippets) { expect(snippet.clamp).toBe('2'); expect(snippet.height).toBeLessThanOrEqual(snippet.line * 2 + 1) }
    await reader.screenshot({ path: resolve(evidence,`demo-portal-${width}.png`), fullPage: true, animations: 'disabled' })
  }
  expect(await reader.locator('body').innerText()).not.toMatch(polluted)
  await reader.setViewportSize({width:1440,height:1050}); await navigate(reader,'/assistant')
  for (const [question, title] of [['网络无法连接怎么办','网络无法连接的排查步骤'],['VPN 连不上','VPN 连接失败处理指南'],['怎么重置密码','安全重置账号密码']]) {
    await reader.locator('#assistant-question').fill(question)
    const accepted = reader.waitForResponse(response => response.url().endsWith('/ai/answers') && response.request().method() === 'POST')
    await reader.locator('#assistant-submit').click(); expect((await accepted).status()).toBe(202)
    await expect(reader.getByRole('button',{name:'采纳建议',exact:true})).toBeVisible()
    await expect(reader.locator('.ai-citations a').first()).toBeVisible(); await expect(reader.locator('.ai-citations a').first()).toHaveAttribute('href', /^\/knowledge\/documents\/[a-f0-9-]{36}\?/)
    await expect(reader.locator('.ai-citations')).toContainText(title)
    await expect(reader.getByText(/本地.*演示|mock/i).first()).toBeVisible()
    console.log(`PASS original demo knowledge returns a cited mock answer: ${question}`)
  }
  for (const username of ['admin','agent','editor','reviewer']) {
    const page = await login(username)
    if (username === 'admin') for (const path of ['/admin/users','/admin/queues','/admin/services']) { await navigate(page,path); await expect(page.locator('table tbody tr').first()).toBeVisible(); expect(await page.locator('body').innerText()).not.toMatch(polluted) }
    else if (username === 'agent') {
      await navigate(page,'/tickets'); await expect(page.locator('#ticket-list-heading')).toContainText('24 条')
      await page.locator('#ticket-search').fill('VPN 连接后无法访问内网'); await page.getByRole('button',{name:'查询',exact:true}).click()
      await expect(page.getByRole('link',{name:'VPN 连接后无法访问内网',exact:true})).toBeVisible()
    }
    else { await navigate(page,'/knowledge'); await expect(page.getByRole('button',{name:'网络无法连接的排查步骤',exact:true})).toBeVisible() }
    expect(await page.locator('body').innerText()).not.toMatch(polluted)
    console.log(`PASS ${username} sees clean original demo data within its role`)
  }
  expect(errors).toEqual([])
  console.log('PASS requester portal has two-line summaries and no overflow at six widths; all five roles are usable; page errors=0')
} finally { for (const context of contexts) await context.close(); await browser.close() }
