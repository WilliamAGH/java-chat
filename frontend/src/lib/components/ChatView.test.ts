import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, fireEvent } from "@testing-library/svelte";
import { tick } from "svelte";

type StreamChatFunction = typeof import("../services/chat").streamChat;

const streamChatMock = vi.fn<StreamChatFunction>();

vi.mock("../services/chat", async () => {
  const actualChatService =
    await vi.importActual<typeof import("../services/chat")>("../services/chat");
  return {
    ...actualChatService,
    streamChat: streamChatMock,
  };
});

async function renderChatView() {
  const ChatViewComponent = (await import("./ChatView.svelte")).default;
  return render(ChatViewComponent);
}

async function sendChatMessage(
  renderedChatView: Awaited<ReturnType<typeof renderChatView>>,
  chatMessage: string,
): Promise<void> {
  const messageInput = renderedChatView.getByLabelText("Message input");
  if (!(messageInput instanceof HTMLTextAreaElement)) {
    throw new Error("Expected message input element to be a textarea");
  }
  await fireEvent.input(messageInput, { target: { value: chatMessage } });
  await fireEvent.click(renderedChatView.getByRole("button", { name: "Send message" }));
}

describe("ChatView streaming stability", () => {
  beforeEach(() => {
    streamChatMock.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("keeps the assistant message DOM node stable when the stream completes", async () => {
    let completeStream: () => void = () => {
      throw new Error("Expected stream completion callback to be set");
    };

    streamChatMock.mockImplementation(async (_sessionId, _message, onChunk, options) => {
      options?.onStatus?.({ message: "Searching", details: "Loading sources" });

      await Promise.resolve();
      onChunk("Hello");

      await Promise.resolve();
      options?.onCitations?.([{ url: "https://example.com", title: "Example" }]);

      return new Promise<void>((resolve) => {
        completeStream = resolve;
      });
    });

    const renderedChatView = await renderChatView();
    const { container, findByText } = renderedChatView;
    await sendChatMessage(renderedChatView, "Hi");

    const assistantTextElement = await findByText("Hello");
    await tick();

    const assistantMessageElement = assistantTextElement.closest(".message.assistant");
    expect(assistantMessageElement).not.toBeNull();

    expect(container.querySelector(".message.assistant .cursor.visible")).not.toBeNull();

    completeStream();
    await tick();

    const assistantTextElementAfter = await findByText("Hello");
    const assistantMessageElementAfter = assistantTextElementAfter.closest(".message.assistant");

    expect(assistantMessageElementAfter).toBe(assistantMessageElement);
    expect(container.querySelector(".message.assistant .cursor.visible")).toBeNull();
  });

  it("shows structured retrieval status details", async () => {
    streamChatMock.mockImplementation(async (_sessionId, _message, _onChunk, options) => {
      options?.onStatus?.({
        message: "Some citations could not be loaded",
        details: "Citations could not be loaded",
      });
      return new Promise<void>(() => {});
    });

    const renderedChatView = await renderChatView();
    const { findByText } = renderedChatView;
    await sendChatMessage(renderedChatView, "Explain records");

    expect(await findByText("Some citations could not be loaded")).toBeTruthy();
    expect(await findByText("Citations could not be loaded")).toBeTruthy();
  });

  it("shows the provider selected for the active stream", async () => {
    streamChatMock.mockImplementation(async (_sessionId, _message, _onChunk, options) => {
      options?.onProvider?.({ provider: "OpenAI" });
      return new Promise<void>(() => {});
    });

    const renderedChatView = await renderChatView();
    await sendChatMessage(renderedChatView, "Explain records");

    expect(await renderedChatView.findByText("Provider: OpenAI")).toBeTruthy();
  });

  it("aborts the active chat stream when the view unmounts", async () => {
    let activeChatStreamSignal: AbortSignal | undefined;
    streamChatMock.mockImplementation(async (_sessionId, _message, _onChunk, options) => {
      activeChatStreamSignal = options?.signal;
      if (!activeChatStreamSignal) {
        throw new Error("Expected ChatView to pass an AbortSignal for chat streaming");
      }

      return new Promise<void>((resolve) => {
        activeChatStreamSignal?.addEventListener("abort", () => resolve(), { once: true });
      });
    });

    const renderedChatView = await renderChatView();
    await sendChatMessage(renderedChatView, "Explain virtual threads");
    await vi.waitFor(() => expect(streamChatMock).toHaveBeenCalledOnce());

    if (!activeChatStreamSignal) {
      throw new Error("Expected the active chat stream signal to be captured");
    }
    expect(activeChatStreamSignal.aborted).toBe(false);

    renderedChatView.unmount();

    expect(activeChatStreamSignal.aborted).toBe(true);
  });

  it("ignores callbacks and rejection from a cancelled chat stream", async () => {
    const BrowserAbortController = globalThis.AbortController;
    class CapturedChatStreamAbortController extends BrowserAbortController {
      static activeController: AbortController | undefined;

      constructor() {
        super();
        CapturedChatStreamAbortController.activeController = this;
      }
    }
    vi.stubGlobal("AbortController", CapturedChatStreamAbortController);

    streamChatMock.mockImplementation(async (_sessionId, _message, onChunk, options) => {
      const chatStreamOptions = options;
      if (!chatStreamOptions?.signal) {
        throw new Error("Expected ChatView to pass an AbortSignal for chat streaming");
      }
      const chatStreamSignal = chatStreamOptions.signal;

      return new Promise<void>((_resolve, reject) => {
        chatStreamSignal.addEventListener(
          "abort",
          () => {
            onChunk("Late chunk");
            chatStreamOptions.onStatus?.({ message: "Late status" });
            chatStreamOptions.onError?.({ message: "Late stream error" });
            chatStreamOptions.onCitations?.([
              { url: "https://example.com/late", title: "Late source" },
            ]);
            reject(new DOMException("The stream was cancelled", "AbortError"));
          },
          { once: true },
        );
      });
    });

    const renderedChatView = await renderChatView();
    await sendChatMessage(renderedChatView, "Explain sealed classes");
    await vi.waitFor(() => expect(streamChatMock).toHaveBeenCalledOnce());

    const activeChatStreamController = CapturedChatStreamAbortController.activeController;
    if (!activeChatStreamController) {
      throw new Error("Expected the active chat stream controller to be captured");
    }
    activeChatStreamController.abort();
    await tick();

    expect(renderedChatView.container.querySelector(".message.assistant")).toBeNull();
    expect(renderedChatView.queryByText("Late status")).toBeNull();
  });
});
