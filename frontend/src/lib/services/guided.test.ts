import { beforeEach, describe, expect, it, vi } from "vitest";

const { streamSseMock, streamSseGetMock } = vi.hoisted(() => {
  return { streamSseMock: vi.fn(), streamSseGetMock: vi.fn() };
});

vi.mock("./sse", () => {
  return { streamSse: streamSseMock, streamSseGet: streamSseGetMock };
});

import { streamGuidedChat, streamLessonContent } from "./guided";

describe("streamGuidedChat", () => {
  beforeEach(() => {
    streamSseMock.mockReset();
    streamSseGetMock.mockReset();
  });

  it("surfaces a recoverable stream failure without replaying the POST", async () => {
    const streamFailure = new Error("OverflowException: malformed response frame");
    const streamError = {
      message: streamFailure.message,
      details: "Malformed response frame at byte 512",
    };
    streamSseMock.mockImplementationOnce(async (_url, _body, callbacks) => {
      callbacks.onError?.(streamError);
      throw streamFailure;
    });

    const onChunk = vi.fn();
    const onStatus = vi.fn();
    const onError = vi.fn();
    const onCitations = vi.fn();
    const streamAbortController = new AbortController();

    await expect(
      streamGuidedChat("guided-session-1", "intro", "Teach me streams", {
        onChunk,
        onStatus,
        onError,
        onCitations,
        signal: streamAbortController.signal,
      }),
    ).rejects.toBe(streamFailure);

    expect(streamSseMock).toHaveBeenCalledTimes(1);
    expect(streamSseMock).toHaveBeenCalledWith(
      "/api/guided/stream",
      { sessionId: "guided-session-1", slug: "intro", latest: "Teach me streams" },
      expect.objectContaining({
        onText: onChunk,
        onStatus,
        onError,
        onCitations,
      }),
      "guided.ts",
      { signal: streamAbortController.signal },
    );
    expect(onStatus).not.toHaveBeenCalled();
    expect(onChunk).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledWith(streamError);
  });

  it("does not retry for non-recoverable rate-limit errors", async () => {
    streamSseMock.mockImplementationOnce(async (_url, _body, callbacks) => {
      callbacks.onError?.({ message: "429 rate limit exceeded" });
      throw new Error("429 rate limit exceeded");
    });

    const onChunk = vi.fn();
    const onStatus = vi.fn();
    const onError = vi.fn();
    const onCitations = vi.fn();

    await expect(
      streamGuidedChat("guided-session-2", "intro", "Teach me streams", {
        onChunk,
        onStatus,
        onError,
        onCitations,
      }),
    ).rejects.toThrow("429 rate limit exceeded");

    expect(streamSseMock).toHaveBeenCalledTimes(1);
    expect(onStatus).not.toHaveBeenCalledWith(
      expect.objectContaining({
        message: "Temporary stream issue detected",
      }),
    );
    const [firstOnErrorCall] = onError.mock.calls;
    expect(firstOnErrorCall).toBeDefined();
    expect(firstOnErrorCall[0]).toEqual({ message: "429 rate limit exceeded" });
  });

  it("forwards a terminal non-retryable provider failure without replaying the POST", async () => {
    const streamError = {
      message: "Streaming error",
      details: "The response stream encountered an error. Please try again.",
      code: "stream.provider.fatal-error",
      retryable: false,
      stage: "stream",
    };
    streamSseMock.mockImplementationOnce(async (_url, _body, callbacks) => {
      callbacks.onError?.(streamError);
      throw new Error(streamError.message);
    });

    const onChunk = vi.fn();
    const onStatus = vi.fn();
    const onError = vi.fn();
    const onCitations = vi.fn();

    await expect(
      streamGuidedChat("guided-session-3", "intro", "Teach me streams", {
        onChunk,
        onStatus,
        onError,
        onCitations,
      }),
    ).rejects.toThrow(streamError.message);

    expect(streamSseMock).toHaveBeenCalledTimes(1);
    expect(onStatus).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledWith(streamError);
  });

  it("forwards the selected provider event", async () => {
    const selectedProvider = { provider: "GitHub Models" };
    streamSseMock.mockImplementationOnce(async (_url, _body, callbacks) => {
      callbacks.onProvider?.(selectedProvider);
    });
    const onProvider = vi.fn();

    await streamGuidedChat("session-provider", "records", "Explain records", {
      onChunk: vi.fn(),
      onProvider,
    });

    expect(onProvider).toHaveBeenCalledOnce();
    expect(onProvider).toHaveBeenCalledWith(selectedProvider);
  });

  it("streams lesson content from the guided content stream endpoint", async () => {
    streamSseGetMock.mockImplementationOnce(async (_url, callbacks) => {
      callbacks.onText("# Lesson");
    });

    const onChunk = vi.fn();
    const onStatus = vi.fn();
    const onError = vi.fn();

    await expect(
      streamLessonContent("intro", {
        onChunk,
        onStatus,
        onError,
      }),
    ).resolves.toBeUndefined();

    expect(streamSseGetMock).toHaveBeenCalledWith(
      "/api/guided/content/stream?slug=intro",
      expect.objectContaining({
        onText: expect.any(Function),
        onStatus,
        onError,
      }),
      "guided.lesson-content.ts",
      { signal: undefined },
    );
    expect(onChunk).toHaveBeenCalledWith("# Lesson");
  });
});
