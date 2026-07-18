import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render } from "@testing-library/svelte";
import { tick } from "svelte";
import type { CitationFetchOptions } from "../services/chat";
import type { GuidedLessonContentCallbacks } from "../services/guided";

const {
  fetchTocMock,
  streamLessonContentMock,
  fetchGuidedLessonCitationsMock,
  streamGuidedChatMock,
  scrollOnceMock,
} = vi.hoisted(() => ({
  fetchTocMock: vi.fn(),
  streamLessonContentMock: vi.fn(),
  fetchGuidedLessonCitationsMock: vi.fn(),
  streamGuidedChatMock: vi.fn(),
  scrollOnceMock: vi.fn(),
}));

vi.mock("../services/guided", async () => {
  const guidedService =
    await vi.importActual<typeof import("../services/guided")>("../services/guided");
  return {
    ...guidedService,
    fetchTOC: fetchTocMock,
    streamLessonContent: streamLessonContentMock,
    fetchGuidedLessonCitations: fetchGuidedLessonCitationsMock,
    streamGuidedChat: streamGuidedChatMock,
  };
});

vi.mock("../composables/createScrollAnchor.svelte", () => ({
  createScrollAnchor: () => ({
    get unseenCount(): number {
      return 0;
    },
    get showIndicator(): boolean {
      return false;
    },
    attach: vi.fn(),
    cleanup: vi.fn(),
    clearIndicatorState: vi.fn(),
    jumpToBottom: vi.fn(),
    onContentAdded: vi.fn(),
    onNewMessageStarted: vi.fn(),
    onUserScroll: vi.fn(),
    reset: vi.fn(),
    scrollOnce: scrollOnceMock,
  }),
}));

const TEST_LESSON = {
  slug: "intro",
  title: "Test Lesson",
  summary: "Lesson summary",
  keywords: [],
};

async function renderSelectedLesson() {
  fetchTocMock.mockResolvedValue([TEST_LESSON]);
  streamLessonContentMock.mockImplementation(
    async (_lessonSlug: string, lessonStreamCallbacks: GuidedLessonContentCallbacks) => {
      lessonStreamCallbacks.onChunk("# Lesson");
    },
  );

  const LearnViewComponent = (await import("./LearnView.svelte")).default;
  const learnView = render(LearnViewComponent);
  await fireEvent.click(await learnView.findByRole("button", { name: /test lesson/i }));
  return learnView;
}

function configurePendingCitationRequest(): () => AbortSignal | undefined {
  let citationAbortSignal: AbortSignal | undefined;
  fetchGuidedLessonCitationsMock.mockImplementation(
    (_lessonSlug: string, citationFetchOptions: CitationFetchOptions = {}) => {
      citationAbortSignal = citationFetchOptions.signal;
      return new Promise<never>((_resolveCitation, rejectCitation) => {
        citationAbortSignal?.addEventListener(
          "abort",
          () => rejectCitation(new DOMException("Citation request cancelled", "AbortError")),
          { once: true },
        );
      });
    },
  );
  return () => citationAbortSignal;
}

describe("LearnView async request ownership", () => {
  beforeEach(() => {
    fetchTocMock.mockReset();
    streamLessonContentMock.mockReset();
    fetchGuidedLessonCitationsMock.mockReset();
    streamGuidedChatMock.mockReset();
    scrollOnceMock.mockReset();
    scrollOnceMock.mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("does not start a stream after Clear Chat wins the pending scroll", async () => {
    const scrollCompletion = Promise.withResolvers<void>();
    scrollOnceMock.mockReturnValueOnce(scrollCompletion.promise);
    fetchGuidedLessonCitationsMock.mockResolvedValue({ success: true, citations: [] });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        status: 200,
        statusText: "OK",
        text: async () => "",
      }),
    );

    const learnView = await renderSelectedLesson();
    const messageInput = learnView.getByLabelText("Message input");
    if (!(messageInput instanceof HTMLTextAreaElement)) {
      throw new Error("Expected the guided message input to be a textarea");
    }
    await fireEvent.input(messageInput, { target: { value: "Explain records" } });

    const sendClick = fireEvent.click(learnView.getByRole("button", { name: "Send message" }));
    await vi.waitFor(() => expect(scrollOnceMock).toHaveBeenCalledOnce());
    await fireEvent.click(learnView.getByRole("button", { name: "Clear chat" }));
    scrollCompletion.resolve();
    await sendClick;
    await tick();

    expect(streamGuidedChatMock).not.toHaveBeenCalled();
  });

  it("aborts the citation request when returning to all lessons", async () => {
    const citationAbortSignal = configurePendingCitationRequest();
    const learnView = await renderSelectedLesson();

    await vi.waitFor(() => expect(citationAbortSignal()).toBeDefined());
    await fireEvent.click(learnView.getByRole("button", { name: "All Lessons" }));
    await tick();

    expect(citationAbortSignal()?.aborted).toBe(true);
  });

  it("aborts the citation request when the guided view unmounts", async () => {
    const citationAbortSignal = configurePendingCitationRequest();
    const learnView = await renderSelectedLesson();

    await vi.waitFor(() => expect(citationAbortSignal()).toBeDefined());
    learnView.unmount();
    await tick();

    expect(citationAbortSignal()?.aborted).toBe(true);
  });
});
