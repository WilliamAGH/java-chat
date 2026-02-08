import type { StreamError, StreamStatus, Citation } from '../validation/schemas'
import { streamSse } from './sse'

const GENERIC_STREAM_FAILURE_MESSAGE = 'Streaming request failed'

/**
 * Error subclass carrying structured SSE stream failure details.
 * Replaces unsafe `as` casts that monkey-patched `details` onto plain Error objects.
 */
export class StreamFailureError extends Error {
  readonly details?: string

  constructor(message: string, details?: string) {
    super(message)
    this.name = 'StreamFailureError'
    this.details = details
  }
}

const RECOVERABLE_STREAM_ERROR_PATTERNS = [
  /overflowexception/i,
  /invalid\s+(stream|response)/i,
  /malformed\s+(stream|response|json)/i,
  /sseexception/i,
  /unexpected end of json input/i,
  /failed to fetch/i,
  /networkerror/i,
  /connection (reset|closed|refused)/i,
  /socket hang up/i,
  /\btimeout\b/i,
  /\btimed out\b/i,
  /\bhttp\s*5\d{2}\b/i,
  /\b5\d{2}\s+(internal|bad gateway|service unavailable|gateway timeout)\b/i
]

const NON_RECOVERABLE_STREAM_ERROR_PATTERNS = [/rate limit/i, /\b429\b/, /\b401\b/, /\b403\b/, /providers unavailable/i]
const ABORT_STREAM_ERROR_PATTERNS = [/aborterror/i, /\baborted\b/i, /\bcancelled\b/i]

const DEFAULT_STREAM_RECOVERY_RETRY_COUNT = 1
const MIN_STREAM_RECOVERY_RETRY_COUNT = 0
const MAX_STREAM_RECOVERY_RETRY_COUNT = 3

export const MAX_STREAM_RECOVERY_RETRIES = resolveStreamRecoveryRetryCount(
  import.meta.env.VITE_STREAM_RECOVERY_MAX_RETRIES
)

/**
 * Decides whether a stream request should be retried for a likely recoverable provider response issue.
 *
 * The retry gate is intentionally strict:
 * - bounded retries via MAX_STREAM_RECOVERY_RETRIES,
 * - only before any assistant chunk has been rendered,
 * - only for known malformed/overflow stream signatures.
 */
export function shouldRetryStreamRequest(
  streamFailure: unknown,
  streamErrorEvent: StreamError | null,
  latestStreamStatus: StreamStatus | null,
  hasStreamedAnyChunk: boolean,
  attemptedRetries: number,
  maxRecoveryRetries: number
): boolean {
  if (hasStreamedAnyChunk) {
    return false
  }
  if (attemptedRetries >= maxRecoveryRetries) {
    return false
  }
  if (latestStreamStatus?.stage === 'stream' && latestStreamStatus.retryable === false) {
    return false
  }
  if (latestStreamStatus?.stage === 'stream' && latestStreamStatus.retryable === true) {
    return true
  }

  const failureDescription = describeStreamFailure(streamFailure, streamErrorEvent)
  if (ABORT_STREAM_ERROR_PATTERNS.some((pattern) => pattern.test(failureDescription))) {
    return false
  }
  if (NON_RECOVERABLE_STREAM_ERROR_PATTERNS.some((pattern) => pattern.test(failureDescription))) {
    return false
  }
  return RECOVERABLE_STREAM_ERROR_PATTERNS.some((pattern) => pattern.test(failureDescription))
}

/**
 * Builds a small user-facing retry status notice for the streaming indicator.
 */
export function buildStreamRetryStatus(nextAttemptNumber: number, maxRecoveryRetries: number): StreamStatus {
  return {
    message: 'Temporary stream issue detected',
    details: `The API response or network stream was temporarily invalid. Retrying your request (${nextAttemptNumber}/${maxRecoveryRetries}).`
  }
}

/**
 * Builds a user-visible status when a retry has succeeded and streaming resumes.
 */
export function buildStreamRecoverySucceededStatus(recoveryAttemptCount: number): StreamStatus {
  return {
    message: 'Streaming recovered',
    details: `Recovered after retry (${recoveryAttemptCount}). Continuing your response.`
  }
}

/**
 * Converts thrown stream failures into the canonical StreamError shape used by UI components.
 */
export function toStreamError(streamFailure: unknown, streamErrorEvent: StreamError | null): StreamError {
  if (streamErrorEvent) {
    return streamErrorEvent
  }
  if (streamFailure instanceof Error) {
    return { message: streamFailure.message }
  }
  return { message: GENERIC_STREAM_FAILURE_MESSAGE }
}

