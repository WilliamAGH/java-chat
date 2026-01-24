<script lang="ts">
  import { tick } from 'svelte'
  import { fetchTOC, fetchLessonContent, streamGuidedChat, type GuidedLesson } from '../services/guided'
  import { fetchCitations } from '../services/chat'
  import { renderMarkdown } from '../services/markdown'
  import type { ChatMessage } from '../services/chat'
  import MessageBubble from './MessageBubble.svelte'
  import ChatInput from './ChatInput.svelte'
  import CitationPanel from './CitationPanel.svelte'

  /** Extended message type that tracks the originating query for citations. */
  interface MessageWithQuery extends ChatMessage {
    queryForCitations?: string
  }

  // TOC state
  let lessons = $state<GuidedLesson[]>([])
  let loadingTOC = $state(true)
  let tocError = $state<string | null>(null)

  // Lesson state
  let selectedLesson = $state<GuidedLesson | null>(null)
  let lessonMarkdown = $state('')
  let lessonError = $state<string | null>(null)
  let loadingLesson = $state(false)


  // Chat state
  let messages = $state<MessageWithQuery[]>([])
  let isStreaming = $state(false)
  let currentStreamingContent = $state('')
  let messagesContainer: HTMLElement | null = $state(null)
  let shouldAutoScroll = $state(true)

  // Element ref for syntax highlighting
  let lessonContentEl: HTMLElement | null = $state(null)

  const sessionId = `guided-${Date.now()}-${Math.random().toString(36).slice(2, 15)}`

  // Rendered lesson content - safe empty string when no content
  let renderedLesson = $derived(
    lessonMarkdown ? renderMarkdown(lessonMarkdown) : ''
  )

  // Load TOC on mount
  $effect(() => {
    loadTOC()
  })

  async function loadTOC(): Promise<void> {
    loadingTOC = true
    tocError = null

    try {
      lessons = await fetchTOC()
    } catch (error) {
      tocError = error instanceof Error ? error.message : 'Failed to load lessons'
      lessons = []
    } finally {
      loadingTOC = false
    }
  }

  async function selectLesson(lesson: GuidedLesson): Promise<void> {
    // Reset state atomically before async operation
    selectedLesson = lesson
    loadingLesson = true
    lessonMarkdown = ''
    lessonError = null
    messages = []

    try {
      const response = await fetchLessonContent(lesson.slug)
      lessonMarkdown = response.markdown

      // Fetch citations for the lesson topic (non-blocking, errors logged for visibility)
      fetchCitations(lesson.title)
        .catch((citationError: unknown) => {
          const errorMessage = citationError instanceof Error
            ? citationError.message
            : 'Unknown error'
          console.warn(`Failed to load lesson citations for "${lesson.title}":`, errorMessage)
        })
    } catch (error) {
      lessonError = error instanceof Error ? error.message : 'Failed to load lesson'
      lessonMarkdown = ''
    } finally {
      loadingLesson = false
    }
  }

  function goBack(): void {
    selectedLesson = null
    lessonMarkdown = ''
    lessonError = null
    messages = []
  }

  function checkAutoScroll(): void {
    if (!messagesContainer) return
    const threshold = 100
    const { scrollTop, scrollHeight, clientHeight } = messagesContainer
    shouldAutoScroll = scrollHeight - scrollTop - clientHeight < threshold
  }

  async function scrollToBottom(): Promise<void> {
    await tick()
    if (messagesContainer && shouldAutoScroll) {
      messagesContainer.scrollTo({
        top: messagesContainer.scrollHeight,
        behavior: 'smooth'
      })
    }
  }

  async function handleSend(message: string): Promise<void> {
    if (!message.trim() || isStreaming || !selectedLesson) return

    const userQuery = message.trim()

    messages = [...messages, {
      role: 'user',
      content: userQuery,
      timestamp: Date.now()
    }]

    shouldAutoScroll = true
    await scrollToBottom()

    isStreaming = true
    currentStreamingContent = ''

    try {
      await streamGuidedChat(sessionId, selectedLesson.slug, userQuery, {
        onChunk: (chunk) => {
          currentStreamingContent += chunk
          scrollToBottom()
        },
        onError: (streamError) => {
          console.error('Stream error during processing:', streamError)
        }
      })

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
      await scrollToBottom()
    }
  }

  // Highlight code blocks after lesson content renders
  // Capture element reference to avoid stale closure
  $effect(() => {
    const contentElement = lessonContentEl
    if (!renderedLesson || !contentElement) return

    Promise.all([
      import('highlight.js/lib/core'),
      import('highlight.js/lib/languages/java'),
      import('highlight.js/lib/languages/xml'),
      import('highlight.js/lib/languages/json'),
      import('highlight.js/lib/languages/bash')
    ]).then(([hljs, java, xml, json, bash]) => {
      // Guard against element becoming null during async load
      if (!contentElement) return

      if (!hljs.default.getLanguage('java')) hljs.default.registerLanguage('java', java.default)
      if (!hljs.default.getLanguage('xml')) hljs.default.registerLanguage('xml', xml.default)
      if (!hljs.default.getLanguage('json')) hljs.default.registerLanguage('json', json.default)
      if (!hljs.default.getLanguage('bash')) hljs.default.registerLanguage('bash', bash.default)

      contentElement.querySelectorAll('pre code:not(.hljs)').forEach((block) => {
        hljs.default.highlightElement(block as HTMLElement)
      })
    }).catch((highlightError) => {
      console.warn('Code highlighting failed:', highlightError)
    })
  })
