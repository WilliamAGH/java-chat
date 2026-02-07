/**
 * Zod schemas for API response validation.
 *
 * All external data from the backend must be validated through these schemas.
 * Schemas are co-located here to ensure single source of truth and DRY compliance.
 *
 * @see {@link ./validate.ts} for validation utilities
 * @see {@link docs/type-safety-zod-validation.md} for validation patterns
 */

import { z } from 'zod/v4'

// =============================================================================
// SSE Stream Event Schemas
// =============================================================================

/** Shared field shape for SSE status and error event payloads. */
const sseEventFieldShape = {
  message: z.string(),
  details: z.string().nullish(),
  code: z.string().nullish(),
  retryable: z.boolean().nullish(),
  provider: z.string().nullish(),
  stage: z.string().nullish(),
  attempt: z.number().int().positive().nullish(),
  maxAttempts: z.number().int().positive().nullish()
}

/** Status message from SSE status events. */
export const StreamStatusSchema = z.object(sseEventFieldShape)

/** Error response from SSE error events. */
export const StreamErrorSchema = z.object(sseEventFieldShape)

/** Text event payload wrapper. */
export const TextEventPayloadSchema = z.object({
  text: z.string()
})

/** Provider metadata from SSE provider events. */
export const ProviderEventSchema = z.object({
  provider: z.string()
})

// =============================================================================
// Citation Schemas
// =============================================================================

/** Single citation from search results or lesson sources. */
export const CitationSchema = z.object({
  url: z.string(),
  title: z.string(),
  anchor: z.string().optional(),
  snippet: z.string().optional()
})

/** Array of citations from citation endpoints. */
export const CitationsArraySchema = z.array(CitationSchema)

// =============================================================================
// Guided Learning Schemas
// =============================================================================

/** Lesson metadata from the guided learning TOC. */
export const GuidedLessonSchema = z.object({
  slug: z.string(),
  title: z.string(),
  summary: z.string(),
  keywords: z.array(z.string())
})

/** Array of lessons for TOC endpoint. */
export const GuidedTOCSchema = z.array(GuidedLessonSchema)

/** Response from the lesson content endpoint. */
export const LessonContentResponseSchema = z.object({
  markdown: z.string(),
  cached: z.boolean()
})

// =============================================================================
// Error Response Schemas
// =============================================================================

/** Standard API error response payload. */
export const ApiErrorResponseSchema = z.object({
  status: z.string(),
  message: z.string(),
  details: z.string().nullable().optional()
})

// =============================================================================
// Inferred Types (export for service layer)
// =============================================================================

export type StreamStatus = z.infer<typeof StreamStatusSchema>
export type StreamError = z.infer<typeof StreamErrorSchema>
export type TextEventPayload = z.infer<typeof TextEventPayloadSchema>
export type ProviderEvent = z.infer<typeof ProviderEventSchema>
export type Citation = z.infer<typeof CitationSchema>
export type GuidedLesson = z.infer<typeof GuidedLessonSchema>
export type LessonContentResponse = z.infer<typeof LessonContentResponseSchema>
export type ApiErrorResponse = z.infer<typeof ApiErrorResponseSchema>
