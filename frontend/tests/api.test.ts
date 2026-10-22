import { describe, expect, it, vi } from 'vitest'
import { ApiError, createApi, createRequestKey } from '../src/api'

describe('API boundary', () => {
  it('sends the in-memory bearer without cookies and preserves structured validation errors', async () => {
    const received: RequestInit[] = []
    const api = createApi({
      getToken: () => 'memory-token',
      onUnauthorized: () => undefined,
      fetcher: async (_url, init) => {
        received.push(init!)
        return new Response(JSON.stringify({ status: 422, code: 'VALIDATION_ERROR', title: '输入有误', traceId: 'trace-1', errors: { title: '不能为空' } }), { status: 422 })
      },
    })
    await expect(api.post('/tickets', { title: '' })).rejects.toMatchObject({ status: 422, problem: { traceId: 'trace-1', errors: { title: '不能为空' } } })
    expect(received[0]?.credentials).toBe('omit')
    expect(new Headers(received[0]?.headers).get('Authorization')).toBe('Bearer memory-token')
  })

  it('expires the session on 401 but keeps it on 403 and never retries a failed mutation', async () => {
    let expired = 0
    let calls = 0
    let status = 403
    const api = createApi({ getToken: () => 'token', onUnauthorized: () => expired++, fetcher: async () => { calls++; return new Response('{}', { status }) } })
    await expect(api.post('/tickets', {})).rejects.toBeInstanceOf(ApiError)
    expect(expired).toBe(0)
    expect(calls).toBe(1)
    status = 401
    await expect(api.get('/me')).rejects.toMatchObject({ status: 401 })
    expect(expired).toBe(1)
    expect(calls).toBe(2)
  })

  it('does not attach credentials or expire another session on a rejected login', async () => {
    const unauthorized = vi.fn()
    let authorization: string | null = null
    const api = createApi({ getToken: () => 'old-token', onUnauthorized: unauthorized, fetcher: async (_url, init) => { authorization = new Headers(init?.headers).get('Authorization'); return new Response('{}', { status: 401 }) } })
    await expect(api.post('/auth/login', {}, { anonymous: true })).rejects.toMatchObject({ status: 401 })
    expect(authorization).toBeNull()
    expect(unauthorized).not.toHaveBeenCalled()
  })

  it('does not clear a replacement session when an old bearer request returns a delayed 401', async () => {
    let token = 'session-a'
    let release!: (response: Response) => void
    const api = createApi({
      getToken: () => token,
      onUnauthorized: () => { token = '' },
      fetcher: () => new Promise<Response>(resolve => { release = resolve }),
    })
    const oldRequest = api.get('/me')
    const rejection = expect(oldRequest).rejects.toMatchObject({ status: 401 })
    token = 'session-b'
    release(new Response('{}', { status: 401 }))
    await rejection
    expect(token).toBe('session-b')
  })

  it('keeps the same idempotency key for retries and changes it when the submitted content changes', () => {
    const key = createRequestKey()
    const first = key.for({ title: '打印机故障', priority: 'NORMAL' })
    expect(key.for({ title: '打印机故障', priority: 'NORMAL' })).toBe(first)
    expect(key.for({ title: '网络故障', priority: 'NORMAL' })).not.toBe(first)
    const second = key.for({ title: '网络故障', priority: 'NORMAL' })
    key.reset()
    expect(key.for({ title: '网络故障', priority: 'NORMAL' })).not.toBe(second)
  })
})
