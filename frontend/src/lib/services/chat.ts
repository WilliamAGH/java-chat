/**
 * Chat service for streaming conversations with the backend.
 *
 * @see {@link ./sse.ts} for SSE stream parsing implementation
 * @see {@link ./stream-types.ts} for shared type definitions
 */

import { streamSse } from './sse'
import type { StreamStatus, StreamError } from './stream-types'

export type { StreamStatus, StreamError }

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

export interface StreamChatOptions {
  onStatus?: (status: StreamStatus) => void
  onError?: (error: StreamError) => void
  onCitations?: (citations: Citation[]) => void
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
    'chat.ts'
  )
}

/**
 * Fetch citations for a query.
 * Used by LearnView to fetch lesson-level citations separately from the chat stream.
 * Returns a Result type to distinguish between empty results and fetch failures.
 */
export async function fetchCitations(query: string): Promise<CitationFetchResult> {
  try {
    const response = await fetch(`/api/chat/citations?q=${encodeURIComponent(query)}`)
    if (!response.ok) {
      return { success: false, error: `HTTP ${response.status}: ${response.statusText}` }
    }
    const citations: Citation[] = await response.json()
    return { success: true, citations }
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : 'Network error fetching citations'
    return { success: false, error: errorMessage }
  }
}
