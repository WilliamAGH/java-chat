export interface ChatMessage {
  role: 'user' | 'assistant'
  content: string
  timestamp: number
  isError?: boolean
}

export interface Citation {
  url: string
  title: string
  anchor?: string
  snippet?: string
}

export interface Enrichment {
  packageName?: string
  jdkVersion?: string
  resource?: string
  hints?: string[]
  reminders?: string[]
  background?: string[]
}

export interface StreamStatus {
  message: string
  details?: string
}

export interface StreamError {
  message: string
  details?: string
}

export interface StreamChatOptions {
  onStatus?: (status: StreamStatus) => void
  onError?: (error: StreamError) => void
}

/**
 * Stream chat response from the backend using Server-Sent Events
 * @param sessionId - Unique session identifier
 * @param message - User's message
 * @param onChunk - Callback for each streamed chunk
 */
export async function streamChat(
  sessionId: string,
  message: string,
  onChunk: (chunk: string) => void,
  options: StreamChatOptions = {}
): Promise<void> {
  const response = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({
      sessionId,
      latest: message
    })
  })

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}: ${response.statusText}`)
  }

  const reader = response.body?.getReader()
  if (!reader) {
    throw new Error('No response body')
  }

  const decoder = new TextDecoder()
  let streamCompletedNormally = false
  let buffer = ''
  let eventBuffer = ''
  let hasEventData = false
  let currentEventType: string | null = null

  /**
   * Attempts JSON parsing only when content looks like JSON.
   * Returns parsed object or null for plain text content.
   * Throws SyntaxError for malformed JSON (starts with '{' or '[' but invalid).
   */
  function tryParseJson<T>(content: string): T | null {
    const trimmed = content.trim()
    if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) {
      return null
    }
    return JSON.parse(trimmed) as T
  }

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

    if (eventType === 'status') {
      const parsed = tryParseJson<StreamStatus>(rawEventData)
      options.onStatus?.(parsed ?? { message: rawEventData })
      return
    }

    if (eventType === 'error') {
      const parsed = tryParseJson<StreamError>(rawEventData)
      const streamError = parsed ?? { message: rawEventData }
      options.onError?.(streamError)
      const error = new Error(streamError.message)
      ;(error as Error & { details?: string }).details = streamError.details
      throw error
    }

    // Default and "text" events - extract text from JSON wrapper if present
    const parsed = tryParseJson<{ text?: string }>(rawEventData)
    const textContent = parsed?.text ?? rawEventData
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

      for (let i = 0; i < lines.length - 1; i++) {
        let line = lines[i]
        if (line.endsWith('\r')) {
          line = line.slice(0, -1)
        }

        // Skip SSE comments (keepalive heartbeats)
        if (line.startsWith(':')) {
          continue
        }

        if (line.startsWith('event:')) {
          currentEventType = line.startsWith('event: ')
            ? line.slice(7)
            : line.slice(6)
          continue
        }

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
  } finally {
    // Cancel the reader on early exit to abort the network stream and avoid dangling connections
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

/**
 * Fetch citations for a query
 */
export async function fetchCitations(query: string): Promise<Citation[]> {
  try {
    const response = await fetch(`/api/chat/citations?q=${encodeURIComponent(query)}`)
    if (!response.ok) return []
    return await response.json()
  } catch {
    return []
  }
}

/**
 * Fetch enrichment data for a query
 */
export async function fetchEnrichment(query: string): Promise<Enrichment | null> {
  try {
    const response = await fetch(`/api/chat/enrich?q=${encodeURIComponent(query)}`)
    if (!response.ok) return null
    return await response.json()
  } catch {
    return null
  }
}
