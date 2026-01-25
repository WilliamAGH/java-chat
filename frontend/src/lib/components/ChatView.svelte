<script lang="ts">
  import MessageBubble from './MessageBubble.svelte'
  import ChatInput from './ChatInput.svelte'
  import WelcomeScreen from './WelcomeScreen.svelte'
  import CitationPanel from './CitationPanel.svelte'
  import ThinkingIndicator from './ThinkingIndicator.svelte'
  import { streamChat, type ChatMessage, type Citation } from '../services/chat'
  import { isNearBottom, scrollToBottom } from '../utils/scroll'

  /** Extended message type that includes inline citations from the stream. */
  interface MessageWithCitations extends ChatMessage {
    /** Citations received inline from the SSE stream (eliminates separate API call). */
    citations?: Citation[]
  }

  let messages = $state<MessageWithCitations[]>([])
  let isStreaming = $state(false)
  let currentStreamingContent = $state('')
  let messagesContainer: HTMLElement | null = $state(null)
  let shouldAutoScroll = $state(true)
  let streamStatusMessage = $state<string | null>(null)
  let streamStatusDetails = $state<string | null>(null)
  let pendingCitations = $state<Citation[]>([])

  /** Timer for delayed status message clearing to allow users to read final status. */
  let statusClearTimer: ReturnType<typeof setTimeout> | null = null

  $effect(() => {
    return () => {
      if (statusClearTimer) {
        clearTimeout(statusClearTimer)
      }
    }
  })

  /** Duration to keep status visible after streaming ends so users can read it. */
  const STATUS_PERSISTENCE_DURATION_MS = 800

  const sessionId = `chat-${Date.now()}-${Math.random().toString(36).slice(2, 15)}`

  /**
   * Cancels any pending status clear timer.
   */
  function cancelStatusTimer(): void {
    if (statusClearTimer) {
      clearTimeout(statusClearTimer)
      statusClearTimer = null
    }
  }

  /**
   * Clears status messages immediately.
   */
  function clearStatusNow(): void {
    cancelStatusTimer()
    streamStatusMessage = null
    streamStatusDetails = null
  }

  /**
   * Clears status messages after a delay to allow users to read final status.
   */
  function clearStatusDelayed(): void {
    cancelStatusTimer()
    statusClearTimer = setTimeout(() => {
      streamStatusMessage = null
      streamStatusDetails = null
      statusClearTimer = null
    }, STATUS_PERSISTENCE_DURATION_MS)
  }

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
          currentStreamingContent += chunk
          doScrollToBottom()
        },
        {
          onStatus: (status) => {
            streamStatusMessage = status.message
            streamStatusDetails = status.details ?? null
          },
          onError: (streamError) => {
            streamStatusMessage = streamError.message
            streamStatusDetails = streamError.details ?? null
          },
          onCitations: (citations) => {
            pendingCitations = citations
          }
        }
      )

      // Add completed assistant message with inline citations
      messages = [...messages, {
        role: 'assistant',
        content: currentStreamingContent,
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
    if (!message.trim() || isStreaming) return

    const userQuery = message.trim()

    // Add user message
    messages = [...messages, {
      role: 'user',
      content: userQuery,
      timestamp: Date.now()
    }]

    shouldAutoScroll = true
    await doScrollToBottom()

    // Start streaming - clear any persisting status immediately
    isStreaming = true
    currentStreamingContent = ''
    clearStatusNow() // Immediate clear for fresh start
    pendingCitations = []

    try {
      await executeChatStream(userQuery)
    } finally {
      isStreaming = false
      currentStreamingContent = ''
      clearStatusDelayed() // Delayed clear so users can read final status
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
      {#if messages.length === 0 && !isStreaming}
        <WelcomeScreen onSuggestionClick={handleSuggestionClick} />
      {:else}
        <div class="messages-list">
          {#each messages as message, i (message.timestamp)}
            <div class="message-with-citations">
              <MessageBubble {message} index={i} />
              {#if message.role === 'assistant' && message.citations && message.citations.length > 0 && !message.isError}
                <CitationPanel citations={message.citations} />
              {/if}
            </div>
          {/each}

          {#if isStreaming && currentStreamingContent}
            <MessageBubble
              message={{
                role: 'assistant',
                content: currentStreamingContent,
                timestamp: Date.now()
              }}
              index={messages.length}
              isStreaming={true}
            />
          {:else if isStreaming}
            <ThinkingIndicator
              statusMessage={streamStatusMessage}
              statusDetails={streamStatusDetails}
              hasContent={false}
            />
          {/if}
        </div>
      {/if}
    </div>
  </div>

  <ChatInput onSend={handleSend} disabled={isStreaming} />
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
    scroll-behavior: smooth;
  }

  .messages-inner {
    max-width: 800px;
    margin: 0 auto;
    padding: var(--space-6);
  }

  .messages-list {
    display: flex;
    flex-direction: column;
    gap: var(--space-6);
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
    }

    .messages-list {
      gap: var(--space-4);
    }
  }

  /* Small phones */
  @media (max-width: 380px) {
    .messages-inner {
      padding: var(--space-2);
    }

    .messages-list {
      gap: var(--space-3);
    }
  }
</style>
