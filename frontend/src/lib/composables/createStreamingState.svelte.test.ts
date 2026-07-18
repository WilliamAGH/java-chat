import { describe, expect, expectTypeOf, it } from "vitest";
import { createCitationPartialFailureStatusFixture } from "../../test/citationPartialFailureStatus";
import type { CitationPartialFailureStatus } from "../validation/schemas";
import { createStreamingState } from "./createStreamingState.svelte";

describe("createStreamingState citation warnings", () => {
  it("keeps validated citation contract fields nominally discriminated", () => {
    expectTypeOf<CitationPartialFailureStatus["code"]>().not.toEqualTypeOf<string>();
    expectTypeOf<CitationPartialFailureStatus["stage"]>().not.toEqualTypeOf<string>();
    expectTypeOf<CitationPartialFailureStatus["retryable"]>().not.toEqualTypeOf<boolean>();
  });

  it("preserves a citation warning through stream completion and clears it for the next stream", () => {
    const streamingState = createStreamingState();
    const citationWarning = createCitationPartialFailureStatusFixture();

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
    streamingState.updateStatus(createCitationPartialFailureStatusFixture());
    streamingState.failStream();

    expect(streamingState.isStreaming).toBe(false);
    expect(streamingState.statusMessage).toBe("");
    expect(streamingState.statusDetails).toBe("");
    expect(streamingState.citationWarning).toBeNull();
    streamingState.cleanup();
  });
});
