import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { render, fireEvent } from "@testing-library/svelte";
import { tick } from "svelte";

type GuidedCitationFetchResult = Awaited<
  ReturnType<typeof import("../services/guided").fetchGuidedLessonCitations>
>;
type GuidedCitationFetchOptions = Parameters<
  typeof import("../services/guided").fetchGuidedLessonCitations
>[1];

const fetchTocMock = vi.fn();
const streamLessonContentMock = vi.fn();
const fetchGuidedLessonCitationsMock = vi.fn();
const streamGuidedChatMock = vi.fn();
const OFFSCREEN_MESSAGES_SCROLL_HEIGHT = 1_000;
const VISIBLE_MESSAGES_CONTAINER_HEIGHT = 200;
const NEW_UPDATES_INDICATOR_NAME = "1 new updates, jump to bottom";
const ANY_NEW_UPDATES_INDICATOR_NAME = /jump to/i;

vi.mock("../services/guided", async () => {
  const actualGuidedService =
    await vi.importActual<typeof import("../services/guided")>("../services/guided");
  return {
    ...actualGuidedService,
    fetchTOC: fetchTocMock,
    streamLessonContent: streamLessonContentMock,
    fetchGuidedLessonCitations: fetchGuidedLessonCitationsMock,
    streamGuidedChat: streamGuidedChatMock,
  };
});

async function renderLearnView() {
  const LearnViewComponent = (await import("./LearnView.svelte")).default;
  return render(LearnViewComponent);
}

function configureGuidedChatWithPendingStream() {
  streamLessonContentMock.mockImplementation(async (_lessonSlug, lessonStreamCallbacks) => {
    lessonStreamCallbacks.onChunk("# Lesson");
  });
  fetchGuidedLessonCitationsMock.mockResolvedValue({ success: true, citations: [] });
  streamGuidedChatMock.mockImplementation(async () => new Promise<void>(() => {}));
}

function makeDesktopMessagesContainerAppearScrolledAway(messagesContainer: HTMLElement) {
  Object.defineProperties(messagesContainer, {
    scrollTop: { configurable: true, value: 0, writable: true },
    scrollHeight: {
      configurable: true,
      value: OFFSCREEN_MESSAGES_SCROLL_HEIGHT,
    },
    clientHeight: {
      configurable: true,
      value: VISIBLE_MESSAGES_CONTAINER_HEIGHT,
    },
  });
}

async function openLessonWithPendingNewUpdatesIndicator(initialLessonName: RegExp) {
  configureGuidedChatWithPendingStream();

  const learnView = await renderLearnView();
  const initialLessonButton = await learnView.findByRole("button", {
    name: initialLessonName,
  });
  await fireEvent.click(initialLessonButton);
  await tick();

  const messagesContainer = learnView.container.querySelector<HTMLElement>(
    ".chat-panel--desktop .messages-container",
  );
  if (!messagesContainer) {
    throw new Error("Expected the desktop messages container to be rendered");
  }
  makeDesktopMessagesContainerAppearScrolledAway(messagesContainer);

  vi.useFakeTimers();
  const messageInput = learnView.getByLabelText("Message input");
  if (!(messageInput instanceof HTMLTextAreaElement)) {
    throw new Error("Expected message input element to be a textarea");
  }
  await fireEvent.input(messageInput, { target: { value: "Hi" } });
  await fireEvent.click(learnView.getByRole("button", { name: "Send message" }));
  await tick();

  return learnView;
}

