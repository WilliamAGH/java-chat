/**
 * Zod schemas for API response validation.
 *
 * All external data from the backend must be validated through these schemas.
 * Schemas are co-located here to ensure single source of truth and DRY compliance.
 *
 * @see {@link ./validate.ts} for validation utilities
 * @see {@link docs/type-safety-zod-validation.md} for validation patterns
 */

import { z } from "zod/v4";
import sseStatusContracts from "../../../../src/main/resources/sse-status-contracts.json";

// =============================================================================
// SSE Stream Event Schemas
// =============================================================================

/** Shared field shape for SSE status and error event payloads. */
const sseEventFieldShape = {
  message: z.string(),
  details: z.string().nullish(),
  code: z.string().nullish(),
  retryable: z.boolean().nullish(),
  stage: z.string().nullish(),
};

/** Frontend projection of the canonical citation partial-failure contract. */
export const CITATION_PARTIAL_FAILURE_STATUS_CONTRACT = sseStatusContracts.citationPartialFailure;

/** Validates citation partial-failure statuses before they enter durable UI state. */
export const CitationPartialFailureStatusSchema = z.object({
  ...sseEventFieldShape,
  code: z
    .literal(CITATION_PARTIAL_FAILURE_STATUS_CONTRACT.code)
    .brand<"CitationPartialFailureStatusCode">(),
  retryable: z
    .literal(CITATION_PARTIAL_FAILURE_STATUS_CONTRACT.retryable)
    .brand<"CitationPartialFailureStatusRetryable">(),
  stage: z
    .literal(CITATION_PARTIAL_FAILURE_STATUS_CONTRACT.stage)
    .brand<"CitationPartialFailureStatusStage">(),
});

/** Generic status message for status codes without specialized UI behavior. */
const GenericStreamStatusSchema = z
  .object(sseEventFieldShape)
  .refine(
    (streamStatus) => streamStatus.code !== CITATION_PARTIAL_FAILURE_STATUS_CONTRACT.code,
    "Citation partial-failure statuses must satisfy their specialized contract",
  );

/** Status message from SSE status events. */
export const StreamStatusSchema = z.union([
  CitationPartialFailureStatusSchema,
  GenericStreamStatusSchema,
]);

/** Error response from SSE error events. */
export const StreamErrorSchema = z.object(sseEventFieldShape);

/** Canonical text chunk emitted by SSE streams. */
export const TextChunkSchema = z.object({
  text: z.string(),
});

/** Provider metadata from SSE provider events. */
export const ProviderEventSchema = z.object({
  provider: z.string(),
});

// =============================================================================
// Citation Schemas
// =============================================================================

/** Single citation from search results or lesson sources. */
export const CitationSchema = z.object({
  url: z.string(),
  title: z.string(),
  anchor: z.string().optional(),
  snippet: z.string().optional(),
});

/** Array of citations from citation endpoints. */
export const CitationsArraySchema = z.array(CitationSchema);

// =============================================================================
// Guided Learning Schemas
// =============================================================================

/** Lesson metadata from the guided learning TOC. */
export const GuidedLessonSchema = z.object({
  slug: z.string(),
  title: z.string(),
  summary: z.string(),
  keywords: z.array(z.string()),
  technology: z.string(),
  docSet: z.array(z.string()),
});

/** Array of lessons for TOC endpoint. */
export const GuidedTOCSchema = z.array(GuidedLessonSchema);

/** Response from the lesson content endpoint. */
export const LessonContentResponseSchema = z.object({
  markdown: z.string(),
  cached: z.boolean(),
});

// =============================================================================
// Error Response Schemas
// =============================================================================

/** Standard API error response payload. */
export const ApiErrorResponseSchema = z.object({
  status: z.string(),
  message: z.string(),
  details: z.string().nullable().optional(),
});

// =============================================================================
// Inferred Types (export for service layer)
// =============================================================================

export type StreamStatus = z.infer<typeof StreamStatusSchema>;
export type CitationPartialFailureStatus = z.infer<typeof CitationPartialFailureStatusSchema>;
export type StreamError = z.infer<typeof StreamErrorSchema>;
export type TextChunk = z.infer<typeof TextChunkSchema>;
export type ProviderEvent = z.infer<typeof ProviderEventSchema>;
export type Citation = z.infer<typeof CitationSchema>;
export type GuidedLesson = z.infer<typeof GuidedLessonSchema>;
export type LessonContentResponse = z.infer<typeof LessonContentResponseSchema>;
export type ApiErrorResponse = z.infer<typeof ApiErrorResponseSchema>;
