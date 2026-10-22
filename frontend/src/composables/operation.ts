import { computed, ref, shallowRef } from 'vue'
import { ApiError } from '../api'
export function useOperation() {
  const pending = ref(false); const error = shallowRef<ApiError | null>(null)
  const conflict = computed(() => error.value?.status === 409)
  async function run<T>(task: () => Promise<T>): Promise<T | undefined> {
    if (pending.value) return undefined
    pending.value = true; error.value = null
    try { return await task() }
    catch (failure) { error.value = failure instanceof ApiError ? failure : new ApiError(0, { status: 0, title: '操作未完成，请重试。' }); return undefined }
    finally { pending.value = false }
  }
  return { pending, error, conflict, run }
}
