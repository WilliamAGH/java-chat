<script lang="ts">
  import type { ChatMessage, Citation } from '../services/chat'
  import ChatInput from './ChatInput.svelte'
  import CitationPanel from './CitationPanel.svelte'
  import MessageBubble from './MessageBubble.svelte'
  import StreamingMessagesList from './StreamingMessagesList.svelte'

  /** Chat message type enriched with streamed citations. */
  interface MessageWithCitations extends ChatMessage {
    citations?: Citation[]
  }

  interface Props {
    messages: MessageWithCitations[]
    isStreaming: boolean
    statusMessage: string
    statusDetails: string
    hasContent: boolean
    streamingMessageId: string | null
    lessonTitle: string
    onClear: () => void
    onSend: (message: string) => void
    onScroll: () => void
  }

  let {
    messages,
    isStreaming,
    statusMessage,
    statusDetails,
    hasContent,
    streamingMessageId,
    lessonTitle,
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

<div class="chat-panel chat-panel--desktop">
  <div class="chat-panel-header">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
      <path d="M7.5 8.25h9m-9 3H12m-9.75 1.51c0 1.6 1.123 2.994 2.707 3.227 1.129.166 2.27.293 3.423.379.35.026.67.21.865.501L12 21l2.755-4.133a1.14 1.14 0 0 1 .865-.501 48.172 48.172 0 0 0 3.423-.379c1.584-.233 2.707-1.626 2.707-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0 0 12 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018Z"/>
    </svg>
    <span>Ask about this lesson</span>
    {#if messages.length > 0}
      <button type="button" class="clear-chat-btn" onclick={onClear} title="Clear chat">
        <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
          <path fill-rule="evenodd" d="M8.75 1A2.75 2.75 0 0 0 6 3.75v.443c-.795.077-1.584.176-2.365.298a.75.75 0 1 0 .23 1.482l.149-.022.841 10.518A2.75 2.75 0 0 0 7.596 19h4.807a2.75 2.75 0 0 0 2.742-2.53l.841-10.519.149.023a.75.75 0 0 0 .23-1.482A41.03 41.03 0 0 0 14 4.193V3.75A2.75 2.75 0 0 0 11.25 1h-2.5ZM10 4c.84 0 1.673.025 2.5.075V3.75c0-.69-.56-1.25-1.25-1.25h-2.5c-.69 0-1.25.56-1.25 1.25v.325C8.327 4.025 9.16 4 10 4ZM8.58 7.72a.75.75 0 0 0-1.5.06l.3 7.5a.75.75 0 1 0 1.5-.06l-.3-7.5Zm4.34.06a.75.75 0 1 0-1.5-.06l-.3 7.5a.75.75 0 1 0 1.5.06l.3-7.5Z" clip-rule="evenodd"/>
        </svg>
      </button>
    {/if}
  </div>

  <div class="messages-container" bind:this={messagesContainer} onscroll={onScroll}>
    {#if messages.length === 0 && !isStreaming}
      <div class="chat-empty">
        <p>Have questions about <strong>{lessonTitle}</strong>?</p>
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
      >
        {#snippet messageRenderer({ message, index, isStreaming })}
          {@const typedMessage = message as MessageWithCitations}
          <div class="message-with-citations">
            <MessageBubble message={typedMessage} index={index} isStreaming={isStreaming} />
            {#if typedMessage.role === 'assistant' && typedMessage.citations && typedMessage.citations.length > 0 && !typedMessage.isError}
              <CitationPanel citations={typedMessage.citations} />
            {/if}
          </div>
        {/snippet}
      </StreamingMessagesList>
    {/if}
  </div>

  <ChatInput {onSend} disabled={isStreaming} placeholder="Ask about this lesson..." />
</div>

<style>
  /* Chat Panel - Pinned Frame */
  .chat-panel {
    display: flex;
    flex-direction: column;
    height: 100%;
    min-height: 0; /* Critical: allows flex children to shrink for scrolling */
    overflow: hidden; /* Contains scrolling to messages-container only */
    background: var(--color-bg-primary);
    border-left: 1px solid var(--color-border-default);
    box-shadow: -4px 0 24px rgba(0, 0, 0, 0.15);
    position: relative;
  }

  /* Subtle pinned indicator line at top */
  .chat-panel::before {
    content: '';
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    height: 2px;
    background: linear-gradient(
      90deg,
      var(--color-accent-muted) 0%,
      var(--color-accent) 50%,
      var(--color-accent-muted) 100%
    );
    opacity: 0.6;
    z-index: 1;
  }

  .chat-panel-header {
    flex-shrink: 0; /* Never shrink - stays at top */
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-3) var(--space-4);
    border-bottom: 1px solid var(--color-border-subtle);
    background: var(--color-bg-secondary);
    font-size: var(--text-sm);
    font-weight: 500;
    color: var(--color-text-secondary);
  }

  .chat-panel-header > svg {
    flex-shrink: 0;
    width: 18px;
    height: 18px;
    color: var(--color-accent);
  }

  .chat-panel-header > span {
    flex: 1;
  }

  .clear-chat-btn {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    padding: 0;
    background: transparent;
    border: none;
    border-radius: var(--radius-md);
    color: var(--color-text-tertiary);
    cursor: pointer;
    transition: all var(--duration-fast) var(--ease-out);
  }

  .clear-chat-btn:hover {
    background: var(--color-bg-tertiary);
    color: var(--color-text-secondary);
  }

  .clear-chat-btn svg {
    width: 16px;
    height: 16px;
  }

  .messages-container {
    flex: 1; /* Takes all remaining space between header and input */
    min-height: 0; /* Critical: allows overflow scroll to work in flexbox */
    overflow-y: auto;
    overflow-x: hidden;
    padding: var(--space-4);
  }

  @media (prefers-reduced-motion: no-preference) {
    .messages-container {
      scroll-behavior: smooth;
    }
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

  .message-with-citations {
    display: flex;
    flex-direction: column;
  }

  /* ChatInput pinned within chat-panel - the :global selector targets ChatInput's wrapper */
  .chat-panel :global(.input-area) {
    flex-shrink: 0; /* Never shrink - stays pinned at bottom */
    border-top: 1px solid var(--color-border-subtle);
    background: var(--color-bg-secondary);
    padding: var(--space-3);
  }

  .chat-panel :global(.input-container) {
    max-width: none; /* Use full width within panel */
  }

  .chat-panel :global(.input-hint) {
    display: none; /* Hide hints in compact panel view */
  }

  /* Hide desktop chat panel on smaller screens (mobile uses MobileChatDrawer) */
  @media (max-width: 1024px) {
    .chat-panel--desktop {
      display: none;
    }
  }
</style>

