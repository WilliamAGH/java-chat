<script lang="ts">
  import { fade } from 'svelte/transition'

  /**
   * ThinkingIndicator - Transparent AI processing status display.
   *
   * Replaces opaque "bouncing dots" with meaningful status updates that show
   * what the AI is actually doing: searching knowledge base, finding documents,
   * generating response, etc.
   *
   * Designed to match the "Warm Precision" aesthetic with terracotta accents.
   */

  /** Processing phases with distinct visual treatments. */
  export type ProcessingPhase = 'connecting' | 'searching' | 'generating'

  /** Default status messages for each processing phase. */
  const PHASE_DEFAULT_MESSAGES: Record<ProcessingPhase, string> = {
    connecting: 'Connecting',
    searching: 'Searching',
    generating: 'Generating response'
  } as const

  /** Fallback message when phase is unknown (defensive). */
  const FALLBACK_MESSAGE = 'Processing'

  /** Phase icon types for template rendering. */
  type PhaseIconType = 'dots' | 'search' | 'sparkle'

  /** Maps each phase to its icon type for polymorphic rendering. */
  const PHASE_ICONS: Record<ProcessingPhase, PhaseIconType> = {
    connecting: 'dots',
    searching: 'search',
    generating: 'sparkle'
  } as const

  /** SVG path data for phase icons (extracted to avoid inline duplication). */
  const ICON_PATHS = {
    search: 'M9 3.5a5.5 5.5 0 1 0 0 11 5.5 5.5 0 0 0 0-11ZM2 9a7 7 0 1 1 12.452 4.391l3.328 3.329a.75.75 0 1 1-1.06 1.06l-3.329-3.328A7 7 0 0 1 2 9Z',
    sparkle: 'M10.868 2.884c-.321-.772-1.415-.772-1.736 0l-1.83 4.401-4.753.381c-.833.067-1.171 1.107-.536 1.651l3.62 3.102-1.106 4.637c-.194.813.691 1.456 1.405 1.02L10 15.591l4.069 2.485c.713.436 1.598-.207 1.404-1.02l-1.106-4.637 3.62-3.102c.635-.544.297-1.584-.536-1.65l-4.752-.382-1.831-4.401Z'
  } as const

  interface Props {
    /** Primary status message (e.g., "Searching knowledge base") */
    statusMessage?: string | null
    /** Secondary details (e.g., "Java Stream API, version 24") */
    statusDetails?: string | null
    /** Whether we've started receiving content (transitions to "generating" state) */
    hasContent?: boolean
  }

  let {
    statusMessage = null,
    statusDetails = null,
    hasContent = false
  }: Props = $props()

  // Derive the current phase for visual treatment
  let phase = $derived.by((): ProcessingPhase => {
    if (hasContent) return 'generating'
    if (statusMessage) return 'searching'
    return 'connecting'
  })

  // Derive icon type from phase (map lookup instead of conditional)
  let iconType = $derived(PHASE_ICONS[phase])

  // Default messages for each phase (map lookup instead of switch)
  let displayMessage = $derived(statusMessage ?? PHASE_DEFAULT_MESSAGES[phase] ?? FALLBACK_MESSAGE)
</script>

