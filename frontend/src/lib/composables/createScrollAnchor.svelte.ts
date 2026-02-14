/**
 * Reactive scroll indicator composable for streaming chat interfaces.
 *
 * Implements an **inverted scroll model** where:
 * - Auto-scroll is NEVER enabled during streaming
 * - User scroll position is always respected
 * - Indicator appears when new content streams off-screen
 * - Indicator disappears when user scrolls to ~95% of content
 *
 * This approach eliminates "scroll fighting" by removing auto-scroll entirely.
 * The user is always in control of their scroll position.
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
 * Creates a reactive scroll indicator for chat containers.
 *
 * Returns an object with reactive state (via Svelte 5 runes) and
 * methods to wire up scroll behavior. Auto-scroll is completely disabled;
 * only the "new content" indicator and manual jump-to-bottom are provided.
 */
export function createScrollAnchor(options: ScrollAnchorOptions = {}) {
  const nearBottomThreshold = options.nearBottomThreshold ?? DEFAULT_NEAR_BOTTOM_THRESHOLD;
  const indicatorDelayMs = options.indicatorDelayMs ?? DEFAULT_INDICATOR_DELAY_MS;

  // Internal state
  let container: HTMLElement | null = null;
  let indicatorTimeoutId: ReturnType<typeof setTimeout> | null = null;

  // Reactive state (Svelte 5 runes)
  let unseenCount = $state(0);
  let showIndicator = $state(false);

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
    if (indicatorTimeoutId) {
      clearTimeout(indicatorTimeoutId);
      indicatorTimeoutId = null;
    }

    if (unseenCount > 0 && !isNearBottom()) {
      // Delay showing indicator to prevent flicker
      indicatorTimeoutId = setTimeout(() => {
        showIndicator = true;
      }, indicatorDelayMs);
    } else {
      showIndicator = false;
    }
  }

  /**
   * Clears indicator state and pending timeouts.
   * Internal helper that doesn't rely on `this` binding.
   */
  function clearIndicatorStateInternal(): void {
    unseenCount = 0;
    showIndicator = false;

    if (indicatorTimeoutId) {
      clearTimeout(indicatorTimeoutId);
      indicatorTimeoutId = null;
    }
  }

  /**
   * Performs the actual scroll-to-bottom with motion preferences.
   */
  async function performScroll(): Promise<void> {
    await tick();
    if (!container) return;

    const prefersReducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    container.scrollTo({
      top: container.scrollHeight,
      behavior: prefersReducedMotion ? "auto" : "smooth",
    });
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
    attach(element: HTMLElement | null): void {
      container = element;
    },

    /**
     * Cleanup function to clear any pending timeouts.
     * Call this when the component unmounts.
     */
    cleanup(): void {
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
        // User reached near-bottom, clear indicator
        unseenCount = 0;
        showIndicator = false;

        if (indicatorTimeoutId) {
          clearTimeout(indicatorTimeoutId);
          indicatorTimeoutId = null;
        }
      }
    },

    /**
     * Called when a new message starts streaming.
     *
     * Increments the unseen message count (not chunk count) when user
     * is scrolled away from bottom. Call this once per new assistant
     * message, not on every streaming chunk.
     */
    onNewMessageStarted(): void {
      if (!isNearBottom()) {
        unseenCount++;
        updateIndicatorVisibility();
      }
    },

    /**
     * Called when new content is added to the container (streaming chunks).
     *
     * **Never auto-scrolls.** Only updates indicator visibility based on
     * current scroll position. Does NOT increment the count—use
     * `onNewMessageStarted()` when a new message begins.
     */
    onContentAdded(): void {
      if (!isNearBottom()) {
        // User is scrolled up - update visibility but don't increment count
        // (count is incremented once per message via onNewMessageStarted)
        updateIndicatorVisibility();
      }
      // User at bottom - no need for indicator
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
      clearIndicatorStateInternal();
      await performScroll();
    },

    /**
     * Programmatically scrolls to bottom and clears indicator.
     * Use this for the "jump to bottom" button click handler.
     *
     * Note: Uses internal function reference to avoid `this` binding
     * issues when passed as a callback prop.
     */
    async jumpToBottom(): Promise<void> {
      clearIndicatorStateInternal();
      await performScroll();
    },

    /**
     * Resets all state. Use when clearing chat or switching contexts.
     */
    reset(): void {
      clearIndicatorStateInternal();
    },
  };
}

/** Type for the scroll anchor instance. */
export type ScrollAnchor = ReturnType<typeof createScrollAnchor>;
