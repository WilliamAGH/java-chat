<script lang="ts">
  import ChatInput from './ChatInput.svelte'
  import WelcomeScreen from './WelcomeScreen.svelte'
  import CitationPanel from './CitationPanel.svelte'
  import MessageBubble from './MessageBubble.svelte'
  import StreamingMessagesList from './StreamingMessagesList.svelte'
  import { streamChat, type ChatMessage, type Citation } from '../services/chat'
  import { isNearBottom, scrollToBottom } from '../utils/scroll'
  import { generateSessionId } from '../utils/session'
  import { createStreamingState } from '../composables/createStreamingState.svelte'

  /** Extended message type that includes inline citations from the stream. */
  interface MessageWithCitations extends ChatMessage {
    /** Citations received inline from the SSE stream (eliminates separate API call). */
    citations?: Citation[]
  }

  // View-specific state
  let messages = $state<MessageWithCitations[]>([])
  let messagesContainer: HTMLElement | null = $state(null)
  let shouldAutoScroll = $state(true)
  let pendingCitations = $state<Citation[]>([])

  // Streaming state from composable (with 800ms status persistence)
  const streaming = createStreamingState({ statusClearDelayMs: 800 })

  // Cleanup timer on unmount
  $effect(() => {
    return streaming.cleanup
  })

  // Session ID for chat continuity
  const sessionId = generateSessionId('chat')

  function checkAutoScroll() {
    shouldAutoScroll = isNearBottom(messagesContainer)
  }

  async function doScrollToBottom() {
    await scrollToBottom(messagesContainer, shouldAutoScroll)
  }

  async function executeChatStream(userQuery: string): Promise<void> {
    try {
      await streamChat(
        sessionId,
        userQuery,
        (chunk) => {
          streaming.appendContent(chunk)
          doScrollToBottom()
        },
        {
          onStatus: streaming.updateStatus,
          onError: streaming.updateStatus,
          onCitations: (citations) => {
            pendingCitations = citations
          }
        }
      )

      // Add completed assistant message with inline citations
      messages = [...messages, {
        role: 'assistant',
        content: streaming.streamingContent,
        timestamp: Date.now(),
        citations: pendingCitations
      }]
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Sorry, I encountered an error. Please try again.'
      messages = [...messages, {
        role: 'assistant',
        content: errorMessage,
        timestamp: Date.now(),
        isError: true
      }]
    }
  }

  async function handleSend(message: string): Promise<void> {
    if (!message.trim() || streaming.isStreaming) return

    const userQuery = message.trim()

    // Add user message
    messages = [...messages, {
      role: 'user',
      content: userQuery,
      timestamp: Date.now()
    }]

    shouldAutoScroll = true
    await doScrollToBottom()

    // Start streaming
    streaming.startStream()
    pendingCitations = []

    try {
      await executeChatStream(userQuery)
    } finally {
      streaming.finishStream()
      pendingCitations = []
      await doScrollToBottom()
    }
  }

  function handleSuggestionClick(suggestion: string) {
    handleSend(suggestion)
  }
</script>

<div class="chat-view">
  <div
    class="messages-container"
    bind:this={messagesContainer}
    onscroll={checkAutoScroll}
  >
    <div class="messages-inner">
      {#if messages.length === 0 && !streaming.isStreaming}
        <WelcomeScreen onSuggestionClick={handleSuggestionClick} />
      {:else}
        <StreamingMessagesList
          {messages}
          isStreaming={streaming.isStreaming}
          streamingContent={streaming.streamingContent}
          statusMessage={streaming.statusMessage}
          statusDetails={streaming.statusDetails}
          hasContent={false}
        >
          {#snippet messageRenderer({ message, index })}
            {@const typedMessage = message as MessageWithCitations}
            <div class="message-with-citations">
              <MessageBubble message={typedMessage} {index} />
              {#if typedMessage.role === 'assistant' && typedMessage.citations && typedMessage.citations.length > 0 && !typedMessage.isError}
                <CitationPanel citations={typedMessage.citations} />
              {/if}
            </div>
          {/snippet}
        </StreamingMessagesList>
      {/if}
    </div>
  </div>

  <ChatInput onSend={handleSend} disabled={streaming.isStreaming} />
</div>

<style>
  .chat-view {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }

  .messages-container {
    flex: 1;
    overflow-y: auto;
  }

  @media (prefers-reduced-motion: no-preference) {
    .messages-container {
      scroll-behavior: smooth;
    }
  }

  .messages-inner {
    max-width: 800px;
    margin: 0 auto;
    padding: var(--space-6);
    --messages-list-gap: var(--space-6);
  }

  .message-with-citations {
    display: flex;
    flex-direction: column;
  }

  /* Tablet */
  @media (max-width: 768px) {
    .messages-inner {
      padding: var(--space-4);
    }
  }

  /* Mobile */
  @media (max-width: 640px) {
    .messages-inner {
      padding: var(--space-3);
      --messages-list-gap: var(--space-4);
    }
  }

  /* Small phones */
  @media (max-width: 380px) {
    .messages-inner {
      padding: var(--space-2);
      --messages-list-gap: var(--space-3);
    }
  }
</style>
