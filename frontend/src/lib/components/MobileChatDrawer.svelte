<script lang="ts">
  import type { Snippet } from 'svelte'
  import type { ChatMessage } from '../services/chat'
  import ChatInput from './ChatInput.svelte'
  import StreamingMessagesList from './StreamingMessagesList.svelte'

  /**
   * Mobile chat drawer with FAB trigger.
   *
   * Provides a slide-up drawer UI for chat on mobile devices, including:
   * - Floating action button (FAB) with badge/streaming indicator
   * - Full-screen backdrop
   * - Header with title, clear, and close actions
   * - Scrollable message list using StreamingMessagesList
   * - Fixed-position chat input
   */
  interface Props {
    isOpen: boolean
    messages: ChatMessage[]
    isStreaming: boolean
    statusMessage: string
    statusDetails: string
    /** Whether the current in-progress assistant message has content yet. */
    hasContent: boolean
    /** Stable identifier for the in-progress assistant message (if present). */
    streamingMessageId?: string | null
    /** Custom renderer for each message (e.g., to append citations). */
    messageRenderer?: Snippet<[{ message: ChatMessage; index: number; isStreaming: boolean }]>
    /** Title shown in drawer header */
    title: string
    /** Subject for empty state prompt (e.g., "Variables and Data Types") */
    emptyStateSubject: string
    placeholder: string
    onToggle: () => void
    onClose: () => void
    onClear: () => void
    onSend: (message: string) => void
    onScroll: () => void
  }

  let {
    isOpen,
    messages,
    isStreaming,
    statusMessage,
    statusDetails,
    hasContent,
    streamingMessageId = null,
    messageRenderer,
    title,
    emptyStateSubject,
    placeholder,
    onToggle,
    onClose,
    onClear,
    onSend,
    onScroll
  }: Props = $props()

  let messagesContainer: HTMLElement | null = $state(null)

  /** Exposes the messages container element for external scroll management. */
  export function getMessagesContainer(): HTMLElement | null {
    return messagesContainer
  }
</script>

<!-- Mobile Chat FAB -->
<button
  type="button"
  class="chat-fab"
  onclick={onToggle}
  aria-label="Ask questions about this lesson"
  aria-expanded={isOpen}
