import { afterEach, describe, expect, it, vi } from "vitest";
import { tick } from "svelte";
import { createScrollAnchor } from "./createScrollAnchor.svelte";

const USER_SCROLL_INTENT_CASES = [
  { name: "wheel", createScrollIntentEvent: () => new WheelEvent("wheel") },
  { name: "touch", createScrollIntentEvent: () => new Event("touchstart") },
  { name: "pointer", createScrollIntentEvent: () => new Event("pointerdown") },
  {
    name: "keyboard",
    createScrollIntentEvent: () => new KeyboardEvent("keydown", { key: "PageUp" }),
  },
];

function setScrollGeometry(
  scrollContainer: HTMLElement,
  scrollTop: number,
  scrollHeight: number,
  clientHeight: number,
): void {
  Object.defineProperties(scrollContainer, {
    scrollTop: { configurable: true, value: scrollTop },
    scrollHeight: { configurable: true, value: scrollHeight },
    clientHeight: { configurable: true, value: clientHeight },
  });
}

describe("createScrollAnchor final content", () => {
  afterEach(() => {
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  it("reveals final content when content growth did not follow a user scroll", async () => {
    const scrollContainer = document.createElement("div");
    setScrollGeometry(scrollContainer, 0, 1_000, 500);
    const scrollToSpy = vi.spyOn(scrollContainer, "scrollTo");
    const scrollAnchor = createScrollAnchor();
    scrollAnchor.attach(scrollContainer);

    await scrollAnchor.revealFinalContentIfFollowing();

    expect(scrollToSpy).toHaveBeenCalledOnce();
    expect(scrollToSpy).toHaveBeenCalledWith({
      top: 1_000,
      behavior: "smooth",
    });
    scrollAnchor.cleanup();
  });

  it("keeps following during an intermediate smooth programmatic scroll event", async () => {
    const scrollContainer = document.createElement("div");
    setScrollGeometry(scrollContainer, 0, 1_000, 500);
    const scrollToSpy = vi.spyOn(scrollContainer, "scrollTo");
    const scrollAnchor = createScrollAnchor();
    scrollAnchor.attach(scrollContainer);
    await scrollAnchor.scrollOnce();
    setScrollGeometry(scrollContainer, 200, 1_000, 500);
    scrollAnchor.onUserScroll();

    await scrollAnchor.revealFinalContentIfFollowing();

    expect(scrollToSpy).toHaveBeenCalledTimes(2);
    expect(scrollAnchor.showIndicator).toBe(false);
    scrollAnchor.cleanup();
  });

  it("preserves an intentional scroll-away and exposes the final update indicator", async () => {
    vi.useFakeTimers();
    const scrollContainer = document.createElement("div");
    setScrollGeometry(scrollContainer, 100, 1_000, 500);
    const scrollToSpy = vi.spyOn(scrollContainer, "scrollTo");
    const scrollAnchor = createScrollAnchor({ indicatorDelayMs: 150 });
    scrollAnchor.attach(scrollContainer);
    scrollContainer.dispatchEvent(new WheelEvent("wheel"));
    scrollAnchor.onUserScroll();

    await scrollAnchor.revealFinalContentIfFollowing();
    vi.advanceTimersByTime(150);

    expect(scrollToSpy).not.toHaveBeenCalled();
    expect(scrollAnchor.unseenCount).toBe(1);
    expect(scrollAnchor.showIndicator).toBe(true);

    scrollAnchor.cleanup();
  });

  it("exposes the active message when streamed content grows off-screen", async () => {
    vi.useFakeTimers();
    const scrollContainer = document.createElement("div");
    setScrollGeometry(scrollContainer, 800, 1_000, 200);
    const scrollAnchor = createScrollAnchor({ indicatorDelayMs: 150 });
    scrollAnchor.attach(scrollContainer);
    scrollAnchor.onNewMessageStarted();

    expect(scrollAnchor.unseenCount).toBe(0);

    scrollContainer.dispatchEvent(new WheelEvent("wheel"));
    setScrollGeometry(scrollContainer, 600, 1_000, 200);
    scrollAnchor.onUserScroll();

    setScrollGeometry(scrollContainer, 600, 3_657, 200);
    await scrollAnchor.onContentAdded();

    expect(scrollAnchor.unseenCount).toBe(1);
    expect(scrollAnchor.showIndicator).toBe(false);

    vi.advanceTimersByTime(100);
    await scrollAnchor.onContentAdded();
    vi.advanceTimersByTime(50);

    expect(scrollAnchor.unseenCount).toBe(1);
    expect(scrollAnchor.showIndicator).toBe(true);
    scrollAnchor.cleanup();
  });

  it("suppresses the indicator when content grows while follow is engaged", async () => {
    vi.useFakeTimers();
    const scrollContainer = document.createElement("div");
    setScrollGeometry(scrollContainer, 800, 1_000, 200);
    const scrollAnchor = createScrollAnchor({ indicatorDelayMs: 150 });
    scrollAnchor.attach(scrollContainer);
    await scrollAnchor.scrollOnce();
    scrollAnchor.onNewMessageStarted();

    expect(scrollAnchor.unseenCount).toBe(0);

    setScrollGeometry(scrollContainer, 800, 3_657, 200);
    await scrollAnchor.onContentAdded();
    vi.advanceTimersByTime(150);

    expect(scrollAnchor.unseenCount).toBe(0);
    expect(scrollAnchor.showIndicator).toBe(false);

    setScrollGeometry(scrollContainer, 800, 5_157, 200);
    await scrollAnchor.onContentAdded();
    vi.advanceTimersByTime(150);

    expect(scrollAnchor.unseenCount).toBe(0);
    expect(scrollAnchor.showIndicator).toBe(false);
    scrollAnchor.cleanup();
  });

  it("does not restore a pending update after the scroll state resets", async () => {
    vi.useFakeTimers();
    const scrollContainer = document.createElement("div");
    setScrollGeometry(scrollContainer, 800, 3_657, 200);
    const scrollAnchor = createScrollAnchor({ indicatorDelayMs: 150 });
    scrollAnchor.attach(scrollContainer);

    const pendingContentMeasurement = scrollAnchor.onContentAdded();
    scrollAnchor.reset();
    await pendingContentMeasurement;
    vi.advanceTimersByTime(150);

    expect(scrollAnchor.unseenCount).toBe(0);
    expect(scrollAnchor.showIndicator).toBe(false);
    scrollAnchor.cleanup();
  });

  it("does not restore a pending intent-settlement update after reset", async () => {
    vi.useFakeTimers();
    const scrollContainer = document.createElement("div");
    setScrollGeometry(scrollContainer, 3_457, 4_157, 200);
    const scrollAnchor = createScrollAnchor({ indicatorDelayMs: 150 });
    scrollAnchor.attach(scrollContainer);
    scrollContainer.dispatchEvent(new Event("pointerdown"));

    const pendingContentMeasurement = scrollAnchor.onContentAdded();
    await tick();
    scrollAnchor.reset();
    await vi.runAllTimersAsync();
    await pendingContentMeasurement;

    expect(scrollAnchor.unseenCount).toBe(0);
    expect(scrollAnchor.showIndicator).toBe(false);
    scrollAnchor.cleanup();
  });

  it("invalidates a pending final reveal on reset, container replacement, or cleanup", async () => {
    const originalScrollContainer = document.createElement("div");
    const replacementScrollContainer = document.createElement("div");
    setScrollGeometry(originalScrollContainer, 3_457, 3_657, 200);
    setScrollGeometry(replacementScrollContainer, 3_700, 4_000, 300);
    const originalScrollToSpy = vi.spyOn(originalScrollContainer, "scrollTo");
    const replacementScrollToSpy = vi.spyOn(replacementScrollContainer, "scrollTo");
    const scrollAnchor = createScrollAnchor();
    scrollAnchor.attach(originalScrollContainer);

    const resetReveal = scrollAnchor.revealFinalContentIfFollowing();
    scrollAnchor.reset();
    await resetReveal;

    const replacedReveal = scrollAnchor.revealFinalContentIfFollowing();
    scrollAnchor.attach(replacementScrollContainer);
    await replacedReveal;

    const cleanedUpReveal = scrollAnchor.revealFinalContentIfFollowing();
    scrollAnchor.cleanup();
    await cleanedUpReveal;

    expect(originalScrollToSpy).not.toHaveBeenCalled();
    expect(replacementScrollToSpy).not.toHaveBeenCalled();
    expect(scrollAnchor.unseenCount).toBe(0);
    expect(scrollAnchor.showIndicator).toBe(false);
  });

  it("does not restore active-follow state after reset invalidates pending content", async () => {
    vi.useFakeTimers();
    const scrollContainer = document.createElement("div");
    setScrollGeometry(scrollContainer, 800, 3_657, 200);
    const scrollToSpy = vi.spyOn(scrollContainer, "scrollTo");
    const scrollAnchor = createScrollAnchor({ indicatorDelayMs: 150 });
    scrollAnchor.attach(scrollContainer);
    await scrollAnchor.jumpToBottom();
    scrollToSpy.mockClear();

    setScrollGeometry(scrollContainer, 2_000, 5_157, 200);
    const pendingFollow = scrollAnchor.onContentAdded();
    scrollAnchor.reset();
    await pendingFollow;
    vi.advanceTimersByTime(150);

    expect(scrollToSpy).not.toHaveBeenCalled();
    expect(scrollAnchor.unseenCount).toBe(0);
    expect(scrollAnchor.showIndicator).toBe(false);
    scrollAnchor.cleanup();
  });

  it("follows future stream chunks after an explicit indicator jump", async () => {
    vi.useFakeTimers();
    const scrollContainer = document.createElement("div");
    setScrollGeometry(scrollContainer, 800, 3_657, 200);
    const scrollToSpy = vi.spyOn(scrollContainer, "scrollTo");
    const scrollAnchor = createScrollAnchor({ indicatorDelayMs: 150 });
    scrollAnchor.attach(scrollContainer);

    scrollContainer.dispatchEvent(new WheelEvent("wheel"));
    setScrollGeometry(scrollContainer, 700, 3_657, 200);
    scrollAnchor.onUserScroll();

    await scrollAnchor.onContentAdded();
    vi.advanceTimersByTime(150);
    expect(scrollAnchor.showIndicator).toBe(true);

    await scrollAnchor.jumpToBottom();
    scrollToSpy.mockClear();

    setScrollGeometry(scrollContainer, 3_457, 4_157, 200);
    await scrollAnchor.onContentAdded();
    expect(scrollToSpy).toHaveBeenLastCalledWith({ top: 4_157, behavior: "auto" });
    expect(scrollAnchor.unseenCount).toBe(0);
    expect(scrollAnchor.showIndicator).toBe(false);

    vi.advanceTimersByTime(1_000);
    setScrollGeometry(scrollContainer, 3_957, 5_157, 200);
    await scrollAnchor.onContentAdded();
    expect(scrollToSpy).toHaveBeenLastCalledWith({ top: 5_157, behavior: "auto" });
    expect(scrollAnchor.unseenCount).toBe(0);
    expect(scrollAnchor.showIndicator).toBe(false);
    scrollAnchor.cleanup();
  });

  it("stops following future chunks after genuine user scroll intent", async () => {
    vi.useFakeTimers();
    const scrollContainer = document.createElement("div");
    setScrollGeometry(scrollContainer, 800, 3_657, 200);
    const scrollToSpy = vi.spyOn(scrollContainer, "scrollTo");
    const scrollAnchor = createScrollAnchor({ indicatorDelayMs: 150 });
    scrollAnchor.attach(scrollContainer);
    await scrollAnchor.jumpToBottom();
    scrollToSpy.mockClear();

    setScrollGeometry(scrollContainer, 2_000, 5_157, 200);
    scrollContainer.dispatchEvent(new WheelEvent("wheel"));
    scrollAnchor.onUserScroll();

    setScrollGeometry(scrollContainer, 2_000, 6_157, 200);
    await scrollAnchor.onContentAdded();
    vi.advanceTimersByTime(150);

    expect(scrollToSpy).not.toHaveBeenCalled();
    expect(scrollAnchor.unseenCount).toBe(1);
    expect(scrollAnchor.showIndicator).toBe(true);
    scrollAnchor.cleanup();
  });

  it.each(USER_SCROLL_INTENT_CASES)(
    "cancels a pending follow scroll immediately on $name intent",
    async ({ createScrollIntentEvent }) => {
      const scrollContainer = document.createElement("div");
      setScrollGeometry(scrollContainer, 800, 3_657, 200);
      const scrollToSpy = vi.spyOn(scrollContainer, "scrollTo");
      const scrollAnchor = createScrollAnchor();
      scrollAnchor.attach(scrollContainer);
      await scrollAnchor.jumpToBottom();
      scrollToSpy.mockClear();

      setScrollGeometry(scrollContainer, 3_457, 4_157, 200);
      const pendingFollowScroll = scrollAnchor.onContentAdded();
      scrollContainer.dispatchEvent(createScrollIntentEvent());
      await pendingFollowScroll;

      expect(scrollToSpy).not.toHaveBeenCalled();

      setScrollGeometry(scrollContainer, 2_000, 4_157, 200);
      scrollAnchor.onUserScroll();
      setScrollGeometry(scrollContainer, 2_000, 5_157, 200);
      await scrollAnchor.onContentAdded();
      expect(scrollToSpy).not.toHaveBeenCalled();
      scrollAnchor.cleanup();
    },
  );

  it("continues following after pointer interaction that does not scroll away", async () => {
    const scrollContainer = document.createElement("div");
    setScrollGeometry(scrollContainer, 3_457, 3_657, 200);
    const scrollToSpy = vi.spyOn(scrollContainer, "scrollTo");
    const scrollAnchor = createScrollAnchor();
    scrollAnchor.attach(scrollContainer);
    await scrollAnchor.jumpToBottom();
    scrollToSpy.mockClear();

    scrollContainer.dispatchEvent(new Event("pointerdown"));
    setScrollGeometry(scrollContainer, 3_457, 4_157, 200);
    await scrollAnchor.onContentAdded();
    expect(scrollToSpy).toHaveBeenCalledWith({ top: 4_157, behavior: "auto" });

    scrollToSpy.mockClear();
    setScrollGeometry(scrollContainer, 3_957, 4_657, 200);
    await scrollAnchor.onContentAdded();
    expect(scrollToSpy).toHaveBeenCalledWith({ top: 4_657, behavior: "auto" });
    scrollAnchor.cleanup();
  });

  it("moves pending follow work to a replacement container", async () => {
    const desktopScrollContainer = document.createElement("div");
    const mobileScrollContainer = document.createElement("div");
    setScrollGeometry(desktopScrollContainer, 800, 3_657, 200);
    setScrollGeometry(mobileScrollContainer, 2_000, 4_000, 300);
    const desktopScrollToSpy = vi.spyOn(desktopScrollContainer, "scrollTo");
    const mobileScrollToSpy = vi.spyOn(mobileScrollContainer, "scrollTo");
    const scrollAnchor = createScrollAnchor();
    scrollAnchor.attach(desktopScrollContainer);
    await scrollAnchor.jumpToBottom();
    desktopScrollToSpy.mockClear();

    setScrollGeometry(desktopScrollContainer, 3_457, 4_157, 200);
    const staleDesktopFollowScroll = scrollAnchor.onContentAdded();
    scrollAnchor.attach(mobileScrollContainer);
    const mobileFollowScroll = scrollAnchor.onContentAdded();
    await Promise.all([staleDesktopFollowScroll, mobileFollowScroll]);

    expect(desktopScrollToSpy).not.toHaveBeenCalled();
    expect(mobileScrollToSpy).toHaveBeenCalledWith({ top: 4_000, behavior: "auto" });
    expect(scrollAnchor.unseenCount).toBe(0);
    expect(scrollAnchor.showIndicator).toBe(false);
    scrollAnchor.cleanup();
  });

  it("coalesces concurrent future chunks after an explicit indicator jump", async () => {
    const scrollContainer = document.createElement("div");
    setScrollGeometry(scrollContainer, 800, 3_657, 200);
    const scrollToSpy = vi.spyOn(scrollContainer, "scrollTo");
    const scrollAnchor = createScrollAnchor();
    scrollAnchor.attach(scrollContainer);
    await scrollAnchor.jumpToBottom();
    scrollToSpy.mockClear();

    setScrollGeometry(scrollContainer, 3_457, 4_157, 200);
    await Promise.all([
      scrollAnchor.onContentAdded(),
      scrollAnchor.onContentAdded(),
      scrollAnchor.onContentAdded(),
    ]);

    expect(scrollToSpy).toHaveBeenCalledOnce();
    expect(scrollToSpy).toHaveBeenCalledWith({ top: 4_157, behavior: "auto" });
    expect(scrollAnchor.showIndicator).toBe(false);
    scrollAnchor.cleanup();
  });

  it("retains a dirty follow signal when a later chunk renders during the owner scroll", async () => {
    const scrollContainer = document.createElement("div");
    setScrollGeometry(scrollContainer, 800, 3_657, 200);
    const scrollToSpy = vi.spyOn(scrollContainer, "scrollTo");
    const scrollAnchor = createScrollAnchor();
    scrollAnchor.attach(scrollContainer);
    await scrollAnchor.jumpToBottom();
    scrollToSpy.mockClear();

    let lateContentFollow: Promise<void> | undefined;
    scrollToSpy.mockImplementationOnce(() => {
      setScrollGeometry(scrollContainer, 3_957, 4_657, 200);
      lateContentFollow = scrollAnchor.onContentAdded();
    });
    setScrollGeometry(scrollContainer, 3_457, 4_157, 200);
    await scrollAnchor.onContentAdded();
    await lateContentFollow;

    expect(scrollToSpy).toHaveBeenCalledTimes(2);
    expect(scrollToSpy).toHaveBeenLastCalledWith({ top: 4_657, behavior: "auto" });
    scrollAnchor.cleanup();
  });

  it("does not let a second chunk outrun a delayed genuine scroll-away event", async () => {
    vi.useFakeTimers();
    const scrollContainer = document.createElement("div");
    setScrollGeometry(scrollContainer, 800, 3_657, 200);
    const scrollToSpy = vi.spyOn(scrollContainer, "scrollTo");
    const scrollAnchor = createScrollAnchor({ indicatorDelayMs: 150 });
    scrollAnchor.attach(scrollContainer);
    await scrollAnchor.jumpToBottom();
    scrollToSpy.mockClear();

    setScrollGeometry(scrollContainer, 3_457, 3_657, 200);
    scrollContainer.dispatchEvent(new WheelEvent("wheel"));
    const firstPendingChunk = scrollAnchor.onContentAdded();
    const secondPendingChunk = scrollAnchor.onContentAdded();
    setScrollGeometry(scrollContainer, 2_000, 5_157, 200);
    scrollAnchor.onUserScroll();
    await vi.runAllTimersAsync();
    await Promise.all([firstPendingChunk, secondPendingChunk]);
    vi.advanceTimersByTime(150);

    expect(scrollToSpy).not.toHaveBeenCalled();
    expect(scrollAnchor.unseenCount).toBe(1);
    expect(scrollAnchor.showIndicator).toBe(true);
    scrollAnchor.cleanup();
  });

  it("clears stale input intent before accepting an explicit jump", async () => {
    const scrollContainer = document.createElement("div");
    setScrollGeometry(scrollContainer, 800, 3_657, 200);
    const scrollToSpy = vi.spyOn(scrollContainer, "scrollTo");
    const scrollAnchor = createScrollAnchor();
    scrollAnchor.attach(scrollContainer);
    scrollContainer.dispatchEvent(new Event("pointerdown"));

    const pendingJump = scrollAnchor.jumpToBottom();
    setScrollGeometry(scrollContainer, 1_000, 3_657, 200);
    scrollAnchor.onUserScroll();
    await pendingJump;
    scrollToSpy.mockClear();

    setScrollGeometry(scrollContainer, 3_457, 4_157, 200);
    await scrollAnchor.onContentAdded();

    expect(scrollToSpy).toHaveBeenCalledWith({ top: 4_157, behavior: "auto" });
    expect(scrollAnchor.showIndicator).toBe(false);
    scrollAnchor.cleanup();
  });

  it("cancels a pending jump when state resets or the container changes", async () => {
    const originalScrollContainer = document.createElement("div");
    const replacementScrollContainer = document.createElement("div");
    setScrollGeometry(originalScrollContainer, 800, 3_657, 200);
    setScrollGeometry(replacementScrollContainer, 2_000, 4_000, 300);
    const originalScrollToSpy = vi.spyOn(originalScrollContainer, "scrollTo");
    const replacementScrollToSpy = vi.spyOn(replacementScrollContainer, "scrollTo");
    const scrollAnchor = createScrollAnchor();
    scrollAnchor.attach(originalScrollContainer);

    const resetJump = scrollAnchor.jumpToBottom();
    scrollAnchor.reset();
    await resetJump;
    expect(originalScrollToSpy).not.toHaveBeenCalled();

    const replacedJump = scrollAnchor.jumpToBottom();
    scrollAnchor.attach(replacementScrollContainer);
    await replacedJump;
    expect(originalScrollToSpy).not.toHaveBeenCalled();
    expect(replacementScrollToSpy).not.toHaveBeenCalled();
    scrollAnchor.cleanup();
  });

  it.each(USER_SCROLL_INTENT_CASES)(
    "cancels a pending send scroll on $name intent",
    async ({ createScrollIntentEvent }) => {
      const scrollContainer = document.createElement("div");
      setScrollGeometry(scrollContainer, 800, 3_657, 200);
      const scrollToSpy = vi.spyOn(scrollContainer, "scrollTo");
      const scrollAnchor = createScrollAnchor();
      scrollAnchor.attach(scrollContainer);

      const pendingSendScroll = scrollAnchor.scrollOnce();
      scrollContainer.dispatchEvent(createScrollIntentEvent());
      await pendingSendScroll;
      expect(scrollToSpy).not.toHaveBeenCalled();
      scrollAnchor.cleanup();
    },
  );

  it.each(USER_SCROLL_INTENT_CASES)(
    "preserves a genuine $name scroll-away that begins before final reveal",
    async ({ createScrollIntentEvent }) => {
      vi.useFakeTimers();
      const scrollContainer = document.createElement("div");
      setScrollGeometry(scrollContainer, 3_457, 3_657, 200);
      const scrollToSpy = vi.spyOn(scrollContainer, "scrollTo");
      const scrollAnchor = createScrollAnchor({ indicatorDelayMs: 150 });
      scrollAnchor.attach(scrollContainer);

      scrollContainer.dispatchEvent(createScrollIntentEvent());
      const pendingFinalReveal = scrollAnchor.revealFinalContentIfFollowing();
      setScrollGeometry(scrollContainer, 2_000, 5_157, 200);
      scrollAnchor.onUserScroll();
      await vi.runAllTimersAsync();
      await pendingFinalReveal;

      expect(scrollToSpy).not.toHaveBeenCalled();
      expect(scrollAnchor.unseenCount).toBe(1);
      expect(scrollAnchor.showIndicator).toBe(true);
      scrollAnchor.cleanup();
    },
  );

  it.each(USER_SCROLL_INTENT_CASES)(
    "recovers final reveal after non-scrolling $name intent",
    async ({ createScrollIntentEvent }) => {
      vi.useFakeTimers();
      const scrollContainer = document.createElement("div");
      setScrollGeometry(scrollContainer, 3_457, 3_657, 200);
      const scrollToSpy = vi.spyOn(scrollContainer, "scrollTo");
      const scrollAnchor = createScrollAnchor();
      scrollAnchor.attach(scrollContainer);

      const pendingFinalReveal = scrollAnchor.revealFinalContentIfFollowing();
      scrollContainer.dispatchEvent(createScrollIntentEvent());
      await vi.runAllTimersAsync();
      await pendingFinalReveal;

      expect(scrollToSpy).toHaveBeenCalledWith({ top: 3_657, behavior: "smooth" });
      expect(scrollAnchor.unseenCount).toBe(0);
      expect(scrollAnchor.showIndicator).toBe(false);
      scrollAnchor.cleanup();
    },
  );

  it("preserves a downward user scroll after an initial no-op programmatic scroll", async () => {
    vi.useFakeTimers();
    const scrollContainer = document.createElement("div");
    setScrollGeometry(scrollContainer, 0, 500, 500);
    const scrollToSpy = vi.spyOn(scrollContainer, "scrollTo");
    const scrollAnchor = createScrollAnchor({ indicatorDelayMs: 150 });
    scrollAnchor.attach(scrollContainer);
    await scrollAnchor.scrollOnce();

    setScrollGeometry(scrollContainer, 200, 1_000, 500);
    scrollContainer.dispatchEvent(new WheelEvent("wheel"));
    scrollAnchor.onUserScroll();
    await scrollAnchor.revealFinalContentIfFollowing();
    vi.advanceTimersByTime(150);

    expect(scrollToSpy).toHaveBeenCalledOnce();
    expect(scrollAnchor.unseenCount).toBe(1);
    expect(scrollAnchor.showIndicator).toBe(true);
    scrollAnchor.cleanup();
  });
});
