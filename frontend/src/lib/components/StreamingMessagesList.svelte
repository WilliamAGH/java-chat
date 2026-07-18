<script lang="ts">
  import type { ChatMessage } from '../services/chat'
  import type { CitationPartialFailureStatus } from '../validation/schemas'
  import type { Snippet } from 'svelte'
  import MessageBubble from './MessageBubble.svelte'
  import ThinkingIndicator from './ThinkingIndicator.svelte'

  /**
   * Reusable component for rendering a chat message list with streaming support.
   *
   * Encapsulates the common pattern of:
   * - Rendering committed messages
   * - Showing in-progress streaming content as a MessageBubble
   * - Showing ThinkingIndicator when streaming but no content yet
   *
   * Use the `messageRenderer` snippet prop to customize per-message rendering
   * (e.g., for adding CitationPanel wrappers).
   */
  interface Props {
    messages: ChatMessage[]
    isStreaming: boolean
    statusMessage: string | null
    statusDetails: string | null
    citationWarning: CitationPartialFailureStatus | null
    hasContent?: boolean
    /** Stable identifier for the in-progress assistant message (if present). */
    streamingMessageId?: string | null
    /** Custom renderer for each message. Receives message, index, and streaming flag. Defaults to MessageBubble. */
    messageRenderer?: Snippet<[{ message: ChatMessage; index: number; isStreaming: boolean }]>
  }

  let {
    messages,
    isStreaming,
    statusMessage,
    statusDetails,
    citationWarning,
    hasContent = false,
    streamingMessageId = null,
    messageRenderer
  }: Props = $props()
</script>

<div class="messages-list">
  {#each messages as message, messageIndex (message.messageId)}
    {@const messageIsStreaming = isStreaming && !!streamingMessageId && message.messageId === streamingMessageId}
    {#if messageRenderer}
      {@render messageRenderer({ message, index: messageIndex, isStreaming: messageIsStreaming })}
    {:else}
      <MessageBubble {message} index={messageIndex} isStreaming={messageIsStreaming} />
    {/if}
  {/each}

  {#if citationWarning}
    <section class="citation-warning" role="status" aria-label="Citation warning" aria-live="polite">
      <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
        <path fill-rule="evenodd" d="M8.485 2.495c.673-1.167 2.357-1.167 3.03 0l6.28 10.88c.673 1.166-.168 2.625-1.515 2.625H3.72c-1.347 0-2.188-1.459-1.515-2.625l6.28-10.88ZM10 6.5a.75.75 0 0 1 .75.75v3.5a.75.75 0 0 1-1.5 0v-3.5A.75.75 0 0 1 10 6.5Zm0 7.25a1 1 0 1 0 0-2 1 1 0 0 0 0 2Z" clip-rule="evenodd" />
      </svg>
      <div>
        <p class="citation-warning-message">{citationWarning.message}</p>
        {#if citationWarning.details}
          <p class="citation-warning-details">{citationWarning.details}</p>
        {/if}
      </div>
    </section>
  {/if}

  {#if isStreaming && !hasContent && !citationWarning}
    <ThinkingIndicator {statusMessage} {statusDetails} {hasContent} />
  {/if}
</div>

<style>
  .messages-list {
    display: flex;
    flex-direction: column;
    gap: var(--messages-list-gap, var(--space-4));
  }

  .citation-warning {
    display: flex;
    align-items: flex-start;
    gap: var(--space-3);
    padding: var(--space-3) var(--space-4);
    color: var(--color-text-primary);
    background: var(--color-bg-secondary);
    border: 1px solid var(--color-border-default);
    border-left: 4px solid var(--color-warning);
    border-radius: var(--radius-lg);
  }

  .citation-warning > svg {
    flex-shrink: 0;
    width: 20px;
    height: 20px;
    color: var(--color-warning);
  }

  .citation-warning-message,
  .citation-warning-details {
    margin: 0;
  }

  .citation-warning-message {
    font-size: var(--text-sm);
    font-weight: 600;
  }

  .citation-warning-details {
    margin-top: var(--space-1);
    color: var(--color-text-secondary);
    font-size: var(--text-xs);
  }
</style>
