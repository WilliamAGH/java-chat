/**
 * Guided learning service for lesson navigation and streaming.
 *
 * @see {@link ./sse.ts} for SSE stream parsing implementation
 * @see {@link ../validation/schemas.ts} for type definitions
 */

import {
  GuidedTOCSchema,
  GuidedLessonSchema,
  LessonContentResponseSchema,
  type StreamStatus,
  type StreamError,
  type Citation,
  type GuidedLesson,
  type LessonContentResponse,
} from "../validation/schemas";
import { validateFetchJson } from "../validation/validate";
import { fetchCitationsByEndpoint, type CitationFetchResult } from "./chat";
import { streamWithRetry } from "./streamRecovery";

export type { StreamStatus, GuidedLesson, LessonContentResponse };

/** Callbacks for guided chat streaming with explicit error handling. */
export interface GuidedStreamCallbacks {
  onChunk: (chunk: string) => void;
  onStatus?: (status: StreamStatus) => void;
  onError?: (error: StreamError) => void;
  onCitations?: (citations: Citation[]) => void;
  signal?: AbortSignal;
}

/**
 * Fetch the table of contents for guided learning.
 * Validates response structure via Zod schema.
 *
 * @throws Error if fetch fails or validation fails
 */
export async function fetchTOC(): Promise<GuidedLesson[]> {
  const tocResponse = await fetch("/api/guided/toc");
  const tocValidation = await validateFetchJson(
    tocResponse,
    GuidedTOCSchema,
    "fetchTOC [/api/guided/toc]",
  );

  if (!tocValidation.success) {
    throw new Error(`Failed to fetch TOC: ${tocValidation.error}`);
  }

  return tocValidation.validated;
}

/**
 * Fetch a single lesson's metadata.
 * Validates response structure via Zod schema.
 *
 * @throws Error if fetch fails or validation fails
 */
export async function fetchLesson(slug: string): Promise<GuidedLesson> {
  const lessonResponse = await fetch(`/api/guided/lesson?slug=${encodeURIComponent(slug)}`);
  const lessonValidation = await validateFetchJson(
    lessonResponse,
    GuidedLessonSchema,
    `fetchLesson [slug=${slug}]`,
  );

  if (!lessonValidation.success) {
    throw new Error(`Failed to fetch lesson: ${lessonValidation.error}`);
  }

  return lessonValidation.validated;
}

/**
 * Fetch lesson content as markdown (non-streaming).
 * Validates response structure via Zod schema.
 *
 * @throws Error if fetch fails or validation fails
 */
export async function fetchLessonContent(slug: string): Promise<LessonContentResponse> {
  const lessonContentResponse = await fetch(`/api/guided/content?slug=${encodeURIComponent(slug)}`);
  const contentValidation = await validateFetchJson(
    lessonContentResponse,
    LessonContentResponseSchema,
    `fetchLessonContent [slug=${slug}]`,
  );

  if (!contentValidation.success) {
    throw new Error(`Failed to fetch lesson content: ${contentValidation.error}`);
  }

  return contentValidation.validated;
}

/**
 * Fetch Think Java-only citations for a guided lesson slug.
 * Used by LearnView to render lesson sources with proper PDF page anchors.
 */
export async function fetchGuidedLessonCitations(slug: string): Promise<CitationFetchResult> {
  return fetchCitationsByEndpoint(
    `/api/guided/citations?slug=${encodeURIComponent(slug)}`,
    `fetchGuidedLessonCitations [slug=${slug}]`,
  );
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
  callbacks: GuidedStreamCallbacks,
): Promise<void> {
  return streamWithRetry(
    "/api/guided/stream",
    { sessionId, slug, latest: message },
    {
      onChunk: callbacks.onChunk,
      onStatus: callbacks.onStatus,
      onError: callbacks.onError,
      onCitations: callbacks.onCitations,
      signal: callbacks.signal,
    },
    "guided.ts",
  );
}
