import { readFile, writeFile, appendFile } from 'node:fs/promises'
import { randomBytes } from 'node:crypto'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const secret = () => randomBytes(32).toString('base64url')
export async function ensureDemoEnv(root) {
  const file = resolve(root, '.env.demo')
  let existing
  try { existing = await readFile(file, 'utf8') } catch (error) { if (error.code !== 'ENOENT') throw error }
  if (existing !== undefined) {
    if (!/^DOCLING_INTERNAL_TOKEN=\S+/m.test(existing)) {
      await appendFile(file, `${existing.endsWith('\n') ? '' : '\n'}DOCLING_INTERNAL_TOKEN=${secret()}\n`, 'utf8')
      return 'parser-token-added'
    }
    return 'preserved'
  }
  const lines = [`DB_PASSWORD=${secret()}`, `SESSION_TOKEN_PEPPER=${secret()}`, `DEMO_INITIAL_PASSWORD=${secret()}`, 'DEMO_SEED_ENABLED=true', `TEST_DB_PASSWORD=${secret()}`, `DOCLING_INTERNAL_TOKEN=${secret()}`, 'WEB_PORT=5173', 'API_PORT=8080']
  await writeFile(file, lines.join('\n') + '\n', { encoding: 'utf8', mode: 0o600, flag: 'wx' })
  return 'created'
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  console.log(`Local demo configuration: ${await ensureDemoEnv(resolve(dirname(fileURLToPath(import.meta.url)), '..'))}. Secrets are not printed.`)
}
