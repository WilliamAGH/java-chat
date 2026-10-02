import { afterEach, describe, expect, it, vi } from "vitest";
import { streamSse, streamSseGet } from "./sse";

const SSE_STREAM_RESPONSE_STATUS = 200;
const HTTP_BAD_REQUEST_STATUS = 400;
const HTTP_TOO_MANY_REQUESTS_STATUS = 429;
const HTTP_SERVICE_UNAVAILABLE_STATUS = 503;
const FETCH_FAILURE_MESSAGE = "Network request failed";
const STREAM_READ_FAILURE_MESSAGE = "Unable to read the SSE stream";
const NETWORK_FAILURE_MESSAGE = "Couldn't reach the server";
const NETWORK_FAILURE_DETAILS = "Check your connection and try again.";
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

  it("reports and rejects a non-abort fetch failure as a retryable network failure", async () => {
    const fetchFailure = new Error(FETCH_FAILURE_MESSAGE);
    const fetchMock = vi.fn().mockRejectedValue(fetchFailure);
    vi.stubGlobal("fetch", fetchMock);
    vi.spyOn(console, "error").mockImplementation(() => undefined);

    const onText = vi.fn();
    const onError = vi.fn();

    const rejection = await streamSse(
      "/api/test/stream",
      { hello: "world" },
      { onText, onError },
      "sse.test.ts",
    ).catch((caughtFailure: unknown) => caughtFailure);

    expect(rejection).toMatchObject({
      name: "StreamFailureError",
      message: NETWORK_FAILURE_MESSAGE,
      details: NETWORK_FAILURE_DETAILS,
      retryable: true,
    });
    expect(rejection).toHaveProperty("cause", fetchFailure);

    expect(fetchMock).toHaveBeenCalledOnce();
    expect(onText).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledOnce();
    expect(onError).toHaveBeenCalledWith({
      message: NETWORK_FAILURE_MESSAGE,
      details: NETWORK_FAILURE_DETAILS,
      retryable: true,
    });
  });

  it("reports and rejects a non-abort GET fetch failure as a retryable network failure", async () => {
    const fetchFailure = new Error(FETCH_FAILURE_MESSAGE);
    const fetchMock = vi.fn().mockRejectedValue(fetchFailure);
    vi.stubGlobal("fetch", fetchMock);
    vi.spyOn(console, "error").mockImplementation(() => undefined);

    const onText = vi.fn();
    const onError = vi.fn();

    await expect(
      streamSseGet("/api/test/stream", { onText, onError }, "sse.test.ts"),
    ).rejects.toMatchObject({
      name: "StreamFailureError",
      message: NETWORK_FAILURE_MESSAGE,
      details: NETWORK_FAILURE_DETAILS,
      retryable: true,
    });

    expect(fetchMock).toHaveBeenCalledOnce();
    expect(onText).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledOnce();
    expect(onError).toHaveBeenCalledWith({
      message: NETWORK_FAILURE_MESSAGE,
      details: NETWORK_FAILURE_DETAILS,
      retryable: true,
    });
  });

  it("reports a 5xx response as a retryable stream failure", async () => {
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
    ).rejects.toMatchObject({
      name: "StreamFailureError",
      message: serviceUnavailableMessage,
      retryable: true,
    });

    expect(onText).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledOnce();
    expect(onError).toHaveBeenCalledWith({
      message: serviceUnavailableMessage,
      retryable: true,
    });
  });

  it("reports a 429 response as a retryable stream failure", async () => {
    const rateLimitedMessage = `HTTP ${HTTP_TOO_MANY_REQUESTS_STATUS}: Too Many Requests`;
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(null, {
          status: HTTP_TOO_MANY_REQUESTS_STATUS,
          statusText: "Too Many Requests",
        }),
      ),
    );

    const onText = vi.fn();
    const onError = vi.fn();

    await expect(
      streamSseGet("/api/test/stream", { onText, onError }, "sse.test.ts"),
    ).rejects.toMatchObject({
      name: "StreamFailureError",
      message: rateLimitedMessage,
      retryable: true,
    });

    expect(onError).toHaveBeenCalledWith({
      message: rateLimitedMessage,
      retryable: true,
    });
  });

  it("reports a 4xx client error as a non-retryable stream failure", async () => {
    const badRequestMessage = `HTTP ${HTTP_BAD_REQUEST_STATUS}: Bad Request`;
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(null, {
          status: HTTP_BAD_REQUEST_STATUS,
          statusText: "Bad Request",
        }),
      ),
    );

    const onText = vi.fn();
    const onError = vi.fn();

    await expect(
      streamSseGet("/api/test/stream", { onText, onError }, "sse.test.ts"),
    ).rejects.toMatchObject({
      name: "StreamFailureError",
      message: badRequestMessage,
      retryable: false,
    });

    expect(onText).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledOnce();
    expect(onError).toHaveBeenCalledWith({
      message: badRequestMessage,
      retryable: false,
    });
  });

  it("attaches the HTTP status as details when the server returns an API error message", async () => {
    const apiErrorMessage = "The model is overloaded";
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ status: "error", message: apiErrorMessage }), {
          status: HTTP_SERVICE_UNAVAILABLE_STATUS,
          statusText: "Service Unavailable",
          headers: { "Content-Type": "application/json" },
        }),
      ),
    );

    const onText = vi.fn();
    const onError = vi.fn();

    await expect(
      streamSseGet("/api/test/stream", { onText, onError }, "sse.test.ts"),
    ).rejects.toMatchObject({
      name: "StreamFailureError",
      message: apiErrorMessage,
      details: `HTTP ${HTTP_SERVICE_UNAVAILABLE_STATUS}: Service Unavailable`,
      retryable: true,
    });

    expect(onError).toHaveBeenCalledWith({
      message: apiErrorMessage,
      details: `HTTP ${HTTP_SERVICE_UNAVAILABLE_STATUS}: Service Unavailable`,
      retryable: true,
    });
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
    ).rejects.toMatchObject({
      name: "StreamFailureError",
      message: MISSING_STREAM_BODY_MESSAGE,
      retryable: true,
    });

    expect(onText).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledOnce();
    expect(onError).toHaveBeenCalledWith({
      message: MISSING_STREAM_BODY_MESSAGE,
      retryable: true,
    });
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

  it("reports and rejects a stream-read failure as a retryable network failure", async () => {
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
    vi.spyOn(console, "error").mockImplementation(() => undefined);

    const onText = vi.fn();
    const onError = vi.fn();

    const rejection = await streamSse(
      "/api/test/stream",
      { hello: "world" },
      { onText, onError },
      "sse.test.ts",
    ).catch((caughtFailure: unknown) => caughtFailure);

    expect(rejection).toMatchObject({
      name: "StreamFailureError",
      message: NETWORK_FAILURE_MESSAGE,
      details: NETWORK_FAILURE_DETAILS,
      retryable: true,
    });
    expect(rejection).toHaveProperty("cause", streamReadFailure);

    expect(fetchMock).toHaveBeenCalledOnce();
    expect(onText).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledOnce();
    expect(onError).toHaveBeenCalledWith({
      message: NETWORK_FAILURE_MESSAGE,
      details: NETWORK_FAILURE_DETAILS,
      retryable: true,
    });
  });
});

