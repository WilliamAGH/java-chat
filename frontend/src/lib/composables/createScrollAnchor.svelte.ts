/**
 * Reactive scroll anchor composable for streaming chat interfaces.
 *
 * Solves the "scroll fighting" problem where auto-scroll hijacks control
 * from users who are reading earlier content. Provides:
 *
 * 1. **Intent detection** - Distinguishes user scrolling from programmatic scrolls
 * 2. **Throttled updates** - Batches rapid scroll-to-bottom calls during streaming
 * 3. **New content tracking** - Counts unseen content when user is scrolled up
 * 4. **Re-engagement** - Jump-to-bottom restores auto-scroll behavior
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
 *   // Call on each streaming chunk
 *   function onChunk(text: string) {
 *     appendContent(text)
 *     scroll.onContentAdded()
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

import { tick } from 'svelte'

/** Configuration options for scroll anchor behavior. */
export interface ScrollAnchorOptions {
  /**
   * Distance from bottom (in pixels) to consider "at bottom".
   * Users within this threshold are auto-scrolled on new content.
   * @default 100
   */
  threshold?: number

  /**
   * Minimum interval between scroll-to-bottom calls (in milliseconds).
   * Prevents scroll spam during rapid streaming chunks.
   * @default 50
   */
  throttleMs?: number

  /**
   * Delay before showing the new content indicator (in milliseconds).
   * Prevents flicker for brief scroll-aways.
   * @default 150
   */
  indicatorDelayMs?: number
}

/** Default configuration values. */
const DEFAULT_THRESHOLD = 100
const DEFAULT_THROTTLE_MS = 50
const DEFAULT_INDICATOR_DELAY_MS = 150

/**
 * Creates a reactive scroll anchor for chat containers.
 *
 * Returns an object with reactive state (via Svelte 5 runes) and
 * methods to wire up scroll behavior.
 */