<div class="thinking-indicator" data-phase={phase}>
  <!-- Avatar with animated state -->
  <div class="thinking-avatar">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
      <path d="M9.813 15.904 9 18.75l-.813-2.846a4.5 4.5 0 0 0-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 0 0 3.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 0 0 3.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 0 0-3.09 3.09ZM18.259 8.715 18 9.75l-.259-1.035a3.375 3.375 0 0 0-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 0 0 2.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 0 0 2.456 2.456L21.75 6l-1.035.259a3.375 3.375 0 0 0-2.456 2.456ZM16.894 20.567 16.5 21.75l-.394-1.183a2.25 2.25 0 0 0-1.423-1.423L13.5 18.75l1.183-.394a2.25 2.25 0 0 0 1.423-1.423l.394-1.183.394 1.183a2.25 2.25 0 0 0 1.423 1.423l1.183.394-1.183.394a2.25 2.25 0 0 0-1.423 1.423Z" />
    </svg>
  </div>

  <!-- Status card -->
  <div class="thinking-card">
    <!-- Phase icons cross-fade: dots for connecting, search/sparkle for later phases -->
    <div class="phase-icon" aria-hidden="true">
      <div class="phase-icon-layer" class:phase-icon-active={iconType === 'dots'}>
        <div class="dots-row">
          <span class="dot"></span>
          <span class="dot"></span>
          <span class="dot"></span>
        </div>
      </div>
      <div class="phase-icon-layer" class:phase-icon-active={iconType === 'search'}>
        <svg class="icon-search" viewBox="0 0 20 20" fill="currentColor">
          <path fill-rule="evenodd" clip-rule="evenodd" d={ICON_PATHS.search}/>
        </svg>
      </div>
      <div class="phase-icon-layer" class:phase-icon-active={iconType === 'sparkle'}>
        <svg class="icon-sparkle" viewBox="0 0 20 20" fill="currentColor">
          <path d={ICON_PATHS.sparkle}/>
        </svg>
      </div>
    </div>

    <!-- Status content -->
    <div class="status-content">
      {#key displayMessage}
        <p class="status-message" in:fade={{ duration: 180 }}>{displayMessage}</p>
      {/key}
      {#if statusDetails}
        <p class="status-details">{statusDetails}</p>
      {/if}
    </div>

    <!-- Progress shimmer -->
    <div class="shimmer"></div>
  </div>
</div>

<style>
  /* Component-scoped design tokens for animation timing.
   * Shimmer ratios create visual rhythm offset from dot bounce:
   * - 1.07x bounce: shimmer slightly out-of-phase prevents lockstep monotony
   * - 0.8x glow: faster shimmer during search feels more active/urgent */
  .thinking-indicator {
    --shimmer-duration: calc(var(--duration-bounce) * 1.07);
    --shimmer-duration-fast: calc(var(--duration-glow) * 0.8);
    /* Decelerate curve reserved for phase-icon cross-fades. */
    --ease-icon-swap: cubic-bezier(0.2, 0, 0, 1);

    display: flex;
    gap: var(--space-3);
  }

  /* Split enter: avatar lands first, card follows a beat later. */
  .thinking-avatar {
    flex-shrink: 0;
    width: 32px;
    height: 32px;
    display: flex;
    align-items: center;
    justify-content: center;
    background: var(--color-accent-subtle);
    border: 1px solid var(--color-accent-muted);
    border-radius: var(--radius-md);
    color: var(--color-accent);
    animation: fade-in-up var(--duration-normal) var(--ease-out) backwards;
  }

  .thinking-avatar svg {
    width: 18px;
    height: 18px;
    animation: sparkle-pulse var(--duration-pulse) ease-in-out infinite;
  }

  @keyframes sparkle-pulse {
    0%, 100% {
      opacity: 0.7;
      transform: scale(0.95);
    }
    50% {
      opacity: 1;
      transform: scale(1);
    }
  }

  /* Status card */
  .thinking-card {
    position: relative;
    display: flex;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-3) var(--space-4);
    background: var(--color-bg-secondary);
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-xl);
    border-bottom-left-radius: var(--radius-sm);
    overflow: hidden;
    min-width: 180px;
    max-width: 320px;
    animation: fade-in-up var(--duration-normal) var(--ease-out) 80ms backwards;
  }

  /* Phase icon container: stacked layers cross-fade on phase change
   * (scale 0.25→1, opacity 0→1, blur 4px→0) so swaps feel continuous
   * rather than hard cuts. */
  .phase-icon {
    position: relative;
    flex-shrink: 0;
    width: 24px;
    height: 24px;
    color: var(--color-accent);
  }

  .phase-icon-layer {
    position: absolute;
    inset: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    opacity: 0;
    transform: scale(0.25);
    filter: blur(4px);
    transition:
      opacity var(--duration-normal) var(--ease-icon-swap),
      transform var(--duration-normal) var(--ease-icon-swap),
      filter var(--duration-normal) var(--ease-icon-swap);
  }

  .phase-icon-layer.phase-icon-active {
    opacity: 1;
    transform: scale(1);
    filter: blur(0);
  }

  /* Dots animation for connecting phase */
  .dots-row {
    display: flex;
    gap: var(--loading-dot-gap);
  }

  .dot {
    width: var(--loading-dot-size);
    height: var(--loading-dot-size);
    background: var(--color-accent);
    border-radius: 50%;
    animation: bounce var(--duration-bounce) ease-in-out infinite;
  }

  .dot:nth-child(1) { animation-delay: -0.32s; }
  .dot:nth-child(2) { animation-delay: -0.16s; }
  .dot:nth-child(3) { animation-delay: 0s; }

  /* Icons for searching/generating */
  .icon-search {
    width: 18px;
    height: 18px;
    animation: search-scan var(--shimmer-duration) ease-in-out infinite;
  }

  @keyframes search-scan {
    0%, 100% {
      transform: translateX(0);
      opacity: 0.7;
    }
    50% {
      transform: translateX(var(--space-0, 2px));
      opacity: 1;
    }
  }

  .icon-sparkle {
    width: 18px;
    height: 18px;
    animation: sparkle-glow var(--duration-glow) ease-in-out infinite;
  }

  /* Gentle opacity/scale pulse; animating filter (drop-shadow) forces
   * expensive repaints every frame, so the glow is conveyed by motion. */
  @keyframes sparkle-glow {
    0%, 100% {
      opacity: 0.75;
      transform: scale(0.92);
    }
    50% {
      opacity: 1;
      transform: scale(1);
    }
  }

  /* Status content */
  .status-content {
    flex: 1;
    min-width: 0;
  }

  .status-message {
    margin: 0;
    font-size: var(--text-sm);
    font-weight: 500;
    color: var(--color-text-secondary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .status-details {
    margin: var(--space-1) 0 0;
    font-size: var(--text-xs);
    color: var(--color-text-muted);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    animation: detail-fade-in var(--duration-slow) ease-out;
  }

  @keyframes detail-fade-in {
    from {
      opacity: 0;
      transform: translateY(calc(-1 * var(--loading-dot-gap)));
    }
    to {
      opacity: 1;
      transform: translateY(0);
    }
  }

  /* Progress shimmer effect */
  .shimmer {
    position: absolute;
    bottom: 0;
    left: 0;
    right: 0;
    height: var(--space-0, 2px);
    background: linear-gradient(
      90deg,
      transparent 0%,
      var(--color-accent-muted) 20%,
      var(--color-accent) 50%,
      var(--color-accent-muted) 80%,
      transparent 100%
    );
    background-size: 200% 100%;
    animation: shimmer-slide var(--shimmer-duration) ease-in-out infinite;
  }

  @keyframes shimmer-slide {
    0% {
      background-position: 200% 0;
    }
    100% {
      background-position: -200% 0;
    }
  }

  /* Phase-specific treatments */
  [data-phase="searching"] .thinking-card {
    border-color: var(--color-accent-muted);
  }

  [data-phase="generating"] .shimmer {
    animation-duration: var(--shimmer-duration-fast);
    background: linear-gradient(
      90deg,
      transparent 0%,
      var(--color-accent) 40%,
      var(--color-accent-hover) 50%,
      var(--color-accent) 60%,
      transparent 100%
    );
    background-size: 200% 100%;
  }

  /* Reduced motion: keep the status readable, drop all ambient motion. */
  @media (prefers-reduced-motion: reduce) {
    .thinking-avatar,
    .thinking-card {
      animation: fade-in var(--duration-fast) ease-out backwards;
    }

    .thinking-avatar svg,
    .dot,
    .icon-search,
    .icon-sparkle,
    .shimmer {
      animation: none;
    }

    .phase-icon-layer {
      transition-property: opacity;
    }

    .shimmer {
      opacity: 0.4;
    }
  }

  /* Mobile adjustments */
  @media (max-width: 640px) {
    .thinking-avatar {
      width: 28px;
      height: 28px;
    }

    .thinking-avatar svg {
      width: 16px;
      height: 16px;
    }

    .thinking-card {
      padding: var(--space-2) var(--space-3);
      min-width: 140px;
      max-width: 260px;
    }

    .phase-icon {
      width: 20px;
      height: 20px;
    }

    .dot {
      width: calc(var(--loading-dot-size) - 1px);
      height: calc(var(--loading-dot-size) - 1px);
    }

    .icon-search,
    .icon-sparkle {
      width: 16px;
      height: 16px;
    }

    .status-message {
      font-size: var(--text-xs);
    }
  }

  /* Small phones */
  @media (max-width: 380px) {
    .thinking-indicator {
      gap: var(--space-2);
    }

    .thinking-avatar {
      width: 24px;
      height: 24px;
    }

    .thinking-avatar svg {
      width: 14px;
      height: 14px;
    }

    .thinking-card {
      min-width: 120px;
      max-width: 200px;
    }
  }
</style>
