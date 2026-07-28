import { afterEach, describe, expect, it, vi } from "vitest";
import { createScrollAnchor } from "./createScrollAnchor.svelte";

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
