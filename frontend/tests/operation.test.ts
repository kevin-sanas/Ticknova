import { describe, expect, it } from 'vitest'
import { useOperation } from '../src/composables/operation'
import { ApiError } from '../src/api'

describe('mutation feedback', () => {
  it('keeps the draft and conflict until an explicit reload succeeds', async () => {
    const draft = { body: '尚未发送的回复' }
    const operation = useOperation()
    await operation.run(async () => { throw new ApiError(409, { title: '版本冲突', status: 409, code: 'TICKET_VERSION_CONFLICT', traceId: 'trace-2' }) })
    expect(draft.body).toBe('尚未发送的回复')
    expect(operation.conflict.value).toBe(true)
    expect(operation.error.value).toMatchObject({ status: 409 })
    await operation.run(async () => 'ok')
    expect(operation.conflict.value).toBe(false)
    expect(operation.pending.value).toBe(false)
  })
  it('rejects a second submit while the first mutation is in flight', async () => {
    const operation = useOperation()
    let release!: () => void
    let attempts = 0
    const first = operation.run(() => new Promise<void>((resolve) => { attempts++; release = resolve }))
    const second = await operation.run(async () => { attempts++ })
    expect(attempts).toBe(1)
    expect(second).toBeUndefined()
    release()
    await first
    expect(operation.pending.value).toBe(false)
  })
})
