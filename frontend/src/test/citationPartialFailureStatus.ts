import {
  CitationPartialFailureStatusSchema,
  type CitationPartialFailureStatus,
} from "../lib/validation/schemas";
import { validateWithSchema } from "../lib/validation/validate";

/** Builds the SSE warning fixture for a nonzero citation conversion failure. */
export function createCitationPartialFailureStatusFixture(): CitationPartialFailureStatus {
  const citationWarningValidation = validateWithSchema(
    CitationPartialFailureStatusSchema,
    {
      message: "Some citations could not be loaded (1 failed)",
      details: "Citations could not be loaded",
      code: "citation.partial-failure",
      retryable: false,
      stage: "citation",
    },
    "citation partial-failure test fixture",
  );

  if (!citationWarningValidation.success) {
    throw new Error("Expected the citation partial-failure fixture to validate");
  }

  return citationWarningValidation.validated;
}
