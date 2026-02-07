/**
 * Validation utilities for parsing external API responses.
 *
 * Provides type-safe validation with comprehensive error logging.
 * Never swallows errors - every validation failure is logged with full context.
 *
 * @see {@link ./schemas.ts} for schema definitions
 * @see {@link docs/type-safety-zod-validation.md} for validation patterns
 */

import { z } from 'zod/v4'

// =============================================================================
// Result Types (Discriminated Unions)
// =============================================================================

/** Success result with validated data. */
interface ValidationSuccess<T> {
  success: true
  validated: T
}

/** Failure result with Zod error. */
interface ValidationFailure {
  success: false
  error: z.ZodError
}

/** Discriminated union - never null, always explicit success/failure. */
export type ValidationResult<T> = ValidationSuccess<T> | ValidationFailure

// =============================================================================
// Error Logging
// =============================================================================

/**
 * Logs Zod validation failures with full context for debugging.
 *
 * ZodError objects collapse to `{}` in browser consoles, so this extracts
 * actionable details: field paths, expected types, received values.
 *
 * @param context - Identifies which validation failed (e.g., "fetchTOC [/api/guided/toc]")
 * @param error - The caught error (may or may not be ZodError)
 * @param payload - The raw payload that failed validation
 */
export function logZodFailure(context: string, error: unknown, rawInput?: unknown): void {
  const inputKeys =
    typeof rawInput === 'object' && rawInput !== null ? Object.keys(rawInput).slice(0, 20) : []

  if (error instanceof z.ZodError) {
    const issueSummaries = error.issues.slice(0, 10).map((issue) => {
      const path = issue.path.length > 0 ? issue.path.join('.') : '(root)'

      // Zod v4: 'input' contains the failing value, 'received' for type errors
      const inputValue = 'input' in issue ? issue.input : undefined
      const receivedValue = 'received' in issue ? issue.received : undefined
      const actualValue = receivedValue ?? inputValue

      const received = actualValue !== undefined ? ` (received: ${JSON.stringify(actualValue)})` : ''
      const expected = 'expected' in issue ? ` (expected: ${issue.expected})` : ''

      return `  - ${path}: ${issue.message}${expected}${received}`
    })

    // Log as readable string - NOT collapsed object
    console.error(
      `[Zod] ${context} validation failed\n` +
        `Issues:\n${issueSummaries.join('\n')}\n` +
        `Payload keys: ${inputKeys.join(', ')}`
    )

    // Full details for deep debugging
    console.error(`[Zod] ${context} - full details:`, {
      prettifiedError: z.prettifyError(error),
      issues: error.issues,
      rawInput
    })
  } else {
    console.error(`[Zod] ${context} validation failed (non-ZodError):`, error)
  }
}

// =============================================================================
// Validation Functions
// =============================================================================

/**
 * Validates unknown data against a Zod schema with full error logging.
 *
 * Returns a discriminated union - never null, never throws.
 * Every failure is logged with record identifier and full context.
 *
 * @param schema - Zod schema to validate against
 * @param payload - Unknown data to validate (typically from JSON.parse or fetch)
 * @param recordId - Identifier for error logs (e.g., slug, URL, endpoint name)
 * @returns Discriminated union with validated data or error
 */
export function validateWithSchema<T>(
  schema: z.ZodType<T>,
  rawInput: unknown,
  recordId: string
): ValidationResult<T> {
  const result = schema.safeParse(rawInput)

  if (!result.success) {
    logZodFailure(`validateWithSchema [${recordId}]`, result.error, rawInput)
    return { success: false, error: result.error }
  }

  return { success: true, validated: result.data }
}

/**
 * Validates JSON from a fetch response with comprehensive error handling.
 *
 * Handles both network/HTTP errors and validation failures.
 * Returns discriminated union - never throws, never swallows errors.
 *
 * @param response - Fetch Response object
 * @param schema - Zod schema to validate the JSON body
 * @param recordId - Identifier for error logs
 * @returns Discriminated union with validated data or error message
 */
export async function validateFetchJson<T>(
  response: Response,
  schema: z.ZodType<T>,
  recordId: string
): Promise<{ success: true; validated: T } | { success: false; error: string }> {
  if (!response.ok) {
    const errorMessage = `HTTP ${response.status}: ${response.statusText}`
    console.error(`[Fetch] ${recordId} failed: ${errorMessage}`)
    return { success: false, error: errorMessage }
  }

  let fetchedJson: unknown
  try {
    fetchedJson = await response.json()
  } catch (parseError) {
    const errorMessage = `JSON parse failed: ${parseError instanceof Error ? parseError.message : String(parseError)}`
    console.error(`[Fetch] ${recordId} ${errorMessage}`)
    return { success: false, error: errorMessage }
  }

  const validationResult = validateWithSchema(schema, fetchedJson, recordId)

  if (!validationResult.success) {
    return { success: false, error: `Validation failed for ${recordId}` }
  }

  return { success: true, validated: validationResult.validated }
}

/**
 * Type guard to check if a value is a plain object (not null, not array).
 *
 * Use this instead of unsafe `as Record<string, unknown>` casts.
 */
export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
