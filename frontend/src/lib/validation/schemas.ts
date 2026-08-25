/**
 * Zod schemas for API response validation.
 *
 * All external data from the backend must be validated through these schemas.
 * Schemas are co-located here to ensure single source of truth and DRY compliance.
 *
 * @see {@link ./validate.ts} for validation utilities
 */

import { z } from "zod/v4";

// The deployed CSP (`app.content-security-policy`) has no 'unsafe-eval', so
// Zod's JIT fast path can never activate; without `jitless` its cached
// `allowsEval` probe calls `new Function("")` once per page load, which the
// browser reports as a `securitypolicyviolation` even though Zod swallows the
// throw (zod/v4/core/util.cjs `allowsEval`).
z.config({ jitless: true });

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

/** Identifies the non-fatal citation conversion warning emitted by SSE streams. */
export const CITATION_PARTIAL_FAILURE_STATUS_CODE = "citation.partial-failure";

const CITATION_PARTIAL_FAILURE_STATUS_RETRYABLE = false;
const CITATION_PARTIAL_FAILURE_STATUS_STAGE = "citation";

/** Validates citation partial-failure statuses before they enter durable UI state. */
export const CitationPartialFailureStatusSchema = z.object({
  ...sseEventFieldShape,
  code: z.literal(CITATION_PARTIAL_FAILURE_STATUS_CODE).brand<"CitationPartialFailureStatusCode">(),
  retryable: z
    .literal(CITATION_PARTIAL_FAILURE_STATUS_RETRYABLE)
    .brand<"CitationPartialFailureStatusRetryable">(),
  stage: z
    .literal(CITATION_PARTIAL_FAILURE_STATUS_STAGE)
    .brand<"CitationPartialFailureStatusStage">(),
});

/** Generic status message for status codes without specialized UI behavior. */
const GenericStreamStatusSchema = z
  .object(sseEventFieldShape)
  .refine(
    (streamStatus) => streamStatus.code !== CITATION_PARTIAL_FAILURE_STATUS_CODE,
    "Citation partial-failure statuses must satisfy their required fields",
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
// Contact Form Schemas
// =============================================================================

/** Longest name accepted by POST /api/contact; mirrored in the form's maxlength. */
export const CONTACT_NAME_MAX_LENGTH = 100;

/** Longest message body accepted by POST /api/contact; mirrored in the form's maxlength. */
export const CONTACT_MESSAGE_MAX_LENGTH = 5000;

const CONTACT_NAME_REQUIRED_MESSAGE = "Enter your name";
const CONTACT_NAME_TOO_LONG_MESSAGE = `Name must be ${CONTACT_NAME_MAX_LENGTH} characters or fewer`;
const CONTACT_EMAIL_INVALID_MESSAGE = "Enter a valid email address";
const CONTACT_MESSAGE_REQUIRED_MESSAGE = "Enter a message";
const CONTACT_MESSAGE_TOO_LONG_MESSAGE = `Message must be ${CONTACT_MESSAGE_MAX_LENGTH} characters or fewer`;

/**
 * Submission contract for POST /api/contact.
 *
 * `website` is a spam honeypot: the form positions the field off-screen so
 * humans leave it empty, and a filled value marks the sender as a bot.
 * `renderedAt` (epoch ms captured when the form renders) lets the backend
 * reject submissions that arrive faster than a human can type.
 */
export const ContactSubmissionSchema = z.object({
  name: z
    .string()
    .trim()
    .min(1, { error: CONTACT_NAME_REQUIRED_MESSAGE })
    .max(CONTACT_NAME_MAX_LENGTH, {
      error: CONTACT_NAME_TOO_LONG_MESSAGE,
    }),
  email: z.email({ error: CONTACT_EMAIL_INVALID_MESSAGE }),
  message: z
    .string()
    .trim()
    .min(1, { error: CONTACT_MESSAGE_REQUIRED_MESSAGE })
    .max(CONTACT_MESSAGE_MAX_LENGTH, {
      error: CONTACT_MESSAGE_TOO_LONG_MESSAGE,
    }),
  website: z.string(),
  renderedAt: z.int().positive(),
});

/** Acknowledgement returned by POST /api/contact alongside HTTP 202. */
export const ContactAcceptedSchema = z.object({
  status: z.literal("accepted"),
});

// =============================================================================
// CLI Authorization Schemas
// =============================================================================

/** Validates the loopback callback requested by the JavaChat CLI login flow. */
export const CliAuthorizationQuerySchema = z.object({
  port: z.coerce.number().int().min(1).max(65_535),
  state: z.string().regex(/^[A-Za-z0-9_-]{32,128}$/),
  label: z.string().trim().min(1).max(64),
});

/** Validates Clerk's one-time API-key creation response before the secret leaves the SDK boundary. */
export const CliApiKeyCreationSchema = z.object({
  secret: z.string().startsWith("ak_").max(512),
});

// =============================================================================
// Local Storage Schemas
// =============================================================================

/**
 * Color-scheme choices offered by the theme toggle. `system` defers to the
 * OS-level `prefers-color-scheme` media query; `light`/`dark` are explicit
 * user overrides persisted in localStorage.
 */
export const ThemePreferenceSchema = z.enum(["system", "light", "dark"]);

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
export type ContactSubmission = z.infer<typeof ContactSubmissionSchema>;
export type ContactAccepted = z.infer<typeof ContactAcceptedSchema>;
export type ApiErrorResponse = z.infer<typeof ApiErrorResponseSchema>;
export type ThemePreference = z.infer<typeof ThemePreferenceSchema>;
