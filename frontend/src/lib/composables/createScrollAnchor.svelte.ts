/**
 * Reactive scroll indicator composable for streaming chat interfaces.
 *
 * Implements an **inverted scroll model** where:
 * - Streaming never auto-scrolls until the user explicitly jumps to the newest content
 * - An explicit jump follows the active stream until the user scrolls away again
 * - Settled final content is revealed while the user follows the newest message
 * - User scroll position is always respected
 * - Indicator appears only when new content arrives after a genuine scroll-away
 * - Indicator disappears when user scrolls to ~95% of content
 *
 * This approach eliminates "scroll fighting" by disabling default streaming
 * auto-scroll. The user remains in control because only an explicit jump
 * enables stream following, and any genuine scroll-away disables it immediately.
 *
 * @example
 * ```svelte
 * <script lang="ts">
 *   import { createScrollAnchor } from '../composables/createScrollAnchor.svelte'
 *
 *   const scroll = createScrollAnchor()
 *   let container: HTMLElement | null = $state(null)
 *
 *   $effect(() => {
 *     if (container) scroll.attach(container)
 *   })
 *
 *   // Call on each streaming chunk - never auto-scrolls, just tracks
 *   function onChunk(text: string) {
 *     appendContent(text)
 *     scroll.onContentAdded()
 *   }
 *
 *   // Call when user sends a message - scrolls once, no anchoring
 *   async function handleSend(message: string) {
 *     addMessage(message)
 *     await scroll.scrollOnce()
 *     // ... start streaming
 *   }
 * </script>
 *
 * <div bind:this={container} onscroll={scroll.onUserScroll}>
 *   ...messages...
 * </div>
 *
 * {#if scroll.showIndicator}
 *   <NewContentIndicator
 *     count={scroll.unseenCount}
 *     onClick={scroll.jumpToBottom}
 *   />
 * {/if}
 * ```
 */

import { tick } from "svelte";

/** Configuration options for scroll indicator behavior. */
export interface ScrollAnchorOptions {
  /**
   * Percentage of scroll position to consider "near bottom" (0-1).
   * When user scrolls past this percentage, the indicator hides.
   * @default 0.95 (95% - user is within 5% of bottom)
   */
  nearBottomThreshold?: number;

  /**
   * Delay before showing the new content indicator (in milliseconds).
   * Prevents flicker for brief scroll-aways.
   * @default 150
   */
  indicatorDelayMs?: number;
}

/** Default configuration values. */
const DEFAULT_NEAR_BOTTOM_THRESHOLD = 0.95;
const DEFAULT_INDICATOR_DELAY_MS = 150;
/**
 * Bounds the post-scroll reconciliation passes. One extra pass covers a single
 * round of content that renders after the reveal target is first measured (the
 * common case: a citation panel appears after the answer text settles). Two
 * passes keep the reveal correct if that late content itself grows the layout
 * a second time, without looping on unbounded async growth.
 */
const MAX_FINAL_REVEAL_RECONCILIATION_PASSES = 2;
const USER_SCROLL_KEYS = new Set([
  "ArrowDown",
  "ArrowUp",
  "End",
  "Home",
  "PageDown",
  "PageUp",
  " ",
]);

/**
 * Creates a reactive scroll indicator for chat containers.
 *
 * Returns an object with reactive state (via Svelte 5 runes) and methods to wire
 * up scroll behavior. Streaming chunks never auto-scroll; settled final content
 * is revealed only while the user remains on the newest-message path.
 */
