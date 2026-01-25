<script lang="ts">
  import { fetchTOC, fetchLessonContent, streamGuidedChat, type GuidedLesson } from '../services/guided'
  import { fetchCitations, type Citation } from '../services/chat'
  import { parseMarkdown, applyJavaLanguageDetection } from '../services/markdown'
  import type { ChatMessage } from '../services/chat'
  import MessageBubble from './MessageBubble.svelte'
  import ChatInput from './ChatInput.svelte'
  import ThinkingIndicator from './ThinkingIndicator.svelte'
  import { sanitizeUrl, deduplicateCitations } from '../utils/url'
  import { highlightCodeBlocks } from '../utils/highlight'
  import { isNearBottom, scrollToBottom } from '../utils/scroll'

  // TOC state
  let lessons = $state<GuidedLesson[]>([])
  let loadingTOC = $state(true)
  let tocError = $state<string | null>(null)

  // Lesson state
  let selectedLesson = $state<GuidedLesson | null>(null)
  let lessonMarkdown = $state('')
  let lessonError = $state<string | null>(null)
  let loadingLesson = $state(false)

  // Lesson-level citations (sources for the lesson topic)
  let lessonCitations = $state<Citation[]>([])
  let lessonCitationsError = $state<string | null>(null)
  let lessonCitationsLoaded = $state(false)

  // Chat state - per-lesson persistence
  const chatHistoryByLesson = new Map<string, ChatMessage[]>()
  let messages = $state<ChatMessage[]>([])
  let isStreaming = $state(false)
  let currentStreamingContent = $state('')
  let streamStatusMessage = $state('')
  let streamStatusDetails = $state('')
  let shouldAutoScroll = $state(true)

  // Separate container refs for desktop panel and mobile drawer to prevent binding conflicts
  // during layout recalculations when content loads on the left panel
  let desktopMessagesContainer: HTMLElement | null = $state(null)
  let drawerMessagesContainer: HTMLElement | null = $state(null)

  // Mobile chat drawer state
  let isChatDrawerOpen = $state(false)

  // Element refs
  let lessonContentEl: HTMLElement | null = $state(null)
  let lessonContentPanelEl: HTMLElement | null = $state(null)

  // Session ID for chat continuity - generated client-side only, not rendered in DOM.
  // Safe for CSR; if SSR is added, move generation to onMount or use server-provided ID.
  const sessionId = `guided-${Date.now()}-${Math.random().toString(36).slice(2, 15)}`

  // Rendered lesson content - SSR-safe parsing without DOM operations
  let renderedLesson = $derived(
    lessonMarkdown ? parseMarkdown(lessonMarkdown) : ''
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
    const targetSlug = lesson.slug

    // Save current chat history before switching lessons
    if (selectedLesson && messages.length > 0) {
      chatHistoryByLesson.set(selectedLesson.slug, [...messages])
    }

    // Reset state atomically before async operation
    selectedLesson = lesson
    loadingLesson = true
    lessonMarkdown = ''
    lessonError = null
    lessonCitations = []
    lessonCitationsError = null
    lessonCitationsLoaded = false
    isChatDrawerOpen = false

    // Restore chat history for this lesson if it exists
    messages = chatHistoryByLesson.get(lesson.slug) ?? []

    try {
      const response = await fetchLessonContent(lesson.slug)
      // Guard against stale response if user switched lessons
      if (selectedLesson?.slug !== targetSlug) return
      lessonMarkdown = response.markdown

      // Fetch citations for the lesson topic (non-blocking, with explicit error tracking)
      fetchCitations(lesson.title).then((result) => {
        // Guard against stale citation response
        if (selectedLesson?.slug !== targetSlug) return

        // Preserve scroll position before updating citations
        const scrollTop = lessonContentPanelEl?.scrollTop ?? 0

        if (result.success) {
          lessonCitations = deduplicateCitations(result.citations)
        } else {
          lessonCitationsError = result.error
        }
        lessonCitationsLoaded = true

        // Restore scroll position after DOM update (content added at bottom shouldn't shift view)
        requestAnimationFrame(() => {
          if (lessonContentPanelEl && scrollTop > 0) {
            lessonContentPanelEl.scrollTop = scrollTop
          }
        })
      }).catch((error) => {
        // Guard against stale citation error
        if (selectedLesson?.slug !== targetSlug) return
        lessonCitationsError = error instanceof Error ? error.message : 'Failed to load lesson sources'
        lessonCitationsLoaded = true
      })
    } catch (error) {
      if (selectedLesson?.slug !== targetSlug) return
      lessonError = error instanceof Error ? error.message : 'Failed to load lesson'
      lessonMarkdown = ''
    } finally {
      if (selectedLesson?.slug === targetSlug) {
        loadingLesson = false
      }
    }
  }

  function goBack(): void {
    // Save current chat history before going back
    if (selectedLesson && messages.length > 0) {
      chatHistoryByLesson.set(selectedLesson.slug, [...messages])
    }

    // Cancel any in-flight stream by clearing streaming state
    isStreaming = false
    currentStreamingContent = ''
    streamStatusMessage = ''
    streamStatusDetails = ''
    isChatDrawerOpen = false
    selectedLesson = null
    lessonMarkdown = ''
    lessonError = null
    lessonCitations = []
    lessonCitationsError = null
    lessonCitationsLoaded = false
    messages = []
  }

  function clearChat(): void {
    if (selectedLesson) {
      chatHistoryByLesson.delete(selectedLesson.slug)
    }
    messages = []
  }

  function toggleChatDrawer(): void {
    isChatDrawerOpen = !isChatDrawerOpen
    // Reset auto-scroll when switching between desktop/drawer views
    shouldAutoScroll = true
  }

  function closeChatDrawer(): void {
    isChatDrawerOpen = false
    // Reset auto-scroll to ensure desktop view scrolls properly
    shouldAutoScroll = true
  }

  /** Returns the currently active messages container based on drawer state. */
  function getActiveMessagesContainer(): HTMLElement | null {
    return isChatDrawerOpen ? drawerMessagesContainer : desktopMessagesContainer
  }

  function checkAutoScroll(): void {
    shouldAutoScroll = isNearBottom(getActiveMessagesContainer())
  }

  async function doScrollToBottom(): Promise<void> {
    await scrollToBottom(getActiveMessagesContainer(), shouldAutoScroll)
  }

  async function handleSend(message: string): Promise<void> {
    if (!message.trim() || isStreaming || !selectedLesson) return

    const streamLessonSlug = selectedLesson.slug
    const userQuery = message.trim()

    messages = [...messages, {
      role: 'user',
      content: userQuery,
      timestamp: Date.now()
    }]

    shouldAutoScroll = true
    await doScrollToBottom()

    isStreaming = true
    currentStreamingContent = ''
    streamStatusMessage = ''
    streamStatusDetails = ''

    try {
      await streamGuidedChat(sessionId, selectedLesson.slug, userQuery, {
        onChunk: (chunk) => {
          // Guard: ignore chunks if user navigated away
          if (selectedLesson?.slug !== streamLessonSlug) return
          currentStreamingContent += chunk
          doScrollToBottom()
        },
        onStatus: (status) => {
          // Guard: ignore status if user navigated away
          if (selectedLesson?.slug !== streamLessonSlug) return
          streamStatusMessage = status.message
          streamStatusDetails = status.details ?? ''
        },
        onError: (streamError) => {
          console.error('Stream error during processing:', streamError)
        }
      })

      // Guard: don't add message if user navigated away
      if (selectedLesson?.slug !== streamLessonSlug) return
      messages = [...messages, {
        role: 'assistant',
        content: currentStreamingContent,
        timestamp: Date.now()
      }]
    } catch (error) {
      if (selectedLesson?.slug !== streamLessonSlug) return
      const errorMessage = error instanceof Error ? error.message : 'Sorry, I encountered an error. Please try again.'
      messages = [...messages, {
        role: 'assistant',
        content: errorMessage,
        timestamp: Date.now(),
        isError: true
      }]
    } finally {
      // Guard: only reset streaming state if still on same lesson
      if (selectedLesson?.slug === streamLessonSlug) {
        isStreaming = false
        currentStreamingContent = ''
        streamStatusMessage = ''
        streamStatusDetails = ''
        await doScrollToBottom()
      }
    }
  }

  // Apply Java language detection and highlight code blocks after lesson content renders
  // Uses shared utility with cancellation support
  $effect(() => {
    const contentElement = lessonContentEl
    if (!renderedLesson || !contentElement) return

    let isCancelled = false

    // Apply Java language detection before highlighting (client-side DOM operation)
    applyJavaLanguageDetection(contentElement)

    highlightCodeBlocks(contentElement).catch((highlightError) => {
      if (!isCancelled) {
        console.warn('[LearnView] Code highlighting failed:', highlightError)
      }
    })

    // Cleanup function runs when effect re-runs or component unmounts
    return () => {
      isCancelled = true
    }
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
            Learn Java programming interactively with live lessons, real documentation, and AI. Select a topic to begin!
          </p>
        </div>

        {#if loadingTOC}
          <div class="loading-state">
            <ThinkingIndicator statusMessage="Loading lessons" />
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
      <!-- Lesson Header - full width with inner constraint -->
      <div class="lesson-header">
        <div class="lesson-header-inner">
          <button type="button" class="back-btn" onclick={goBack}>
            <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path fill-rule="evenodd" d="M17 10a.75.75 0 0 1-.75.75H5.612l4.158 3.96a.75.75 0 1 1-1.04 1.08l-5.5-5.25a.75.75 0 0 1 0-1.08l5.5-5.25a.75.75 0 1 1 1.04 1.08L5.612 9.25H16.25A.75.75 0 0 1 17 10Z" clip-rule="evenodd"/>
            </svg>
            <span>All Lessons</span>
          </button>
          <h2 class="lesson-title-header">{selectedLesson.title}</h2>
        </div>
      </div>

      <!-- Two-column layout: Content + Chat (desktop) -->
      <div class="lesson-layout">
        <!-- Lesson Content Panel -->
        <div class="lesson-content-panel" bind:this={lessonContentPanelEl}>
          {#if loadingLesson}
            <div class="loading-state">
              <ThinkingIndicator statusMessage="Loading lesson" />
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

            <!-- Lesson-level citations -->
            {#if lessonCitationsLoaded && lessonCitationsError}
              <div class="lesson-citations lesson-citations--error">
                <span class="lesson-citations-error">Unable to load lesson sources</span>
              </div>
            {:else if lessonCitationsLoaded && lessonCitations.length > 0}
              <div class="lesson-citations">
                <div class="lesson-citations-header">
                  <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
                    <path fill-rule="evenodd" d="M4.5 2A1.5 1.5 0 0 0 3 3.5v13A1.5 1.5 0 0 0 4.5 18h11a1.5 1.5 0 0 0 1.5-1.5V7.621a1.5 1.5 0 0 0-.44-1.06l-4.12-4.122A1.5 1.5 0 0 0 11.378 2H4.5Zm2.25 8.5a.75.75 0 0 0 0 1.5h6.5a.75.75 0 0 0 0-1.5h-6.5Zm0 3a.75.75 0 0 0 0 1.5h6.5a.75.75 0 0 0 0-1.5h-6.5Z" clip-rule="evenodd"/>
                  </svg>
                  <span>Lesson Sources ({lessonCitations.length})</span>
                </div>
                <ul class="lesson-citations-list">
                  {#each lessonCitations as citation (citation.url)}
                    <li>
                      <a href={sanitizeUrl(citation.url)} target="_blank" rel="noopener noreferrer">
                        {citation.title || citation.url}
                      </a>
                    </li>
                  {/each}
                </ul>
              </div>
            {/if}
          {/if}
        </div>

        <!-- Chat Panel (Desktop only) -->
        <div class="chat-panel chat-panel--desktop">
          <div class="chat-panel-header">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
              <path d="M7.5 8.25h9m-9 3H12m-9.75 1.51c0 1.6 1.123 2.994 2.707 3.227 1.129.166 2.27.293 3.423.379.35.026.67.21.865.501L12 21l2.755-4.133a1.14 1.14 0 0 1 .865-.501 48.172 48.172 0 0 0 3.423-.379c1.584-.233 2.707-1.626 2.707-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0 0 12 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018Z"/>
            </svg>
            <span>Ask about this lesson</span>
            {#if messages.length > 0}
              <button type="button" class="clear-chat-btn" onclick={clearChat} title="Clear chat">
                <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
                  <path fill-rule="evenodd" d="M8.75 1A2.75 2.75 0 0 0 6 3.75v.443c-.795.077-1.584.176-2.365.298a.75.75 0 1 0 .23 1.482l.149-.022.841 10.518A2.75 2.75 0 0 0 7.596 19h4.807a2.75 2.75 0 0 0 2.742-2.53l.841-10.519.149.023a.75.75 0 0 0 .23-1.482A41.03 41.03 0 0 0 14 4.193V3.75A2.75 2.75 0 0 0 11.25 1h-2.5ZM10 4c.84 0 1.673.025 2.5.075V3.75c0-.69-.56-1.25-1.25-1.25h-2.5c-.69 0-1.25.56-1.25 1.25v.325C8.327 4.025 9.16 4 10 4ZM8.58 7.72a.75.75 0 0 0-1.5.06l.3 7.5a.75.75 0 1 0 1.5-.06l-.3-7.5Zm4.34.06a.75.75 0 1 0-1.5-.06l-.3 7.5a.75.75 0 1 0 1.5.06l.3-7.5Z" clip-rule="evenodd"/>
                </svg>
              </button>
            {/if}
          </div>

          <div
            class="messages-container"
            bind:this={desktopMessagesContainer}
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
                  <MessageBubble {message} index={i} />
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
                  />
                {/if}
              </div>
            {/if}
          </div>

          <ChatInput onSend={handleSend} disabled={isStreaming} placeholder="Ask about this lesson..." />
        </div>
      </div>

      <!-- Mobile Chat FAB -->
      <button
        type="button"
        class="chat-fab"
        onclick={toggleChatDrawer}
        aria-label="Ask questions about this lesson"
        aria-expanded={isChatDrawerOpen}
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
      {#if isChatDrawerOpen}
        <div class="chat-drawer-backdrop" onclick={closeChatDrawer} aria-hidden="true"></div>
        <div class="chat-drawer" role="dialog" aria-label="Lesson chat">
          <div class="chat-drawer-header">
            <div class="chat-drawer-title">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" aria-hidden="true">
                <path d="M7.5 8.25h9m-9 3H12m-9.75 1.51c0 1.6 1.123 2.994 2.707 3.227 1.129.166 2.27.293 3.423.379.35.026.67.21.865.501L12 21l2.755-4.133a1.14 1.14 0 0 1 .865-.501 48.172 48.172 0 0 0 3.423-.379c1.584-.233 2.707-1.626 2.707-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0 0 12 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018Z"/>
              </svg>
              <span>Ask about this lesson</span>
            </div>
            <div class="chat-drawer-actions">
              {#if messages.length > 0}
                <button type="button" class="drawer-action-btn" onclick={clearChat} title="Clear chat">
                  <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
                    <path fill-rule="evenodd" d="M8.75 1A2.75 2.75 0 0 0 6 3.75v.443c-.795.077-1.584.176-2.365.298a.75.75 0 1 0 .23 1.482l.149-.022.841 10.518A2.75 2.75 0 0 0 7.596 19h4.807a2.75 2.75 0 0 0 2.742-2.53l.841-10.519.149.023a.75.75 0 0 0 .23-1.482A41.03 41.03 0 0 0 14 4.193V3.75A2.75 2.75 0 0 0 11.25 1h-2.5ZM10 4c.84 0 1.673.025 2.5.075V3.75c0-.69-.56-1.25-1.25-1.25h-2.5c-.69 0-1.25.56-1.25 1.25v.325C8.327 4.025 9.16 4 10 4ZM8.58 7.72a.75.75 0 0 0-1.5.06l.3 7.5a.75.75 0 1 0 1.5-.06l-.3-7.5Zm4.34.06a.75.75 0 1 0-1.5-.06l-.3 7.5a.75.75 0 1 0 1.5.06l.3-7.5Z" clip-rule="evenodd"/>
                  </svg>
                </button>
              {/if}
              <button type="button" class="drawer-close-btn" onclick={closeChatDrawer} aria-label="Close chat">
                <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
                  <path d="M6.28 5.22a.75.75 0 0 0-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 1 0 1.06 1.06L10 11.06l3.72 3.72a.75.75 0 1 0 1.06-1.06L11.06 10l3.72-3.72a.75.75 0 0 0-1.06-1.06L10 8.94 6.28 5.22Z"/>
                </svg>
              </button>
            </div>
          </div>

          <div class="chat-drawer-messages" bind:this={drawerMessagesContainer} onscroll={checkAutoScroll}>
            {#if messages.length === 0 && !isStreaming}
              <div class="chat-empty">
                <p>Have questions about <strong>{selectedLesson.title}</strong>?</p>
                <p class="hint">Ask anything about the concepts in this lesson.</p>
              </div>
            {:else}
              <div class="messages-list">
                {#each messages as message, i (message.timestamp)}
                  <MessageBubble {message} index={i} />
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
                  />
                {/if}
              </div>
            {/if}
          </div>

          <div class="chat-drawer-input">
            <ChatInput onSend={handleSend} disabled={isStreaming} placeholder="Ask about this lesson..." />
          </div>
        </div>
      {/if}
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

  /* Hover effects only for devices with hover capability */
  @media (hover: hover) and (pointer: fine) {
    .lesson-card:hover {
      background: var(--color-bg-tertiary);
      border-color: var(--color-border-default);
      transform: translateX(4px);
    }

    .lesson-card:hover .lesson-arrow {
      opacity: 1;
      transform: translateX(0);
    }
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
    opacity: 0.6; /* Default visible for touch devices */
    transition: all var(--duration-fast) var(--ease-out);
  }

  /* Only hide arrow by default on hover-capable devices */
  @media (hover: hover) and (pointer: fine) {
    .lesson-arrow {
      opacity: 0;
      transform: translateX(-8px);
    }
  }

  /* Loading/Error States */
  .loading-state,
  .error-state {
    position: absolute;
    inset: 0;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: var(--space-4);
    padding: var(--space-12);
    color: var(--color-text-secondary);
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
    position: relative;
  }

  .lesson-header {
    flex-shrink: 0;
    padding: var(--space-4) var(--space-6);
    border-bottom: 1px solid var(--color-border-subtle);
    background: var(--color-bg-secondary);
  }

  .lesson-header-inner {
    display: flex;
    align-items: center;
    gap: var(--space-4);
    max-width: 1400px;
    margin: 0 auto;
    width: 100%;
  }

  .back-btn {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    background: transparent;
    border: 1px solid var(--color-border-default);
    border-radius: var(--radius-md);
    font-size: var(--text-sm);
    line-height: 1;
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
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    line-height: 1;
    margin: 0;
    padding-bottom: 2px;
  }

  /* Two-column Layout */
  .lesson-layout {
    flex: 1;
    display: grid;
    grid-template-columns: 1fr 460px;
    grid-template-rows: 1fr;
    overflow: hidden;
    min-height: 0; /* Critical for flex-in-grid scrolling */
  }

  /* Lesson Content Panel */
  .lesson-content-panel {
    position: relative; /* For absolute positioning of loading state */
    min-height: 0; /* Critical for grid/flex scrolling */
    overflow-y: auto;
    overflow-anchor: none; /* Prevent browser scroll anchoring when citations load */
    padding: var(--space-6);
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

  /* Lesson-level Citations */
  .lesson-citations {
    max-width: 720px;
    margin: var(--space-8) auto 0;
    padding: var(--space-4);
    background: var(--color-surface-subtle);
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-lg);
  }

  .lesson-citations--error {
    padding: var(--space-3);
  }

  .lesson-citations-error {
    font-size: var(--text-sm);
    color: var(--color-text-tertiary);
    font-style: italic;
  }

  .lesson-citations-header {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    margin-bottom: var(--space-3);
    padding-bottom: var(--space-2);
    border-bottom: 1px solid var(--color-border-subtle);
    font-size: var(--text-xs);
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: var(--tracking-wider);
    color: var(--color-text-secondary);
  }

  .lesson-citations-header svg {
    width: 16px;
    height: 16px;
    color: var(--color-accent);
  }

  .lesson-citations-list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }

  .lesson-citations-list li {
    margin: 0;
  }

  .lesson-citations-list a {
    display: block;
    padding: var(--space-2);
    background: var(--color-bg-primary);
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-md);
    font-size: var(--text-sm);
    color: var(--color-accent);
    text-decoration: none;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    transition: all var(--duration-fast) var(--ease-out);
  }

  .lesson-citations-list a:hover {
    background: var(--color-bg-secondary);
    border-color: var(--color-accent-muted);
  }

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
    background: linear-gradient(90deg, var(--color-accent-muted) 0%, var(--color-accent) 50%, var(--color-accent-muted) 100%);
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

  .messages-list {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
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

  /* Mobile Chat FAB - hidden on desktop */
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

  /* Intermediate breakpoint: narrower chat panel on medium screens */
  @media (max-width: 1280px) and (min-width: 1025px) {
    .lesson-layout {
      grid-template-columns: 1fr 400px;
    }
  }

  /* Responsive: Stack on smaller screens with flexible heights */
  @media (max-width: 1024px) {
    /* Hide desktop chat panel, show FAB */
    .chat-panel--desktop {
      display: none;
    }

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

    /* Lesson content takes full height on mobile */
    .lesson-layout {
      display: block;
    }

    .lesson-content-panel {
      height: 100%;
      border-right: none;
      border-bottom: none;
      overflow-y: auto;
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
