<script lang="ts">
  import type { ChatMessage } from '../services/chat'
  import { parseMarkdown, applyJavaLanguageDetection } from '../services/markdown'
  import { createDebouncedHighlighter } from '../utils/highlight'

  interface Props {
    message: ChatMessage
    index: number
    isStreaming?: boolean
  }

  let { message, index, isStreaming = false }: Props = $props()

  let contentEl: HTMLElement | null = $state(null)

  /** Visual feedback state for clipboard operations. */
  let copyState = $state<'idle' | 'success' | 'error'>('idle')

  // Debounced highlighter with automatic cleanup
  const { scheduleHighlight, cleanup: cleanupHighlighter } = createDebouncedHighlighter()

  // Render markdown synchronously - SSR-safe parsing without DOM operations
  let renderedContent = $derived(
    message.role === 'assistant' && message.content
      ? parseMarkdown(message.content)
      : ''
  )

  // Apply Java language detection and highlight code blocks after render
  // Debounced to avoid flicker during streaming
  $effect(() => {
    if (renderedContent && contentEl) {
      if (isStreaming) {
        return cleanupHighlighter
      }
      // Apply Java language detection before highlighting (client-side DOM operation)
      applyJavaLanguageDetection(contentEl)
      scheduleHighlight(contentEl, isStreaming)
    }
    return cleanupHighlighter
  })

  /** Duration to show copy feedback before returning to idle. */
  const COPY_FEEDBACK_DURATION_MS = 1500

  /**
   * Copies text to clipboard with visual feedback.
   * Shows success/error state briefly before returning to idle.
   */
  async function copyToClipboard(text: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(text)
      copyState = 'success'
    } catch {
      copyState = 'error'
    }

    setTimeout(() => {
      copyState = 'idle'
    }, COPY_FEEDBACK_DURATION_MS)
  }

  let animationDelay = $derived(`${Math.min(index * 50, 200)}ms`)
</script>

<div
  class="message"
  class:user={message.role === 'user'}
  class:assistant={message.role === 'assistant'}
  class:error={message.isError}
  class:streaming={isStreaming}
  style:animation-delay={animationDelay}