/**
 * Converts stream failures into a typed StreamFailureError that preserves structured details.
 */
export function toStreamFailureException(
  streamFailure: unknown,
  streamErrorEvent: StreamError | null
): StreamFailureError {
  const mappedStreamError = toStreamError(streamFailure, streamErrorEvent)
  return new StreamFailureError(mappedStreamError.message, mappedStreamError.details ?? undefined)
}

export function resolveStreamRecoveryRetryCount(rawRetrySetting: unknown): number {
  if (rawRetrySetting === null || rawRetrySetting === undefined || rawRetrySetting === '') {
    return DEFAULT_STREAM_RECOVERY_RETRY_COUNT
  }

  const parsedRetryCount = Number(rawRetrySetting)
  if (!Number.isInteger(parsedRetryCount)) {
    return DEFAULT_STREAM_RECOVERY_RETRY_COUNT
  }
  if (parsedRetryCount < MIN_STREAM_RECOVERY_RETRY_COUNT) {
    return MIN_STREAM_RECOVERY_RETRY_COUNT
  }
  if (parsedRetryCount > MAX_STREAM_RECOVERY_RETRY_COUNT) {
    return MAX_STREAM_RECOVERY_RETRY_COUNT
  }
  return parsedRetryCount
}

/** Callbacks for the stream-with-retry wrapper. */
export interface StreamWithRetryCallbacks {
  onChunk: (chunk: string) => void
  onStatus?: (status: StreamStatus) => void
  onError?: (error: StreamError) => void
  onCitations?: (citations: Citation[]) => void
  signal?: AbortSignal
}

/**
 * Streams an SSE endpoint with automatic retry on recoverable failures.
 * Retries only before the first assistant chunk is emitted to the UI.
 */
export async function streamWithRetry(
  endpoint: string,
  body: object,
  callbacks: StreamWithRetryCallbacks,
  sourceLabel: string
): Promise<void> {
  const { onChunk, onStatus, onError, onCitations, signal } = callbacks
  let attemptedRecoveryRetries = 0
  let hasPendingRecoverySuccessNotice = false

  while (true) {
    let hasStreamedAnyChunk = false
    let streamErrorEvent: StreamError | null = null
    let latestStreamStatus: StreamStatus | null = null

    try {
      await streamSse(
        endpoint,
        body,
        {
          onText: (chunk) => {
            hasStreamedAnyChunk = true
            if (hasPendingRecoverySuccessNotice) {
              onStatus?.(buildStreamRecoverySucceededStatus(attemptedRecoveryRetries))
              hasPendingRecoverySuccessNotice = false
            }
            onChunk(chunk)
          },
          onStatus: (status) => {
            latestStreamStatus = status
            onStatus?.(status)
          },
          onError: (streamError) => {
            streamErrorEvent = streamError
          },
          onCitations
        },
        sourceLabel,
        { signal }
      )
      return
    } catch (streamFailure) {
      if (
        shouldRetryStreamRequest(
          streamFailure,
          streamErrorEvent,
          latestStreamStatus,
          hasStreamedAnyChunk,
          attemptedRecoveryRetries,
          MAX_STREAM_RECOVERY_RETRIES
        )
      ) {
        attemptedRecoveryRetries++
        hasPendingRecoverySuccessNotice = true
        onStatus?.(buildStreamRetryStatus(attemptedRecoveryRetries, MAX_STREAM_RECOVERY_RETRIES))
        continue
      }
      const mappedStreamError = toStreamError(streamFailure, streamErrorEvent)
      onError?.(mappedStreamError)
      throw toStreamFailureException(streamFailure, streamErrorEvent)
    }
  }
}

function describeStreamFailure(streamFailure: unknown, streamErrorEvent: StreamError | null): string {
  const diagnosticTokens: string[] = []
  if (streamErrorEvent?.message) {
    diagnosticTokens.push(streamErrorEvent.message)
  }
  if (streamErrorEvent?.details) {
    diagnosticTokens.push(streamErrorEvent.details)
  }
  if (streamErrorEvent?.code) {
    diagnosticTokens.push(streamErrorEvent.code)
  }
  if (streamFailure instanceof Error) {
    diagnosticTokens.push(streamFailure.message)
    if ('details' in streamFailure && typeof streamFailure.details === 'string') {
      diagnosticTokens.push(streamFailure.details)
    }
  } else if (streamFailure !== null && streamFailure !== undefined) {
    diagnosticTokens.push(String(streamFailure))
  }
  return diagnosticTokens.join(' ').trim()
}
