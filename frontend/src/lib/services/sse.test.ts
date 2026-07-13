import { afterEach, describe, expect, it, vi } from "vitest";
import { streamSse } from "./sse";

const STREAM_RESPONSE_STATUS = 200;

function createSseResponse(eventPayload: string): Response {
  const encoder = new TextEncoder();
  const responseBody = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(encoder.encode(eventPayload));
      controller.close();
    },
  });
  return new Response(responseBody, {
    status: STREAM_RESPONSE_STATUS,
    statusText: "OK",
  });
}

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
          controller.enqueue(encoder.encode('event: text\ndata: {"text":"Hello"}\n\n'));
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

describe("streamSse payload validation", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("dispatches canonical chunk payloads", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(createSseResponse('event: text\ndata: {"text":"Hello"}\n\n')),
    );
    const onText = vi.fn();

    await streamSse("/api/test/stream", {}, { onText }, "sse.test.ts");

    expect(onText).toHaveBeenCalledOnce();
    expect(onText).toHaveBeenCalledWith("Hello");
  });

  it("rejects legacy raw text payloads", async () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(createSseResponse("event: text\ndata: Hello\n\n")),
    );
    const onText = vi.fn();

    await expect(streamSse("/api/test/stream", {}, { onText }, "sse.test.ts")).rejects.toThrow(
      "Received an invalid SSE event from the server",
    );
    expect(onText).not.toHaveBeenCalled();
  });

  it("rejects text-shaped payloads with an unsupported event type", async () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(createSseResponse('event: typo\ndata: {"text":"Hello"}\n\n')),
    );
    const onText = vi.fn();

    await expect(streamSse("/api/test/stream", {}, { onText }, "sse.test.ts")).rejects.toThrow(
      "Received an invalid SSE event from the server",
    );
    expect(onText).not.toHaveBeenCalled();
  });

  it("rejects malformed status payloads", async () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(createSseResponse("event: status\ndata: Loading\n\n")),
    );
    const onText = vi.fn();
    const onStatus = vi.fn();

    await expect(
      streamSse("/api/test/stream", {}, { onText, onStatus }, "sse.test.ts"),
    ).rejects.toThrow("Received an invalid SSE event from the server");
    expect(onStatus).not.toHaveBeenCalled();
  });

  it("surfaces malformed error payloads as protocol failures", async () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(createSseResponse("event: error\ndata: Failure\n\n")),
    );
    const onText = vi.fn();
    const onError = vi.fn();

    await expect(
      streamSse("/api/test/stream", {}, { onText, onError }, "sse.test.ts"),
    ).rejects.toThrow("Received an invalid SSE event from the server");
    expect(onError).toHaveBeenCalledWith({
      message: "Received an invalid SSE event from the server",
    });
  });
});
