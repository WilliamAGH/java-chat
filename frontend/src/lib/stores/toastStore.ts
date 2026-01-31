import { derived, writable } from 'svelte/store'

export type ToastSeverity = 'error' | 'info'

export interface ToastAction {
  label: string
  href: string
}

export interface ToastNotice {
  id: string
  message: string
  severity: ToastSeverity
  detail?: string
  action?: ToastAction
}

const TOAST_DURATION_MS = 6_000

let nextToastId = 0
const toastQueue = writable<ToastNotice[]>([])

export function pushToast(
  message: string,
  options: { severity?: ToastSeverity; detail?: string; action?: ToastAction } = {}
): string {
  const id = `toast-${++nextToastId}`
  const notice: ToastNotice = {
    id,
    message,
    severity: options.severity ?? 'error'
  }
  if (options.detail) {
    notice.detail = options.detail
  }
  if (options.action) {
    notice.action = options.action
  }
  toastQueue.update((queue) => [...queue, notice])
  if (typeof window !== 'undefined') {
    window.setTimeout(() => dismissToast(id), TOAST_DURATION_MS)
  }
  return id
}

export function dismissToast(id: string): void {
  toastQueue.update((queue) => queue.filter((notice) => notice.id !== id))
}

export const toasts = derived(toastQueue, ($queue) => $queue)