</script>

<div class="learn-view">
  {#if !selectedLesson}
    <!-- TOC View -->
    <div class="toc-container">
      <div class="toc-inner">
        <div class="toc-header">
          <h1 class="toc-title">
            <span class="title-serif">Learn</span>
            <span class="title-accent">Java</span>
          </h1>
          <p class="toc-subtitle">
            Interactive lessons powered by Think Java. Select a topic to begin.
          </p>
        </div>

        {#if loadingTOC}
          <div class="loading-state">
            <div class="loading-dots">
              <span class="dot"></span>
              <span class="dot"></span>
              <span class="dot"></span>
            </div>
            <p>Loading lessons...</p>
          </div>
        {:else if tocError}
          <div class="error-state">
            <p>{tocError}</p>
            <button type="button" class="retry-btn" onclick={loadTOC}>Try Again</button>
          </div>
        {:else}
          <div class="lessons-grid">
            {#each lessons as lesson, index}
              <button
                type="button"
                class="lesson-card"
                onclick={() => selectLesson(lesson)}
                style:animation-delay="{index * 50}ms"
              >
                <span class="lesson-number">{index + 1}</span>
                <div class="lesson-info">
                  <span class="lesson-title">{lesson.title}</span>
                  <span class="lesson-summary">{lesson.summary}</span>
                </div>
                <svg class="lesson-arrow" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
                  <path fill-rule="evenodd" d="M3 10a.75.75 0 0 1 .75-.75h10.638L10.23 5.29a.75.75 0 1 1 1.04-1.08l5.5 5.25a.75.75 0 0 1 0 1.08l-5.5 5.25a.75.75 0 1 1-1.04-1.08l4.158-3.96H3.75A.75.75 0 0 1 3 10Z" clip-rule="evenodd"/>
                </svg>
              </button>
            {/each}
          </div>
        {/if}
      </div>
    </div>
  {:else}
    <!-- Lesson View -->
    <div class="lesson-container">
      <!-- Lesson Header -->
      <div class="lesson-header">
        <button type="button" class="back-btn" onclick={goBack}>
          <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
            <path fill-rule="evenodd" d="M17 10a.75.75 0 0 1-.75.75H5.612l4.158 3.96a.75.75 0 1 1-1.04 1.08l-5.5-5.25a.75.75 0 0 1 0-1.08l5.5-5.25a.75.75 0 1 1 1.04 1.08L5.612 9.25H16.25A.75.75 0 0 1 17 10Z" clip-rule="evenodd"/>
          </svg>
          <span>All Lessons</span>
        </button>
        <h2 class="lesson-title-header">{selectedLesson.title}</h2>
      </div>

      <!-- Two-column layout: Content + Chat -->
      <div class="lesson-layout">
        <!-- Lesson Content Panel -->
        <div class="lesson-content-panel">
          {#if loadingLesson}
            <div class="loading-state">
              <div class="loading-dots">
                <span class="dot"></span>
                <span class="dot"></span>
                <span class="dot"></span>
              </div>
              <p>Loading lesson...</p>
            </div>
          {:else if lessonError}
            <div class="error-state">
              <p>{lessonError}</p>
              <button type="button" class="retry-btn" onclick={() => selectedLesson && selectLesson(selectedLesson)}>
                Try Again
              </button>
            </div>
          {:else}
            <div class="lesson-content" bind:this={lessonContentEl}>
              {@html renderedLesson}
            </div>
          {/if}
        </div>

        <!-- Chat Panel -->
        <div class="chat-panel">
          <div class="chat-panel-header">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
              <path d="M7.5 8.25h9m-9 3H12m-9.75 1.51c0 1.6 1.123 2.994 2.707 3.227 1.129.166 2.27.293 3.423.379.35.026.67.21.865.501L12 21l2.755-4.133a1.14 1.14 0 0 1 .865-.501 48.172 48.172 0 0 0 3.423-.379c1.584-.233 2.707-1.626 2.707-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0 0 12 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018Z"/>
            </svg>
            <span>Ask about this lesson</span>
          </div>

          <div
            class="messages-container"
            bind:this={messagesContainer}
            onscroll={checkAutoScroll}
          >
            {#if messages.length === 0 && !isStreaming}
              <div class="chat-empty">
                <p>Have questions about <strong>{selectedLesson.title}</strong>?</p>
                <p class="hint">Ask anything about the concepts in this lesson.</p>
              </div>
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
                  </div>
                {/if}
              </div>
            {/if}
          </div>

          <ChatInput onSend={handleSend} disabled={isStreaming} placeholder="Ask about this lesson..." />
        </div>
      </div>
    </div>
  {/if}
</div>

<style>
  .learn-view {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }

  /* TOC Container */
  .toc-container {
    flex: 1;
    overflow-y: auto;
    display: flex;
    flex-direction: column;
    align-items: center;
    padding: var(--space-8);
  }

  .toc-inner {
    max-width: 720px;
    width: 100%;
  }

  .toc-header {
    text-align: center;
    margin-bottom: var(--space-10);
  }

  .toc-title {
    font-size: var(--text-4xl);
    font-weight: 400;
    line-height: var(--leading-tight);
    margin-bottom: var(--space-4);
  }

  .title-serif {
    font-family: var(--font-serif);
    color: var(--color-text-secondary);
  }

  .title-accent {
    font-family: var(--font-sans);
    font-weight: 600;
    color: var(--color-accent);
  }

  .toc-subtitle {
    font-size: var(--text-lg);
    line-height: var(--leading-relaxed);
    color: var(--color-text-secondary);
  }

  /* Lessons Grid */
  .lessons-grid {
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
  }

  .lesson-card {
    display: flex;
    align-items: center;
    gap: var(--space-4);
    padding: var(--space-4) var(--space-5);
    background: var(--color-bg-secondary);
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-lg);
    cursor: pointer;
    text-align: left;
    transition: all var(--duration-fast) var(--ease-out);
    animation: fade-in-up var(--duration-normal) var(--ease-out) backwards;
  }

  .lesson-card:hover {
    background: var(--color-bg-tertiary);
    border-color: var(--color-border-default);
    transform: translateX(4px);
  }

  .lesson-card:hover .lesson-arrow {
    opacity: 1;
    transform: translateX(0);
  }

  .lesson-number {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    background: var(--color-accent-subtle);
    border: 1px solid var(--color-accent-muted);
    border-radius: var(--radius-md);
    font-size: var(--text-sm);
    font-weight: 600;
    color: var(--color-accent);
  }

  .lesson-info {
    flex: 1;
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
    min-width: 0;
  }

  .lesson-title {
    font-size: var(--text-base);
    font-weight: 600;
    color: var(--color-text-primary);
  }

  .lesson-summary {
    font-size: var(--text-sm);
    color: var(--color-text-tertiary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .lesson-arrow {
    flex-shrink: 0;
    width: 20px;
    height: 20px;
    color: var(--color-accent);
    opacity: 0;
    transform: translateX(-8px);
    transition: all var(--duration-fast) var(--ease-out);
  }

  /* Loading/Error States */
  .loading-state,
  .error-state {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--space-4);
    padding: var(--space-12);
    color: var(--color-text-secondary);
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

  @keyframes bounce {
    0%, 80%, 100% { transform: scale(0.8); opacity: 0.5; }
    40% { transform: scale(1); opacity: 1; }
  }

  .retry-btn {
    padding: var(--space-2) var(--space-4);
    background: var(--color-accent);
    color: white;
    border: none;
    border-radius: var(--radius-md);
    font-size: var(--text-sm);
    font-weight: 500;
    cursor: pointer;
    transition: background var(--duration-fast) var(--ease-out);
  }

  .retry-btn:hover {
    background: var(--color-accent-hover);
  }

  /* Lesson Container */
  .lesson-container {
    flex: 1;
    display: flex;
    flex-direction: column;
    overflow: hidden;
  }

  .lesson-header {
    display: flex;
    align-items: center;
    gap: var(--space-4);
    padding: var(--space-4) var(--space-6);
    border-bottom: 1px solid var(--color-border-subtle);
    background: var(--color-bg-secondary);
  }

  .back-btn {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    background: transparent;
    border: 1px solid var(--color-border-default);
    border-radius: var(--radius-md);
    font-size: var(--text-sm);
    color: var(--color-text-secondary);
    cursor: pointer;
    transition: all var(--duration-fast) var(--ease-out);
  }

  .back-btn:hover {
    background: var(--color-bg-tertiary);
    color: var(--color-text-primary);
  }

  .back-btn svg {
    width: 16px;
    height: 16px;
  }

  .lesson-title-header {
    font-family: var(--font-serif);
    font-size: var(--text-xl);
    font-weight: 500;
    color: var(--color-text-primary);
    letter-spacing: var(--tracking-tight);
  }

  /* Two-column Layout */
  .lesson-layout {
    flex: 1;
    display: grid;
    grid-template-columns: 1fr 400px;
    overflow: hidden;
  }

  /* Lesson Content Panel */
  .lesson-content-panel {
    overflow-y: auto;
    padding: var(--space-6);
    border-right: 1px solid var(--color-border-subtle);
  }

  .lesson-content {
    max-width: 720px;
    margin: 0 auto;
    font-size: var(--text-base);
    line-height: var(--leading-relaxed);
    color: var(--color-text-primary);
  }

  .lesson-content :global(h1),
  .lesson-content :global(h2),
  .lesson-content :global(h3) {
    font-family: var(--font-serif);
    font-weight: 500;
    margin: var(--space-8) 0 var(--space-4);
    letter-spacing: var(--tracking-tight);
  }

  .lesson-content :global(h1:first-child),
  .lesson-content :global(h2:first-child) {
    margin-top: 0;
  }

  .lesson-content :global(h1) { font-size: var(--text-2xl); }
  .lesson-content :global(h2) { font-size: var(--text-xl); }
  .lesson-content :global(h3) { font-size: var(--text-lg); }

  .lesson-content :global(p) {
    margin: 0 0 var(--space-4);
  }

  .lesson-content :global(ul),
  .lesson-content :global(ol) {
    margin: 0 0 var(--space-4);
    padding-left: var(--space-6);
  }

  .lesson-content :global(li) {
    margin-bottom: var(--space-2);
  }

  .lesson-content :global(pre) {
    margin: var(--space-4) 0;
    padding: var(--space-4);
    background: var(--color-bg-secondary);
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-lg);
    overflow-x: auto;
    font-size: var(--text-sm);
  }

  .lesson-content :global(pre code) {
    background: none;
    padding: 0;
    border: none;
  }

  .lesson-content :global(code:not(pre code)) {
    padding: 0.125em 0.375em;
    background: var(--color-bg-tertiary);
    border-radius: var(--radius-sm);
    font-size: 0.9em;
  }

  .lesson-content :global(blockquote) {
    margin: var(--space-4) 0;
    padding: var(--space-3) var(--space-4);
    border-left: 3px solid var(--color-accent);
    background: var(--color-surface-subtle);
    border-radius: 0 var(--radius-md) var(--radius-md) 0;
    font-style: italic;
    color: var(--color-text-secondary);
  }

  /* Chat Panel */
  .chat-panel {
    display: flex;
    flex-direction: column;
    background: var(--color-bg-primary);
  }

  .chat-panel-header {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-3) var(--space-4);
    border-bottom: 1px solid var(--color-border-subtle);
    font-size: var(--text-sm);
    font-weight: 500;
    color: var(--color-text-secondary);
  }

  .chat-panel-header svg {
    width: 18px;
    height: 18px;
    color: var(--color-accent);
  }

  .messages-container {
    flex: 1;
    overflow-y: auto;
    padding: var(--space-4);
  }

  .messages-list {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
  }

  .message-with-citations {
    display: flex;
    flex-direction: column;
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

  .loading-indicator {
    display: flex;
    justify-content: flex-start;
    padding-left: var(--space-4);
  }

  /* Responsive: Stack on smaller screens */
  @media (max-width: 1024px) {
    .lesson-layout {
      grid-template-columns: 1fr;
      grid-template-rows: 1fr 1fr;
    }

    .lesson-content-panel {
      border-right: none;
      border-bottom: 1px solid var(--color-border-subtle);
    }
  }

  @media (max-width: 768px) {
    .toc-container {
      padding: var(--space-4);
    }

    .toc-title {
      font-size: var(--text-2xl);
    }

    .toc-subtitle {
      font-size: var(--text-base);
    }

    .lesson-header {
      padding: var(--space-3) var(--space-4);
    }

    .lesson-title-header {
      font-size: var(--text-lg);
    }

    .lesson-content-panel {
      padding: var(--space-4);
    }
  }

  @media (max-width: 640px) {
    .toc-header {
      margin-bottom: var(--space-6);
    }

    .lesson-card {
      padding: var(--space-3) var(--space-4);
    }

    .lesson-number {
      width: 28px;
      height: 28px;
      font-size: var(--text-xs);
    }

    .lesson-title {
      font-size: var(--text-sm);
    }

    .lesson-summary {
      font-size: var(--text-xs);
    }

    .back-btn span {
      display: none;
    }
  }
</style>
