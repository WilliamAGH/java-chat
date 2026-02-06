/**
 * Guided learning service for lesson navigation and streaming.
 *
 * @see {@link ./sse.ts} for SSE stream parsing implementation
 * @see {@link ../validation/schemas.ts} for type definitions
 */

import { streamSse } from './sse'
import {
  GuidedTOCSchema,
  GuidedLessonSchema,
  LessonContentResponseSchema,
  CitationsArraySchema,
  type StreamStatus,
  type StreamError,
  type Citation,
  type GuidedLesson,
  type LessonContentResponse
} from '../validation/schemas'
import { validateFetchJson } from '../validation/validate'
import type { CitationFetchResult } from './chat'
import {
  buildStreamRecoverySucceededStatus,
  buildStreamRetryStatus,
  MAX_STREAM_RECOVERY_RETRIES,
  shouldRetryStreamRequest,
  toStreamError,
  toStreamFailureException
} from './streamRecovery'

export type { StreamStatus, GuidedLesson, LessonContentResponse }

/** Callbacks for guided chat streaming with explicit error handling. */
export interface GuidedStreamCallbacks {
  onChunk: (chunk: string) => void
  onStatus?: (status: StreamStatus) => void
  onError?: (error: StreamError) => void
  onCitations?: (citations: Citation[]) => void
  signal?: AbortSignal
}

/**
 * Fetch the table of contents for guided learning.
 * Validates response structure via Zod schema.
 *
 * @throws Error if fetch fails or validation fails
 */
export async function fetchTOC(): Promise<GuidedLesson[]> {
  const response = await fetch('/api/guided/toc')
  const result = await validateFetchJson(response, GuidedTOCSchema, 'fetchTOC [/api/guided/toc]')

  if (!result.success) {
    throw new Error(`Failed to fetch TOC: ${result.error}`)
  }

  return result.data
}

/**
 * Fetch a single lesson's metadata.
 * Validates response structure via Zod schema.
 *
 * @throws Error if fetch fails or validation fails
 */
export async function fetchLesson(slug: string): Promise<GuidedLesson> {
  const response = await fetch(`/api/guided/lesson?slug=${encodeURIComponent(slug)}`)
  const result = await validateFetchJson(
    response,
    GuidedLessonSchema,
    `fetchLesson [slug=${slug}]`
  )

  if (!result.success) {
    throw new Error(`Failed to fetch lesson: ${result.error}`)
  }

  return result.data
}

/**
 * Fetch lesson content as markdown (non-streaming).
 * Validates response structure via Zod schema.
 *
 * @throws Error if fetch fails or validation fails
 */
export async function fetchLessonContent(slug: string): Promise<LessonContentResponse> {
  const response = await fetch(`/api/guided/content?slug=${encodeURIComponent(slug)}`)
  const result = await validateFetchJson(
    response,
    LessonContentResponseSchema,
    `fetchLessonContent [slug=${slug}]`
  )

  if (!result.success) {
    throw new Error(`Failed to fetch lesson content: ${result.error}`)
  }

  return result.data
}

/**
 * Fetch Think Java-only citations for a guided lesson slug.
 * Used by LearnView to render lesson sources with proper PDF page anchors.
 */
export async function fetchGuidedLessonCitations(slug: string): Promise<CitationFetchResult> {
  try {
    const response = await fetch(`/api/guided/citations?slug=${encodeURIComponent(slug)}`)
    const result = await validateFetchJson(
      response,
      CitationsArraySchema,
      `fetchGuidedLessonCitations [slug=${slug}]`
    )

    if (!result.success) {
      return { success: false, error: result.error }
    }

    return { success: true, citations: result.data }
  } catch (error) {
    const errorMessage = error instanceof Error ? error.message : 'Network error fetching lesson sources'
    console.error(`[fetchGuidedLessonCitations] Unexpected error for slug="${slug}":`, error)
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
  let attemptedRecoveryRetries = 0
  let hasPendingRecoverySuccessNotice = false

  while (true) {
    let hasStreamedAnyChunk = false
    let streamErrorEvent: StreamError | null = null

    try {
      await streamSse(
        '/api/guided/stream',
        { sessionId, slug, latest: message },
        {
          onText: (chunk) => {
            hasStreamedAnyChunk = true
            if (hasPendingRecoverySuccessNotice) {
              onStatus?.(buildStreamRecoverySucceededStatus(attemptedRecoveryRetries))
              hasPendingRecoverySuccessNotice = false
            }
            onChunk(chunk)
          },
          onStatus,
          onCitations,
          onError: (streamError) => {
            streamErrorEvent = streamError
          }
        },
        'guided.ts',
        { signal }
      )
      return
    } catch (streamFailure) {
      if (
        shouldRetryStreamRequest(
          streamFailure,
          streamErrorEvent,
          hasStreamedAnyChunk,
          attemptedRecoveryRetries,
          MAX_STREAM_RECOVERY_RETRIES
        )
      ) {
        attemptedRecoveryRetries++
        hasPendingRecoverySuccessNotice = true
        onStatus?.(buildStreamRetryStatus(attemptedRecoveryRetries, MAX_STREAM_RECOVERY_RETRIES))
        continue
      }
      const streamError = toStreamError(streamFailure, streamErrorEvent)
      onError?.(streamError)
      throw toStreamFailureException(streamFailure, streamErrorEvent)
    }
  }
}
