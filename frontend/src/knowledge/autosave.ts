import { shallowRef } from 'vue'
import type { KnowledgeDraft } from '../types'
export type DraftPayload = Pick<KnowledgeDraft, 'title' | 'categoryId' | 'content' | 'version'>
export function createDraftAutosave(save: (payload: DraftPayload, signal: AbortSignal) => Promise<KnowledgeDraft>) {
  const state = shallowRef<{ draft: KnowledgeDraft | null; status: 'saved' | 'unsaved' | 'saving' | 'error' | 'conflict'; dirty: boolean; error: unknown }>({ draft: null, status: 'saved', dirty: false, error: null })
  let timer: ReturnType<typeof setTimeout> | undefined; let generation = 0; let revision = 0; let version = 0; let active: Promise<boolean> | null = null; let abort: AbortController | undefined
  function stop() { clearTimeout(timer); timer = undefined }
  function schedule() { stop(); if (state.value.status !== 'conflict') timer = setTimeout(() => { void flush() }, 800) }
  function reset(draft: KnowledgeDraft | null) { generation++; stop(); abort?.abort(); active = null; revision = 0; version = draft?.version || 0; state.value = { draft, status: 'saved', dirty: false, error: null } }
  function edit(draft: KnowledgeDraft) { revision++; const conflict = state.value.status === 'conflict'; state.value = { ...state.value, draft: { ...draft, version }, status: conflict ? 'conflict' : active ? 'saving' : 'unsaved', dirty: true }; if (!active && !conflict) schedule() }
  async function flush(): Promise<boolean> {
    stop(); if (state.value.status === 'conflict') return false
    if (active) { await active; return state.value.dirty && state.value.status !== 'error' ? flush() : !state.value.dirty }
    if (!state.value.draft || !state.value.dirty) return true
    const own = generation; const editRevision = revision; const draft = state.value.draft; abort = new AbortController()
    state.value = { ...state.value, status: 'saving', error: null }
    active = (async () => {
      try {
        const result = await save({ title: draft.title.trim(), categoryId: draft.categoryId, content: draft.content, version }, abort!.signal)
        if (own !== generation) return false
        version = result.version
        const dirty = revision !== editRevision
        state.value = { draft: dirty ? { ...state.value.draft!, version } : result, status: dirty ? 'unsaved' : 'saved', dirty, error: null }
        return !dirty
      } catch (error) {
        if (own !== generation) return false
        state.value = { ...state.value, status: (error as { status?: number }).status === 409 ? 'conflict' : 'error', dirty: true, error }; return false
      } finally { if (own === generation) { active = null; if (state.value.status === 'unsaved') schedule() } }
    })()
    return active
  }
  return { state, reset, edit, flush, dispose: () => reset(null) }
}
