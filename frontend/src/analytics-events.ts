export type AnalyticsConnectionState = 'connecting' | 'live' | 'reconnecting'

export function scheduleUtcDayRefresh(refresh: () => void): () => void {
  let timer: ReturnType<typeof setTimeout>
  function schedule() {
    const now = new Date()
    const next = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + 1)
    timer = setTimeout(() => { refresh(); schedule() }, Math.max(1, next - now.getTime()))
  }
  schedule()
  return () => clearTimeout(timer)
}

interface AnalyticsSubscription {
  token: string
  onRefresh: () => void
  onState: (state: AnalyticsConnectionState) => void
  onAccessLost: (status: 401 | 403) => void
  fetcher?: typeof fetch
  retryDelayMs?: number
}

// Only invalidation signals travel on the stream; all numbers come from the
// separately authorized analytics snapshot endpoint.
export function subscribeAnalytics(options: AnalyticsSubscription): () => void {
  const lifetime = new AbortController()
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined
  let retryTimer: ReturnType<typeof setTimeout> | undefined
  let resumeRetry: (() => void) | undefined
  let failures = 0
  const waitForRetry = () => new Promise<void>(resolve => {
    resumeRetry = resolve
    const delay = Math.min((options.retryDelayMs ?? 1000) * 2 ** Math.min(failures++, 5), 30000)
    retryTimer = setTimeout(resolve, delay)
  })
  const stop = () => {
    lifetime.abort()
    if (retryTimer) clearTimeout(retryTimer)
    resumeRetry?.()
    void reader?.cancel().catch(() => {})
  }
  const loseAccess = (status: 401 | 403) => {
    if (!lifetime.signal.aborted) options.onAccessLost(status)
    stop()
  }
  async function run() {
    while (!lifetime.signal.aborted) {
      options.onState(failures ? 'reconnecting' : 'connecting')
      const connection = new AbortController()
      const abortConnection = () => connection.abort()
      lifetime.signal.addEventListener('abort', abortConnection, { once: true })
      let watchdog: ReturnType<typeof setTimeout> | undefined
      let connectedAt = 0
      const resetWatchdog = () => {
        if (watchdog) clearTimeout(watchdog)
        watchdog = setTimeout(() => { connection.abort(); void reader?.cancel().catch(() => {}) }, 45000)
      }
      try {
        resetWatchdog()
        const response = await (options.fetcher ?? fetch)('/api/v1/workspace/analytics/events', {
          headers: { Accept: 'text/event-stream', Authorization: `Bearer ${options.token}` },
          credentials: 'omit', signal: connection.signal,
        })
        if (lifetime.signal.aborted) return
        if (response.status === 401 || response.status === 403) { loseAccess(response.status); return }
        if (!response.ok || !response.body || !response.headers.get('content-type')?.toLowerCase().startsWith('text/event-stream')) throw new Error('Invalid analytics stream')
        reader = response.body.getReader()
        connectedAt = Date.now()
        options.onState('live')
        // Reconcile the snapshot with changes that happened while disconnected.
        options.onRefresh()
        let buffer = ''
        const decoder = new TextDecoder()
        resetWatchdog()
        while (!lifetime.signal.aborted && !connection.signal.aborted) {
          const chunk = await reader.read()
          if (chunk.done) break
          resetWatchdog()
          buffer += decoder.decode(chunk.value, { stream: true })
          if (buffer.length > 65536) throw new Error('Oversized analytics event')
          let boundary: RegExpExecArray | null
          while ((boundary = /\r?\n\r?\n/.exec(buffer))) {
            const frame = buffer.slice(0, boundary.index)
            buffer = buffer.slice(boundary.index + boundary[0].length)
            const event = frame.split(/\r?\n/).find(line => line.startsWith('event:'))?.slice(6).trim()
            if (event === 'refresh') options.onRefresh()
            else if (event === 'auth-expired' || event === 'access-revoked') {
              loseAccess(event === 'auth-expired' ? 401 : 403)
              return
            }
          }
        }
      } catch {
        // Reconnect below; the page retains the last authorized snapshot as stale.
      } finally {
        if (watchdog) clearTimeout(watchdog)
        lifetime.signal.removeEventListener('abort', abortConnection)
        if (reader) { await reader.cancel().catch(() => {}); reader = undefined }
        if (connectedAt && Date.now() - connectedAt >= 30000) failures = 0
      }
      if (!lifetime.signal.aborted) {
        options.onState('reconnecting')
        await waitForRetry()
      }
    }
  }
  void run()
  return stop
}
