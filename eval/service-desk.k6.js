import http from 'k6/http'
import { check, sleep } from 'k6'
import { Trend, Rate, Counter } from 'k6/metrics'

const fixture = JSON.parse(open('/input/fixture.json'))
const base = 'http://127.0.0.1/api/v1'
const headers = { Authorization: `Bearer ${fixture.token}`, 'Content-Type': 'application/json' }
const listMs = new Trend('ticket_list_ms', true), createMs = new Trend('ticket_create_ms', true)
const submitMs = new Trend('answer_submit_ms', true), completeMs = new Trend('answer_complete_ms', true)
const answerSuccess = new Rate('answer_success'), created = new Counter('tickets_created'), answered = new Counter('answers_completed')
export const options = {
  scenarios: {
    list: { executor: 'constant-vus', vus: 50, duration: '5m', gracefulStop: '5s', exec: 'listTickets' },
    create: { executor: 'shared-iterations', vus: 50, iterations: 500, startTime: '5m10s', maxDuration: '1m', gracefulStop: '5s', exec: 'createTicket' },
    answer: { executor: 'shared-iterations', vus: 2, iterations: 30, startTime: '6m20s', maxDuration: '3m', gracefulStop: '5s', exec: 'answerQuestion' },
  },
  thresholds: { checks: ['rate==1'], http_req_failed: ['rate==0'], ticket_list_ms: ['p(95)<=500'], ticket_create_ms: ['p(95)<=500'], answer_success: ['rate==1'], tickets_created: ['count==500'], answers_completed: ['count==30'] },
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'count'],
}
export function setup() {
  const marker = http.get('http://127.0.0.1')
  if (marker.headers['X-Ticknova-Environment'] !== 'perf-test') throw Error('Refusing load on a non-performance environment')
  const config = http.get(`${base}/ai/config`, { headers }).json()
  if (config.mode !== 'mock') throw Error('Real models are forbidden in performance load')
}
export function listTickets() {
  const response = http.get(`${base}/tickets?page=0&size=20`, { headers, tags: { operation: 'ticket-list' } })
  listMs.add(response.timings.duration)
  check(response, { 'list returns a real page': r => r.status === 200 && r.json().items.length === 20 })
  sleep(0.1)
}
export function createTicket() {
  const response = http.post(`${base}/tickets`, JSON.stringify({ title: `隔离k6工单 ${__VU}-${__ITER}`, description: '原创负载数据，仅保留于性能测试临时数据库。', priority: 'NORMAL', serviceId: fixture.serviceId }), { headers: { ...headers, 'Idempotency-Key': `perf-create-${__VU}-${__ITER}-${Date.now()}` }, tags: { operation: 'ticket-create' } })
  createMs.add(response.timings.duration)
  if (check(response, { 'create really persists a ticket': r => r.status === 201 && !!r.json().id })) created.add(1)
}
export function answerQuestion() {
  const questions = ['网络无法连接怎么办', 'VPN 连不上', '怎么重置密码']
  const started = Date.now()
  const response = http.post(`${base}/ai/answers`, JSON.stringify({ question: questions[__ITER % questions.length], spaceIds: [fixture.spaceId] }), { headers: { ...headers, 'Idempotency-Key': `perf-answer-${__VU}-${__ITER}-${Date.now()}` }, tags: { operation: 'answer-submit' } })
  submitMs.add(response.timings.duration)
  if (!check(response, { 'answer job accepted': r => r.status === 202 && !!r.json().jobId })) { answerSuccess.add(false); return }
  const id = response.json().jobId
  while (Date.now() - started < 30000) {
    const polled = http.get(`${base}/jobs/${id}`, { headers, tags: { operation: 'answer-poll' } })
    if (polled.status !== 200) { answerSuccess.add(false); return }
    const job = polled.json()
    if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(job.status)) {
      const valid = job.status === 'COMPLETED' && job.result?.outcome === 'ANSWERED' && job.result.citations.length > 0 && job.result.citations.every(c => fixture.documentIds.includes(c.documentId))
      answerSuccess.add(valid)
      check(job, { 'answer completes with authorized original citations': () => valid })
      if (valid) { completeMs.add(Date.now() - started); answered.add(1) }
      return
    }
    sleep(0.25)
  }
  answerSuccess.add(false)
  check(false, { 'answer finishes before polling deadline': value => value })
}
export function handleSummary(data) {
  const brief = Object.fromEntries(['ticket_list_ms', 'ticket_create_ms', 'answer_submit_ms', 'answer_complete_ms', 'answer_success', 'tickets_created', 'answers_completed'].map(name => [name, data.metrics[name]?.values ?? null]))
  return { '/output/summary.json': JSON.stringify(data), stdout: JSON.stringify(brief, null, 2) + '\n' }
}
