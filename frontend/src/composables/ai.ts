import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch, type Ref } from 'vue'
import { api, ApiError, createRequestKey } from '../api'
import { useSessionStore } from '../stores/session'
import type { AiConfig, AiFeedback, AiJob, AiResult, KnowledgeSpace } from '../types'

function asError(failure: unknown) { return failure instanceof ApiError ? failure : new ApiError(0, { status: 0, title: 'AI 请求未完成，请重试。', code: 'AI_REQUEST_FAILED' }) }
function inaccessible(failure: ApiError) { return [401, 403, 404].includes(failure.status) }
function withinDeadline<T>(controller: AbortController, task: (signal: AbortSignal) => Promise<T>): Promise<T> {
  return new Promise((resolve, reject) => {
    let settled = false
    function finish(action: () => void) { if (settled) return; settled = true; clearTimeout(timeout); controller.signal.removeEventListener('abort', abort); action() }
    const abort = () => finish(() => reject(new DOMException('Aborted', 'AbortError')))
    const timeout = setTimeout(() => { finish(() => reject(new ApiError(0, { status: 0, title: 'AI 请求等待超时，请继续查询或重试。', code: 'AI_REQUEST_TIMEOUT' }))); controller.abort() }, 20000)
    controller.signal.addEventListener('abort', abort, { once: true })
    if (controller.signal.aborted) { abort(); return }
    task(controller.signal).then(value => finish(() => resolve(value)), failure => finish(() => reject(failure)))
  })
}
export function useAiSession() {
  const session = useSessionStore()
  const identity = computed(() => JSON.stringify([session.token, session.user?.id, session.user?.roles, session.user?.mustChangePassword, session.user?.active, session.expiresAt]))
  const usable = computed(() => session.authenticated && !session.user?.mustChangePassword && session.user?.active !== false)
  return { session, identity, usable }
}

export function useAiContext(onUnavailable: () => void) {
  const { identity, usable } = useAiSession()
  const config = shallowRef<AiConfig | null>(null); const spaces = ref<KnowledgeSpace[]>([]); const error = shallowRef<ApiError | null>(null); const loading = ref(false)
  let generation = 0; let controller: AbortController | undefined
  function clear() { generation++; controller?.abort(); config.value = null; spaces.value = []; error.value = null; loading.value = false }
  async function load() {
    clear(); if (!usable.value) return
    const current = generation; controller = new AbortController(); loading.value = true
    try {
      const [nextConfig, nextSpaces] = await withinDeadline(controller, signal => Promise.all([api.get<AiConfig>('/ai/config', { signal }), api.get<KnowledgeSpace[]>('/knowledge/spaces', { signal })]))
      if (generation !== current) return
      if (!nextConfig?.mode || !Array.isArray(nextSpaces)) throw new ApiError(0, { status: 0, title: '无法加载 AI 配置，请重试。', code: 'AI_CONFIG_UNAVAILABLE' })
      config.value = nextConfig; spaces.value = nextSpaces.filter(space => space.active && space.canRead)
    } catch (failure) {
      if (generation !== current) return
      error.value = asError(failure); config.value = null; spaces.value = []; if (inaccessible(error.value)) onUnavailable()
    } finally { if (generation === current) loading.value = false }
  }
  watch(identity, () => { clear(); if (usable.value) void load() }, { flush: 'sync' })
  onMounted(load); onBeforeUnmount(clear)
  return { config, spaces, error, loading, load }
}

/** Each in-memory operation belongs to one payload and session. Aborting is an optimization;
 * generation checks are the authority even when a transport ignores AbortSignal. */
