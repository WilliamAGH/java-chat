/** Lesson metadata from the guided learning TOC. */
export interface GuidedLesson {
  slug: string
  title: string
  summary: string
  keywords: string[]
}

/** Response from the lesson content endpoint. */
export interface LessonContentResponse {
  markdown: string
  cached: boolean
}

/**
 * Fetch the table of contents for guided learning.
 */
export async function fetchTOC(): Promise<GuidedLesson[]> {
  const response = await fetch('/api/guided/toc')
  if (!response.ok) {
    throw new Error(`Failed to fetch TOC: ${response.status}`)
  }
  return response.json()
}

/**
 * Fetch a single lesson's metadata.
 */
export async function fetchLesson(slug: string): Promise<GuidedLesson> {
  const response = await fetch(`/api/guided/lesson?slug=${encodeURIComponent(slug)}`)
  if (!response.ok) {
    throw new Error(`Failed to fetch lesson: ${response.status}`)
  }
  return response.json()
}

/**
 * Fetch lesson content as markdown (non-streaming).
 */
export async function fetchLessonContent(slug: string): Promise<LessonContentResponse> {
  const response = await fetch(`/api/guided/content?slug=${encodeURIComponent(slug)}`)
  if (!response.ok) {
    throw new Error(`Failed to fetch lesson content: ${response.status}`)
  }
  return response.json()
}

/** Callbacks for guided chat streaming with explicit error handling. */
export interface GuidedStreamCallbacks {
  onChunk: (chunk: string) => void
  onError?: (error: Error) => void
}

/** Error response structure from SSE error events. */
interface StreamError {
  message: string
  details?: string
}

/**
 * Attempts JSON parsing only when content looks like JSON.
 * Returns parsed object or null for plain text content.
 */
function tryParseJson<T>(content: string): T | null {
  const trimmed = content.trim()
  if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) {
    return null
  }
  try {
    return JSON.parse(trimmed) as T
  } catch {
    return null
  }
}

/**
 * Stream a chat response within the guided lesson context.
 * Uses the same JSON-wrapped SSE format as the main chat for consistent whitespace handling.
 * Errors are propagated via both the onError callback and thrown promise rejection.
 */
export async function streamGuidedChat(
  sessionId: string,
  slug: string,
  message: string,
  callbacks: GuidedStreamCallbacks
): Promise<void> {
  const { onChunk, onError } = callbacks

  const response = await fetch('/api/guided/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      sessionId,
      slug,
      latest: message
    })
  })

  if (!response.ok) {
    const httpError = new Error(`HTTP ${response.status}: ${response.statusText}`)
    onError?.(httpError)
    throw httpError
  }

  const reader = response.body?.getReader()
  if (!reader) {
    const bodyError = new Error('No response body')
    onError?.(bodyError)
    throw bodyError
  }

  const decoder = new TextDecoder()
  let buffer = ''
  let eventBuffer = ''
  let hasEventData = false
  let currentEventType: string | null = null
  let streamCompletedNormally = false

  const flushEvent = () => {
    if (!hasEventData) {
      currentEventType = null
      return
    }

    const eventType = currentEventType?.trim().toLowerCase() ?? ''
    const rawEventData = eventBuffer
    eventBuffer = ''
    hasEventData = false
    currentEventType = null

    // Handle error events
    if (eventType === 'error') {
      const parsed = tryParseJson<StreamError>(rawEventData)
      const streamError = parsed ?? { message: rawEventData }
      const error = new Error(streamError.message)
      onError?.(error)
      throw error
    }

    // Default and "text" events - extract text from JSON wrapper
    const parsed = tryParseJson<{ text?: string }>(rawEventData)
    // Validate parsed structure before extracting - fall back to raw if structure unexpected
    const textContent = (parsed !== null && typeof parsed === 'object' && 'text' in parsed && typeof parsed.text === 'string')
      ? parsed.text
      : rawEventData
    if (textContent !== '') {
      onChunk(textContent)
    }
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
          const eventPayload = line.startsWith('data: ') ? line.slice(6) : line.slice(5)

          // Skip [DONE] token
          if (eventPayload === '[DONE]') {
            continue
          }

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
  } finally {
    // Cancel reader on abnormal exit to prevent dangling connections
    if (!streamCompletedNormally) {
      try {
        await reader.cancel()
      } catch {
        // Ignore cancel errors - stream may already be closed
      }
    }
    reader.releaseLock()
  }
}
