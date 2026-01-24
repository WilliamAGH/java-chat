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

/**
 * Stream chat response from the backend using Server-Sent Events
 * @param sessionId - Unique session identifier
 * @param message - User's message
 * @param onChunk - Callback for each streamed chunk
 */
export async function streamChat(
  sessionId: string,
  message: string,
  onChunk: (chunk: string) => void
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
  let buffer = ''
  let eventBuffer = ''
  let hasEventData = false

  try {
    while (true) {
      const { done, value } = await reader.read()

      if (done) {
        // Commit any remaining event data at stream end
        if (hasEventData) {
          onChunk(eventBuffer)
        }
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

        if (line.startsWith('data:')) {
          // Per SSE spec, strip optional space after "data:" prefix
          const data = line.startsWith('data: ') ? line.slice(6) : line.slice(5)

          // Skip [DONE] token
          if (data === '[DONE]') {
            continue
          }

          // Parse JSON wrapper to preserve whitespace (Spring SSE can trim leading spaces)
          // Format: {"text":"actual content with spaces"}
          let textContent = data
          if (data.startsWith('{') && data.includes('"text"')) {
            try {
              const parsed = JSON.parse(data) as { text?: string }
              textContent = parsed.text ?? data
            } catch {
              // Fallback to raw data if JSON parsing fails
              textContent = data
            }
          }

          // Accumulate within current SSE event
          if (hasEventData) {
            eventBuffer += '\n'
          }
          eventBuffer += textContent
          hasEventData = true
        } else if (line.trim() === '') {
          // Blank line marks end of SSE event - commit accumulated data
          if (hasEventData) {
            onChunk(eventBuffer)
            eventBuffer = ''
            hasEventData = false
          }
        }
      }
    }
  } finally {
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