>
  {#if message.role === 'assistant'}
    <div class="avatar">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
        <path d="M9.813 15.904 9 18.75l-.813-2.846a4.5 4.5 0 0 0-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 0 0 3.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 0 0 3.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 0 0-3.09 3.09ZM18.259 8.715 18 9.75l-.259-1.035a3.375 3.375 0 0 0-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 0 0 2.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 0 0 2.456 2.456L21.75 6l-1.035.259a3.375 3.375 0 0 0-2.456 2.456ZM16.894 20.567 16.5 21.75l-.394-1.183a2.25 2.25 0 0 0-1.423-1.423L13.5 18.75l1.183-.394a2.25 2.25 0 0 0 1.423-1.423l.394-1.183.394 1.183a2.25 2.25 0 0 0 1.423 1.423l1.183.394-1.183.394a2.25 2.25 0 0 0-1.423 1.423Z" />
      </svg>
    </div>
  {/if}

  <div class="bubble">
    {#if message.role === 'user'}
      <p class="user-text">{message.content}</p>
    {:else}
      <div class="assistant-content" bind:this={contentEl}>
        {#if renderedContent}
          {@html renderedContent}
        {:else}
          <p>{message.content}</p>
        {/if}
        <span class="cursor" class:visible={isStreaming}></span>
      </div>
    {/if}

    {#if message.role === 'assistant'}
      <div class="bubble-actions">
        <button
          type="button"
          class="action-btn"
          class:action-btn--success={copyState === 'success'}
          class:action-btn--error={copyState === 'error'}
          onclick={() => copyToClipboard(message.content)}
          title={copyState === 'success' ? 'Copied!' : copyState === 'error' ? 'Copy failed' : 'Copy message'}
          aria-label={copyState === 'success' ? 'Copied to clipboard' : copyState === 'error' ? 'Failed to copy' : 'Copy message'}
        >
          {#if copyState === 'success'}
            <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path fill-rule="evenodd" d="M16.704 4.153a.75.75 0 0 1 .143 1.052l-8 10.5a.75.75 0 0 1-1.127.075l-4.5-4.5a.75.75 0 0 1 1.06-1.06l3.894 3.893 7.48-9.817a.75.75 0 0 1 1.05-.143Z" clip-rule="evenodd"/>
            </svg>
          {:else if copyState === 'error'}
            <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path d="M6.28 5.22a.75.75 0 0 0-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 1 0 1.06 1.06L10 11.06l3.72 3.72a.75.75 0 1 0 1.06-1.06L11.06 10l3.72-3.72a.75.75 0 0 0-1.06-1.06L10 8.94 6.28 5.22Z"/>
            </svg>
          {:else}
            <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path d="M7 3.5A1.5 1.5 0 0 1 8.5 2h3.879a1.5 1.5 0 0 1 1.06.44l3.122 3.12A1.5 1.5 0 0 1 17 6.622V12.5a1.5 1.5 0 0 1-1.5 1.5h-1v-3.379a3 3 0 0 0-.879-2.121L10.5 5.379A3 3 0 0 0 8.379 4.5H7v-1Z"/>
              <path d="M4.5 6A1.5 1.5 0 0 0 3 7.5v9A1.5 1.5 0 0 0 4.5 18h7a1.5 1.5 0 0 0 1.5-1.5v-5.879a1.5 1.5 0 0 0-.44-1.06L9.44 6.439A1.5 1.5 0 0 0 8.378 6H4.5Z"/>
            </svg>
          {/if}
        </button>
      </div>
    {/if}
  </div>
</div>

<style>
  .message {
    display: flex;
    gap: var(--space-3);
    animation: fade-in-up var(--duration-normal) var(--ease-out) backwards;
  }

  .message.user {
    justify-content: flex-end;
  }

  .message.assistant {
    justify-content: flex-start;
  }

  /* Avatar */
  .avatar {
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
  }

  .avatar svg {
    width: 18px;
    height: 18px;
  }

  /* Bubble */
  .bubble {
    position: relative;
    overflow: visible;
    max-width: 85%;
    padding: var(--space-4);
    border-radius: var(--radius-xl);
    transition: background-color var(--duration-fast) var(--ease-out);
  }

  .message.user .bubble {
    background: var(--color-accent);
    color: white;
    border-bottom-right-radius: var(--radius-sm);
  }

  .message.assistant .bubble {
    background: var(--color-bg-secondary);
    border: 1px solid var(--color-border-subtle);
    border-bottom-left-radius: var(--radius-sm);
  }

  .message.error .bubble {
    background: rgba(196, 93, 93, 0.1);
    border-color: rgba(196, 93, 93, 0.2);
    color: var(--color-error);
  }

  /* Shared text wrapping for long unbroken strings (URLs, code identifiers) */
  .user-text,
  .assistant-content {
    overflow-wrap: break-word;
    word-break: break-word;
  }

  /* User text */
  .user-text {
    font-size: var(--text-base);
    line-height: var(--leading-relaxed);
    margin: 0;
  }

  /* Assistant content - prose styles */
  .assistant-content {
    font-size: var(--text-base);
    line-height: var(--leading-relaxed);
    color: var(--color-text-primary);
  }

  .assistant-content :global(p) {
    margin: 0 0 var(--space-4);
  }

  .assistant-content :global(p:last-child) {
    margin-bottom: 0;
  }

  .assistant-content :global(h1),
  .assistant-content :global(h2),
  .assistant-content :global(h3),
  .assistant-content :global(h4) {
    font-family: var(--font-serif);
    font-weight: 500;
    margin: var(--space-6) 0 var(--space-3);
    letter-spacing: var(--tracking-tight);
  }

  .assistant-content :global(h1:first-child),
  .assistant-content :global(h2:first-child),
  .assistant-content :global(h3:first-child) {
    margin-top: 0;
  }

  .assistant-content :global(h2) {
    font-size: var(--text-xl);
  }

  .assistant-content :global(h3) {
    font-size: var(--text-lg);
  }

  .assistant-content :global(ul),
  .assistant-content :global(ol) {
    margin: 0 0 var(--space-4);
    padding-left: var(--space-6);
  }

  .assistant-content :global(li) {
    margin-bottom: var(--space-2);
  }

  .assistant-content :global(li:last-child) {
    margin-bottom: 0;
  }

  .assistant-content :global(strong) {
    font-weight: 600;
    color: var(--color-text-primary);
  }

  .assistant-content :global(a) {
    color: var(--color-accent);
    text-decoration: underline;
    text-underline-offset: 2px;
  }

  .assistant-content :global(a:hover) {
    text-decoration-thickness: 2px;
  }

  .assistant-content :global(blockquote) {
    margin: var(--space-4) 0;
    padding: var(--space-3) var(--space-4);
    border-left: 3px solid var(--color-accent);
    background: var(--color-surface-subtle);
    border-radius: 0 var(--radius-md) var(--radius-md) 0;
    font-style: italic;
    color: var(--color-text-secondary);
  }

  /* Code blocks in assistant messages */
  .assistant-content :global(pre) {
    margin: var(--space-4) 0;
    padding: var(--space-4);
    background: var(--color-bg-primary);
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-lg);
    overflow-x: auto;
    font-size: var(--text-sm);
  }

  .assistant-content :global(pre code) {
    background: none;
    padding: 0;
    border: none;
    font-size: inherit;
    color: var(--color-text-primary);
  }

  /* Streaming cursor - always present, visibility controlled by class */
  .cursor {
    display: inline-block;
    width: 2px;
    height: 1.2em;
    background: var(--color-accent);
    margin-left: 2px;
    vertical-align: text-bottom;
    opacity: 0;
    transition: opacity 150ms ease-out;
  }

  .cursor.visible {
    opacity: 1;
    animation: typing-cursor 0.8s ease-in-out infinite;
  }

  @keyframes typing-cursor {
    0%, 100% { opacity: 1; }
    50% { opacity: 0; }
  }

  /* Actions - desktop hover only (never on touch / narrow viewports) */
  .bubble-actions {
    display: none;
    position: absolute;
    top: var(--space-2);
    left: calc(100% + var(--space-2));
    opacity: 0;
    pointer-events: none;
    transition: opacity var(--duration-fast) var(--ease-out);
  }

  /* Only show hover actions on wide viewports with a fine pointer (mouse/trackpad). */
  @media (hover: hover) and (pointer: fine) and (min-width: 641px) {
    .bubble-actions {
      display: block;
    }

    .bubble:hover .bubble-actions,
    .bubble:focus-within .bubble-actions {
      opacity: 1;
      pointer-events: auto;
    }
  }

  .action-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    background: var(--color-bg-elevated);
    border: 1px solid var(--color-border-default);
    border-radius: var(--radius-md);
    color: var(--color-text-tertiary);
    cursor: pointer;
    transition: all var(--duration-fast) var(--ease-out);
  }

  .action-btn:hover {
    color: var(--color-text-primary);
    background: var(--color-bg-hover);
  }

  .action-btn--success {
    background: var(--color-success);
    border-color: var(--color-success);
    color: white;
  }

  .action-btn--success:hover {
    background: var(--color-success);
    color: white;
  }

  .action-btn--error {
    background: var(--color-error);
    border-color: var(--color-error);
    color: white;
  }

  .action-btn--error:hover {
    background: var(--color-error);
    color: white;
  }

  .action-btn svg {
    width: 14px;
    height: 14px;
  }

  /* Tablet */
  @media (max-width: 768px) {
    .bubble {
      max-width: 88%;
    }
  }

  /* Mobile */
  @media (max-width: 640px) {
    .message {
      gap: var(--space-2);
    }

    .bubble {
      max-width: 92%;
      padding: var(--space-3);
    }

    .avatar {
      width: 28px;
      height: 28px;
    }

    .avatar svg {
      width: 16px;
      height: 16px;
    }

    .assistant-content {
      font-size: var(--text-sm);
    }

    .assistant-content :global(pre) {
      padding: var(--space-3);
      font-size: var(--text-xs);
      margin: var(--space-3) calc(-1 * var(--space-3));
      border-radius: 0;
      border-left: none;
      border-right: none;
    }

    .assistant-content :global(code:not(pre code)) {
      font-size: 0.8em;
    }
  }

  /* Small phones */
  @media (max-width: 380px) {
    .bubble {
      max-width: 95%;
      padding: var(--space-2) var(--space-3);
    }

    .avatar {
      width: 24px;
      height: 24px;
    }

    .avatar svg {
      width: 14px;
      height: 14px;
    }

    .user-text {
      font-size: var(--text-sm);
    }
  }
</style>
