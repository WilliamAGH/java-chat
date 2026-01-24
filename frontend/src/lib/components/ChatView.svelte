<script lang="ts">
  import { tick } from 'svelte'
  import MessageBubble from './MessageBubble.svelte'
  import ChatInput from './ChatInput.svelte'
  import WelcomeScreen from './WelcomeScreen.svelte'
  import CitationPanel from './CitationPanel.svelte'
  import { streamChat, type ChatMessage } from '../services/chat'

  /** Extended message type that tracks the originating query for citations. */
  interface MessageWithQuery extends ChatMessage {
    /** The user query this assistant message responds to (for citation lookup). */
    queryForCitations?: string
  }

  let messages = $state<MessageWithQuery[]>([])
  let isStreaming = $state(false)
  let currentStreamingContent = $state('')
  let currentUserQuery = $state('')
  let messagesContainer: HTMLElement | null = $state(null)
  let shouldAutoScroll = $state(true)
  let streamStatusMessage = $state<string | null>(null)
  let streamStatusDetails = $state<string | null>(null)

  const sessionId = `chat-${Date.now()}-${Math.random().toString(36).slice(2, 15)}`

  function checkAutoScroll() {
    if (!messagesContainer) return
    const threshold = 100
    const { scrollTop, scrollHeight, clientHeight } = messagesContainer
    shouldAutoScroll = scrollHeight - scrollTop - clientHeight < threshold
  }

  async function scrollToBottom() {
    await tick()
    if (messagesContainer && shouldAutoScroll) {
      messagesContainer.scrollTo({
        top: messagesContainer.scrollHeight,
        behavior: 'smooth'
      })
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
    await scrollToBottom()

    // Start streaming - track query for citations
    isStreaming = true
    currentStreamingContent = ''
    currentUserQuery = userQuery
    streamStatusMessage = null
    streamStatusDetails = null

    try {
      await streamChat(
        sessionId,
        userQuery,
        (chunk) => {
          currentStreamingContent += chunk
          scrollToBottom()
        },
        {
          onStatus: (status) => {
            streamStatusMessage = status.message
            streamStatusDetails = status.details ?? null
          },
          onError: (streamError) => {
            streamStatusMessage = streamError.message
            streamStatusDetails = streamError.details ?? null
          }
        }
      )

      // Add completed assistant message with query reference for citations
      messages = [...messages, {
        role: 'assistant',
        content: currentStreamingContent,
        timestamp: Date.now(),
        queryForCitations: userQuery
      }]
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Sorry, I encountered an error. Please try again.'
      messages = [...messages, {
        role: 'assistant',
        content: errorMessage,
        timestamp: Date.now(),
        isError: true
      }]
    } finally {
      isStreaming = false
      currentStreamingContent = ''
      currentUserQuery = ''
      streamStatusMessage = null
      streamStatusDetails = null
      await scrollToBottom()
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
              {#if message.role === 'assistant' && message.queryForCitations && !message.isError}
                <CitationPanel query={message.queryForCitations} />
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
            <div class="loading-indicator">
              <div class="loading-dots">
                <span class="dot"></span>
                <span class="dot"></span>
                <span class="dot"></span>
              </div>
              {#if streamStatusMessage}
                <div class="stream-status">
                  <p class="stream-status-title">{streamStatusMessage}</p>
                  {#if streamStatusDetails}
                    <p class="stream-status-details">{streamStatusDetails}</p>
                  {/if}
                </div>
              {/if}
            </div>
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

  /* Loading indicator */
  .loading-indicator {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-2);
    justify-content: flex-start;
    padding-left: var(--space-4);
  }

  .loading-dots {
    display: flex;
    gap: 6px;
    padding: var(--space-4);
    background: var(--color-bg-secondary);
    border-radius: var(--radius-xl);
    border: 1px solid var(--color-border-subtle);
  }

  .dot {
    width: 8px;
    height: 8px;
    background: var(--color-text-muted);
    border-radius: 50%;
    animation: bounce 1.4s infinite ease-in-out both;
  }

  .dot:nth-child(1) { animation-delay: -0.32s; }
  .dot:nth-child(2) { animation-delay: -0.16s; }
  .dot:nth-child(3) { animation-delay: 0s; }

  .stream-status {
    padding: 0 var(--space-2);
  }

  .stream-status-title {
    margin: 0;
    font-size: var(--text-xs);
    color: var(--color-text-muted);
  }

  .stream-status-details {
    margin: var(--space-1) 0 0;
    font-size: var(--text-xs);
    color: var(--color-text-muted);
  }

  @keyframes bounce {
    0%, 80%, 100% {
      transform: scale(0.8);
      opacity: 0.5;
    }
    40% {
      transform: scale(1);
      opacity: 1;
    }
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

    .loading-dots {
      padding: var(--space-3);
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