export function createScrollAnchor(options: ScrollAnchorOptions = {}) {
  const nearBottomThreshold = options.nearBottomThreshold ?? DEFAULT_NEAR_BOTTOM_THRESHOLD;
  const indicatorDelayMs = options.indicatorDelayMs ?? DEFAULT_INDICATOR_DELAY_MS;

  // Internal state
  let container: HTMLElement | null = null;
  let indicatorTimeoutId: ReturnType<typeof setTimeout> | null = null;
  let scrollStateVersion = 0;

  // Reactive state (Svelte 5 runes)
  let unseenCount = $state(0);
  let showIndicator = $state(false);
  let followsNewestContent = true;
  let followsActiveStreamAfterJump = false;
  let activeStreamFollowPromise: Promise<void> | null = null;
  let activeStreamFollowDirty = false;
  let programmaticScrollActive = false;
  let userScrollIntent = false;
  let userScrollIntentVersion = 0;
  let userScrollIntentStartOffset: number | null = null;

  function markUserScrollIntent(): void {
    userScrollIntent = true;
    userScrollIntentVersion++;
    userScrollIntentStartOffset = container?.scrollTop ?? null;
    programmaticScrollActive = false;
  }

  function markKeyboardScrollIntent(keyboardEvent: KeyboardEvent): void {
    if (USER_SCROLL_KEYS.has(keyboardEvent.key)) {
      markUserScrollIntent();
    }
  }

  function detachUserIntentListeners(): void {
    if (!container) return;
    container.removeEventListener("wheel", markUserScrollIntent);
    container.removeEventListener("touchstart", markUserScrollIntent);
    container.removeEventListener("pointerdown", markUserScrollIntent);
    container.removeEventListener("keydown", markKeyboardScrollIntent);
  }

  /**
   * Checks if the container is scrolled near the bottom.
   * Uses percentage-based threshold (default 95%).
   */
  function isNearBottom(): boolean {
    if (!container) return true;
    const { scrollTop, scrollHeight, clientHeight } = container;

    // Handle edge case: content fits without scrolling
    if (scrollHeight <= clientHeight) return true;

    // Calculate scroll percentage (0 = top, 1 = bottom)
    const maxScroll = scrollHeight - clientHeight;
    const scrollPercentage = scrollTop / maxScroll;

    return scrollPercentage >= nearBottomThreshold;
  }

  /**
   * Updates the indicator visibility with debouncing.
   */
  function updateIndicatorVisibility(): void {
    if (unseenCount > 0 && !isNearBottom()) {
      if (showIndicator || indicatorTimeoutId) return;
      indicatorTimeoutId = setTimeout(() => {
        indicatorTimeoutId = null;
        showIndicator = unseenCount > 0 && !isNearBottom();
      }, indicatorDelayMs);
      return;
    }

    showIndicator = false;
    if (indicatorTimeoutId) {
      clearTimeout(indicatorTimeoutId);
      indicatorTimeoutId = null;
    }
  }

  /**
   * Clears indicator state and pending timeouts.
   * Internal helper that doesn't rely on `this` binding.
   */
  function clearIndicatorStateInternal(): void {
    scrollStateVersion++;
    hideIndicatorInternal();
  }

  /** Hides the indicator without invalidating an in-flight content measurement. */
  function hideIndicatorInternal(): void {
    unseenCount = 0;
    showIndicator = false;

    if (indicatorTimeoutId) {
      clearTimeout(indicatorTimeoutId);
      indicatorTimeoutId = null;
    }
  }

  /** Records that streamed or final content arrived while the user was away from the bottom. */
  function claimUnseenContent(): void {
    if (unseenCount === 0) {
      unseenCount = 1;
    }
    updateIndicatorVisibility();
  }

  /** Stops all automatic following after geometry confirms that the user scrolled away. */
  function stopFollowingNewestContent(): void {
    scrollStateVersion++;
    followsNewestContent = false;
    followsActiveStreamAfterJump = false;
    activeStreamFollowPromise = null;
    activeStreamFollowDirty = false;
    userScrollIntent = false;
    userScrollIntentStartOffset = null;
    programmaticScrollActive = false;
  }

  /**
   * Lets the browser apply a user input before deciding whether it changed scroll geometry.
   *
   * Wheel, touch, pointer, and keyboard input can be consumed without moving the container.
   * Deferring one task preserves that distinction: genuine scroll-away stops following,
   * while a non-scrolling interaction leaves the explicit follow choice intact.
   */
  async function settleUserScrollIntent(): Promise<void> {
    if (!userScrollIntent) {
      return;
    }
    await new Promise<void>((resolveIntent) => {
      setTimeout(resolveIntent, 0);
    });
    if (!userScrollIntent) {
      return;
    }
    if (
      userScrollIntentStartOffset === null ||
      container?.scrollTop === userScrollIntentStartOffset
    ) {
      userScrollIntent = false;
      userScrollIntentStartOffset = null;
      return;
    }
    stopFollowingNewestContent();
  }

  /**
   * Performs the actual scroll-to-bottom with motion preferences.
   *
   * Settled final content (the answer text) and content that renders afterward
   * (a citation panel, a highlighted code block) can land in separate Svelte
   * render ticks. Scrolling only to the height measured before that late
   * content renders leaves the bottom of the final message obscured behind the
   * sticky composer. Each pass awaits a Svelte render tick to flush pending DOM
   * updates, re-measures `scrollHeight`, and re-scrolls when the layout grew,
   * so the final message tail stays visible above the composer. Bounded by
   * {@link MAX_FINAL_REVEAL_RECONCILIATION_PASSES}.
   */
  async function performScroll(): Promise<boolean> {
    const scrollContainer = container;
    const scrollVersion = scrollStateVersion;
    if (!scrollContainer || userScrollIntent) {
      return false;
    }
    programmaticScrollActive = true;
    await tick();
    if (container !== scrollContainer || scrollStateVersion !== scrollVersion || userScrollIntent) {
      return false;
    }

    const prefersReducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    let performedScroll = false;
    let previousScrollHeight = -1;
    for (let pass = 0; pass <= MAX_FINAL_REVEAL_RECONCILIATION_PASSES; pass++) {
      await tick();
      if (
        container !== scrollContainer ||
        scrollStateVersion !== scrollVersion ||
        userScrollIntent
      ) {
        return false;
      }
      const currentScrollHeight = scrollContainer.scrollHeight;
      if (currentScrollHeight === previousScrollHeight) {
        break;
      }
      previousScrollHeight = currentScrollHeight;
      performedScroll = true;
      programmaticScrollActive = true;
      scrollContainer.scrollTo({
        top: currentScrollHeight,
        behavior: prefersReducedMotion ? "auto" : "smooth",
      });
    }
    return performedScroll;
  }

  /** Reveals final content after non-scrolling input settles, without overriding a scroll-away. */
  async function revealFinalContentWhileFollowing(): Promise<void> {
    const revealedContainer = container;
    let revealedScrollStateVersion = scrollStateVersion;
    for (;;) {
      if (!revealedContainer || container !== revealedContainer) {
        return;
      }
      if (scrollStateVersion !== revealedScrollStateVersion) {
        if (!followsNewestContent) {
          claimUnseenContent();
        }
        return;
      }
      if (!followsNewestContent) {
        claimUnseenContent();
        return;
      }
      await settleUserScrollIntent();
      if (container !== revealedContainer || scrollStateVersion !== revealedScrollStateVersion) {
        if (container === revealedContainer && !followsNewestContent) {
          claimUnseenContent();
        }
        return;
      }
      if (!followsNewestContent) {
        claimUnseenContent();
        return;
      }
      clearIndicatorStateInternal();
      revealedScrollStateVersion = scrollStateVersion;
      if (await performScroll()) {
        return;
      }
      if (!userScrollIntent) {
        return;
      }
    }
  }

  /** Coalesces streamed chunks while retaining a dirty signal for content rendered later. */
  async function followActiveStream(): Promise<void> {
    const followedContainer = container;
    const followedScrollStateVersion = scrollStateVersion;
    let followedScrollHeight = -1;
    do {
      activeStreamFollowDirty = false;
      const pendingIntentVersion = userScrollIntentVersion;
      const hadPendingUserScrollIntent = userScrollIntent;
      await tick();
      if (!followedContainer || container !== followedContainer) {
        return;
      }
      if (scrollStateVersion !== followedScrollStateVersion || !followsActiveStreamAfterJump) {
        if (!followsNewestContent && !isNearBottom()) {
          claimUnseenContent();
        }
        return;
      }
      if (
        !hadPendingUserScrollIntent &&
        userScrollIntent &&
        userScrollIntentVersion !== pendingIntentVersion
      ) {
        return;
      }
      await settleUserScrollIntent();
      if (
        container !== followedContainer ||
        scrollStateVersion !== followedScrollStateVersion ||
        !followsActiveStreamAfterJump
      ) {
        if (container === followedContainer && !followsNewestContent && !isNearBottom()) {
          claimUnseenContent();
        }
        return;
      }
      hideIndicatorInternal();
      const currentScrollHeight = followedContainer.scrollHeight;
      if (currentScrollHeight === followedScrollHeight) {
        continue;
      }
      followedScrollHeight = currentScrollHeight;
      programmaticScrollActive = true;
      followedContainer.scrollTo({
        top: currentScrollHeight,
        behavior: "auto",
      });
    } while (activeStreamFollowDirty);
  }

  return {
    // ─────────────────────────────────────────────────────────────────────────
    // Reactive getters (read-only state)
    // ─────────────────────────────────────────────────────────────────────────

    /** Number of content updates since user was not at bottom. */
    get unseenCount(): number {
      return unseenCount;
    },

    /** Whether to show the "new content" indicator. */
    get showIndicator(): boolean {
      return showIndicator;
    },

    // ─────────────────────────────────────────────────────────────────────────
    // Setup methods
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Attaches the scroll anchor to a container element.
     * Call this when the container is mounted or changes.
     */
    attach(scrollContainer: HTMLElement | null): void {
      detachUserIntentListeners();
      if (container !== scrollContainer) {
        scrollStateVersion++;
        activeStreamFollowPromise = null;
        activeStreamFollowDirty = false;
        userScrollIntent = false;
        userScrollIntentStartOffset = null;
        programmaticScrollActive = false;
      }
      container = scrollContainer;
      if (!container) return;
      container.addEventListener("wheel", markUserScrollIntent, { passive: true });
      container.addEventListener("touchstart", markUserScrollIntent, { passive: true });
      container.addEventListener("pointerdown", markUserScrollIntent, { passive: true });
      container.addEventListener("keydown", markKeyboardScrollIntent);
    },

    /**
     * Cleanup function to clear any pending timeouts.
     * Call this when the component unmounts.
     */
    cleanup(): void {
      detachUserIntentListeners();
      scrollStateVersion++;
      followsActiveStreamAfterJump = false;
      activeStreamFollowPromise = null;
      activeStreamFollowDirty = false;
      userScrollIntent = false;
      userScrollIntentStartOffset = null;
      programmaticScrollActive = false;
      container = null;
      if (indicatorTimeoutId) {
        clearTimeout(indicatorTimeoutId);
        indicatorTimeoutId = null;
      }
    },

    // ─────────────────────────────────────────────────────────────────────────
    // Event handlers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Handles user scroll events. Bind to `onscroll` on the container.
     *
     * When user scrolls to ~95% of content:
     * - Clears unseen count
     * - Hides indicator
     */
    onUserScroll(): void {
      if (!container) return;

      if (isNearBottom()) {
        followsNewestContent = true;
        userScrollIntent = false;
        userScrollIntentStartOffset = null;
        programmaticScrollActive = false;
        // User reached near-bottom, clear indicator
        unseenCount = 0;
        showIndicator = false;

        if (indicatorTimeoutId) {
          clearTimeout(indicatorTimeoutId);
          indicatorTimeoutId = null;
        }
      } else if (userScrollIntent) {
        stopFollowingNewestContent();
      } else if (!userScrollIntent && !programmaticScrollActive) {
        stopFollowingNewestContent();
      }
    },

    /**
     * Called when a new message starts streaming.
     *
     * Increments the unseen message count (not chunk count) only when the user
     * genuinely scrolled away and follow disengaged. Content growth alone never
     * counts: the send flow re-engages follow via `scrollOnce()`, so a geometry
     * dip during its smooth scroll must not surface the indicator. Call this
     * once per new assistant message, not on every streaming chunk.
     */
    onNewMessageStarted(): void {
      if (!followsNewestContent && !isNearBottom()) {
        unseenCount++;
        updateIndicatorVisibility();
      }
    },

    /**
     * Called when new content is added to the container (streaming chunks).
     *
     * Waits for the streamed DOM update. After an explicit indicator jump, it
     * follows the active stream until genuine user scroll intent moves away.
     * Otherwise, it claims the active message once when content grows while
     * follow is disengaged and keeps that count stable for subsequent chunks.
     * While follow is engaged the viewport belongs to the stream, so growth
     * past the bottom edge never surfaces the indicator.
     */
    async onContentAdded(): Promise<void> {
      const contentContainer = container;
      const contentScrollStateVersion = scrollStateVersion;
      if (followsActiveStreamAfterJump) {
        activeStreamFollowDirty = true;
        if (activeStreamFollowPromise) {
          await activeStreamFollowPromise;
          return;
        }
        const ownedStreamFollowPromise = followActiveStream();
        activeStreamFollowPromise = ownedStreamFollowPromise;
        try {
          await ownedStreamFollowPromise;
        } finally {
          if (activeStreamFollowPromise === ownedStreamFollowPromise) {
            activeStreamFollowPromise = null;
          }
        }
        return;
      }
      await tick();
      if (
        !contentContainer ||
        container !== contentContainer ||
        scrollStateVersion !== contentScrollStateVersion
      ) {
        return;
      }
      if (userScrollIntent) {
        await settleUserScrollIntent();
        if (container !== contentContainer || scrollStateVersion !== contentScrollStateVersion) {
          if (container === contentContainer && !followsNewestContent && !isNearBottom()) {
            claimUnseenContent();
          }
          return;
        }
      }
      if (!followsNewestContent && !isNearBottom()) {
        claimUnseenContent();
      }
      // Follow engaged or user at bottom - no need for indicator
    },

    /**
     * Clears indicator state and pending timeouts.
     * Public API that delegates to internal helper.
     */
    clearIndicatorState(): void {
      clearIndicatorStateInternal();
    },

    /**
     * Scrolls to bottom once. Use when user sends a message.
     *
     * Unlike the old `anchor()` + `jumpToBottom()` pattern, this:
     * - Does NOT enable any auto-scroll behavior
     * - Simply scrolls once and clears the indicator
     */
    async scrollOnce(): Promise<void> {
      followsNewestContent = true;
      followsActiveStreamAfterJump = false;
      clearIndicatorStateInternal();
      await performScroll();
    },

    /**
     * Reveals settled success or error content unless the user intentionally scrolled away.
     */
    async revealFinalContentIfFollowing(): Promise<void> {
      followsActiveStreamAfterJump = false;
      if (followsNewestContent) {
        await revealFinalContentWhileFollowing();
        return;
      }
      claimUnseenContent();
    },

    /**
     * Programmatically scrolls to bottom and clears indicator.
     * Use this for the "jump to bottom" button click handler.
     *
     * Note: Uses internal function reference to avoid `this` binding
     * issues when passed as a callback prop.
     */
    async jumpToBottom(): Promise<void> {
      followsNewestContent = true;
      followsActiveStreamAfterJump = true;
      userScrollIntent = false;
      userScrollIntentStartOffset = null;
      clearIndicatorStateInternal();
      activeStreamFollowPromise = null;
      await performScroll();
    },

    /**
     * Resets all state. Use when clearing chat or switching contexts.
     */
    reset(): void {
      followsNewestContent = true;
      followsActiveStreamAfterJump = false;
      activeStreamFollowPromise = null;
      activeStreamFollowDirty = false;
      programmaticScrollActive = false;
      userScrollIntent = false;
      userScrollIntentStartOffset = null;
      clearIndicatorStateInternal();
    },
  };
}

/** Type for the scroll anchor instance. */
export type ScrollAnchor = ReturnType<typeof createScrollAnchor>;
