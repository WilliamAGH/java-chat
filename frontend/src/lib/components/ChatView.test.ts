import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, fireEvent } from "@testing-library/svelte";
import { tick } from "svelte";
import { createCitationPartialFailureStatusFixture } from "../../test/citationPartialFailureStatus";

type StreamChatFunction = typeof import("../services/chat").streamChat;

const TERMINAL_STREAM_FAILURE_MESSAGE = "The provider ended the stream";
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

  it("does not submit while an IME composition is active", async () => {
    const renderedChatView = await renderChatView();
    const messageInput = renderedChatView.getByLabelText("Message input");
    if (!(messageInput instanceof HTMLTextAreaElement)) {
      throw new Error("Expected message input element to be a textarea");
    }

    await fireEvent.input(messageInput, { target: { value: "record" } });
    const composingEnterEvent = new KeyboardEvent("keydown", {
      bubbles: true,
      cancelable: true,
      isComposing: true,
      key: "Enter",
    });
    await fireEvent(messageInput, composingEnterEvent);

    expect(composingEnterEvent.defaultPrevented).toBe(false);
    expect(streamChatMock).not.toHaveBeenCalled();
    expect(renderedChatView.container.querySelector(".message.user")).toBeNull();
    expect(messageInput).toHaveValue("record");
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

  it("restores input focus after a submitted stream completes with focus in the form", async () => {
    let completeStream: () => void = () => {
      throw new Error("Expected stream completion callback to be set");
    };
    streamChatMock.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          completeStream = resolve;
        }),
    );

    const renderedChatView = await renderChatView();
    const messageInput = renderedChatView.getByLabelText("Message input");
    if (!(messageInput instanceof HTMLTextAreaElement)) {
      throw new Error("Expected message input element to be a textarea");
    }
    messageInput.focus();

    await sendChatMessage(renderedChatView, "Explain records");
    await vi.waitFor(() => expect(messageInput).toBeDisabled());
    expect(messageInput).toHaveFocus();

    completeStream();

    await vi.waitFor(() => {
      expect(messageInput).toBeEnabled();
      expect(messageInput).toHaveFocus();
    });
  });

  it("restores input focus after a submitted stream completes with focus on the document body", async () => {
    let completeStream: () => void = () => {
      throw new Error("Expected stream completion callback to be set");
    };
    streamChatMock.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          completeStream = resolve;
        }),
    );

    const renderedChatView = await renderChatView();
    const messageInput = renderedChatView.getByLabelText("Message input");
    if (!(messageInput instanceof HTMLTextAreaElement)) {
      throw new Error("Expected message input element to be a textarea");
    }

    await sendChatMessage(renderedChatView, "Explain records");
    await vi.waitFor(() => expect(messageInput).toBeDisabled());

    const transientFocusControl = document.createElement("button");
    renderedChatView.container.append(transientFocusControl);
    transientFocusControl.focus();
    transientFocusControl.remove();
    expect(document.activeElement).toBe(document.body);

    completeStream();

    await vi.waitFor(() => {
      expect(messageInput).toBeEnabled();
      expect(messageInput).toHaveFocus();
    });
  });

  it("preserves external focus after a submitted stream completes", async () => {
    let completeStream: () => void = () => {
      throw new Error("Expected stream completion callback to be set");
    };
    streamChatMock.mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          completeStream = resolve;
        }),
    );

    const renderedChatView = await renderChatView();
    const messageInput = renderedChatView.getByLabelText("Message input");
    if (!(messageInput instanceof HTMLTextAreaElement)) {
      throw new Error("Expected message input element to be a textarea");
    }

    await sendChatMessage(renderedChatView, "Explain records");
    await vi.waitFor(() => expect(messageInput).toBeDisabled());

    const externalNavigationButton = document.createElement("button");
    renderedChatView.container.append(externalNavigationButton);
    externalNavigationButton.focus();
    expect(externalNavigationButton).toHaveFocus();

    completeStream();

    await vi.waitFor(() => expect(messageInput).toBeEnabled());
    expect(externalNavigationButton).toHaveFocus();
  });

  it("keeps a citation warning visible after response text and stream completion", async () => {
    let completeStream: () => void = () => {
      throw new Error("Expected stream completion callback to be set");
    };
    const citationWarning = createCitationPartialFailureStatusFixture();

    streamChatMock.mockImplementation(async (_sessionId, _message, onChunk, options) => {
      options?.onStatus?.(citationWarning);
      onChunk("Records are immutable data carriers.");
      return new Promise<void>((resolve) => {
        completeStream = resolve;
      });
    });

    const renderedChatView = await renderChatView();
    await sendChatMessage(renderedChatView, "Explain records");

    expect(await renderedChatView.findByText("Records are immutable data carriers.")).toBeTruthy();
    const warningRegion = await renderedChatView.findByRole("status", {
      name: "Citation warning",
    });
    expect(warningRegion).toHaveTextContent(citationWarning.message);
    expect(warningRegion).toHaveTextContent(citationWarning.details ?? "");

    completeStream();
    await vi.waitFor(() =>
      expect(
        renderedChatView.container.querySelector(".message.assistant .cursor.visible"),
      ).toBeNull(),
    );

    expect(renderedChatView.getByRole("status", { name: "Citation warning" })).toBe(warningRegion);
  });

  it("removes a citation warning when the response stream fails", async () => {
    let failResponseStream: () => void = () => {
      throw new Error("Expected stream failure callback to be set");
    };
    const citationWarning = createCitationPartialFailureStatusFixture();

    streamChatMock.mockImplementation(async (_sessionId, _message, onChunk, options) => {
      options?.onStatus?.(citationWarning);
      onChunk("Incomplete response");
      return new Promise<void>((_resolve, reject) => {
        failResponseStream = () => reject(new Error(TERMINAL_STREAM_FAILURE_MESSAGE));
      });
    });

    const renderedChatView = await renderChatView();
    await sendChatMessage(renderedChatView, "Explain records");

    expect(
      await renderedChatView.findByRole("status", { name: "Citation warning" }),
    ).toHaveTextContent(citationWarning.message);

    failResponseStream();

    expect(await renderedChatView.findByText("Incomplete response")).toBeTruthy();
    expect(await renderedChatView.findByRole("alert")).toHaveTextContent(
      TERMINAL_STREAM_FAILURE_MESSAGE,
    );
    expect(renderedChatView.queryByRole("status", { name: "Citation warning" })).toBeNull();
  });

  it("renders a visible error bubble when the stream fails before text", async () => {
    streamChatMock.mockRejectedValue(new Error(TERMINAL_STREAM_FAILURE_MESSAGE));

    const renderedChatView = await renderChatView();
    await sendChatMessage(renderedChatView, "Explain records");

    const errorMessage = await renderedChatView.findByText(TERMINAL_STREAM_FAILURE_MESSAGE);
    expect(errorMessage.closest(".message.assistant.error")).not.toBeNull();
  });

  it("treats invisible streamed characters as an empty failed response", async () => {
    streamChatMock.mockImplementation(async (_sessionId, _message, onChunk) => {
      onChunk(" \t\n\u200B\uFEFF\u2060");
      throw new Error(TERMINAL_STREAM_FAILURE_MESSAGE);
    });

    const renderedChatView = await renderChatView();
    await sendChatMessage(renderedChatView, "Explain records");

    const errorMessage = await renderedChatView.findByText(TERMINAL_STREAM_FAILURE_MESSAGE);
    expect(errorMessage.closest(".message.assistant.error")).not.toBeNull();
    expect(renderedChatView.container.querySelector(".stream-error")).toBeNull();
  });

  it("keeps the connecting indicator active for an unresolved invisible chunk", async () => {
    streamChatMock.mockImplementation(async (_sessionId, _message, onChunk) => {
      onChunk("\u200B\uFEFF\u2060");
      return new Promise<void>(() => {});
    });

    const renderedChatView = await renderChatView();
    await sendChatMessage(renderedChatView, "Explain records");

    await vi.waitFor(() =>
      expect(
        renderedChatView.container.querySelector('.thinking-indicator[data-phase="connecting"]'),
      ).not.toBeNull(),
    );
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
