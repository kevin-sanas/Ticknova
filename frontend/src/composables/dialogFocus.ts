import { nextTick, onBeforeUnmount } from 'vue'

function isVisible(element: HTMLElement) {
  for (let node: HTMLElement | null = element; node; node = node.parentElement) {
    const style = window.getComputedStyle(node)
    if (node.hidden || style.display === 'none' || style.visibility === 'hidden') return false
  }
  return true
}

/** Capture before opening, restore only after ElDialog's closed transition. */
export function useDialogFocus() {
  let opener: HTMLElement | null = null
  let generation = 0
  function capture(event?: Event) {
    generation++
    opener = event?.currentTarget instanceof HTMLElement ? event.currentTarget : document.activeElement instanceof HTMLElement ? document.activeElement : null
  }
  async function restore() {
    const current = generation; const target = opener
    await nextTick()
    if (current !== generation || !target?.isConnected || target.matches(':disabled, [aria-disabled="true"]') || target.closest('[inert]') || !isVisible(target)) return
    // A newly opened modal owns focus; a nested dialog may return to its parent.
    const anotherModal = [...document.querySelectorAll<HTMLElement>('[aria-modal="true"]')].some(modal => isVisible(modal) && !modal.contains(target))
    if (anotherModal) return
    opener = null
    target.focus({ preventScroll: true })
  }
  onBeforeUnmount(() => { generation++; opener = null })
  return { capture, restore }
}
