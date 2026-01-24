/**
 * Guided learning service for lesson navigation and streaming.
 *
 * @see {@link ./sse.ts} for SSE stream parsing implementation
 * @see {@link ./stream-types.ts} for shared type definitions
 */

import { streamSse } from './sse'
import type { StreamStatus } from './stream-types'

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
  const { onChunk, onStatus, onError } = callbacks

  try {
    await streamSse(
      '/api/guided/stream',
      { sessionId, slug, latest: message },
      {
        onText: onChunk,
        onStatus,
        onError: (streamError) => {
          onError?.(new Error(streamError.message))
        }
      },
      'guided.ts'
    )
  } catch (error) {
    // Re-throw after invoking callback to maintain dual error propagation
    if (error instanceof Error) {
      onError?.(error)
    }
    throw error
  }
}