>
  {#if isStreaming}
    <div class="fab-streaming-indicator"></div>
  {:else if messages.length > 0}
    <span class="fab-badge">{messages.length}</span>
  {/if}
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
    <path d="M7.5 8.25h9m-9 3H12m-9.75 1.51c0 1.6 1.123 2.994 2.707 3.227 1.129.166 2.27.293 3.423.379.35.026.67.21.865.501L12 21l2.755-4.133a1.14 1.14 0 0 1 .865-.501 48.172 48.172 0 0 0 3.423-.379c1.584-.233 2.707-1.626 2.707-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0 0 12 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018Z"/>
  </svg>
</button>

<!-- Mobile Chat Drawer -->
{#if isOpen}
  <div class="chat-drawer-backdrop" onclick={onClose} aria-hidden="true"></div>
  <div class="chat-drawer" role="dialog" aria-label="Lesson chat">
    <div class="chat-drawer-header">
      <div class="chat-drawer-title">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
          <path d="M7.5 8.25h9m-9 3H12m-9.75 1.51c0 1.6 1.123 2.994 2.707 3.227 1.129.166 2.27.293 3.423.379.35.026.67.21.865.501L12 21l2.755-4.133a1.14 1.14 0 0 1 .865-.501 48.172 48.172 0 0 0 3.423-.379c1.584-.233 2.707-1.626 2.707-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0 0 12 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018Z"/>
        </svg>
        <span>{title}</span>
      </div>
      <div class="chat-drawer-actions">
        {#if messages.length > 0}
          <button type="button" class="drawer-action-btn" onclick={onClear} title="Clear chat">
            <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path fill-rule="evenodd" d="M8.75 1A2.75 2.75 0 0 0 6 3.75v.443c-.795.077-1.584.176-2.365.298a.75.75 0 1 0 .23 1.482l.149-.022.841 10.518A2.75 2.75 0 0 0 7.596 19h4.807a2.75 2.75 0 0 0 2.742-2.53l.841-10.519.149.023a.75.75 0 0 0 .23-1.482A41.03 41.03 0 0 0 14 4.193V3.75A2.75 2.75 0 0 0 11.25 1h-2.5ZM10 4c.84 0 1.673.025 2.5.075V3.75c0-.69-.56-1.25-1.25-1.25h-2.5c-.69 0-1.25.56-1.25 1.25v.325C8.327 4.025 9.16 4 10 4ZM8.58 7.72a.75.75 0 0 0-1.5.06l.3 7.5a.75.75 0 1 0 1.5-.06l-.3-7.5Zm4.34.06a.75.75 0 1 0-1.5-.06l-.3 7.5a.75.75 0 1 0 1.5.06l.3-7.5Z" clip-rule="evenodd"/>
            </svg>
          </button>
        {/if}
        <button type="button" class="drawer-close-btn" onclick={onClose} aria-label="Close chat">
          <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
            <path d="M6.28 5.22a.75.75 0 0 0-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 1 0 1.06 1.06L10 11.06l3.72 3.72a.75.75 0 1 0 1.06-1.06L11.06 10l3.72-3.72a.75.75 0 0 0-1.06-1.06L10 8.94 6.28 5.22Z"/>
          </svg>
        </button>
      </div>
    </div>

    <div class="chat-drawer-messages" bind:this={messagesContainer} onscroll={onScroll}>
      {#if messages.length === 0 && !isStreaming}
        <div class="chat-empty">
          <p>Have questions about <strong>{emptyStateSubject}</strong>?</p>
          <p class="hint">Ask anything about the concepts in this lesson.</p>
        </div>
      {:else}
        <StreamingMessagesList
          {messages}
          {isStreaming}
          {statusMessage}
          {statusDetails}
          {hasContent}
          {streamingMessageId}
          {messageRenderer}
        />
      {/if}
    </div>

    <div class="chat-drawer-input">
      <ChatInput {onSend} disabled={isStreaming} {placeholder} />
    </div>
  </div>
{/if}

<style>
  /* Mobile Chat FAB - hidden on desktop, shown via media query */
  .chat-fab {
    display: none;
    position: fixed;
    bottom: var(--space-6);
    right: var(--space-6);
    width: 56px;
    height: 56px;
    padding: 0;
    background: var(--color-accent);
    border: none;
    border-radius: 50%;
    color: white;
    cursor: pointer;
    box-shadow: var(--shadow-lg);
    transition: all var(--duration-fast) var(--ease-out);
    z-index: 50;
  }

  .chat-fab:hover {
    background: var(--color-accent-hover);
    transform: scale(1.05);
  }

  .chat-fab:active {
    transform: scale(0.95);
  }

  .chat-fab svg {
    width: 24px;
    height: 24px;
  }

  .fab-badge {
    position: absolute;
    top: -4px;
    right: -4px;
    min-width: 20px;
    height: 20px;
    padding: 0 6px;
    background: var(--color-error);
    border-radius: 10px;
    font-size: var(--text-xs);
    font-weight: 600;
    color: white;
    display: flex;
    align-items: center;
    justify-content: center;
  }

  .fab-streaming-indicator {
    position: absolute;
    top: -4px;
    right: -4px;
    width: 16px;
    height: 16px;
    background: var(--color-success);
    border-radius: 50%;
    animation: pulse 1.5s infinite;
  }

  @keyframes pulse {
    0%, 100% { opacity: 1; transform: scale(1); }
    50% { opacity: 0.7; transform: scale(1.2); }
  }

  /* Mobile Chat Drawer */
  .chat-drawer-backdrop {
    display: none;
    position: fixed;
    inset: 0;
    background: rgba(0, 0, 0, 0.5);
    z-index: 60;
    animation: fade-in var(--duration-fast) var(--ease-out);
  }

  @keyframes fade-in {
    from { opacity: 0; }
    to { opacity: 1; }
  }

  .chat-drawer {
    display: none;
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    height: 85vh;
    max-height: 85vh;
    background: var(--color-bg-primary);
    border-radius: var(--radius-xl) var(--radius-xl) 0 0;
    box-shadow: var(--shadow-xl);
    z-index: 70;
    flex-direction: column;
    animation: slide-up var(--duration-normal) var(--ease-out);
  }

  @keyframes slide-up {
    from { transform: translateY(100%); }
    to { transform: translateY(0); }
  }

  .chat-drawer-header {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: var(--space-4);
    border-bottom: 1px solid var(--color-border-subtle);
  }

  .chat-drawer-title {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    font-size: var(--text-base);
    font-weight: 500;
    color: var(--color-text-primary);
  }

  .chat-drawer-title svg {
    width: 20px;
    height: 20px;
    color: var(--color-accent);
  }

  .chat-drawer-actions {
    display: flex;
    align-items: center;
    gap: var(--space-2);
  }

  .drawer-action-btn,
  .drawer-close-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 36px;
    height: 36px;
    padding: 0;
    background: transparent;
    border: none;
    border-radius: var(--radius-md);
    color: var(--color-text-secondary);
    cursor: pointer;
    transition: all var(--duration-fast) var(--ease-out);
  }

  .drawer-action-btn:hover,
  .drawer-close-btn:hover {
    background: var(--color-bg-tertiary);
    color: var(--color-text-primary);
  }

  .drawer-action-btn svg,
  .drawer-close-btn svg {
    width: 20px;
    height: 20px;
  }

  .chat-drawer-messages {
    flex: 1;
    overflow-y: auto;
    padding: var(--space-4);
  }

  .chat-drawer-input {
    flex-shrink: 0;
    border-top: 1px solid var(--color-border-subtle);
    padding-bottom: env(safe-area-inset-bottom, 0);
  }

  .chat-empty {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    height: 100%;
    text-align: center;
    color: var(--color-text-secondary);
    padding: var(--space-6);
  }

  .chat-empty p {
    margin: 0;
  }

  .chat-empty .hint {
    font-size: var(--text-sm);
    color: var(--color-text-tertiary);
    margin-top: var(--space-2);
  }

  /* Show FAB and drawer on mobile (≤1024px) */
  @media (max-width: 1024px) {
    .chat-fab {
      display: flex;
      align-items: center;
      justify-content: center;
    }

    .chat-drawer-backdrop {
      display: block;
    }

    .chat-drawer {
      display: flex;
    }
  }

  @media (max-width: 640px) {
    .chat-fab {
      bottom: var(--space-4);
      right: var(--space-4);
      width: 52px;
      height: 52px;
    }

    .chat-fab svg {
      width: 22px;
      height: 22px;
    }

    .chat-drawer {
      height: 90vh;
      max-height: 90vh;
    }
  }
</style>
