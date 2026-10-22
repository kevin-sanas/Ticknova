import { describe, expect, it } from 'vitest'
import { createApi } from '../src/api'

class UploadRequest {
  upload = new EventTarget(); events = new EventTarget(); status = 200; responseText = '{}'; headers: Record<string, string> = {}; withCredentials = true
  addEventListener(type: string, listener: EventListener) { this.events.addEventListener(type, listener) }
  open() { /* Browser transport boundary. */ }
  setRequestHeader(key: string, value: string) { this.headers[key] = value }
  send() { /* Controlled transport completion. */ }
  abort() { this.events.dispatchEvent(new Event('abort')) }
  complete(status: number, body: unknown) { this.status = status; this.responseText = JSON.stringify(body); this.events.dispatchEvent(new Event('load')) }
}
describe('original file upload transport', () => {
  it('reports actual browser progress and preserves the browser multipart boundary', async () => {
    const xhr = new UploadRequest(); const percentages: number[] = []
    const api = createApi({ getToken: () => 'session-a', onUnauthorized: () => {}, xhrFactory: () => xhr as unknown as XMLHttpRequest })
    expect(api).toHaveProperty('upload')
    const promise = api.upload('/knowledge/spaces/s1/documents', new FormData(), { onProgress: value => percentages.push(value), key: 'request-key' })
    xhr.upload.dispatchEvent(new ProgressEvent('progress', { loaded: 3, total: 4, lengthComputable: true })); xhr.complete(202, { documentId: 'd1' })
    expect(await promise).toEqual({ documentId: 'd1' }); expect(percentages).toEqual([75]); expect(xhr.headers.Authorization).toBe('Bearer session-a'); expect(xhr.headers['Content-Type']).toBeUndefined(); expect(xhr.headers['Idempotency-Key']).toBe('request-key'); expect(xhr.withCredentials).toBe(false)
  })
  it('keeps a replacement session when an old upload returns 401 and supports abort', async () => {
    let token = 'session-a'; const xhr = new UploadRequest()
    const api = createApi({ getToken: () => token, onUnauthorized: () => { token = '' }, xhrFactory: () => xhr as unknown as XMLHttpRequest })
    expect(api).toHaveProperty('upload'); const request = api.upload('/file', new FormData()); token = 'session-b'; xhr.complete(401, { title: '登录失效' }); await expect(request).rejects.toMatchObject({ status: 401 }); expect(token).toBe('session-b')
    const controller = new AbortController(); const aborted = api.upload('/file', new FormData(), { signal: controller.signal }); controller.abort(); await expect(aborted).rejects.toMatchObject({ name: 'AbortError' })
  })
})
