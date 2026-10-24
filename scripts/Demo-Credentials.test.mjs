import test from 'node:test'
import assert from 'node:assert/strict'
import { changeDemoPassword, parseDemoEnv, signInDemo } from './Demo-Credentials.mjs'

function memoryEnv(initial = 'DEMO_INITIAL_PASSWORD=initial\n') {
  let content = initial
  return {
    readFile: async () => content,
    writeFile: async (_path, value) => { content = value },
    settings: () => parseDemoEnv(content)
  }
}

function response(status, login) {
  return { status: () => status, json: async () => login }
}

test('demo seed credentials remain separate from browser credentials', async () => {
  const io = memoryEnv('DEMO_INITIAL_PASSWORD=initial\nDEMO_BROWSER_ADMIN_PASSWORD=browser-password\n')
  const request = { post: async () => response(204) }
  await changeDemoPassword(request, 'http://127.0.0.1', '.env.demo', 'admin', 'initial', 'token', 'demo-password', io, 'DEMO')
  assert.equal(io.settings().DEMO_ADMIN_PASSWORD, 'demo-password')
  assert.equal(io.settings().DEMO_BROWSER_ADMIN_PASSWORD, 'browser-password')
  assert.equal(io.settings().DEMO_ADMIN_PENDING_PASSWORD, undefined)
})

test('fresh demo sign-in uses initial password without changing the environment', async () => {
  const io = memoryEnv()
  const request = { post: async (_url, options) => {
    assert.equal(options.data.password, 'initial')
    return response(200, { accessToken: 'token' })
  } }
  const result = await signInDemo(request, 'http://127.0.0.1', '.env', 'admin', io)
  assert.equal(result.password, 'initial')
  assert.equal(result.login.accessToken, 'token')
  assert.deepEqual(io.settings(), { DEMO_INITIAL_PASSWORD: 'initial' })
})

test('password change writes recovery candidate before request and final value after success', async () => {
  const io = memoryEnv()
  const request = { post: async (_url, options) => {
    assert.equal(io.settings().DEMO_BROWSER_ADMIN_PENDING_PASSWORD, 'replacement')
    assert.equal(options.data.oldPassword, 'initial')
    assert.equal(options.data.newPassword, 'replacement')
    return response(204)
  } }
  const changed = await changeDemoPassword(request, 'http://127.0.0.1', '.env', 'admin', 'initial', 'token', 'replacement', io)
  assert.equal(changed, 'replacement')
  assert.equal(io.settings().DEMO_BROWSER_ADMIN_PASSWORD, 'replacement')
  assert.equal(io.settings().DEMO_BROWSER_ADMIN_PENDING_PASSWORD, undefined)
})

test('interrupted password response is recovered by trying the pending candidate first', async () => {
  const io = memoryEnv()
  await assert.rejects(
    changeDemoPassword({ post: async () => { throw new Error('connection closed') } }, 'http://127.0.0.1', '.env', 'editor', 'initial', 'token', 'replacement', io),
    /connection closed/
  )
  assert.equal(io.settings().DEMO_BROWSER_EDITOR_PENDING_PASSWORD, 'replacement')
  const tried = []
  const request = { post: async (_url, options) => {
    tried.push(options.data.password)
    return response(options.data.password === 'replacement' ? 200 : 401, { accessToken: 'token' })
  } }
  const result = await signInDemo(request, 'http://127.0.0.1', '.env', 'editor', io)
  assert.equal(result.password, 'replacement')
  assert.deepEqual(tried, ['replacement'])
  assert.equal(io.settings().DEMO_BROWSER_EDITOR_PASSWORD, 'replacement')
  assert.equal(io.settings().DEMO_BROWSER_EDITOR_PENDING_PASSWORD, undefined)
})

test('rejected password change keeps the old known password recoverable', async () => {
  const io = memoryEnv('DEMO_INITIAL_PASSWORD=initial\r\nDEMO_BROWSER_AGENT_PASSWORD=current\r\nDEMO_AGENT_PASSWORD=real-demo-password\r\n')
  await assert.rejects(
    changeDemoPassword({ post: async () => response(400) }, 'http://127.0.0.1', '.env', 'agent', 'current', 'token', 'replacement', io),
    /HTTP 400/
  )
  const tried = []
  const request = { post: async (_url, options) => {
    tried.push(options.data.password)
    return response(options.data.password === 'current' ? 200 : 401, { accessToken: 'token' })
  } }
  const result = await signInDemo(request, 'http://127.0.0.1', '.env', 'agent', io)
  assert.equal(result.password, 'current')
  assert.deepEqual(tried, ['replacement', 'current'])
  assert.equal(io.settings().DEMO_BROWSER_AGENT_PASSWORD, 'current')
  assert.equal(io.settings().DEMO_BROWSER_AGENT_PENDING_PASSWORD, undefined)
  assert.equal(io.settings().DEMO_AGENT_PASSWORD, 'real-demo-password')
})

test('non-authentication failure stops credential attempts', async () => {
  const io = memoryEnv('DEMO_INITIAL_PASSWORD=initial\nDEMO_BROWSER_ADMIN_PASSWORD=current\n')
  let attempts = 0
  await assert.rejects(signInDemo({ post: async () => { attempts++; return response(429) } }, 'http://127.0.0.1', '.env', 'admin', io), /HTTP 429/)
  assert.equal(attempts, 1)
})
