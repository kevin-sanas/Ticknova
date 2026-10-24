import { readFile } from 'node:fs/promises'
import { resolve } from 'node:path'
import { randomBytes, randomUUID } from 'node:crypto'
import { queues, services, documents, tickets, richContent } from './Demo-Dataset.mjs'
import { signInDemo, changeDemoPassword, parseDemoEnv } from './Demo-Credentials.mjs'

// The PowerShell entry point holds a local exclusive lock and verifies the empty database identity.
const [base, envFile, confirmation] = process.argv.slice(2)
const browserTest = confirmation === 'verified-empty-ticknova-browser'
if ((!browserTest && confirmation !== 'verified-empty-ticknova-demo') || !base || !envFile) throw Error('Run scripts/Seed-Demo.ps1 after starting the clean demo Compose project')
const origin = new URL(base)
if (origin.protocol !== 'http:' || origin.hostname !== '127.0.0.1' || origin.username || origin.password || origin.pathname !== '/' || origin.search || origin.hash) throw Error('Seed requires the local origin')
const marker = await fetch(origin, { signal: AbortSignal.timeout(10000), redirect: 'error' })
if (!marker.ok || marker.headers.get('x-ticknova-environment') !== (browserTest ? 'browser-test' : 'demo')) throw Error('Refusing seed: target environment does not match')
const envPath = resolve(envFile), settings = parseDemoEnv(await readFile(envPath, 'utf8'))
const request = { post: async (url, { data, headers }) => {
  const result = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(data), signal: AbortSignal.timeout(15000) })
  return { status: () => result.status, json: () => result.json() }
} }
async function call(actor, method, path, data, idempotent = false) {
  const response = await fetch(`${base}/api/v1${path}`, { method, headers: { Authorization: `Bearer ${actor.accessToken}`, ...(data === undefined ? {} : { 'Content-Type': 'application/json' }), ...(idempotent ? { 'Idempotency-Key': randomUUID() } : {}) }, ...(data === undefined ? {} : { body: JSON.stringify(data) }), signal: AbortSignal.timeout(20000) })
  if (![200,201,202,204].includes(response.status)) throw Error(`Demo seed ${method} ${path}: HTTP ${response.status}; partial data retained`)
  return response.status === 204 ? null : response.json()
}
async function login(username) {
  const prefix = browserTest ? 'DEMO_BROWSER' : 'DEMO'
  let signed = await signInDemo(request, base, envPath, username, undefined, prefix)
  if (signed.login.user.mustChangePassword) {
    await changeDemoPassword(request, base, envPath, username, signed.password, signed.login.accessToken, `Demo-${randomBytes(24).toString('hex')}!`, undefined, prefix)
    signed = await signInDemo(request, base, envPath, username, undefined, prefix)
  }
  return signed.login
}
async function until(task, check, label) {
  const deadline = Date.now() + 120000
  while (Date.now() < deadline) {
    const value = await task()
    if (check(value)) return value
    await new Promise(resolve => setTimeout(resolve, 750))
  }
  throw Error(`Demo seed timed out: ${label}; partial data retained`)
}
const actors = {}, queueIds = {}, serviceIds = {}, categoryIds = {}
try {
  actors.admin = await login('admin')
  const users = await call(actors.admin, 'GET', '/users?size=100')
  if (users.total !== 4 || users.items.some(user => !['admin','agent','requester','editor'].includes(user.username))) throw Error('Demo database is not empty; no business data changed')
  await call(actors.admin, 'POST', '/users', { username: 'reviewer', displayName: '知识审核者', password: settings.DEMO_INITIAL_PASSWORD, roles: ['KB_EDITOR'] })
  for (const username of ['agent','requester','editor','reviewer']) actors[username] = await login(username)
  const currentQueues = await call(actors.admin, 'GET', '/queues')
  for (const [code, name] of queues) {
    const queue = currentQueues.find(row => row.code === code) || await call(actors.admin, 'POST', '/queues', { code, name })
    queueIds[code] = queue.id
    await call(actors.admin, 'PUT', `/queues/${queue.id}/members/${actors.agent.user.id}`, { canProcess: true })
  }
  const currentServices = await call(actors.admin, 'GET', '/services')
  for (const [code, name] of services) serviceIds[code] = (currentServices.find(row => row.code === code) || await call(actors.admin, 'POST', '/services', { code, name })).id
  const space = await call(actors.editor, 'POST', '/knowledge/spaces', { name: '员工帮助中心' }, true)
  for (const username of ['admin','agent','requester','reviewer']) await call(actors.editor, 'PUT', `/knowledge/spaces/${space.id}/members/${actors[username].user.id}`, { canRead: true, canEdit: username === 'reviewer', reason: '初始化原创演示知识的阅读与独立审核权限' })
  for (const category of new Set(documents.map(row => row[0]))) categoryIds[category] = (await call(actors.editor, 'POST', `/knowledge/spaces/${space.id}/categories`, { name: category })).id
  for (const document of documents) {
    const draft = await call(actors.editor, 'POST', `/knowledge/spaces/${space.id}/articles`, { title: document[1], categoryId: categoryIds[document[0]], content: richContent(document) }, true)
    const uploaded = await call(actors.editor, 'POST', `/knowledge/documents/${draft.id}/draft/versions`, { version: draft.version }, true)
    const path = `/knowledge/documents/${draft.id}/versions/${uploaded.versionId}`
    await until(() => call(actors.editor, 'GET', `/knowledge/documents/${draft.id}`), detail => {
      const version = detail.versions.find(row => row.id === uploaded.versionId)
      if (version?.ingestState === 'FAILED') throw Error(`Original demo document parsing failed: ${version.errorCode}`)
      return version?.ingestState === 'READY'
    }, 'original document parsing')
    await call(actors.editor, 'POST', `${path}/review-requests`)
    await call(actors.reviewer, 'POST', `${path}/reviews`, { decision: 'APPROVED' })
    await call(actors.editor, 'POST', `${path}/publish`)
    await until(() => call(actors.editor, 'GET', `/knowledge/documents/${draft.id}`), detail => detail.versions.find(row => row.id === uploaded.versionId)?.embeddingState === 'READY', 'mock knowledge indexing')
  }
  for (const sample of tickets) {
    let ticket = await call(actors.requester, 'POST', '/tickets', { title: sample.title, description: `${sample.title}。已检查基本连接和应用状态，问题仍然存在，请服务团队协助定位并给出处理建议。`, priority: sample.priority, serviceId: serviceIds[sample.service] }, true)
    if (sample.status !== 'NEW') {
      ticket = await call(actors.agent, 'PUT', `/tickets/${ticket.id}/assignment`, { queueId: queueIds[sample.queue], assigneeId: actors.agent.user.id, version: ticket.version })
      await call(actors.agent, 'POST', `/tickets/${ticket.id}/comments`, { visibility: 'PUBLIC_REPLY', body: '已收到你的请求，支持团队正在核对环境和影响范围，将在此工单中同步处理进展。' }, true)
      await call(actors.agent, 'POST', `/tickets/${ticket.id}/comments`, { visibility: 'INTERNAL_NOTE', body: '已核对服务目录和处理队列；排查信息仅供支持团队协作，等待补充诊断后更新结论。' }, true)
      const path = sample.status === 'TRIAGED' ? ['TRIAGED'] : ['TRIAGED','IN_PROGRESS', ...(['IN_PROGRESS'].includes(sample.status) ? [] : sample.status === 'CLOSED' ? ['RESOLVED','CLOSED'] : [sample.status])]
      for (const status of path) ticket = await call(status === 'CLOSED' ? actors.requester : actors.agent, 'POST', `/tickets/${ticket.id}/transitions`, { toStatus: status, reason: status === 'RESOLVED' ? '已按对应知识指引完成检查和配置修正，基本功能验证通过，请申请人确认。' : status === 'CLOSED' ? '已确认问题解决，同意关闭工单。' : status.startsWith('WAITING') ? '已说明需要补充的信息，等待相关人员确认后继续处理。' : '已完成服务分诊，按队列流程推进处理。', version: ticket.version }, true)
    }
  }
  for (const question of ['网络无法连接怎么办','VPN 连不上','怎么重置密码']) {
    const created = await call(actors.requester, 'POST', '/ai/answers', { question, spaceIds: [space.id] }, true)
    const job = await until(() => call(actors.requester, 'GET', `/jobs/${created.jobId}`), value => ['COMPLETED','FAILED'].includes(value.status), 'mock answer')
    if (job.status !== 'COMPLETED' || job.result?.outcome !== 'ANSWERED' || !job.result.citations?.length) throw Error('Demo question did not return an authorized cited mock answer')
  }
  console.log(`Demo seed complete: ${queues.length} queues, ${services.length} services, ${documents.length} original reviewed/indexed documents, ${tickets.length} tickets; model=mock. Credentials saved only in the selected local environment file.`)
} finally {
  for (const actor of Object.values(actors)) await call(actor, 'POST', '/auth/logout').catch(() => {})
}
