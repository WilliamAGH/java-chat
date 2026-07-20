import { beforeEach, describe, expect, it, vi } from "vitest";

const { streamSseMock } = vi.hoisted(() => {
  return { streamSseMock: vi.fn() };
});

vi.mock("./sse", () => {
  return { streamSse: streamSseMock };
});

import { hasVisibleChatMessageText, streamChat } from "./chat";

describe("streamChat", () => {
  beforeEach(() => {
    streamSseMock.mockReset();
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
    const streamAbortController = new AbortController();

    await expect(
      streamChat("session-1", "What is new in Java 25?", onChunk, {
        onStatus,
        onError,
        signal: streamAbortController.signal,
      }),
    ).rejects.toBe(streamFailure);

    expect(streamSseMock).toHaveBeenCalledTimes(1);
    expect(streamSseMock).toHaveBeenCalledWith(
      "/api/chat/stream",
      { sessionId: "session-1", latest: "What is new in Java 25?" },
      expect.objectContaining({
        onText: onChunk,
        onStatus,
        onError,
      }),
      "chat.ts",
      { signal: streamAbortController.signal },
    );
    expect(onStatus).not.toHaveBeenCalled();
    expect(onChunk).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledWith(streamError);
  });

  it("does not replay after a stream has emitted a chunk", async () => {
    streamSseMock.mockImplementationOnce(async (_url, _body, callbacks) => {
      callbacks.onText("Partial answer");
      callbacks.onError?.({ message: "OverflowException: malformed response frame" });
      throw new Error("OverflowException: malformed response frame");
    });

    const onChunk = vi.fn();
    const onStatus = vi.fn();
    const onError = vi.fn();

    await expect(
      streamChat("session-2", "Explain records", onChunk, { onStatus, onError }),
    ).rejects.toThrow("OverflowException: malformed response frame");

    expect(streamSseMock).toHaveBeenCalledTimes(1);
    expect(onChunk).toHaveBeenCalledWith("Partial answer");
    expect(onError).toHaveBeenCalledWith({
      message: "OverflowException: malformed response frame",
    });
  });

  it("forwards backend non-retryable stream status metadata without replaying", async () => {
    streamSseMock.mockImplementationOnce(async (_url, _body, callbacks) => {
      callbacks.onStatus?.({
        message: "Provider returned fatal stream error",
        code: "stream.provider.fatal-error",
        retryable: false,
        stage: "stream",
      });
      callbacks.onError?.({ message: "Unexpected provider failure" });
      throw new Error("Unexpected provider failure");
    });

    const onChunk = vi.fn();
    const onStatus = vi.fn();
    const onError = vi.fn();

    await expect(
      streamChat("session-3", "Explain virtual threads", onChunk, { onStatus, onError }),
    ).rejects.toThrow("Unexpected provider failure");

    expect(streamSseMock).toHaveBeenCalledTimes(1);
    expect(onStatus).toHaveBeenCalledWith(
      expect.objectContaining({
        code: "stream.provider.fatal-error",
        retryable: false,
      }),
    );
    expect(onError).toHaveBeenCalledWith({ message: "Unexpected provider failure" });
  });

  it("forwards the selected provider event", async () => {
    const selectedProvider = { provider: "OpenAI" };
    streamSseMock.mockImplementationOnce(async (_url, _body, callbacks) => {
      callbacks.onProvider?.(selectedProvider);
    });
    const onChunk = vi.fn();
    const onProvider = vi.fn();

    await streamChat("session-4", "Explain sealed classes", onChunk, { onProvider });

    expect(onProvider).toHaveBeenCalledOnce();
    expect(onProvider).toHaveBeenCalledWith(selectedProvider);
  });
});

describe("hasVisibleChatMessageText", () => {
  it("rejects whitespace and invisible format characters", () => {
    expect(hasVisibleChatMessageText(" \t\n\u200B\uFEFF\u2060")).toBe(false);
  });

  it("accepts a visible code point among invisible characters", () => {
    expect(hasVisibleChatMessageText("\u200BJava 24\u2060")).toBe(true);
  });
});
