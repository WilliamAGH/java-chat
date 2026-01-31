# Zod v4 Validation, Error Handling, and Schema Design Guide

This document establishes **mandatory** patterns for runtime validation of external data in the frontend. All external data (API responses, SSE events, localStorage) MUST be validated through Zod schemas before use. These are strict requirements, not guidelines.

## Core Principles (Mandatory)

1. **External data is `unknown` until validated** - Never trust API responses
2. **Never swallow errors** - Every validation failure MUST be logged with full context
3. **Discriminated unions, not null** - Return `{ success: true, data }` or `{ success: false, error }`, never `null`
4. **Record identification is mandatory** - Every error log MUST identify WHICH record failed

## Import Pattern

```typescript
import { z } from 'zod/v4'
```

## Architecture

### Schema Location

All schemas live in a single source of truth:

```text
frontend/src/lib/validation/
├── schemas.ts    # All Zod schemas and inferred types
└── validate.ts   # Validation utilities and error logging
```

### Type Inference

Types are **inferred from schemas**, not duplicated:

```typescript
// schemas.ts
export const CitationSchema = z.object({
  url: z.string(),
  title: z.string(),
  anchor: z.string().optional(),
  snippet: z.string().optional()
})

// Type is INFERRED, not manually written
export type Citation = z.infer<typeof CitationSchema>
```

## Schema Design

### Optional vs Nullable - Know the Difference

```typescript
// optional() - field may be OMITTED (undefined), but null fails
field: z.string().optional()     // OK: undefined, FAIL: null

// nullable() - field accepts null, but MUST be present  
field: z.string().nullable()     // OK: null, FAIL: omitted

// nullish() - field may be omitted OR be null
field: z.string().nullish()      // OK: undefined, OK: null
```

**Rule**: Match the API contract exactly. Check real API responses before writing schemas.

### Schema Composition (Zod v4)

```typescript
// PREFERRED: .shape spread with z.looseObject()
const CombinedSchema = z.looseObject({
  ...BaseSchema.shape,
  ...ExtensionSchema.shape,
})

// DEPRECATED: .merge() is deprecated in Zod v4
```

## Validation Pattern - Never Swallow Errors

### Canonical Result Type

```typescript
type ValidationResult<T> = 
  | { success: true; data: T } 
  | { success: false; error: z.ZodError }
```

### Correct Fetch Validation

```typescript
export async function fetchTOC(): Promise<GuidedLesson[]> {
  const response = await fetch('/api/guided/toc')
  const result = await validateFetchJson(
    response,
    GuidedTOCSchema,
    'fetchTOC [/api/guided/toc]'  // Record identifier for debugging
  )

  if (!result.success) {
    throw new Error(`Failed to fetch TOC: ${result.error}`)
  }

  return result.data
}
```

### FORBIDDEN Patterns

```typescript
// FORBIDDEN: parse() throws and crashes rendering
const data = schema.parse(raw)

// FORBIDDEN: silent fallback swallows errors
const data = schema.safeParse(raw).data ?? defaultValue

// FORBIDDEN: empty catch hides failures  
try { schema.parse(raw) } catch { return null }

// FORBIDDEN: no record identifier - can't debug
logZodFailure('parseResponse', error, raw)  // Which record??

// FORBIDDEN: unsafe type assertion
const data = response as MyType
```

## Error Logging - Full Context Required

ZodError objects collapse to `{}` in browser consoles. The `logZodFailure` utility extracts actionable details:

### Expected Output

When validation fails, logs should show:

```text
[Zod] fetchTOC [/api/guided/toc] validation failed
Issues:
  - 0.slug: Invalid input (expected: string) (received: null)
  - 2.keywords: Expected array, received string
Payload keys: length, 0, 1, 2, 3

[Zod] fetchTOC [/api/guided/toc] - full details:
  prettifiedError: <human-readable string>
  issues: [...]
  payload: {...}
```

This tells you:
- **Which endpoint**: `/api/guided/toc`
- **Which field**: `0.slug` (first item's slug)
- **What was expected**: `string`
- **What was received**: `null`

## Type Guards (No Assertions)

```typescript
// FORBIDDEN: unsafe type assertions
const data = response as MyType
const value = (obj as { field: string }).field

// CORRECT: Zod validation for external data
const result = MyTypeSchema.safeParse(response)
if (result.success) {
  // result.data is properly typed
}

// CORRECT: type guard for narrowing
function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v)
}
```

## Zod v4 Built-in Utilities

```typescript
// Error formatting
z.prettifyError(error)    // Human-readable multi-line string
z.flattenError(error)     // Flat object for form field errors

// String format validators
z.email()                 // Validates email format
z.uuid()                  // Validates UUID format
z.url()                   // Validates URL format
z.iso.datetime()          // Validates ISO datetime strings

// Coercion (with explicit error handling)
const numResult = z.coerce.number().safeParse(input)
if (!numResult.success) {
  // Handle coercion failure - don't ignore it
}
```

## Adding New API Endpoints

When adding a new fetch function:

1. **Add schema to `schemas.ts`**:
   ```typescript
   export const NewResponseSchema = z.object({
     id: z.string(),
     name: z.string(),
     createdAt: z.string()  // or z.iso.datetime() if validated
   })
   
   export type NewResponse = z.infer<typeof NewResponseSchema>
   ```

2. **Use `validateFetchJson` in service**:
   ```typescript
   export async function fetchNewThing(id: string): Promise<NewResponse> {
     const response = await fetch(`/api/thing/${id}`)
     const result = await validateFetchJson(
       response,
       NewResponseSchema,
       `fetchNewThing [id=${id}]`
     )
     
     if (!result.success) {
       throw new Error(`Failed to fetch thing: ${result.error}`)
     }
     
     return result.data
   }
   ```

3. **Never duplicate type definitions** - Use the inferred type from the schema

## Checklist Before Shipping

- [ ] All external data validated with `safeParse()` (never `parse()`)
- [ ] Validation failures return discriminated union `{ success, data/error }`
- [ ] Error logs include record identifier (slug, ID, URL, endpoint name)
- [ ] Error logs show: issue path, expected type, received value
- [ ] Schema optional/nullable matches actual API contract
- [ ] No `as` type assertions for external data
- [ ] No silent fallbacks (`?? defaultValue` after safeParse)
- [ ] Types inferred from schemas, not duplicated

## Summary

**Zero silent failures is mandatory.** Every validation error MUST tell you:
1. WHAT failed (which field path)
2. WHY it failed (expected vs received)
3. WHICH record (identifier for debugging)
4. The full payload structure (for context)

Never guess what went wrong. Always know exactly.
