<script lang="ts">
  /**
   * Floating indicator for new content below the viewport.
   *
   * Appears when the user has scrolled up during streaming, showing
   * them that new content is available. Clicking jumps to bottom
   * and re-enables auto-scroll.
   *
   * Design: A refined pill that floats above the input area, using
   * the terracotta accent with subtle glow. Respects reduced motion.
   */

  interface Props {
    /** Whether to show the indicator. */
    visible: boolean
    /** Number of unseen content chunks (shown as badge when > 0). */
    unseenCount?: number
    /** Callback when user clicks to jump to bottom. */
    onJumpToBottom: () => void
  }

  let { visible, unseenCount = 0, onJumpToBottom }: Props = $props()

  /** Format the unseen count for display. */
  let displayCount = $derived(unseenCount > 99 ? '99+' : String(unseenCount))
  let showCount = $derived(unseenCount > 0)
</script>

{#if visible}
  <button
    type="button"
    class="new-content-indicator"
    onclick={onJumpToBottom}
    aria-label={showCount ? `${unseenCount} new updates, jump to bottom` : 'Jump to new content'}
  >
    <span class="indicator-content">
      <svg
        class="indicator-arrow"
        viewBox="0 0 16 16"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        stroke-linecap="round"
        stroke-linejoin="round"
        aria-hidden="true"
      >
        <path d="M8 3v10M4 9l4 4 4-4" />
      </svg>
      <span class="indicator-text">New content</span>
      {#if showCount}
        <span class="indicator-badge">{displayCount}</span>
      {/if}
    </span>
  </button>
{/if}

<style>
  .new-content-indicator {
    position: absolute;
    bottom: var(--space-4);
    left: 50%;
    transform: translateX(-50%);
    z-index: 20;

    display: flex;
    align-items: center;
    justify-content: center;

    padding: var(--space-2) var(--space-4);
    background: var(--color-bg-elevated);
    border: 1px solid var(--color-accent-muted);
    border-radius: var(--radius-full);
    box-shadow:
      var(--shadow-md),
      0 0 20px rgba(196, 93, 58, 0.15);

    font-family: var(--font-sans);
    font-size: var(--text-sm);
    font-weight: 500;
    color: var(--color-text-primary);
    cursor: pointer;

    /* Animation */
    animation: indicator-enter var(--duration-normal) var(--ease-spring);
    transition:
      background var(--duration-fast) var(--ease-out),
      border-color var(--duration-fast) var(--ease-out),
      box-shadow var(--duration-fast) var(--ease-out),
      transform var(--duration-fast) var(--ease-out);
  }

  @keyframes indicator-enter {
    from {
      opacity: 0;
      transform: translateX(-50%) translateY(8px) scale(0.95);
    }
    to {
      opacity: 1;
      transform: translateX(-50%) translateY(0) scale(1);
    }
  }

  /* Reduced motion: simpler fade */
  @media (prefers-reduced-motion: reduce) {
    .new-content-indicator {
      animation: indicator-fade-in var(--duration-fast) ease-out;
    }

    @keyframes indicator-fade-in {
      from { opacity: 0; }
      to { opacity: 1; }
    }
  }

  .new-content-indicator:hover {
    background: var(--color-bg-hover);
    border-color: var(--color-accent);
    box-shadow:
      var(--shadow-lg),
      0 0 24px rgba(196, 93, 58, 0.25);
    transform: translateX(-50%) translateY(-2px);
  }

  .new-content-indicator:active {
    transform: translateX(-50%) translateY(0);
    box-shadow:
      var(--shadow-sm),
      0 0 16px rgba(196, 93, 58, 0.12);
  }

  /* Focus ring */
  .new-content-indicator:focus-visible {
    outline: 2px solid var(--color-accent);
    outline-offset: 2px;
  }

  .indicator-content {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }

  .indicator-arrow {
    width: 14px;
    height: 14px;
    color: var(--color-accent);
    flex-shrink: 0;

    /* Subtle bounce animation */
    animation: arrow-bounce 1.5s var(--ease-in-out) infinite;
  }

  @keyframes arrow-bounce {
    0%, 100% {
      transform: translateY(0);
    }
    50% {
      transform: translateY(2px);
    }
  }

  @media (prefers-reduced-motion: reduce) {
    .indicator-arrow {
      animation: none;
    }
  }

  .indicator-text {
    color: var(--color-text-secondary);
    letter-spacing: var(--tracking-wide);
  }

  .indicator-badge {
    display: flex;
    align-items: center;
    justify-content: center;
    min-width: 20px;
    height: 20px;
    padding: 0 var(--space-1);
    background: var(--color-accent);
    border-radius: var(--radius-full);
    font-size: var(--text-xs);
    font-weight: 600;
    color: white;
    letter-spacing: normal;
  }

  /* Mobile adjustments */
  @media (max-width: 640px) {
    .new-content-indicator {
      padding: var(--space-2) var(--space-3);
      font-size: var(--text-xs);
    }

    .indicator-arrow {
      width: 12px;
      height: 12px;
    }

    .indicator-badge {
      min-width: 18px;
      height: 18px;
      font-size: 10px;
    }
  }
</style>
