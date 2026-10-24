import { readFile, writeFile, lstat, mkdir } from 'node:fs/promises'
import { execFileSync } from 'node:child_process'
import { resolve, dirname, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const evidenceTag = process.env.TICKNOVA_EVIDENCE_TAG
if (evidenceTag && !/^[a-z0-9-]+$/.test(evidenceTag)) throw new Error('Invalid evidence tag')
const local = (await Promise.all(['.env','.env.demo','.env.browser'].map(file => readFile(resolve(root, file), 'utf8').catch(error => { if (error.code === 'ENOENT') return ''; throw error })))).join('\n').split(/\r?\n/).filter(line => /^[A-Z_]*(?:PASSWORD|TOKEN|PEPPER|API_KEY)[A-Z_]*=/.test(line)).map(line => {
  const i = line.indexOf('='); return { name: line.slice(0, i), value: line.slice(i + 1) }
}).filter(secret => secret.value.length >= 12)
const files = [...new Set(execFileSync('git', ['ls-files', '--cached', '--others', '--exclude-standard', '-z'], { cwd: root, encoding: 'utf8' }).split('\0').filter(Boolean))]
const matches = []; let scanned = 0
for (const file of files) {
  const path = resolve(root, file); if (!path.startsWith(root + sep)) throw new Error('Path outside workspace')
  let stat; try { stat = await lstat(path) } catch (error) { if (error.code === 'ENOENT') continue; throw error }
  if (!stat.isFile() || stat.isSymbolicLink()) continue
  const bytes = await readFile(path); if (bytes.includes(0)) continue
  const text = bytes.toString('utf8'); scanned++
  for (const secret of local) if (text.includes(secret.value)) matches.push({ file, kind: 'local-secret-value', variable: secret.name })
  for (const [kind, pattern] of [['aws-key', /\bAKIA[A-Z0-9]{16}\b/], ['private-key', /-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----/], ['npm-token', /\bnpm_[A-Za-z0-9]{36}\b/]]) if (pattern.test(text)) matches.push({ file, kind })
}
const report = { checkedAt: new Date().toISOString(), status: matches.length ? 'FINDINGS' : 'PASS', scannedTextFiles: scanned, localSecretFieldsChecked: local.length, matches }
const output = evidenceTag ? `docs/evidence/${evidenceTag}-secret-scan.json` : '.cache/secret-scan.json'
await mkdir(resolve(root, '.cache'), { recursive: true })
await writeFile(resolve(root, output), JSON.stringify(report, null, 2) + '\n')
console.log(`Project secret scan: ${report.status}; text files=${scanned}; matches=${matches.length}`)
if (matches.length) process.exitCode = 1