export function useAiRequest(payloadIdentity: Ref<string>, onUnavailable: () => void = () => {}) {
  const { identity, usable } = useAiSession()
  const result = shallowRef<AiResult | null>(null); const job = shallowRef<AiJob | null>(null); const error = shallowRef<ApiError | null>(null)
  const submitting = ref(false); const feedbackPending = ref(false); const pollPending = ref(false); const waiting = ref(false)
  const cancelPending = ref(false)
  const busy = computed(() => submitting.value || (!!job.value && ['QUEUED', 'RUNNING', 'RETRYING'].includes(job.value.status) && !waiting.value))
  const requestKey = createRequestKey()
  let generation = 0; let pollSequence = 0; let timer: ReturnType<typeof setTimeout> | undefined
  let polls = 0; let failures = 0; let started = 0
  const controllers = new Set<AbortController>()
  function stop() { generation++; pollSequence++; if (timer) clearTimeout(timer); timer = undefined; controllers.forEach(controller => controller.abort()); controllers.clear(); submitting.value = false; feedbackPending.value = false; pollPending.value = false }
  function invalidate() { stop(); cancelPending.value = false; requestKey.reset(); result.value = null; job.value = null; error.value = null; waiting.value = false }
  function deny(failure: ApiError) { invalidate(); error.value = failure; onUnavailable() }
  function current(value: number) { return generation === value && usable.value }
  function schedule(value: number) {
    if (!current(value) || !job.value || !['QUEUED', 'RUNNING', 'RETRYING'].includes(job.value.status)) return
    if (polls >= 60 || failures >= 5 || Date.now() - started >= 300000) { waiting.value = true; return }
    timer = setTimeout(() => { timer = undefined; void poll(value) }, Math.min(1000 * 2 ** Math.min(polls - 1, 4), 10000))
  }
  async function poll(value: number) {
    if (!current(value) || !job.value) return
    const jobId = job.value.id; const sequence = ++pollSequence; const controller = new AbortController(); controllers.add(controller); pollPending.value = true; polls++
    try {
      const next = await withinDeadline(controller, signal => api.get<AiJob>(`/jobs/${jobId}`, { signal }))
      if (!current(value) || sequence !== pollSequence) return
      failures = 0; error.value = null; job.value = next
      if (next.status === 'COMPLETED') {
        if (!next.result) throw new ApiError(0, { status: 0, title: 'AI 结果不可用，请重新生成。', code: 'AI_OUTPUT_INVALID' })
        result.value = next.result
      } else if (next.status === 'FAILED') { result.value = null; error.value = new ApiError(0, { status: 0, title: 'AI 生成失败，可重新提交。', code: next.errorCode || 'AI_REQUEST_FAILED' }) }
    } catch (failure) {
      if (!current(value) || sequence !== pollSequence) return
      const problem = asError(failure)
      if (inaccessible(problem)) { deny(problem); return }
      failures++; error.value = problem
    } finally {
      controllers.delete(controller)
      if (current(value) && sequence === pollSequence) { pollPending.value = false; schedule(value) }
    }
  }
  async function start(path: string, payload: unknown) {
    if (busy.value || !usable.value) return
    stop(); const value = generation; result.value = null; job.value = null; error.value = null; waiting.value = false; submitting.value = true
    const controller = new AbortController(); controllers.add(controller)
    try {
      const next = await withinDeadline(controller, signal => api.post<{ jobId: string }>(path, payload, { key: requestKey.for(payload), signal }))
      if (!current(value)) return
      requestKey.reset(); job.value = { id: next.jobId, status: 'QUEUED', attemptCount: 0, errorCode: null, result: null }; polls = 0; failures = 0; started = Date.now(); await poll(value)
    } catch (failure) {
      if (!current(value)) return
      const problem = asError(failure); if (inaccessible(problem)) deny(problem); else error.value = problem
    } finally { controllers.delete(controller); if (current(value)) submitting.value = false }
  }
  async function refresh() {
    if (!job.value || pollPending.value || submitting.value) return
    if (timer) clearTimeout(timer); timer = undefined; polls = 0; failures = 0; started = Date.now(); waiting.value = false
    await poll(generation)
  }
  async function feedback(choice: AiFeedback): Promise<AiResult | null> {
    if (!result.value || feedbackPending.value || !usable.value) return null
    const value = generation; const suggestion = result.value; const controller = new AbortController(); controllers.add(controller); feedbackPending.value = true; error.value = null
    try {
      await withinDeadline(controller, signal => api.post(`/ai/suggestions/${suggestion.suggestionId}/feedback`, { feedback: choice }, { signal }))
      if (!current(value) || result.value?.suggestionId !== suggestion.suggestionId) return null
      result.value = { ...suggestion, feedback: choice }; return result.value
    } catch (failure) {
      if (!current(value)) return null
      const problem = asError(failure); if (inaccessible(problem)) deny(problem); else error.value = problem
      return null
    } finally { controllers.delete(controller); if (current(value)) feedbackPending.value = false }
  }
  async function cancel() {
    if (!usable.value || !job.value || cancelPending.value || !['QUEUED', 'RUNNING', 'RETRYING'].includes(job.value.status)) return
    const value = generation; const original = job.value; const controller = new AbortController(); controllers.add(controller); cancelPending.value = true
    try {
      await withinDeadline(controller, signal => api.post(`/ai/jobs/${original.id}/cancel`, undefined, { signal }))
      if (!current(value) || job.value?.id !== original.id) return
      stop(); requestKey.reset(); job.value = { ...original, status: 'CANCELLED', result: null, errorCode: null }; result.value = null; error.value = null; waiting.value = false
    } catch (failure) {
      if (!current(value)) return
      const problem = asError(failure); if (inaccessible(problem)) deny(problem); else error.value = problem
    } finally { controllers.delete(controller); if (generation === value || job.value?.id === original.id) cancelPending.value = false }
  }
  watch([identity, payloadIdentity], invalidate, { flush: 'sync' }); onBeforeUnmount(invalidate)
  return { result, job, error, busy, submitting, feedbackPending, pollPending, cancelPending, waiting, start, refresh, feedback, cancel, invalidate }
}
