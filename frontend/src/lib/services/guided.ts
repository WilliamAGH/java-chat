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

/** Citation from guided learning context. */
export interface GuidedCitation {
  url: string
  title: string
  anchor?: string
  snippet?: string
}

/** Result type for citation fetches - distinguishes empty results from errors. */
export type CitationFetchResult =
  | { success: true; citations: GuidedCitation[] }
  | { success: false; error: string }

/**
 * Fetch citations for a guided lesson.
 * Returns a Result type to distinguish between empty results and fetch failures.
 */
export async function fetchGuidedCitations(slug: string): Promise<CitationFetchResult> {
  try {
    const response = await fetch(`/api/guided/citations?slug=${encodeURIComponent(slug)}`)
    if (!response.ok) {
      return { success: false, error: `HTTP ${response.status}: ${response.statusText}` }
    }
    const citations: GuidedCitation[] = await response.json()
    return { success: true, citations }
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : 'Network error fetching citations'
    return { success: false, error: errorMessage }
  }
}

/** Callbacks for guided chat streaming with explicit error handling. */
export interface GuidedStreamCallbacks {
  onChunk: (chunk: string) => void
  onError?: (error: Error) => void
}

/**
 * Stream a chat response within the guided lesson context.
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
  let streamCompletedNormally = false

  try {
    while (true) {
      const { done, value } = await reader.read()

      if (done) {
        streamCompletedNormally = true
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

        if (line.startsWith('data:')) {
          const payload = line.startsWith('data: ') ? line.slice(6) : line.slice(5)

          // Skip [DONE] token
          if (payload === '[DONE]') {
            continue
          }

          // Check for error markers from server
          if (payload.startsWith('[ERROR]')) {
            const serverError = new Error(payload.slice(8).trim())
            onError?.(serverError)
            throw serverError
          }

          // Emit text content
          if (payload) {
            onChunk(payload)
          }
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
