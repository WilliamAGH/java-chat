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
