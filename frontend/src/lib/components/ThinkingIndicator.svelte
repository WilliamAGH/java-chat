<script lang="ts">
  import { fade } from 'svelte/transition'

  /**
   * ThinkingIndicator - Transparent AI processing status display.
   *
   * Shows what the assistant is actually doing (searching the documentation
   * index, reranking matches, connecting) as calm editorial text rather than
   * a decorated card. Status text wraps in full — nothing is truncated.
   *
   * The single motion cue is a slow shine sweeping the status message, which
   * marks the text as live. Everything else is static typography.
   */

  interface Props {
    /** Primary status message (e.g., "Searching the Java documentation index") */
    statusMessage?: string | null
    /** Secondary details (e.g., "Embedding your question… · Provider: gpt-5.4") */
    statusDetails?: string | null
    /** Whether we've started receiving content (transitions to "generating" state) */
    hasContent?: boolean
  }

  let {
    statusMessage = null,
    statusDetails = null,
    hasContent = false
  }: Props = $props()

  // Empty-string statuses (the streaming state's idle value) fall back to the
  // phase default so the indicator never renders a blank message.
  let displayMessage = $derived(statusMessage || (hasContent ? 'Generating response' : 'Connecting'))
</script>

<div class="thinking-indicator" role="status" aria-live="polite">
  <!-- Same avatar the assistant bubble uses, so the status reads as the
       assistant's pending message rather than a separate widget. -->
  <div class="thinking-avatar" aria-hidden="true">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
      <path d="M9.813 15.904 9 18.75l-.813-2.846a4.5 4.5 0 0 0-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 0 0 3.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 0 0 3.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 0 0-3.09 3.09ZM18.259 8.715 18 9.75l-.259-1.035a3.375 3.375 0 0 0-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 0 0 2.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 0 0 2.456 2.456L21.75 6l-1.035.259a3.375 3.375 0 0 0-2.456 2.456ZM16.894 20.567 16.5 21.75l-.394-1.183a2.25 2.25 0 0 0-1.423-1.423L13.5 18.75l1.183-.394a2.25 2.25 0 0 0 1.423-1.423l.394-1.183.394 1.183a2.25 2.25 0 0 0 1.423 1.423l1.183.394-1.183.394a2.25 2.25 0 0 0-1.423 1.423Z" />
    </svg>
  </div>

  <div class="status-content">
    {#key displayMessage}
      <p class="status-message" in:fade={{ duration: 180 }}>{displayMessage}</p>
    {/key}
    {#if statusDetails}
      <p class="status-details">{statusDetails}</p>
    {/if}
  </div>
</div>

<style>
  .thinking-indicator {
    /* Avatar size lives on the container so the text block can optically
       align its first line against it at every breakpoint. */
    --avatar-size: 32px;

    display: flex;
    align-items: flex-start;
    gap: var(--space-3);
  }

  .thinking-avatar {
    flex-shrink: 0;
    width: var(--avatar-size);
    height: var(--avatar-size);
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
  }

  /* Split enter: avatar lands first, text follows a beat later. */
  .status-content {
    flex: 1;
    min-width: 0;
    /* Long detail lines stay readable instead of stretching full width. */
    max-width: 60ch;
    /* Optically center the first text line against the square avatar. */
    padding-top: calc((var(--avatar-size) - var(--text-sm) * var(--leading-snug)) / 2);
    animation: fade-in-up var(--duration-normal) var(--ease-out) 80ms backwards;
  }

  .status-message {
    margin: 0;
    font-size: var(--text-sm);
    font-weight: 500;
    line-height: var(--leading-snug);
    text-wrap: pretty;
    overflow-wrap: break-word;
    color: var(--color-text-secondary);
    /* A slow bright band sweeping the glyphs is the only "live" cue. */
    background: linear-gradient(
      90deg,
      var(--color-text-secondary) 30%,
      var(--color-text-primary) 50%,
      var(--color-text-secondary) 70%
    );
    background-size: 200% 100%;
    -webkit-background-clip: text;
    background-clip: text;
    -webkit-text-fill-color: transparent;
    animation: status-shine 2.4s linear infinite;
  }

  @keyframes status-shine {
    from {
      background-position: 200% 0;
    }
    to {
      background-position: -200% 0;
    }
  }

  .status-details {
    margin: var(--space-1) 0 0;
    font-size: var(--text-xs);
    line-height: var(--leading-relaxed);
    color: var(--color-text-tertiary);
    text-wrap: pretty;
    overflow-wrap: break-word;
  }

  /* Reduced motion: static secondary-colored text, no shine. */
  @media (prefers-reduced-motion: reduce) {
    .thinking-avatar,
    .status-content {
      animation: fade-in var(--duration-fast) ease-out backwards;
    }

    .status-message {
      animation: none;
      background: none;
      -webkit-text-fill-color: var(--color-text-secondary);
    }
  }

  /* Mobile */
  @media (max-width: 640px) {
    .thinking-indicator {
      --avatar-size: 28px;
      gap: var(--space-2);
    }

    .thinking-avatar svg {
      width: 16px;
      height: 16px;
    }
  }

  /* Small phones */
  @media (max-width: 380px) {
    .thinking-indicator {
      --avatar-size: 24px;
    }

    .thinking-avatar svg {
      width: 14px;
      height: 14px;
    }
  }
</style>
