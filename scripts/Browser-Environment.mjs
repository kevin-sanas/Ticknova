import { readFile, mkdir, mkdtemp } from 'node:fs/promises'
import { resolve, relative, isAbsolute, join } from 'node:path'
import { tmpdir } from 'node:os'
import { parseDemoEnv } from './Demo-Credentials.mjs'

// Every browser entry point checks the server before it can authenticate or write.
export async function browserEnvironment(root, settings = process.env, io = { fetch, readFile, mkdir, mkdtemp }) {
  if (!settings.TICKNOVA_URL || !settings.TICKNOVA_ENV_FILE) throw Error('Use scripts/Test-Browser.ps1: an explicit isolated test environment is required')
  const origin = new URL(settings.TICKNOVA_URL)
  if (origin.protocol !== 'http:' || origin.hostname !== '127.0.0.1' || origin.username || origin.password || origin.pathname !== '/' || origin.search || origin.hash) throw Error('Local test origin required')
  const response = await io.fetch(origin, { signal: AbortSignal.timeout(10000), redirect: 'error' })
  if (!response.ok || response.headers.get('x-ticknova-environment') !== 'browser-test') throw Error('Refusing browser writes: target is not the isolated browser-test server')
  const envPath = resolve(settings.TICKNOVA_ENV_FILE)
  if (['.env', '.env.demo'].some(name => envPath === resolve(root, name))) throw Error('Browser tests cannot use demo credentials')
  const evidence = settings.TICKNOVA_EVIDENCE_DIR ? resolve(settings.TICKNOVA_EVIDENCE_DIR) : await io.mkdtemp(join(tmpdir(), 'ticknova-browser-'))
  const path = relative(resolve(root), evidence)
  if (!path || (!path.startsWith('..') && !isAbsolute(path))) throw Error('Browser screenshots must stay outside the project')
  await io.mkdir(evidence, { recursive: true })
  return { base: origin.origin, envPath, env: parseDemoEnv(await io.readFile(envPath, 'utf8')), evidence }
}
