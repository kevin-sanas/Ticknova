import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdir, mkdtemp, readFile, writeFile, rm, stat } from 'node:fs/promises'
import { resolve, join, sep } from 'node:path'
import { ensureDemoEnv } from './New-LocalEnv.mjs'

const cache = resolve('.cache')
async function fixture(work) {
  await mkdir(cache, { recursive: true })
  const root = await mkdtemp(join(cache, 'env-test-'))
  try { await work(root) } finally { assert.ok(root.startsWith(cache + sep)); await rm(root, { recursive: true, force: true }) }
}
test('fresh cross-platform configuration uses separate random secrets and private mode', () => fixture(async root => {
  assert.equal(await ensureDemoEnv(root), 'created')
  const file = join(root, '.env.demo'), content = await readFile(file, 'utf8')
  const secrets = content.split('\n').filter(row => /^(DB_PASSWORD|SESSION_TOKEN_PEPPER|DEMO_INITIAL_PASSWORD|TEST_DB_PASSWORD|DOCLING_INTERNAL_TOKEN)=/.test(row)).map(row => row.split('=')[1])
  assert.equal(secrets.length, 5)
  assert.equal(new Set(secrets).size, 5)
  assert.ok(secrets.every(value => value.length >= 32))
  if (process.platform !== 'win32') assert.equal((await stat(file)).mode & 0o777, 0o600)
}))
test('restarting preserves all existing configuration bytes', () => fixture(async root => {
  await ensureDemoEnv(root)
  const file = join(root, '.env.demo'), before = await readFile(file, 'utf8')
  assert.equal(await ensureDemoEnv(root), 'preserved')
  assert.equal(await readFile(file, 'utf8'), before)
}))
test('legacy configuration only gains its missing private parser token', () => fixture(async root => {
  const file = join(root, '.env.demo'), before = 'DB_PASSWORD=local-fixture-value\nWEB_PORT=5199\n'
  await writeFile(file, before)
  assert.equal(await ensureDemoEnv(root), 'parser-token-added')
  const after = await readFile(file, 'utf8')
  assert.ok(after.startsWith(before))
  assert.match(after, /DOCLING_INTERNAL_TOKEN=[A-Za-z0-9_-]{32,}/)
}))
