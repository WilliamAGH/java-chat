/**
 * Scroll management utilities for chat interfaces.
 *
 * Provides auto-scroll behavior that respects user scroll position,
 * enabling smooth streaming experiences without hijacking manual scrolling.
 */

import { tick } from 'svelte'

/** Default threshold in pixels for determining if user is "at bottom". */
const AUTO_SCROLL_THRESHOLD = 100

/**
 * Checks if the user is scrolled near the bottom of a container.
 * Used to determine if auto-scroll should be active during streaming.
 *
 * @param container - The scrollable container element
 * @param threshold - Distance from bottom in pixels to consider "at bottom"
 * @returns true if within threshold of bottom, false otherwise
 */
export function isNearBottom(container: HTMLElement | null, threshold = AUTO_SCROLL_THRESHOLD): boolean {
  if (!container) return true // Default to auto-scroll if no container
  const { scrollTop, scrollHeight, clientHeight } = container
  return scrollHeight - scrollTop - clientHeight < threshold
}

/**
 * Scrolls a container to the bottom with smooth animation.
 * Only scrolls if shouldScroll is true (typically based on isNearBottom check).
 *
 * @param container - The scrollable container element
 * @param shouldScroll - Whether to actually perform the scroll
 */
export async function scrollToBottom(container: HTMLElement | null, shouldScroll: boolean): Promise<void> {
  await tick()
  if (container && shouldScroll) {
    container.scrollTo({
      top: container.scrollHeight,
      behavior: 'smooth'
    })
  }
}

/**
 * Creates scroll management state and handlers for a chat container.
 * Returns reactive handlers that can be bound to Svelte components.
 *
 * @param threshold - Distance from bottom in pixels to consider "at bottom"
 * @returns Object with scroll handlers and state accessor
 */
export function createScrollManager(threshold = AUTO_SCROLL_THRESHOLD) {
  let shouldAutoScroll = true
  let container: HTMLElement | null = null

  return {
    /** Sets the container element to manage. */
    setContainer(element: HTMLElement | null): void {
      container = element
    },

    /** Checks scroll position and updates auto-scroll state. Bind to onscroll. */
    checkAutoScroll(): void {
      shouldAutoScroll = isNearBottom(container, threshold)
    },

    /** Scrolls to bottom if auto-scroll is enabled. Call after content updates. */
    async scrollToBottom(): Promise<void> {
      await scrollToBottom(container, shouldAutoScroll)
    },

    /** Forces auto-scroll to be enabled (e.g., when user sends a message). */
    enableAutoScroll(): void {
      shouldAutoScroll = true
    },

    /** Returns current auto-scroll state. */
    isAutoScrollEnabled(): boolean {
      return shouldAutoScroll
    }
  }
}