export function createScrollAnchor(options: ScrollAnchorOptions = {}) {
  const threshold = options.threshold ?? DEFAULT_THRESHOLD
  const throttleMs = options.throttleMs ?? DEFAULT_THROTTLE_MS
  const indicatorDelayMs = options.indicatorDelayMs ?? DEFAULT_INDICATOR_DELAY_MS

  // Internal state
  let container: HTMLElement | null = null
  let lastScrollTop = 0
  let lastScrollTime = 0
  let indicatorTimeoutId: ReturnType<typeof setTimeout> | null = null
  let isScrollingProgrammatically = false

  // Reactive state (Svelte 5 runes)
  let isAnchored = $state(true)
  let unseenCount = $state(0)
  let showIndicator = $state(false)

  /**
   * Checks if the container is scrolled near the bottom.
   */
  function isNearBottom(): boolean {
    if (!container) return true
    const { scrollTop, scrollHeight, clientHeight } = container
    return scrollHeight - scrollTop - clientHeight < threshold
  }

  /**
   * Detects scroll direction from the last known position.
   * Returns 'up', 'down', or 'none'.
   */
  function detectScrollDirection(): 'up' | 'down' | 'none' {
    if (!container) return 'none'
    const currentScrollTop = container.scrollTop
    if (currentScrollTop < lastScrollTop - 5) return 'up'
    if (currentScrollTop > lastScrollTop + 5) return 'down'
    return 'none'
  }

  /**
   * Updates the indicator visibility with debouncing.
   */
  function updateIndicatorVisibility(): void {
    if (indicatorTimeoutId) {
      clearTimeout(indicatorTimeoutId)
      indicatorTimeoutId = null
    }

    if (!isAnchored && unseenCount > 0) {
      // Delay showing indicator to prevent flicker
      indicatorTimeoutId = setTimeout(() => {
        showIndicator = true
      }, indicatorDelayMs)
    } else {
      showIndicator = false
    }
  }

  /**
   * Performs the actual scroll-to-bottom with motion preferences.
   */
  async function performScroll(): Promise<void> {
    await tick()
    if (!container) return

    const prefersReducedMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
    isScrollingProgrammatically = true

    container.scrollTo({
      top: container.scrollHeight,
      behavior: prefersReducedMotion ? 'auto' : 'smooth'
    })

    // Reset flag after scroll completes (approximate)
    setTimeout(() => {
      isScrollingProgrammatically = false
      lastScrollTop = container?.scrollTop ?? 0
    }, prefersReducedMotion ? 0 : 300)
  }

  return {
    // ─────────────────────────────────────────────────────────────────────────
    // Reactive getters (read-only state)
    // ─────────────────────────────────────────────────────────────────────────

    /** Whether auto-scroll is currently active. */
    get isAnchored(): boolean {
      return isAnchored
    },

    /** Number of content updates since user scrolled away. */
    get unseenCount(): number {
      return unseenCount
    },

    /** Whether to show the "new content" indicator. */
    get showIndicator(): boolean {
      return showIndicator
    },

    // ─────────────────────────────────────────────────────────────────────────
    // Setup methods
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Attaches the scroll anchor to a container element.
     * Call this when the container is mounted or changes.
     */
    attach(element: HTMLElement | null): void {
      container = element
      if (container) {
        lastScrollTop = container.scrollTop
        isAnchored = isNearBottom()
      }
    },

    /**
     * Cleanup function to clear any pending timeouts.
     * Call this when the component unmounts.
     */
    cleanup(): void {
      if (indicatorTimeoutId) {
        clearTimeout(indicatorTimeoutId)
        indicatorTimeoutId = null
      }
    },

    // ─────────────────────────────────────────────────────────────────────────
    // Event handlers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Handles user scroll events. Bind to `onscroll` on the container.
     *
     * Detects user intent:
     * - Scrolling UP = user is reading, disable auto-scroll
     * - Scrolling to bottom = user wants to follow, enable auto-scroll
     */
    onUserScroll(): void {
      if (!container || isScrollingProgrammatically) return

      const direction = detectScrollDirection()
      const nearBottom = isNearBottom()

      // User scrolled up - they want to read, disable auto-scroll
      if (direction === 'up' && !nearBottom) {
        isAnchored = false
        updateIndicatorVisibility()
      }

      // User scrolled back to bottom - re-enable auto-scroll
      if (nearBottom && !isAnchored) {
        isAnchored = true
        unseenCount = 0
        showIndicator = false
        if (indicatorTimeoutId) {
          clearTimeout(indicatorTimeoutId)
          indicatorTimeoutId = null
        }
      }

      lastScrollTop = container.scrollTop
    },

    /**
     * Called when new content is added to the container.
     *
     * If anchored: scrolls to bottom (throttled)
     * If not anchored: increments unseen count
     */
    onContentAdded(): void {
      if (isAnchored) {
        // Throttle scroll-to-bottom calls
        const now = Date.now()
        if (now - lastScrollTime >= throttleMs) {
          lastScrollTime = now
          void performScroll()
        }
      } else {
        // Track unseen content
        unseenCount++
        updateIndicatorVisibility()
      }
    },

    /**
     * Programmatically scrolls to bottom and re-enables auto-scroll.
     * Use this for the "jump to bottom" button click handler.
     */
    async jumpToBottom(): Promise<void> {
      isAnchored = true
      unseenCount = 0
      showIndicator = false

      if (indicatorTimeoutId) {
        clearTimeout(indicatorTimeoutId)
        indicatorTimeoutId = null
      }

      await performScroll()
    },

    /**
     * Forces auto-scroll to be enabled.
     * Use when the user sends a new message.
     */
    anchor(): void {
      isAnchored = true
      unseenCount = 0
      showIndicator = false

      if (indicatorTimeoutId) {
        clearTimeout(indicatorTimeoutId)
        indicatorTimeoutId = null
      }
    },

    /**
     * Resets all state. Use when clearing chat or switching contexts.
     */
    reset(): void {
      isAnchored = true
      unseenCount = 0
      showIndicator = false
      lastScrollTop = 0
      lastScrollTime = 0

      if (indicatorTimeoutId) {
        clearTimeout(indicatorTimeoutId)
        indicatorTimeoutId = null
      }
    }
  }
}

/** Type for the scroll anchor instance. */
export type ScrollAnchor = ReturnType<typeof createScrollAnchor>
