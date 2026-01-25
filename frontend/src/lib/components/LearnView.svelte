<script lang="ts">
  import { fetchTOC, fetchLessonContent, fetchGuidedLessonCitations, streamGuidedChat, type GuidedLesson } from '../services/guided'
  import { clearChatSession, type Citation, type ChatMessage } from '../services/chat'
  import { parseMarkdown, applyJavaLanguageDetection } from '../services/markdown'
  import CitationPanel from './CitationPanel.svelte'
  import GuidedLessonChatPanel from './GuidedLessonChatPanel.svelte'
  import LessonCitations from './LessonCitations.svelte'
  import MessageBubble from './MessageBubble.svelte'
  import ThinkingIndicator from './ThinkingIndicator.svelte'
  import MobileChatDrawer from './MobileChatDrawer.svelte'
  import { deduplicateCitations } from '../utils/url'
  import { highlightCodeBlocks } from '../utils/highlight'
  import { isNearBottom, scrollToBottom } from '../utils/scroll'
  import { generateSessionId } from '../utils/session'
  import { createChatMessageId } from '../utils/chatMessageId'
  import { createStreamingState } from '../composables/createStreamingState.svelte'

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

  /** Chat message type enriched with streamed citations. */
  interface MessageWithCitations extends ChatMessage {
    citations?: Citation[]
  }

  // Chat state - per-lesson persistence
  const chatHistoryByLesson = new Map<string, MessageWithCitations[]>()
  let messages = $state<MessageWithCitations[]>([])
  let shouldAutoScroll = $state(true)
  let activeStreamingMessageId = $state<string | null>(null)

  // Streaming state from composable (immediate status clear for LearnView)
  const streaming = createStreamingState()

  let hasStreamingContent = $derived.by(() => {
    if (!streaming.isStreaming || !activeStreamingMessageId) return false
    const activeMessage = messages.find((existingMessage) => existingMessage.messageId === activeStreamingMessageId)
    return !!activeMessage?.content
  })

  // Desktop chat panel ref for scroll management
  let desktopChatPanel: GuidedLessonChatPanel | null = $state(null)
  // Component ref for mobile drawer to access its scroll container
  let mobileDrawer: MobileChatDrawer | null = $state(null)

  // Mobile chat drawer state
  let isChatDrawerOpen = $state(false)

  // Element refs
  let lessonContentEl: HTMLElement | null = $state(null)
  let lessonContentPanelEl: HTMLElement | null = $state(null)

  // Session IDs per lesson for backend conversation isolation
  // Each lesson gets its own session ID to prevent conversation bleeding across topics
  const sessionIdsByLesson = new Map<string, string>()

  let guidedChatAbortController: AbortController | null = null
  let guidedChatStreamVersion = 0

  /**
   * Gets or creates a session ID for a specific lesson.
   * Each lesson gets its own backend session to prevent conversation bleeding.
   */
  function getSessionIdForLesson(slug: string): string {
    let lessonSessionId = sessionIdsByLesson.get(slug)
    if (!lessonSessionId) {
      lessonSessionId = generateSessionId(`guided:${slug}`)
      sessionIdsByLesson.set(slug, lessonSessionId)
    }
    return lessonSessionId
  }

  function cancelInFlightGuidedChatStream(): void {
    guidedChatStreamVersion++
    if (guidedChatAbortController) {
      guidedChatAbortController.abort()
      guidedChatAbortController = null
    }
    streaming.reset()
    activeStreamingMessageId = null
  }

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

      // Fetch citations for the lesson topic (Think Java-only, with explicit error tracking)
      fetchGuidedLessonCitations(lesson.slug).then((result) => {
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

    // Cancel any in-flight stream
    cancelInFlightGuidedChatStream()
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
    cancelInFlightGuidedChatStream()
    if (selectedLesson) {
      const lessonSlug = selectedLesson.slug
      const lessonSessionId = sessionIdsByLesson.get(lessonSlug)
      if (lessonSessionId) {
        sessionIdsByLesson.delete(lessonSlug)
        void clearChatSession(lessonSessionId).catch((error) => {
          console.warn(`[LearnView] Failed to clear backend session for lesson: ${lessonSlug}`, error)
        })
      }

      chatHistoryByLesson.delete(lessonSlug)
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
    return isChatDrawerOpen
      ? mobileDrawer?.getMessagesContainer() ?? null
      : desktopChatPanel?.getMessagesContainer() ?? null
  }

  function checkAutoScroll(): void {
    shouldAutoScroll = isNearBottom(getActiveMessagesContainer())
  }

  async function doScrollToBottom(): Promise<void> {
    await scrollToBottom(getActiveMessagesContainer(), shouldAutoScroll)
  }

  function findMessageIndex(messageId: string): number {
    return messages.findIndex((existingMessage) => existingMessage.messageId === messageId)
  }

  function ensureAssistantMessage(messageId: string): void {
    if (findMessageIndex(messageId) >= 0) return
    messages = [
      ...messages,
      {
        messageId,
        role: 'assistant',
        content: '',
        timestamp: Date.now()
      }
    ]
  }

  function updateAssistantMessage(messageId: string, updater: (message: MessageWithCitations) => MessageWithCitations): void {
    const targetIndex = findMessageIndex(messageId)
    if (targetIndex < 0) return

    const existingMessage = messages[targetIndex]
    const updatedMessage = updater(existingMessage)

    messages = [
      ...messages.slice(0, targetIndex),
      updatedMessage,
      ...messages.slice(targetIndex + 1)
    ]
  }

	  async function handleSend(message: string): Promise<void> {
	    if (!message.trim() || streaming.isStreaming || !selectedLesson) return
	
	    guidedChatStreamVersion++
	    const activeStreamVersion = guidedChatStreamVersion
	    guidedChatAbortController?.abort()
	    guidedChatAbortController = new AbortController()
	    const abortSignal = guidedChatAbortController.signal

	    const streamLessonSlug = selectedLesson.slug
	    const userQuery = message.trim()
	    const lessonSessionId = getSessionIdForLesson(streamLessonSlug)

    messages = [
      ...messages,
      {
        messageId: createChatMessageId('guided', lessonSessionId),
        role: 'user',
        content: userQuery,
        timestamp: Date.now()
      }
    ]

    shouldAutoScroll = true
    await doScrollToBottom()

    streaming.startStream()
    const assistantMessageId = createChatMessageId('guided', lessonSessionId)
	    activeStreamingMessageId = assistantMessageId
	
	    try {
	      await streamGuidedChat(lessonSessionId, selectedLesson.slug, userQuery, {
	        signal: abortSignal,
	        onChunk: (chunk) => {
	          // Guard: ignore chunks if user navigated away
	          if (selectedLesson?.slug !== streamLessonSlug) return
	          if (guidedChatStreamVersion !== activeStreamVersion) return
	          if (abortSignal.aborted) return
	          ensureAssistantMessage(assistantMessageId)
	          updateAssistantMessage(assistantMessageId, (existingMessage) => ({
	            ...existingMessage,
	            content: existingMessage.content + chunk
          }))
          doScrollToBottom()
        },
	        onStatus: (status) => {
	          // Guard: ignore status if user navigated away
	          if (selectedLesson?.slug !== streamLessonSlug) return
	          if (guidedChatStreamVersion !== activeStreamVersion) return
	          if (abortSignal.aborted) return
	          streaming.updateStatus(status)
	        },
	        onCitations: (citations) => {
	          // Guard: ignore citations if user navigated away
	          if (selectedLesson?.slug !== streamLessonSlug) return
	          if (guidedChatStreamVersion !== activeStreamVersion) return
	          if (abortSignal.aborted) return
	          ensureAssistantMessage(assistantMessageId)
	          updateAssistantMessage(assistantMessageId, (existingMessage) => ({
	            ...existingMessage,
	            citations
          }))
        },
        onError: (streamError) => {
          console.error('Stream error during processing:', streamError)
        }
	      })
	    } catch (error) {
	      if (selectedLesson?.slug !== streamLessonSlug) return
	      if (guidedChatStreamVersion !== activeStreamVersion) return
	      if (abortSignal.aborted) return
	      const errorMessage = error instanceof Error ? error.message : 'Sorry, I encountered an error. Please try again.'
	      ensureAssistantMessage(assistantMessageId)
	      updateAssistantMessage(assistantMessageId, (existingMessage) => ({
        ...existingMessage,
        content: errorMessage,
        isError: true
      }))
	    } finally {
	      // Guard: only reset streaming state if still on same lesson
	      if (
	        selectedLesson?.slug === streamLessonSlug &&
	        guidedChatStreamVersion === activeStreamVersion &&
	        !abortSignal.aborted
	      ) {
	        streaming.finishStream()
	        activeStreamingMessageId = null
	        await doScrollToBottom()
	      }

	      if (guidedChatStreamVersion === activeStreamVersion) {
	        guidedChatAbortController = null
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

	            <LessonCitations
	              citations={lessonCitations}
	              loaded={lessonCitationsLoaded}
	              error={lessonCitationsError}
	              slug={selectedLesson.slug}
	            />
	          {/if}
	        </div>

        <!-- Chat Panel (Desktop only) -->
        <GuidedLessonChatPanel
          bind:this={desktopChatPanel}
          {messages}
          isStreaming={streaming.isStreaming}
          statusMessage={streaming.statusMessage}
          statusDetails={streaming.statusDetails}
          hasContent={hasStreamingContent}
          streamingMessageId={activeStreamingMessageId}
          lessonTitle={selectedLesson.title}
          onClear={clearChat}
          onSend={handleSend}
          onScroll={checkAutoScroll}
        />
      </div>

      <!-- Mobile Chat FAB + Drawer -->
		      <MobileChatDrawer
		        bind:this={mobileDrawer}
		        isOpen={isChatDrawerOpen}
		        {messages}
		        isStreaming={streaming.isStreaming}
		        statusMessage={streaming.statusMessage}
		        statusDetails={streaming.statusDetails}
		        hasContent={hasStreamingContent}
		        streamingMessageId={activeStreamingMessageId}
		        title="Ask about this lesson"
		        emptyStateSubject={selectedLesson.title}
		        placeholder="Ask about this lesson..."
	        onToggle={toggleChatDrawer}
	        onClose={closeChatDrawer}
	        onClear={clearChat}
	        onSend={handleSend}
	        onScroll={checkAutoScroll}
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
	      </MobileChatDrawer>
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
    max-height: 100%; /* Prevent grid from expanding beyond parent */
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

	  /* Intermediate breakpoint: narrower chat panel on medium screens */
	  @media (max-width: 1280px) and (min-width: 1025px) {
	    .lesson-layout {
	      grid-template-columns: 1fr 400px;
    }
  }

	  /* Responsive: Stack on smaller screens with flexible heights */
	  @media (max-width: 1024px) {
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
  }
</style>
