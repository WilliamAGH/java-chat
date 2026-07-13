import { afterEach, describe, expect, it, vi } from "vitest";
import { streamSse } from "./sse";

const SSE_STREAM_RESPONSE_STATUS = 200;
const FETCH_FAILURE_MESSAGE = "Network request failed";
const STREAM_READ_FAILURE_MESSAGE = "Unable to read the SSE stream";

function createSseStreamResponse(sseWireText: string): Response {
  const encoder = new TextEncoder();
  const sseStreamBody = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(encoder.encode(sseWireText));
      controller.close();
    },
  });
  return new Response(sseStreamBody, {
    status: SSE_STREAM_RESPONSE_STATUS,
    statusText: "OK",
  });
}

describe("streamSse transport handling", () => {
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

  it("reports and rejects a non-abort fetch failure exactly once", async () => {
    const fetchFailure = new Error(FETCH_FAILURE_MESSAGE);
    const fetchMock = vi.fn().mockRejectedValue(fetchFailure);
    vi.stubGlobal("fetch", fetchMock);

    const onText = vi.fn();
    const onError = vi.fn();

    await expect(
      streamSse("/api/test/stream", { hello: "world" }, { onText, onError }, "sse.test.ts"),
    ).rejects.toBe(fetchFailure);

    expect(fetchMock).toHaveBeenCalledOnce();
    expect(onText).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledOnce();
    expect(onError).toHaveBeenCalledWith({ message: FETCH_FAILURE_MESSAGE });
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

  it("reports and rejects a stream-read failure exactly once", async () => {
    const streamReadFailure = new Error(STREAM_READ_FAILURE_MESSAGE);
    const sseStreamBody = new ReadableStream<Uint8Array>({
      start(streamController) {
        streamController.error(streamReadFailure);
      },
    });
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(sseStreamBody, {
        status: SSE_STREAM_RESPONSE_STATUS,
        statusText: "OK",
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    const onText = vi.fn();
    const onError = vi.fn();

    await expect(
      streamSse("/api/test/stream", { hello: "world" }, { onText, onError }, "sse.test.ts"),
    ).rejects.toBe(streamReadFailure);

    expect(fetchMock).toHaveBeenCalledOnce();
    expect(onText).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledOnce();
    expect(onError).toHaveBeenCalledWith({ message: STREAM_READ_FAILURE_MESSAGE });
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
      vi.fn().mockResolvedValue(createSseStreamResponse('event: text\ndata: {"text":"Hello"}\n\n')),
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
      vi.fn().mockResolvedValue(createSseStreamResponse("event: text\ndata: Hello\n\n")),
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
      vi.fn().mockResolvedValue(createSseStreamResponse('event: typo\ndata: {"text":"Hello"}\n\n')),
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
      vi.fn().mockResolvedValue(createSseStreamResponse("event: status\ndata: Loading\n\n")),
    );
    const onText = vi.fn();
    const onStatus = vi.fn();

    await expect(
      streamSse("/api/test/stream", {}, { onText, onStatus }, "sse.test.ts"),
    ).rejects.toThrow("Received an invalid SSE event from the server");
    expect(onStatus).not.toHaveBeenCalled();
  });

  it("terminates the stream when a provider payload fails validation", async () => {
    const consoleError = vi.spyOn(console, "error").mockImplementation(() => undefined);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(createSseStreamResponse("event: provider\ndata: {}\n\n")),
    );
    const onText = vi.fn();
    const onError = vi.fn();
    const onProvider = vi.fn();

    await expect(
      streamSse("/api/test/stream", {}, { onText, onError, onProvider }, "sse.test.ts"),
    ).rejects.toThrow("Received an invalid SSE event from the server");

    expect(onProvider).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledWith({
      message: "Received an invalid SSE event from the server",
    });
    expect(consoleError).toHaveBeenCalledWith(
      expect.stringContaining("validateWithSchema [sse.test.ts:provider]"),
    );
  });

  it("surfaces malformed error payloads as protocol failures", async () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(createSseStreamResponse("event: error\ndata: Failure\n\n")),
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

  it("rejects malformed JSON-shaped text payloads", async () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(createSseStreamResponse('event: text\ndata: {"text":\n\n')),
    );
    const onText = vi.fn();
    const onError = vi.fn();

    await expect(
      streamSse("/api/test/stream", {}, { onText, onError }, "sse.test.ts"),
    ).rejects.toThrow("Received an invalid SSE event from the server");
    expect(onText).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledWith({
      message: "Received an invalid SSE event from the server",
    });
  });
});
