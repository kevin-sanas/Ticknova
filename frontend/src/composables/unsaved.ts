import { onBeforeUnmount } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessageBox } from 'element-plus'

const activeGuards = new Set<() => boolean>()
async function confirmChanges(isDirty: () => boolean) {
  if (!isDirty()) return true
  try {
    await ElMessageBox.confirm('还有未保存的内容。离开后这些修改将丢失。', '确认离开', {
      confirmButtonText: '放弃修改并离开', cancelButtonText: '继续编辑', type: 'warning',
      closeOnClickModal: false, distinguishCancelAndClose: true,
    })
    return true
  } catch { return false }
}
export function confirmBeforeLogout() { return confirmChanges(() => [...activeGuards].some(guard => guard())) }

export function useUnsavedChanges(isDirty: () => boolean) {
  activeGuards.add(isDirty)
  const confirmDiscard = () => confirmChanges(isDirty)
  const beforeUnload = (event: BeforeUnloadEvent) => { if (isDirty()) { event.preventDefault(); event.returnValue = '' } }
  window.addEventListener('beforeunload', beforeUnload)
  onBeforeUnmount(() => { activeGuards.delete(isDirty); window.removeEventListener('beforeunload', beforeUnload) })
  onBeforeRouteLeave(confirmDiscard)
  return { confirmDiscard }
}
