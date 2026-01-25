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
    streamingContent: string
    statusMessage: string | null
    statusDetails: string | null
    hasContent?: boolean
    /** Custom renderer for each message. Receives message and index. Defaults to plain MessageBubble. */
    messageRenderer?: Snippet<[{ message: ChatMessage; index: number }]>
  }

  let {
    messages,
    isStreaming,
    streamingContent,
    statusMessage,
    statusDetails,
    hasContent = false,
    messageRenderer
  }: Props = $props()
</script>

<div class="messages-list">
  {#each messages as message, messageIndex (message.timestamp)}
    {#if messageRenderer}
      {@render messageRenderer({ message, index: messageIndex })}
    {:else}
      <MessageBubble {message} index={messageIndex} />
    {/if}
  {/each}

  {#if isStreaming && streamingContent}
    <MessageBubble
      message={{ role: 'assistant', content: streamingContent, timestamp: Date.now() }}
      index={messages.length}
      isStreaming={true}
    />
  {:else if isStreaming}
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
