<script lang="ts">
  import type { ChatMessage } from '../services/chat'
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

  {#if isStreaming && !hasContent}
    <ThinkingIndicator {statusMessage} {statusDetails} {hasContent} />
  {/if}
</div>

<style>
  .messages-list {
    display: flex;
    flex-direction: column;
    gap: var(--messages-list-gap, var(--space-4));
  }
</style>