describe("streamSse payload validation", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it.each(['event: text\ndata: {"text":"Hello"}\n\n', 'event: text\ndata:{"text":"Hello"}\n\n'])(
    "dispatches canonical chunk payloads",
    async (sseWireText) => {
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(createSseStreamResponse(sseWireText)));
      const onText = vi.fn();

      await streamSse("/api/test/stream", {}, { onText }, "sse.test.ts");

      expect(onText).toHaveBeenCalledOnce();
      expect(onText).toHaveBeenCalledWith("Hello");
    },
  );

  it.each(['event: text\ndata: {"text":"Hello"}', 'event: text\ndata: {"text":"Hello"}\n'])(
    "rejects an SSE event that ends before a blank line",
    async (sseWireText) => {
      vi.stubGlobal("fetch", vi.fn().mockResolvedValue(createSseStreamResponse(sseWireText)));
      const onText = vi.fn();
      const onError = vi.fn();

      await expect(
        streamSse("/api/test/stream", {}, { onText, onError }, "sse.test.ts"),
      ).rejects.toThrow("Received an invalid SSE event from the server");

      expect(onText).not.toHaveBeenCalled();
      expect(onError).toHaveBeenCalledOnce();
      expect(onError).toHaveBeenCalledWith({
        message: "Received an invalid SSE event from the server",
      });
    },
  );

  it("accepts a terminal DONE line without dispatching content", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(createSseStreamResponse("data:[DONE]")));
    const onText = vi.fn();
    const onError = vi.fn();

    await streamSse("/api/test/stream", {}, { onText, onError }, "sse.test.ts");

    expect(onText).not.toHaveBeenCalled();
    expect(onError).not.toHaveBeenCalled();
  });

  it("preserves CRLF, multiline data, and split UTF-8 decoding", async () => {
    const encoder = new TextEncoder();
    const sseBytes = encoder.encode('event: text\r\ndata: {"text":\r\ndata: "héllo"}\r\n\r\n');
    const multibyteStart = sseBytes.indexOf(0xc3);
    const sseStreamBody = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(sseBytes.slice(0, multibyteStart + 1));
        controller.enqueue(sseBytes.slice(multibyteStart + 1));
        controller.close();
      },
    });
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(new Response(sseStreamBody, { status: SSE_STREAM_RESPONSE_STATUS })),
    );
    const onText = vi.fn();

    await streamSse("/api/test/stream", {}, { onText }, "sse.test.ts");

    expect(onText).toHaveBeenCalledOnce();
    expect(onText).toHaveBeenCalledWith("héllo");
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
