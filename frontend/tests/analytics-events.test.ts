import { afterEach, expect, it, vi } from 'vitest'
import { scheduleUtcDayRefresh, subscribeAnalytics } from '../src/analytics-events'

afterEach(() => { vi.useRealTimers(); vi.restoreAllMocks() })

function stream() {
  let controller!: ReadableStreamDefaultController<Uint8Array>
  const body = new ReadableStream<Uint8Array>({ start(value) { controller = value } })
  return { body, write(value: string) { controller.enqueue(new TextEncoder().encode(value)) }, close() { controller.close() } }
}

it('uses authenticated SSE and refreshes only on complete refresh frames', async () => {
  const source = stream(), refresh = vi.fn(), state = vi.fn(), access = vi.fn()
  const fetcher = vi.fn().mockResolvedValue(new Response(source.body, { headers: { 'Content-Type': 'text/event-stream' } }))
  const stop = subscribeAnalytics({ token: 'private-token', onRefresh: refresh, onState: state, onAccessLost: access, fetcher })
  await vi.waitFor(() => expect(state).toHaveBeenCalledWith('live'))
  expect(fetcher).toHaveBeenCalledWith('/api/v1/workspace/analytics/events', expect.objectContaining({ headers: expect.objectContaining({ Authorization: 'Bearer private-token' }), credentials: 'omit' }))
  expect(refresh).toHaveBeenCalledTimes(1)
  source.write('event: ref'); source.write('resh\r\ndata: {}\r\n\r\n: heartbeat\n\n')
  await vi.waitFor(() => expect(refresh).toHaveBeenCalledTimes(2))
  expect(access).not.toHaveBeenCalled()
  stop()
})

it('stops after access revocation without retrying or refreshing', async () => {
  const source = stream(), refresh = vi.fn(), access = vi.fn()
  const fetcher = vi.fn().mockResolvedValue(new Response(source.body, { headers: { 'Content-Type': 'text/event-stream' } }))
  const stop = subscribeAnalytics({ token: 't', onRefresh: refresh, onState: vi.fn(), onAccessLost: access, fetcher })
  await vi.waitFor(() => expect(refresh).toHaveBeenCalledTimes(1))
  source.write('event: access-revoked\ndata: {}\n\n')
  await vi.waitFor(() => expect(access).toHaveBeenCalledWith(403))
  expect(fetcher).toHaveBeenCalledTimes(1)
  stop()
})

it('treats an HTTP 401 as expired authentication and does not retry', async () => {
  const access = vi.fn(), refresh = vi.fn()
  const fetcher = vi.fn().mockResolvedValue(new Response(null, { status: 401 }))
  const stop = subscribeAnalytics({ token: 't', onRefresh: refresh, onState: vi.fn(), onAccessLost: access, fetcher, retryDelayMs: 5 })
  await vi.waitFor(() => expect(access).toHaveBeenCalledWith(401))
  expect(refresh).not.toHaveBeenCalled()
  expect(fetcher).toHaveBeenCalledTimes(1)
  stop()
})

it('reconnects after a disconnected stream and stops on abort', async () => {
  const first = stream(), second = stream(), refresh = vi.fn(), state = vi.fn()
  const fetcher = vi.fn().mockResolvedValueOnce(new Response(first.body, { headers: { 'Content-Type': 'text/event-stream' } })).mockResolvedValueOnce(new Response(second.body, { headers: { 'Content-Type': 'text/event-stream' } }))
  const stop = subscribeAnalytics({ token: 't', onRefresh: refresh, onState: state, onAccessLost: vi.fn(), fetcher, retryDelayMs: 5 })
  await vi.waitFor(() => expect(refresh).toHaveBeenCalledTimes(1))
  first.close()
  await vi.waitFor(() => expect(refresh).toHaveBeenCalledTimes(2))
  expect(state).toHaveBeenCalledWith('reconnecting')
  expect(fetcher).toHaveBeenCalledTimes(2)
  stop()
})

it('backs off repeated short-lived connections', async () => {
  vi.useFakeTimers()
  const fetcher = vi.fn(() => {
    const source = stream(); source.close()
    return Promise.resolve(new Response(source.body, { headers: { 'Content-Type': 'text/event-stream' } }))
  })
  const stop = subscribeAnalytics({ token: 't', onRefresh: vi.fn(), onState: vi.fn(), onAccessLost: vi.fn(), fetcher, retryDelayMs: 10 })
  await vi.advanceTimersByTimeAsync(0)
  expect(fetcher).toHaveBeenCalledTimes(1)
  await vi.advanceTimersByTimeAsync(10)
  expect(fetcher).toHaveBeenCalledTimes(2)
  await vi.advanceTimersByTimeAsync(10)
  expect(fetcher).toHaveBeenCalledTimes(2)
  await vi.advanceTimersByTimeAsync(10)
  expect(fetcher).toHaveBeenCalledTimes(3)
  stop()
})

it('retries when a connection never returns response headers', async () => {
  vi.useFakeTimers()
  const fetcher = vi.fn((_url: RequestInfo | URL, init?: RequestInit) => new Promise<Response>((_resolve, reject) => {
    init?.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')), { once: true })
  }))
  const state = vi.fn()
  const stop = subscribeAnalytics({ token: 't', onRefresh: vi.fn(), onState: state, onAccessLost: vi.fn(), fetcher, retryDelayMs: 10 })
  await vi.advanceTimersByTimeAsync(45000)
  expect(state).toHaveBeenCalledWith('reconnecting')
  await vi.advanceTimersByTimeAsync(10)
  expect(fetcher).toHaveBeenCalledTimes(2)
  stop()
})

it('refreshes the rolling date range at UTC midnight and stops on cleanup', async () => {
  vi.useFakeTimers()
  vi.setSystemTime(new Date('2026-10-24T23:59:59.000Z'))
  const refresh = vi.fn(), stop = scheduleUtcDayRefresh(refresh)
  await vi.advanceTimersByTimeAsync(999)
  expect(refresh).not.toHaveBeenCalled()
  await vi.advanceTimersByTimeAsync(1)
  expect(refresh).toHaveBeenCalledTimes(1)
  stop()
  await vi.advanceTimersByTimeAsync(86400000)
  expect(refresh).toHaveBeenCalledTimes(1)
})
