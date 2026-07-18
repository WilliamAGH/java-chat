import {
  CITATION_PARTIAL_FAILURE_STATUS_CONTRACT,
  CitationPartialFailureStatusSchema,
  type CitationPartialFailureStatus,
} from "../lib/validation/schemas";
import { validateWithSchema } from "../lib/validation/validate";

/** Builds the shared nonzero-failure warning from the canonical SSE contract projection. */
export function createCitationPartialFailureStatusFixture(): CitationPartialFailureStatus {
  const citationWarningValidation = validateWithSchema(
    CitationPartialFailureStatusSchema,
    {
      message: "Some citations could not be loaded (1 failed)",
      details: "Citations could not be loaded",
      ...CITATION_PARTIAL_FAILURE_STATUS_CONTRACT,
    },
    "citation partial-failure test projection",
  );

  if (!citationWarningValidation.success) {
    throw new Error("Expected the canonical citation warning projection to validate");
  }

  return citationWarningValidation.validated;
}
