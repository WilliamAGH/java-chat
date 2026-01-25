/**
 * Canonical SSE response types for streaming endpoints.
 *
 * Shared between chat and guided learning streams to ensure consistent
 * handling of status updates and error responses from the backend.
 */

/** Status message structure from SSE status events. */
export interface StreamStatus {
  message: string
  details?: string
}

/** Error response structure from SSE error events. */
export interface StreamError {
  message: string
  details?: string
}

/**
 * Common state shape for streaming chat interfaces.
 *
 * Documents the fields used by components that handle SSE streaming responses.
 * Note: Cannot be used as a factory for reactive Svelte 5 $state() - declare
 * fields inline instead. This type exists for documentation and type checking.
 */
export interface StreamingChatFields {
  isStreaming: boolean
  streamingContent: string
  statusMessage: string
  statusDetails: string
}
