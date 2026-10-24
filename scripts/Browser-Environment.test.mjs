import { test } from 'node:test'
import assert from 'node:assert/strict'
import { resolve, join } from 'node:path'
import { tmpdir } from 'node:os'
import { browserEnvironment } from './Browser-Environment.mjs'

const root = resolve('browser-project'), settings = { TICKNOVA_URL: 'http://127.0.0.1:5174', TICKNOVA_ENV_FILE: resolve('.env.browser'), TICKNOVA_EVIDENCE_DIR: join(tmpdir(), 'ticknova-browser-tests') }
function io(marker = 'browser-test') { return { fetch: async () => ({ ok: true, headers: new Headers({ 'x-ticknova-environment': marker }) }), readFile: async () => 'DEMO_INITIAL_PASSWORD=test-only\n', mkdir: async () => {}, mkdtemp: async () => settings.TICKNOVA_EVIDENCE_DIR } }
test('missing explicit test environment fails before any request', async () => {
  await assert.rejects(browserEnvironment(root, {}, { fetch: () => assert.fail('must not request') }), /explicit isolated/)
})
test('demo and unmarked servers are rejected before reading credentials', async () => {
  for (const marker of ['demo', 'local', '']) await assert.rejects(browserEnvironment(root, settings, { ...io(marker), readFile: () => assert.fail('must not read secrets') }), /not the isolated/)
})
test('demo credentials and in-project screenshots are rejected', async () => {
  await assert.rejects(browserEnvironment(root, { ...settings, TICKNOVA_ENV_FILE: resolve(root, '.env.demo') }, io()), /demo credentials/)
  await assert.rejects(browserEnvironment(root, { ...settings, TICKNOVA_EVIDENCE_DIR: resolve(root, 'docs/evidence') }, io()), /outside the project/)
})
test('only marked loopback test origin accepts isolated credentials and external artifacts', async () => {
  const result = await browserEnvironment(root, settings, io())
  assert.equal(result.base, settings.TICKNOVA_URL); assert.equal(result.env.DEMO_INITIAL_PASSWORD, 'test-only')
  await assert.rejects(browserEnvironment(root, { ...settings, TICKNOVA_URL: 'https://example.com' }, io()), /Local test/)
})
