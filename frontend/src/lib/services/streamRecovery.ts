import type { StreamError, StreamStatus } from '../validation/schemas'

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
 * - at most one retry,
 * - only before any assistant chunk has been rendered,
 * - only for known malformed/overflow stream signatures.
 */
export function shouldRetryStreamRequest(
  streamFailure: unknown,
  streamErrorEvent: StreamError | null,
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
  return { message: 'Streaming request failed' }
}

/**
 * Converts stream failures into a thrown Error that preserves structured StreamError details.
 */
export function toStreamFailureException(streamFailure: unknown, streamErrorEvent: StreamError | null): Error {
  const mappedStreamError = toStreamError(streamFailure, streamErrorEvent)
  if (streamFailure instanceof Error && streamFailure.message === mappedStreamError.message) {
    if (mappedStreamError.details) {
      ;(streamFailure as Error & { details?: string }).details = mappedStreamError.details
    }
    return streamFailure
  }

  const streamFailureException = new Error(mappedStreamError.message)
  if (mappedStreamError.details) {
    ;(streamFailureException as Error & { details?: string }).details = mappedStreamError.details
  }
  return streamFailureException
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

function describeStreamFailure(streamFailure: unknown, streamErrorEvent: StreamError | null): string {
  const diagnosticTokens: string[] = []
  if (streamErrorEvent?.message) {
    diagnosticTokens.push(streamErrorEvent.message)
  }
  if (streamErrorEvent?.details) {
    diagnosticTokens.push(streamErrorEvent.details)
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
