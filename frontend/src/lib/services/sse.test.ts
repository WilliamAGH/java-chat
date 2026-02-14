import { afterEach, describe, expect, it, vi } from "vitest";
import { streamSse } from "./sse";

describe("streamSse abort handling", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("returns without invoking callbacks when fetch is aborted", async () => {
    const abortController = new AbortController();
    abortController.abort();

    const fetchMock = vi
      .fn()
      .mockRejectedValue(Object.assign(new Error("Aborted"), { name: "AbortError" }));
    vi.stubGlobal("fetch", fetchMock);

    const onText = vi.fn();
    const onError = vi.fn();

    await streamSse("/api/test/stream", { hello: "world" }, { onText, onError }, "sse.test.ts", {
      signal: abortController.signal,
    });

    expect(onText).not.toHaveBeenCalled();
    expect(onError).not.toHaveBeenCalled();
  });

  it("treats AbortError during read as a cancellation (no onError)", async () => {
    const encoder = new TextEncoder();
    const abortError = Object.assign(new Error("Aborted"), { name: "AbortError" });
    let didEnqueue = false;

    const responseBody = new ReadableStream<Uint8Array>({
      pull(controller) {
        if (!didEnqueue) {
          didEnqueue = true;
          controller.enqueue(encoder.encode('data: {"text":"Hello"}\n\n'));
          return;
        }
        controller.error(abortError);
      },
    });

    const fetchMock = vi
      .fn()
      .mockResolvedValue({ ok: true, body: responseBody, status: 200, statusText: "OK" });
    vi.stubGlobal("fetch", fetchMock);

    const onText = vi.fn();
    const onError = vi.fn();

    await streamSse("/api/test/stream", { hello: "world" }, { onText, onError }, "sse.test.ts");

    expect(onText).toHaveBeenCalledWith("Hello");
    expect(onError).not.toHaveBeenCalled();
  });
});
