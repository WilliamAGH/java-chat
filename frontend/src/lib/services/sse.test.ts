import { afterEach, describe, expect, it, vi } from "vitest";
import { streamSse, streamSseGet } from "./sse";

const SSE_STREAM_RESPONSE_STATUS = 200;
const HTTP_SERVICE_UNAVAILABLE_STATUS = 503;
const FETCH_FAILURE_MESSAGE = "Network request failed";
const STREAM_READ_FAILURE_MESSAGE = "Unable to read the SSE stream";
const SERVER_EVENT_ERROR_MESSAGE = "The provider ended the stream";
const CITATION_WARNING_MESSAGE = "Some citations could not be loaded";
const CITATION_WARNING_DETAILS = "Citations could not be loaded";
const CITATION_WARNING_CODE = "citation.partial-failure";
const CITATION_WARNING_RETRYABLE = false;
const CITATION_WARNING_STAGE = "citation";
const SELECTED_PROVIDER_NAME = "OpenAI";
const MISSING_STREAM_BODY_MESSAGE = "No response body";

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

  it("reports and rejects a non-abort GET fetch failure exactly once", async () => {
    const fetchFailure = new Error(FETCH_FAILURE_MESSAGE);
    const fetchMock = vi.fn().mockRejectedValue(fetchFailure);
    vi.stubGlobal("fetch", fetchMock);

    const onText = vi.fn();
    const onError = vi.fn();

    await expect(streamSseGet("/api/test/stream", { onText, onError }, "sse.test.ts")).rejects.toBe(
      fetchFailure,
    );

    expect(fetchMock).toHaveBeenCalledOnce();
    expect(onText).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledOnce();
    expect(onError).toHaveBeenCalledWith({ message: FETCH_FAILURE_MESSAGE });
  });

  it("reports a non-OK GET response exactly once", async () => {
    const serviceUnavailableMessage = `HTTP ${HTTP_SERVICE_UNAVAILABLE_STATUS}: Service Unavailable`;
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(null, {
          status: HTTP_SERVICE_UNAVAILABLE_STATUS,
          statusText: "Service Unavailable",
        }),
      ),
    );

    const onText = vi.fn();
    const onError = vi.fn();

    await expect(
      streamSseGet("/api/test/stream", { onText, onError }, "sse.test.ts"),
    ).rejects.toThrow(serviceUnavailableMessage);

    expect(onText).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledOnce();
    expect(onError).toHaveBeenCalledWith({ message: serviceUnavailableMessage });
  });

  it("reports a missing GET response body exactly once", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(null, {
          status: SSE_STREAM_RESPONSE_STATUS,
          statusText: "OK",
        }),
      ),
    );

    const onText = vi.fn();
    const onError = vi.fn();

    await expect(
      streamSseGet("/api/test/stream", { onText, onError }, "sse.test.ts"),
    ).rejects.toThrow(MISSING_STREAM_BODY_MESSAGE);

    expect(onText).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledOnce();
    expect(onError).toHaveBeenCalledWith({ message: MISSING_STREAM_BODY_MESSAGE });
  });

  it("reports a valid server error event exactly once", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          createSseStreamResponse(
            `event: error\ndata: {"message":"${SERVER_EVENT_ERROR_MESSAGE}"}\n\n`,
          ),
        ),
    );

    const onText = vi.fn();
    const onError = vi.fn();

    await expect(
      streamSseGet("/api/test/stream", { onText, onError }, "sse.test.ts"),
    ).rejects.toThrow(SERVER_EVENT_ERROR_MESSAGE);

    expect(onText).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledOnce();
    expect(onError).toHaveBeenCalledWith({ message: SERVER_EVENT_ERROR_MESSAGE });
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

  it("preserves a valid structured status event", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          createSseStreamResponse(
            `event: status\ndata: {"message":"${CITATION_WARNING_MESSAGE}","details":"${CITATION_WARNING_DETAILS}","code":"${CITATION_WARNING_CODE}","retryable":${CITATION_WARNING_RETRYABLE},"stage":"${CITATION_WARNING_STAGE}"}\n\n`,
          ),
        ),
    );
    const onText = vi.fn();
    const onStatus = vi.fn();

    await streamSse("/api/test/stream", {}, { onText, onStatus }, "sse.test.ts");

    expect(onText).not.toHaveBeenCalled();
    expect(onStatus).toHaveBeenCalledOnce();
    expect(onStatus).toHaveBeenCalledWith({
      message: CITATION_WARNING_MESSAGE,
      details: CITATION_WARNING_DETAILS,
      code: CITATION_WARNING_CODE,
      retryable: CITATION_WARNING_RETRYABLE,
      stage: CITATION_WARNING_STAGE,
    });
  });

  it.each([
    {
      retryable: !CITATION_WARNING_RETRYABLE,
      stage: CITATION_WARNING_STAGE,
    },
    {
      retryable: CITATION_WARNING_RETRYABLE,
      stage: "unexpected-stage",
    },
  ])(
    "rejects a citation partial-failure status with invalid required fields",
    async ({ retryable, stage }) => {
      vi.spyOn(console, "error").mockImplementation(() => undefined);
      vi.stubGlobal(
        "fetch",
        vi
          .fn()
          .mockResolvedValue(
            createSseStreamResponse(
              `event: status\ndata: {"message":"${CITATION_WARNING_MESSAGE}","details":"${CITATION_WARNING_DETAILS}","code":"${CITATION_WARNING_CODE}","retryable":${retryable},"stage":"${stage}"}\n\n`,
            ),
          ),
      );
      const onText = vi.fn();
      const onStatus = vi.fn();

      await expect(
        streamSse("/api/test/stream", {}, { onText, onStatus }, "sse.test.ts"),
      ).rejects.toThrow("Received an invalid SSE event from the server");
      expect(onText).not.toHaveBeenCalled();
      expect(onStatus).not.toHaveBeenCalled();
    },
  );

  it("dispatches the selected provider from a valid provider event", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          createSseStreamResponse(
            `event: provider\ndata: {"provider":"${SELECTED_PROVIDER_NAME}"}\n\n`,
          ),
        ),
    );
    const onText = vi.fn();
    const onProvider = vi.fn();

    await streamSse("/api/test/stream", {}, { onText, onProvider }, "sse.test.ts");

    expect(onText).not.toHaveBeenCalled();
    expect(onProvider).toHaveBeenCalledOnce();
    expect(onProvider).toHaveBeenCalledWith({ provider: SELECTED_PROVIDER_NAME });
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
