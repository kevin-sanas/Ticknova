export interface Problem {
  type?: string; title: string; status: number; code?: string; traceId?: string; detail?: string
  errors?: Record<string, unknown> | unknown[]
}
export class ApiError extends Error {
  status: number
  problem: Problem
  constructor(status: number, problem: Problem) {
    super(problem.detail || problem.title || '请求失败')
    this.name = 'ApiError'; this.status = status; this.problem = problem
  }
}
interface ApiOptions { getToken: () => string; onUnauthorized: () => void; fetcher?: typeof fetch; xhrFactory?: () => XMLHttpRequest }
interface RequestOptions { anonymous?: boolean; key?: string; signal?: AbortSignal; onProgress?: (percent: number) => void }
export function createApi(options: ApiOptions) {
  function upload<T>(path: string, body: FormData, config: RequestOptions = {}): Promise<T> {
    const token = config.anonymous ? '' : options.getToken()
    return new Promise((resolve, reject) => {
      if (config.signal?.aborted) { reject(new DOMException('上传已取消', 'AbortError')); return }
      const xhr = options.xhrFactory?.() || new XMLHttpRequest(); let finished = false
      const abort = () => xhr.abort()
      function finish(task: () => void) { if (finished) return; finished = true; config.signal?.removeEventListener('abort', abort); task() }
      xhr.open('POST', `/api/v1${path}`); xhr.withCredentials = false; xhr.timeout = 120000
      xhr.setRequestHeader('Accept', 'application/json'); if (token) xhr.setRequestHeader('Authorization', `Bearer ${token}`); if (config.key) xhr.setRequestHeader('Idempotency-Key', config.key)
      xhr.upload.addEventListener('progress', event => { if (!finished && event.lengthComputable && event.total > 0) config.onProgress?.(Math.round(event.loaded / event.total * 100)) })
      xhr.addEventListener('load', () => finish(() => {
        let data: unknown; try { data = xhr.responseText ? JSON.parse(xhr.responseText) : undefined } catch { /* Gateway response may be text. */ }
        if (xhr.status >= 200 && xhr.status < 300) { resolve(data as T); return }
        if (xhr.status === 401 && token && token === options.getToken()) options.onUnauthorized()
        reject(new ApiError(xhr.status, { title: '上传失败，请重试。', ...(data && typeof data === 'object' ? data : {}), status: xhr.status }))
      }))
      xhr.addEventListener('abort', () => finish(() => reject(new DOMException('上传已取消', 'AbortError'))))
      xhr.addEventListener('error', () => finish(() => reject(new ApiError(0, { title: '上传连接中断，请检查网络后重试。', status: 0 }))))
      xhr.addEventListener('timeout', () => finish(() => reject(new ApiError(0, { title: '上传超时，请重试。', status: 0 }))))
      config.signal?.addEventListener('abort', abort, { once: true }); xhr.send(body)
    })
  }
  async function request<T>(path: string, method: string, body?: unknown, config: RequestOptions = {}): Promise<T> {
    const headers = new Headers({ Accept: 'application/json' })
    const token = config.anonymous ? '' : options.getToken()
    if (token) headers.set('Authorization', `Bearer ${token}`)
    const multipart = body instanceof FormData
    if (body !== undefined && !multipart) headers.set('Content-Type', 'application/json')
    if (config.key) headers.set('Idempotency-Key', config.key)
    let response: Response
    try {
      response = await (options.fetcher || fetch)(`/api/v1${path}`, { method, headers, body: body === undefined ? undefined : multipart ? body : JSON.stringify(body), credentials: 'omit', signal: config.signal })
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') throw error
      throw new ApiError(0, { title: '无法连接服务器，请检查网络后重试。', status: 0, code: 'NETWORK_ERROR' })
    }
    if (!response.ok) {
      let problem: Problem = { title: '请求失败，请稍后重试。', status: response.status }
      try { problem = { ...problem, ...await response.json(), status: response.status } } catch { /* A gateway may return a non-JSON response. */ }
      if (response.status === 401 && !config.anonymous && token && token === options.getToken()) options.onUnauthorized()
      throw new ApiError(response.status, problem)
    }
    if (response.status === 204) return undefined as T
    return response.json() as Promise<T>
  }
  return {
    upload,
    get: <T>(path: string, config?: RequestOptions) => request<T>(path, 'GET', undefined, config),
    post: <T>(path: string, body?: unknown, config?: RequestOptions) => request<T>(path, 'POST', body, config),
    multipart: <T>(path: string, body: FormData, config?: RequestOptions) => request<T>(path, 'POST', body, config),
    patch: <T>(path: string, body: unknown) => request<T>(path, 'PATCH', body),
    put: <T>(path: string, body: unknown, config?: RequestOptions) => request<T>(path, 'PUT', body, config),
    delete: <T>(path: string, body?: unknown) => request<T>(path, 'DELETE', body),
  }
}
let session = { getToken: () => '', onUnauthorized: () => {} }
export function bindApiSession(adapter: typeof session) { session = adapter }
export const api = createApi({ getToken: () => session.getToken(), onUnauthorized: () => session.onUnauthorized() })
export function createRequestKey() {
  let serialized = ''; let key = ''
  return {
    for(payload: unknown) { const next = JSON.stringify(payload); if (serialized !== next || !key) { serialized = next; key = crypto.randomUUID() }; return key },
    reset() { serialized = ''; key = '' },
  }
}
export function queryString(values: Record<string, string | number | null | undefined>) {
  const query = new URLSearchParams()
  for (const [key, value] of Object.entries(values)) if (value !== '' && value != null) query.set(key, String(value))
  return query.toString()
}
