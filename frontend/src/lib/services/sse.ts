/**
 * Canonical SSE stream parser for chat endpoints.
 *
 * Provides unified Server-Sent Events parsing with proper buffering,
 * event type handling, and connection cleanup. Used by both main chat
 * and guided learning streams.
 */

import {
  StreamStatusSchema,
  StreamErrorSchema,
  TextEventPayloadSchema,
  CitationsArraySchema,
  type StreamStatus,
  type StreamError,
  type Citation
} from '../validation/schemas'
import { validateWithSchema } from '../validation/validate'

/** SSE event types emitted by streaming endpoints. */
const SSE_EVENT_STATUS = 'status'
const SSE_EVENT_ERROR = 'error'
const SSE_EVENT_CITATION = 'citation'

/** Optional request options for streaming fetch calls. */
export interface StreamSseRequestOptions {
  signal?: AbortSignal
}

/** Callbacks for SSE stream processing. */
export interface SseCallbacks {
  onText: (content: string) => void
  onStatus?: (status: StreamStatus) => void
  onError?: (error: StreamError) => void
  onCitations?: (citations: Citation[]) => void
}

function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === 'AbortError'
}

/**
 * Attempts JSON parsing only when content looks like JSON.
 * Returns parsed object or null for plain text content.
 * Logs parse errors for debugging without interrupting stream processing.
 */
export function tryParseJson(content: string, source: string): unknown {
  const trimmed = content.trim()
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) {
    return null
  }
  try {
    return JSON.parse(trimmed)
  } catch (parseError) {
    // Log for debugging but don't throw - allows graceful fallback to raw text
    console.warn(`[${source}] JSON parse failed for content that looked like JSON:`, {
      preview: trimmed.slice(0, 100),
      error: parseError instanceof Error ? parseError.message : String(parseError)
    })
    return null
  }
}

/**
 * Processes a complete SSE event and dispatches to appropriate callback.
 *
 * @param eventType - The SSE event type (status, error, citation, text, or empty for default)
 * @param eventData - The raw event data payload
 * @param callbacks - Callbacks to invoke based on event type
 * @throws Error when an error event is received (to terminate the stream)
 */
function processEvent(
  eventType: string,
  eventData: string,
  callbacks: SseCallbacks,
  source: string
): void {
  const normalizedType = eventType.trim().toLowerCase()

  if (normalizedType === SSE_EVENT_STATUS) {
    const parsed = tryParseJson(eventData, source)
    const validated = validateWithSchema(StreamStatusSchema, parsed, `${source}:status`)
    callbacks.onStatus?.(validated.success ? validated.data : { message: eventData })
    return
  }

  if (normalizedType === SSE_EVENT_ERROR) {
    const parsed = tryParseJson(eventData, source)
    const validated = validateWithSchema(StreamErrorSchema, parsed, `${source}:error`)
    const streamError: StreamError = validated.success ? validated.data : { message: eventData }
    callbacks.onError?.(streamError)
    const error = new Error(streamError.message)
    ;(error as Error & { details?: string }).details = streamError.details
    throw error
  }

  if (normalizedType === SSE_EVENT_CITATION) {
    const parsed = tryParseJson(eventData, source)
    const validated = validateWithSchema(CitationsArraySchema, parsed, `${source}:citations`)
    if (validated.success) {
      callbacks.onCitations?.(validated.data)
    }
    return
  }

  // Default and "text" events - extract text from JSON wrapper if present
  const parsed = tryParseJson(eventData, source)
  const validated = validateWithSchema(TextEventPayloadSchema, parsed, `${source}:text`)
  const textContent = validated.success ? validated.data.text : eventData
  if (textContent !== '') {
    callbacks.onText(textContent)
  }
}

