import { describe, expect, it } from "vitest";
import {
  CITATION_PARTIAL_FAILURE_STATUS_CODE,
  CitationPartialFailureStatusSchema,
} from "../validation/schemas";
import { validateWithSchema } from "../validation/validate";
import { createStreamingState } from "./createStreamingState.svelte";

function validatedCitationWarning() {
  const citationWarningValidation = validateWithSchema(
    CitationPartialFailureStatusSchema,
    {
      message: "Some citations could not be loaded (1 failed)",
      details: "Citations could not be loaded",
      code: CITATION_PARTIAL_FAILURE_STATUS_CODE,
      retryable: false,
      stage: "citation",
    },
    "createStreamingState citation warning fixture",
  );

  if (!citationWarningValidation.success) {
    throw new Error("Expected the citation warning fixture to satisfy its canonical schema");
  }

  return citationWarningValidation.validated;
}

describe("createStreamingState citation warnings", () => {
  it("preserves a citation warning through stream completion and clears it for the next stream", () => {
    const streamingState = createStreamingState();
    const citationWarning = validatedCitationWarning();

    streamingState.startStream();
    streamingState.updateStatus(citationWarning);
    streamingState.finishStream();

    expect(streamingState.isStreaming).toBe(false);
    expect(streamingState.statusMessage).toBe("");
    expect(streamingState.citationWarning).toEqual(citationWarning);

    streamingState.startStream();

    expect(streamingState.citationWarning).toBeNull();
    streamingState.cleanup();
  });

  it("removes a citation warning when the active stream fails", () => {
    const streamingState = createStreamingState();

    streamingState.startStream();
    streamingState.updateStatus(validatedCitationWarning());
    streamingState.failStream();

    expect(streamingState.isStreaming).toBe(false);
    expect(streamingState.statusMessage).toBe("");
    expect(streamingState.statusDetails).toBe("");
    expect(streamingState.citationWarning).toBeNull();
    streamingState.cleanup();
  });
});
