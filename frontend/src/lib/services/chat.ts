/**
 * Chat service for streaming conversations with the backend.
 *
 * @see {@link ./sse.ts} for SSE stream parsing implementation
 * @see {@link ../validation/schemas.ts} for type definitions
 */

import { streamSse } from './sse'
import {
  CitationsArraySchema,
  type StreamStatus,
  type StreamError,
  type Citation
} from '../validation/schemas'
import { validateFetchJson } from '../validation/validate'

export type { StreamStatus, StreamError, Citation }

export interface ChatMessage {
  /** Stable client-side identifier for rendering and list keying. */
  messageId: string
  role: 'user' | 'assistant'
  content: string
  timestamp: number
  isError?: boolean
}

export interface StreamChatOptions {
  onStatus?: (status: StreamStatus) => void
  onError?: (error: StreamError) => void
  onCitations?: (citations: Citation[]) => void
  signal?: AbortSignal
}

/** Result type for citation fetches - distinguishes empty results from errors. */
export type CitationFetchResult =
  | { success: true; citations: Citation[] }
  | { success: false; error: string }

/**
 * Stream chat response from the backend using Server-Sent Events.
 *
 * @param sessionId - Unique session identifier
 * @param message - User's message
 * @param onChunk - Callback for each streamed text chunk
 * @param options - Optional callbacks for status, error, and citation events
 */
export async function streamChat(
  sessionId: string,
  message: string,
  onChunk: (chunk: string) => void,
  options: StreamChatOptions = {}
): Promise<void> {
  await streamSse(
    '/api/chat/stream',
    { sessionId, latest: message },
    {
      onText: onChunk,
      onStatus: options.onStatus,
      onError: options.onError,
      onCitations: options.onCitations
    },
    'chat.ts',
    { signal: options.signal }
  )
}

/**
 * Clears the server-side chat memory for a session.
 *
 * @param sessionId - Session identifier to clear on the backend.
 */
export async function clearChatSession(sessionId: string): Promise<void> {
  const normalizedSessionId = sessionId.trim()
  if (!normalizedSessionId) {
    throw new Error('Session ID is required')
  }

  const response = await fetch(`/api/chat/clear?sessionId=${encodeURIComponent(normalizedSessionId)}`, {
    method: 'POST'
  })

  if (!response.ok) {
    const errorBody = await response.text().catch(() => '')
    const suffix = errorBody ? `: ${errorBody}` : ''
    throw new Error(`Failed to clear chat session (HTTP ${response.status})${suffix}`)
  }
}

/**
 * Fetch citations for a query.
 * Used by LearnView to fetch lesson-level citations separately from the chat stream.
 * Returns a Result type to distinguish between empty results and fetch failures.
 */
export async function fetchCitations(query: string): Promise<CitationFetchResult> {
  try {
    const response = await fetch(`/api/chat/citations?q=${encodeURIComponent(query)}`)
    const result = await validateFetchJson(
      response,
      CitationsArraySchema,
      `fetchCitations [query=${query}]`
    )

    if (!result.success) {
      return { success: false, error: result.error }
    }

    return { success: true, citations: result.data }
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : 'Network error fetching citations'
    console.error(`[fetchCitations] Unexpected error for query="${query}":`, error)
    return { success: false, error: errorMessage }
  }
}