/**
 * Streams SSE responses from a POST endpoint with proper buffering and cleanup.
 *
 * Handles:
 * - Multi-byte character buffering across chunks
 * - SSE event type parsing (event:, data:, blank line termination)
 * - Heartbeat comment filtering
 * - [DONE] token skipping
 * - Graceful stream cancellation on early exit
 *
 * @param url - The endpoint URL
 * @param body - The request body to POST
 * @param callbacks - Callbacks for different event types
 * @param source - Source identifier for logging (e.g., 'chat.ts', 'guided.ts')
 */
export async function streamSse(
  url: string,
  body: object,
  callbacks: SseCallbacks,
  source: string,
  options: StreamSseRequestOptions = {}
): Promise<void> {
  const abortSignal = options.signal
  let response: Response

  try {
    response = await fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify(body),
      signal: abortSignal
    })
  } catch (fetchError) {
    if (abortSignal?.aborted || isAbortError(fetchError)) {
      return
    }
    throw fetchError
  }

  if (!response.ok) {
    const httpError = new Error(`HTTP ${response.status}: ${response.statusText}`)
    callbacks.onError?.({ message: httpError.message })
    throw httpError
  }

  const reader = response.body?.getReader()
  if (!reader) {
    const bodyError = new Error('No response body')
    callbacks.onError?.({ message: bodyError.message })
    throw bodyError
  }

  const decoder = new TextDecoder()
  let streamCompletedNormally = false
  let buffer = ''
  let eventBuffer = ''
  let hasEventData = false
  let currentEventType: string | null = null

  const flushEvent = () => {
    if (!hasEventData) {
      currentEventType = null
      return
    }

    const eventType = currentEventType ?? ''
    const rawEventData = eventBuffer
    eventBuffer = ''
    hasEventData = false
    currentEventType = null

    processEvent(eventType, rawEventData, callbacks, source)
  }

  try {
    while (true) {
      const { done, value } = await reader.read()

      if (done) {
        streamCompletedNormally = true
        // Flush any remaining bytes from the TextDecoder (handles multi-byte chars split across chunks)
        const remaining = decoder.decode()
        if (remaining) {
          buffer += remaining
        }
        // Commit any remaining buffered line before flushing event data
        if (buffer.length > 0) {
          eventBuffer = eventBuffer ? `${eventBuffer}\n${buffer}` : buffer
          hasEventData = true
          buffer = ''
        }
        flushEvent()
        break
      }

      const chunk = decoder.decode(value, { stream: true })
      buffer += chunk
      const lines = buffer.split('\n')
      buffer = lines[lines.length - 1]

      for (let lineIndex = 0; lineIndex < lines.length - 1; lineIndex++) {
        let line = lines[lineIndex]
        if (line.endsWith('\r')) {
          line = line.slice(0, -1)
        }

        // Skip SSE comments (keepalive heartbeats)
        if (line.startsWith(':')) {
          continue
        }

        // Track event type
        if (line.startsWith('event:')) {
          currentEventType = line.startsWith('event: ') ? line.slice(7) : line.slice(6)
          continue
        }

        // Accumulate data within current SSE event
        if (line.startsWith('data:')) {
          // Per SSE spec, strip optional space after "data:" prefix
          const eventPayload = line.startsWith('data: ') ? line.slice(6) : line.slice(5)

          // Skip [DONE] token
          if (eventPayload === '[DONE]') {
            continue
          }

          // Accumulate within current SSE event
          if (hasEventData) {
            eventBuffer += '\n'
          }
          eventBuffer += eventPayload
          hasEventData = true
        } else if (line.trim() === '') {
          // Blank line marks end of SSE event - commit accumulated data
          flushEvent()
        }
      }
    }
  } catch (streamError) {
    if (abortSignal?.aborted || isAbortError(streamError)) {
      return
    }
    throw streamError
  } finally {
    // Cancel reader on abnormal exit to prevent dangling connections
    if (!streamCompletedNormally) {
      try {
        await reader.cancel()
      } catch {
        // Expected: cancel() throws if stream already closed by abort signal or server.
        // Safe to ignore - we're in cleanup and the stream is already terminated.
      }
    }
    reader.releaseLock()
  }
}
