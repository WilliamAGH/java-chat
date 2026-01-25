/**
 * Composable for managing streaming chat state across views.
 *
 * Encapsulates the reactive state and lifecycle methods for SSE streaming,
 * with optional timer-based status message persistence.
 */

import type { StreamStatus } from '../services/stream-types'

/** Configuration options for streaming state behavior. */
export interface StreamingStateOptions {
  /**
   * Delay in milliseconds before clearing status after stream ends.
   *
   * - `0` (default): Immediate clear - status disappears when stream ends
   * - `> 0`: Delayed clear - status persists so users can read final message
   *
   * ChatView uses 800ms to let users read "Done" status; LearnView uses 0.
   */
  statusClearDelayMs?: number
}

/** Streaming state with reactive getters and action methods. */
export interface StreamingState {
  /** Whether a stream is currently active. */
  readonly isStreaming: boolean
  /** Accumulated content from stream chunks. */
  readonly streamingContent: string
  /** Current status message (e.g., "Searching...", "Done"). */
  readonly statusMessage: string
  /** Additional status details. */
  readonly statusDetails: string

  /** Marks stream as active and resets content/status. */
  startStream: () => void
  /** Appends a text chunk to streaming content. */
  appendContent: (chunk: string) => void
  /** Updates status message and optional details. */
  updateStatus: (status: StreamStatus) => void
  /** Marks stream as complete and schedules status clearing. */
  finishStream: () => void
  /** Immediately resets all state (cancels any pending timers). */
  reset: () => void
  /** Cleanup function to clear timers - call from $effect cleanup. */
  cleanup: () => void
}

/**
 * Creates reactive streaming state for chat interfaces.
 *
 * @example
 * ```svelte
 * <script lang="ts">
 *   import { createStreamingState } from '../composables/createStreamingState.svelte'
 *
 *   const streaming = createStreamingState({ statusClearDelayMs: 800 })
 *
 *   // Cleanup timer on unmount - return the cleanup function
 *   $effect(() => { return streaming.cleanup })
 *
 *   async function handleSend(message: string) {
 *     streaming.startStream()
 *     try {
 *       await streamChat(sessionId, message, (chunk) => {
 *         streaming.appendContent(chunk)
 *       }, {
 *         onStatus: streaming.updateStatus
 *       })
 *     } finally {
 *       streaming.finishStream()
 *     }
 *   }
 * </script>
 *
 * {#if streaming.isStreaming}
 *   <StreamingUI content={streaming.streamingContent} />
 * {/if}
 * ```
 */
export function createStreamingState(options: StreamingStateOptions = {}): StreamingState {
  const { statusClearDelayMs = 0 } = options

  // Internal reactive state
  let isStreaming = $state(false)
  let streamingContent = $state('')
  let statusMessage = $state('')
  let statusDetails = $state('')

  // Timer for delayed status clearing
  let statusClearTimer: ReturnType<typeof setTimeout> | null = null

  function cancelStatusTimer(): void {
    if (statusClearTimer) {
      clearTimeout(statusClearTimer)
      statusClearTimer = null
    }
  }

  function clearStatusNow(): void {
    cancelStatusTimer()
    statusMessage = ''
    statusDetails = ''
  }

  function clearStatusDelayed(): void {
    if (statusClearDelayMs <= 0) {
      clearStatusNow()
      return
    }

    cancelStatusTimer()
    statusClearTimer = setTimeout(() => {
      statusMessage = ''
      statusDetails = ''
      statusClearTimer = null
    }, statusClearDelayMs)
  }

  return {
    // Reactive getters
    get isStreaming() {
      return isStreaming
    },
    get streamingContent() {
      return streamingContent
    },
    get statusMessage() {
      return statusMessage
    },
    get statusDetails() {
      return statusDetails
    },

    // Actions
    startStream() {
      cancelStatusTimer()
      isStreaming = true
      streamingContent = ''
      statusMessage = ''
      statusDetails = ''
    },

    appendContent(chunk: string) {
      streamingContent += chunk
    },

    updateStatus(status: StreamStatus) {
      statusMessage = status.message
      statusDetails = status.details ?? ''
    },

    finishStream() {
      isStreaming = false
      streamingContent = ''
      clearStatusDelayed()
    },

    reset() {
      cancelStatusTimer()
      isStreaming = false
      streamingContent = ''
      statusMessage = ''
      statusDetails = ''
    },

    cleanup() {
      cancelStatusTimer()
    }
  }
}