describe("LearnView guided chat streaming stability", () => {
  beforeEach(() => {
    fetchTocMock.mockReset();
    streamLessonContentMock.mockReset();
    fetchGuidedLessonCitationsMock.mockReset();
    streamGuidedChatMock.mockReset();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it("keeps the All Lessons back control named when its visual label is hidden on mobile", async () => {
    fetchTocMock.mockResolvedValue([
      { slug: "intro", title: "Test Lesson", summary: "Lesson summary", keywords: [] },
    ]);

    streamLessonContentMock.mockImplementation(async (_slug, callbacks) => {
      callbacks.onChunk("# Lesson");
    });
    fetchGuidedLessonCitationsMock.mockResolvedValue({ success: true, citations: [] });

    const { findByRole } = await renderLearnView();
    const lessonButton = await findByRole("button", { name: /test lesson/i });
    await fireEvent.click(lessonButton);

    const allLessonsButton = await findByRole("button", { name: "All Lessons" });
    expect(allLessonsButton).toHaveAttribute("aria-label", "All Lessons");
    expect(allLessonsButton).toHaveTextContent("All Lessons");

    await fireEvent.click(allLessonsButton);
    expect(await findByRole("button", { name: /test lesson/i })).toBeInTheDocument();
  });

  it("returns focus to the mobile chat trigger after closing the drawer", async () => {
    fetchTocMock.mockResolvedValue([
      { slug: "intro", title: "Test Lesson", summary: "Lesson summary", keywords: [] },
    ]);
    streamLessonContentMock.mockImplementation(async (_lessonSlug, lessonStreamCallbacks) => {
      lessonStreamCallbacks.onChunk("# Lesson");
    });
    fetchGuidedLessonCitationsMock.mockResolvedValue({ success: true, citations: [] });

    const learnView = await renderLearnView();
    await fireEvent.click(await learnView.findByRole("button", { name: /test lesson/i }));

    const mobileChatTrigger = learnView.getByRole("button", {
      name: "Ask questions about this lesson",
    });
    mobileChatTrigger.focus();
    await fireEvent.click(mobileChatTrigger);

    const closeChatButton = await learnView.findByRole("button", { name: "Close chat" });
    closeChatButton.focus();
    await fireEvent.click(closeChatButton);
    await tick();

    expect(mobileChatTrigger).toHaveFocus();
  });

  it("keeps the guided assistant message DOM node stable when the stream completes", async () => {
    fetchTocMock.mockResolvedValue([
      { slug: "intro", title: "Test Lesson", summary: "Lesson summary", keywords: [] },
    ]);

    streamLessonContentMock.mockImplementation(async (_slug, callbacks) => {
      callbacks.onChunk("# Lesson");
    });
    fetchGuidedLessonCitationsMock.mockResolvedValue({ success: true, citations: [] });

    let completeStream: () => void = () => {
      throw new Error("Expected guided stream completion callback to be set");
    };
    streamGuidedChatMock.mockImplementation(async (_sessionId, _slug, _message, callbacks) => {
      callbacks.onStatus?.({ message: "Searching", details: "Loading sources" });

      await Promise.resolve();
      callbacks.onChunk("Hello");

      await Promise.resolve();
      callbacks.onCitations?.([{ url: "https://example.com", title: "Example" }]);

      return new Promise<void>((resolve) => {
        completeStream = resolve;
      });
    });

    const { findByRole, getByLabelText, getByRole, container, findByText } =
      await renderLearnView();

    const lessonButton = await findByRole("button", { name: /test lesson/i });
    await fireEvent.click(lessonButton);

    const inputElement = getByLabelText("Message input");
    if (!(inputElement instanceof HTMLTextAreaElement)) {
      throw new Error("Expected message input element to be a textarea");
    }
    await fireEvent.input(inputElement, { target: { value: "Hi" } });

    const sendButton = getByRole("button", { name: "Send message" });
    await fireEvent.click(sendButton);

    const assistantTextElement = await findByText("Hello");
    await tick();

    const assistantMessageElement = assistantTextElement.closest(
      ".chat-panel--desktop .message.assistant",
    );
    expect(assistantMessageElement).not.toBeNull();

    expect(
      container.querySelector(".chat-panel--desktop .message.assistant .cursor.visible"),
    ).not.toBeNull();

    completeStream();
    await tick();

    const assistantTextElementAfter = await findByText("Hello");
    const assistantMessageElementAfter = assistantTextElementAfter.closest(
      ".chat-panel--desktop .message.assistant",
    );

    expect(assistantMessageElementAfter).toBe(assistantMessageElement);
    expect(
      container.querySelector(".chat-panel--desktop .message.assistant .cursor.visible"),
    ).toBeNull();
  });

  it("shows the provider selected for the active guided stream", async () => {
    fetchTocMock.mockResolvedValue([
      { slug: "intro", title: "Test Lesson", summary: "Lesson summary", keywords: [] },
    ]);
    streamLessonContentMock.mockImplementation(async (_lessonSlug, lessonStreamCallbacks) => {
      lessonStreamCallbacks.onChunk("# Lesson");
    });
    fetchGuidedLessonCitationsMock.mockResolvedValue({ success: true, citations: [] });
    streamGuidedChatMock.mockImplementation(
      async (_sessionId, _lessonSlug, _message, callbacks) => {
        callbacks.onProvider?.({ provider: "GitHub Models" });
        return new Promise<void>(() => {});
      },
    );

    const learnView = await renderLearnView();
    await fireEvent.click(await learnView.findByRole("button", { name: /test lesson/i }));
    await fireEvent.input(learnView.getByLabelText("Message input"), {
      target: { value: "Explain records" },
    });
    await fireEvent.click(learnView.getByRole("button", { name: "Send message" }));

    expect(await learnView.findAllByText("Provider: GitHub Models")).not.toHaveLength(0);
  });

  it("clears the new-updates indicator when clearing chat", async () => {
    fetchTocMock.mockResolvedValue([
      { slug: "intro", title: "Test Lesson", summary: "Lesson summary", keywords: [] },
    ]);
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        statusText: "OK",
        text: async () => "",
      }),
    );

    const learnView = await openLessonWithPendingNewUpdatesIndicator(/test lesson/i);

    await vi.runAllTimersAsync();
    await tick();

    expect(learnView.getByRole("button", { name: NEW_UPDATES_INDICATOR_NAME })).toBeInTheDocument();

    await fireEvent.click(learnView.getByRole("button", { name: "Clear chat" }));
    await tick();

    expect(learnView.queryByRole("button", { name: ANY_NEW_UPDATES_INDICATOR_NAME })).toBeNull();
  });

  it("clears the new-updates indicator when switching lessons", async () => {
    fetchTocMock.mockResolvedValue([
      { slug: "intro", title: "Intro Lesson", summary: "Lesson summary", keywords: [] },
      {
        slug: "classes",
        title: "Classes Lesson",
        summary: "Classes summary",
        keywords: [],
      },
    ]);

    const learnView = await openLessonWithPendingNewUpdatesIndicator(/intro lesson/i);

    await fireEvent.click(learnView.getByRole("button", { name: "All Lessons" }));
    await fireEvent.click(learnView.getByRole("button", { name: /classes lesson/i }));
    await vi.runAllTimersAsync();
    await tick();

    expect(learnView.queryByRole("button", { name: ANY_NEW_UPDATES_INDICATOR_NAME })).toBeNull();
  });

  it("cancels the guided stream and clears messages without late writes after clear chat", async () => {
    fetchTocMock.mockResolvedValue([
      { slug: "intro", title: "Test Lesson", summary: "Lesson summary", keywords: [] },
    ]);

    streamLessonContentMock.mockImplementation(async (_slug, callbacks) => {
      callbacks.onChunk("# Lesson");
    });
    fetchGuidedLessonCitationsMock.mockResolvedValue({ success: true, citations: [] });

    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      statusText: "OK",
      text: async () => "",
    });
    vi.stubGlobal("fetch", fetchMock);

    const guidedSessionIds: string[] = [];
    const abortSignalsByStream: Array<AbortSignal | undefined> = [];
    let hasIssuedClear = false;

    streamGuidedChatMock.mockImplementation(async (sessionId, _slug, _message, callbacks) => {
      guidedSessionIds.push(sessionId);
      abortSignalsByStream.push(callbacks.signal);
      callbacks.onChunk(hasIssuedClear ? "Hello again" : "Hello");

      if (hasIssuedClear) {
        return;
      }

      const streamAbortSignal = callbacks.signal;
      if (!streamAbortSignal) {
        throw new Error("Expected LearnView to pass an AbortSignal for guided streaming");
      }

      return new Promise<void>((resolve) => {
        streamAbortSignal.addEventListener(
          "abort",
          () => {
            // Simulate a late chunk arriving after Clear Chat.
            void Promise.resolve().then(() => callbacks.onChunk("Late chunk"));
            resolve();
          },
          { once: true },
        );
      });
    });

    const { findByRole, getByLabelText, getByRole, findByText, queryByText } =
      await renderLearnView();

    const lessonButton = await findByRole("button", { name: /test lesson/i });
    await fireEvent.click(lessonButton);

    const inputElement = getByLabelText("Message input");
    if (!(inputElement instanceof HTMLTextAreaElement)) {
      throw new Error("Expected message input element to be a textarea");
    }
    await fireEvent.input(inputElement, { target: { value: "Hi" } });

    const sendButton = getByRole("button", { name: "Send message" });
    await fireEvent.click(sendButton);

    await findByText("Hello");

    const clearChatButton = getByRole("button", { name: "Clear chat" });
    await fireEvent.click(clearChatButton);
    hasIssuedClear = true;
    await tick();

    expect(queryByText("Hello")).toBeNull();
    expect(queryByText("Late chunk")).toBeNull();

    const inputElementAfterClear = getByLabelText("Message input");
    if (!(inputElementAfterClear instanceof HTMLTextAreaElement)) {
      throw new Error("Expected message input element to be a textarea");
    }
    await fireEvent.input(inputElementAfterClear, { target: { value: "Hi again" } });

    const sendButtonAfterClear = getByRole("button", { name: "Send message" });
    await fireEvent.click(sendButtonAfterClear);

    await findByText("Hello again");

    expect(guidedSessionIds).toHaveLength(2);
    expect(guidedSessionIds[1]).not.toBe(guidedSessionIds[0]);

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/chat/clear?sessionId=${encodeURIComponent(guidedSessionIds[0])}`,
      expect.objectContaining({ method: "POST" }),
    );
    expect(abortSignalsByStream[0]?.aborted ?? false).toBe(true);
  });

  it("ignores stale citations when the same lesson is reselected", async () => {
    fetchTocMock.mockResolvedValue([
      { slug: "intro", title: "Test Lesson", summary: "Lesson summary", keywords: [] },
    ]);
    streamLessonContentMock.mockImplementation(async (_lessonSlug, lessonStreamCallbacks) => {
      lessonStreamCallbacks.onChunk("# Lesson");
    });
    const firstCitationRequest = Promise.withResolvers<GuidedCitationFetchResult>();
    const currentCitationRequest = Promise.withResolvers<GuidedCitationFetchResult>();
    let firstCitationAbortSignal: AbortSignal | undefined;
    fetchGuidedLessonCitationsMock
      .mockImplementationOnce(
        (_lessonSlug: string, citationFetchOptions: GuidedCitationFetchOptions = {}) => {
          firstCitationAbortSignal = citationFetchOptions.signal;
          return firstCitationRequest.promise;
        },
      )
      .mockReturnValueOnce(currentCitationRequest.promise);

    const learnView = await renderLearnView();
    await fireEvent.click(await learnView.findByRole("button", { name: /test lesson/i }));
    await vi.waitFor(() => expect(fetchGuidedLessonCitationsMock).toHaveBeenCalledTimes(1));

    await fireEvent.click(learnView.getByRole("button", { name: "All Lessons" }));
    expect(firstCitationAbortSignal?.aborted).toBe(true);
    await fireEvent.click(await learnView.findByRole("button", { name: /test lesson/i }));
    await vi.waitFor(() => expect(fetchGuidedLessonCitationsMock).toHaveBeenCalledTimes(2));

    currentCitationRequest.resolve({
      success: true,
      citations: [{ url: "https://example.com/current", title: "Current Source" }],
    });
    await fireEvent.click(await learnView.findByRole("button", { name: /1 source/i }));
    expect(await learnView.findByText("Current Source")).toBeInTheDocument();

    firstCitationRequest.resolve({
      success: true,
      citations: [{ url: "https://example.com/stale", title: "Stale Source" }],
    });
    await tick();

    expect(learnView.queryByText("Stale Source")).toBeNull();
    expect(learnView.getByText("Current Source")).toBeInTheDocument();
  });

  it("aborts lesson and guided streams when the view unmounts", async () => {
    fetchTocMock.mockResolvedValue([
      { slug: "intro", title: "Test Lesson", summary: "Lesson summary", keywords: [] },
    ]);
    let lessonStreamSignal: AbortSignal | undefined;
    streamLessonContentMock.mockImplementation(async (_lessonSlug, lessonStreamCallbacks) => {
      lessonStreamSignal = lessonStreamCallbacks.signal;
      return new Promise<void>((resolve) => {
        lessonStreamSignal?.addEventListener("abort", () => resolve(), { once: true });
      });
    });
    let guidedStreamSignal: AbortSignal | undefined;
    streamGuidedChatMock.mockImplementation(
      async (_sessionId, _lessonSlug, _message, callbacks) => {
        guidedStreamSignal = callbacks.signal;
        return new Promise<void>((resolve) => {
          guidedStreamSignal?.addEventListener("abort", () => resolve(), { once: true });
        });
      },
    );

    const learnView = await renderLearnView();
    await fireEvent.click(await learnView.findByRole("button", { name: /test lesson/i }));
    await vi.waitFor(() => expect(lessonStreamSignal).toBeDefined());
    await fireEvent.input(learnView.getByLabelText("Message input"), {
      target: { value: "Explain records" },
    });
    await fireEvent.click(learnView.getByRole("button", { name: "Send message" }));
    await vi.waitFor(() => expect(guidedStreamSignal).toBeDefined());

    learnView.unmount();

    expect(lessonStreamSignal?.aborted).toBe(true);
    expect(guidedStreamSignal?.aborted).toBe(true);
  });
});
