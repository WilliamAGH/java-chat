/**
 * Guided learning service for lesson navigation and streaming.
 *
 * @see {@link ./sse.ts} for SSE stream parsing implementation
 * @see {@link ./stream-types.ts} for shared type definitions
 */

import { streamSse } from './sse'
import type { StreamStatus } from './stream-types'
import type { Citation, CitationFetchResult } from './chat'

export type { StreamStatus }

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

/** Callbacks for guided chat streaming with explicit error handling. */
export interface GuidedStreamCallbacks {
  onChunk: (chunk: string) => void
  onStatus?: (status: StreamStatus) => void
  onError?: (error: Error) => void
  onCitations?: (citations: Citation[]) => void
  signal?: AbortSignal
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

/**
 * Fetch Think Java-only citations for a guided lesson slug.
 * Used by LearnView to render lesson sources with proper PDF page anchors.
 */
export async function fetchGuidedLessonCitations(slug: string): Promise<CitationFetchResult> {
  try {
    const response = await fetch(`/api/guided/citations?slug=${encodeURIComponent(slug)}`)
    if (!response.ok) {
      return { success: false, error: `HTTP ${response.status}: ${response.statusText}` }
    }
    const citations: Citation[] = await response.json()
    return { success: true, citations }
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : 'Network error fetching lesson sources'
    return { success: false, error: errorMessage }
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
  const { onChunk, onStatus, onError, onCitations, signal } = callbacks
  let errorNotified = false

  try {
    await streamSse(
      '/api/guided/stream',
      { sessionId, slug, latest: message },
      {
        onText: onChunk,
        onStatus,
        onCitations,
        onError: (streamError) => {
          errorNotified = true
          onError?.(new Error(streamError.message))
        }
      },
      'guided.ts',
      { signal }
    )
  } catch (error) {
    // Re-throw after invoking callback to maintain dual error propagation
    if (error instanceof Error) {
      if (!errorNotified) {
        onError?.(error)
      }
    }
    throw error
  }
}
