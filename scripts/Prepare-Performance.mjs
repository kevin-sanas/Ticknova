import { readFile, writeFile } from 'node:fs/promises'
import { resolve, dirname, sep } from 'node:path'
import { fileURLToPath } from 'node:url'
import { randomUUID } from 'node:crypto'
import { signInDemo } from './Demo-Credentials.mjs'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const [base, envFile, outputFile] = process.argv.slice(2)
const origin = new URL(base)
if (origin.protocol !== 'http:' || origin.hostname !== '127.0.0.1' || origin.username || origin.password || origin.pathname !== '/' || origin.search || origin.hash) throw Error('Refusing performance writes: local origin required')
if (resolve(envFile) !== resolve(root, '.env.perf') || !resolve(outputFile).startsWith(resolve(root, '.cache') + sep)) throw Error('Refusing performance writes: dedicated credentials and cache output required')
const marker = await fetch(origin, { signal: AbortSignal.timeout(10000), redirect: 'error' })
if (!marker.ok || marker.headers.get('x-ticknova-environment') !== 'perf-test') throw Error('Refusing performance writes: target is not the isolated perf-test server')
await readFile(envFile, 'utf8')
const request = { post: async (url, { data, headers }) => {
  const response = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(data), signal: AbortSignal.timeout(15000) })
  return { status: () => response.status, json: () => response.json() }
} }
const signed = await signInDemo(request, base, resolve(envFile), 'requester', undefined, 'DEMO_PERF')
const token = signed.login.accessToken
async function call(method, path, data) {
  const response = await fetch(`${base}/api/v1${path}`, { method, headers: { Authorization: `Bearer ${token}`, ...(data ? { 'Content-Type': 'application/json', 'Idempotency-Key': randomUUID() } : {}) }, ...(data ? { body: JSON.stringify(data) } : {}), signal: AbortSignal.timeout(20000) })
  if (![200, 201].includes(response.status)) throw Error(`Performance preparation ${method} ${path}: HTTP ${response.status}`)
  return response.json()
}
if ((await call('GET', '/ai/config')).mode !== 'mock') throw Error('Performance load must never call real models')
const spaces = await call('GET', '/knowledge/spaces')
if (spaces.length !== 1) throw Error('Expected one original fixture space')
const documents = await call('GET', `/knowledge/spaces/${spaces[0].id}/documents?size=100`)
if (documents.total !== 12) throw Error('Expected 12 original reviewed documents')
const services = await call('GET', '/services'), serviceId = services.find(row => row.code === 'GENERAL')?.id
if (!serviceId) throw Error('Missing fixture service')
const before = await call('GET', '/tickets?size=1')
if (before.total !== 24) throw Error('Expected fresh 24-ticket original dataset; refusing extra writes')
let next = 24
await Promise.all(Array.from({ length: 4 }, async () => {
  while (next < 1000) {
    const number = ++next
    await call('POST', '/tickets', { title: `隔离压测工单 ${number}`, description: '原创合成数据，仅用于独立性能测试，不代表实际用户请求。', priority: 'NORMAL', serviceId })
  }
}))
if ((await call('GET', '/tickets?size=1')).total !== 1000) throw Error('Fixture count mismatch')
for (let i = 0; i < 20; i++) await call('GET', '/tickets?size=20')
await writeFile(outputFile, JSON.stringify({ token, serviceId, spaceId: spaces[0].id, documentIds: documents.items.map(row => row.id), initialTickets: 1000 }), { mode: 0o600 })
console.log('Prepared 1000 isolated tickets and 12 original knowledge documents; private session fixture saved locally.')
