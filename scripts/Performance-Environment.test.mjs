import test from 'node:test'
import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { resolve } from 'node:path'
import { readFile } from 'node:fs/promises'

const execute = promisify(execFile)

test('performance database guard requires the exact five seeded identity-role pairs', async () => {
  const source = await readFile(resolve('scripts/Run-Performance.ps1'), 'utf8')
  assert.match(source, /count\(\*\) FROM app_user\)=5/)
  assert.match(source, /count\(\*\) FROM user_role\)=5/)
  for (const [username, role] of [
    ['admin', 'ADMIN'], ['agent', 'AGENT'], ['editor', 'KB_EDITOR'],
    ['requester', 'REQUESTER'], ['reviewer', 'KB_REVIEWER']
  ]) assert.match(source, new RegExp(`\\('${username}','${role}'\\)`))
  assert.doesNotMatch(source, /count\(\*\) FROM app_user\)=4/)
})

for (const marker of ['demo', 'browser-test', '']) {
  test(`performance preparation rejects ${marker || 'unmarked'} server before credentials or writes`, async () => {
    let writes = 0
    const server = createServer((request, response) => {
      if (request.method !== 'GET') writes++
      if (marker) response.setHeader('X-Ticknova-Environment', marker)
      response.end('local fixture')
    })
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
    try {
      await assert.rejects(execute(process.execPath, ['scripts/Prepare-Performance.mjs', `http://127.0.0.1:${server.address().port}`, resolve('.env.perf'), resolve('.cache/unused-perf-fixture.json')]), error => {
        assert.match(error.stderr, /Refusing performance writes/)
        return true
      })
      assert.equal(writes, 0)
    } finally { await new Promise(resolve => server.close(resolve)) }
  })
}
