<script lang="ts">
    import {
        fetchTOC,
        streamGuidedChat,
        type GuidedLesson,
    } from "../services/guided";
    import {
        clearChatSession,
        type Citation,
        type ChatMessage,
    } from "../services/chat";
    import CitationPanel from "./CitationPanel.svelte";
    import GuidedLessonCatalog from "./GuidedLessonCatalog.svelte";
    import GuidedLessonContentPanel from "./GuidedLessonContentPanel.svelte";
    import GuidedLessonHeader from "./GuidedLessonHeader.svelte";
    import GuidedLessonChatPanel from "./GuidedLessonChatPanel.svelte";
    import MessageBubble from "./MessageBubble.svelte";
    import MobileChatDrawer from "./MobileChatDrawer.svelte";
    import { generateSessionId } from "../utils/session";
    import { createChatMessageId } from "../utils/chatMessageId";
    import { createStreamingState } from "../composables/createStreamingState.svelte";
    import { createScrollAnchor } from "../composables/createScrollAnchor.svelte";

    // TOC state
    let lessons = $state<GuidedLesson[]>([]);
    let loadingTOC = $state(true);
    let tocError = $state<string | null>(null);

    let selectedLesson = $state<GuidedLesson | null>(null);

    /** Chat message type enriched with streamed citations. */
    interface MessageWithCitations extends ChatMessage {
        citations?: Citation[];
    }

    // Chat state - per-lesson persistence
    const chatHistoryByLesson = new Map<string, MessageWithCitations[]>();
    let messages = $state<MessageWithCitations[]>([]);
    let activeStreamingMessageId = $state<string | null>(null);

    // Scroll indicator for new off-screen content during streaming
    const scrollAnchor = createScrollAnchor();

    // Streaming state from composable (immediate status clear for LearnView)
    const streaming = createStreamingState();

    // Cleanup active chat state and scroll tracking on unmount
    $effect(() => {
        return () => {
            cancelInFlightGuidedChatStream();
            streaming.cleanup();
            scrollAnchor.cleanup();
        };
    });

    let hasStreamingContent = $derived.by(() => {
        if (!streaming.isStreaming || !activeStreamingMessageId) return false;
        const activeMessage = messages.find(
            (existingMessage) =>
                existingMessage.messageId === activeStreamingMessageId,
        );
        return !!activeMessage?.messageText;
    });

    // Desktop chat panel ref for scroll management
    let desktopChatPanel: GuidedLessonChatPanel | null = $state(null);
    // Component ref for mobile drawer to access its scroll container
    let mobileDrawer: MobileChatDrawer | null = $state(null);

    // Mobile chat drawer state
    let isChatDrawerOpen = $state(false);

    // Attach scroll anchor to the active container when components mount
    $effect(() => {
        // Re-run when drawer state or panel refs change
        const container = isChatDrawerOpen
            ? mobileDrawer?.getMessagesContainer()
            : desktopChatPanel?.getMessagesContainer();
        if (container) {
            scrollAnchor.attach(container);
        }
    });

    // Session IDs per lesson for backend conversation isolation
    // Each lesson gets its own session ID to prevent conversation bleeding across topics
    const sessionIdsByLesson = new Map<string, string>();

    let guidedChatAbortController: AbortController | null = null;
    let guidedChatStreamVersion = 0;

    /**
     * Gets or creates a session ID for a specific lesson.
     * Each lesson gets its own backend session to prevent conversation bleeding.
     */
    function getSessionIdForLesson(slug: string): string {
        let lessonSessionId = sessionIdsByLesson.get(slug);
        if (!lessonSessionId) {
            lessonSessionId = generateSessionId(`guided:${slug}`);
            sessionIdsByLesson.set(slug, lessonSessionId);
        }
        return lessonSessionId;
    }

    function cancelInFlightGuidedChatStream(): void {
        guidedChatStreamVersion++;
        if (guidedChatAbortController) {
            guidedChatAbortController.abort();
            guidedChatAbortController = null;
        }
        streaming.reset();
        activeStreamingMessageId = null;
    }

    // Load TOC on mount
    $effect(() => {
        loadTOC();
    });

    async function loadTOC(): Promise<void> {
        loadingTOC = true;
        tocError = null;

        try {
            lessons = await fetchTOC();
        } catch (error) {
            tocError =
                error instanceof Error
                    ? error.message
                    : "Failed to load lessons";
            lessons = [];
        } finally {
            loadingTOC = false;
        }
    }

    function selectLesson(lesson: GuidedLesson): void {
        if (selectedLesson?.slug !== lesson.slug) {
            scrollAnchor.reset();
        }
        if (selectedLesson && messages.length > 0) {
            chatHistoryByLesson.set(selectedLesson.slug, [...messages]);
        }
        selectedLesson = lesson;
        isChatDrawerOpen = false;
        messages = chatHistoryByLesson.get(lesson.slug) ?? [];
    }

    function goBack(): void {
        // Save current chat history before going back
        if (selectedLesson && messages.length > 0) {
            chatHistoryByLesson.set(selectedLesson.slug, [...messages]);
        }

        cancelInFlightGuidedChatStream();
        isChatDrawerOpen = false;
        selectedLesson = null;
        messages = [];
    }

    function clearChat(): void {
        cancelInFlightGuidedChatStream();
        scrollAnchor.reset();
        if (selectedLesson) {
            const lessonSlug = selectedLesson.slug;
            const lessonSessionId = sessionIdsByLesson.get(lessonSlug);
            if (lessonSessionId) {
                sessionIdsByLesson.delete(lessonSlug);
                void clearChatSession(lessonSessionId).catch((error) => {
                    console.warn(
                        `[LearnView] Failed to clear backend session for lesson: ${lessonSlug}`,
                        error,
                    );
                });
            }

            chatHistoryByLesson.delete(lessonSlug);
        }

        messages = [];
    }

    function toggleChatDrawer(): void {
        isChatDrawerOpen = !isChatDrawerOpen;
        // Re-attach scroll anchor to the new active container
        updateScrollAnchorContainer();
    }

    function closeChatDrawer(): void {
        isChatDrawerOpen = false;
        // Re-attach scroll anchor to desktop container
        updateScrollAnchorContainer();
    }

    /** Returns the currently active messages container based on drawer state. */
    function getActiveMessagesContainer(): HTMLElement | null {
        return isChatDrawerOpen
            ? (mobileDrawer?.getMessagesContainer() ?? null)
            : (desktopChatPanel?.getMessagesContainer() ?? null);
    }

    /** Updates the scroll anchor to track the active container. */
    function updateScrollAnchorContainer(): void {
        // Use setTimeout to ensure DOM has updated after drawer state change
        setTimeout(() => {
            scrollAnchor.attach(getActiveMessagesContainer());
        }, 0);
    }

    function findMessageIndex(messageId: string): number {
        return messages.findIndex(
            (existingMessage) => existingMessage.messageId === messageId,
        );
    }

    function ensureAssistantMessage(messageId: string): void {
        if (findMessageIndex(messageId) >= 0) return;
        messages = [
            ...messages,
            {
                messageId,
                role: "assistant",
                messageText: "",
                timestamp: Date.now(),
            },
        ];
    }

    function updateAssistantMessage(
        messageId: string,
        updater: (message: MessageWithCitations) => MessageWithCitations,
    ): void {
        const targetIndex = findMessageIndex(messageId);
        if (targetIndex < 0) return;

        const existingMessage = messages[targetIndex];
        const updatedMessage = updater(existingMessage);

        messages = [
            ...messages.slice(0, targetIndex),
            updatedMessage,
            ...messages.slice(targetIndex + 1),
        ];
    }

    async function handleSend(message: string): Promise<void> {
        if (!message.trim() || streaming.isStreaming || !selectedLesson) return;

        guidedChatStreamVersion++;
        const activeStreamVersion = guidedChatStreamVersion;

        guidedChatAbortController?.abort();
        guidedChatAbortController = new AbortController();
        const abortSignal = guidedChatAbortController.signal;

        const streamLessonSlug = selectedLesson.slug;
        const userQuery = message.trim();
        const lessonSessionId = getSessionIdForLesson(streamLessonSlug);

        messages = [
            ...messages,
            {
                messageId: createChatMessageId("guided", lessonSessionId),
                role: "user",
                messageText: userQuery,
                timestamp: Date.now(),
            },
        ];

        // Scroll once when user sends - no auto-scroll during streaming
        await scrollAnchor.scrollOnce();

        streaming.startStream();
        const assistantMessageId = createChatMessageId(
            "guided",
            lessonSessionId,
        );
        activeStreamingMessageId = assistantMessageId;

        // Track new message for scroll indicator (counts messages, not chunks)
        scrollAnchor.onNewMessageStarted();

        try {
            await streamGuidedChat(
                lessonSessionId,
                streamLessonSlug,
                userQuery,
                {
                    signal: abortSignal,
                    onChunk: (chunk) => {
                        // Guard: ignore chunks if user navigated away
                        if (selectedLesson?.slug !== streamLessonSlug) return;
                        if (guidedChatStreamVersion !== activeStreamVersion)
                            return;
                        if (abortSignal.aborted) return;
                        ensureAssistantMessage(assistantMessageId);
                        updateAssistantMessage(
                            assistantMessageId,
                            (existingMessage) => ({
                                ...existingMessage,
                                messageText: existingMessage.messageText + chunk,
                            }),
                        );
                        scrollAnchor.onContentAdded();
                    },
                    onStatus: (status) => {
                        // Guard: ignore status if user navigated away
                        if (selectedLesson?.slug !== streamLessonSlug) return;
                        if (guidedChatStreamVersion !== activeStreamVersion)
                            return;
                        if (abortSignal.aborted) return;
                        streaming.updateStatus(status);
                    },
                    onProvider: (providerEvent) => {
                        if (selectedLesson?.slug !== streamLessonSlug) return;
                        if (guidedChatStreamVersion !== activeStreamVersion)
                            return;
                        if (abortSignal.aborted) return;
                        streaming.updateProvider(providerEvent);
                    },
                    onCitations: (citations) => {
                        // Guard: ignore citations if user navigated away
                        if (selectedLesson?.slug !== streamLessonSlug) return;
                        if (guidedChatStreamVersion !== activeStreamVersion)
                            return;
                        if (abortSignal.aborted) return;
                        ensureAssistantMessage(assistantMessageId);
                        updateAssistantMessage(
                            assistantMessageId,
                            (existingMessage) => ({
                                ...existingMessage,
                                citations,
                            }),
                        );
                    },
                    onError: (streamError) => {
                        console.error(
                            "Stream error during processing:",
                            streamError,
                        );
                    },
                },
            );
        } catch (error) {
            if (selectedLesson?.slug !== streamLessonSlug) return;
            if (guidedChatStreamVersion !== activeStreamVersion) return;
            if (abortSignal.aborted) return;
            const errorMessage =
                error instanceof Error
                    ? error.message
                    : "Sorry, I encountered an error. Please try again.";
            ensureAssistantMessage(assistantMessageId);
            updateAssistantMessage(assistantMessageId, (existingMessage) => ({
                ...existingMessage,
                messageText: errorMessage,
                isError: true,
            }));
        } finally {
            // Guard: only reset streaming state if still on same lesson
            if (
                selectedLesson?.slug === streamLessonSlug &&
                guidedChatStreamVersion === activeStreamVersion &&
                !abortSignal.aborted
            ) {
                streaming.finishStream();
                activeStreamingMessageId = null;
                // No final scroll - user maintains their position
            }

            if (guidedChatStreamVersion === activeStreamVersion) {
                guidedChatAbortController = null;
            }
        }
    }

</script>

<div class="learn-view">
    {#if !selectedLesson}
        <GuidedLessonCatalog
            {lessons}
            isLoading={loadingTOC}
            errorMessage={tocError}
            onRetry={loadTOC}
            onSelect={selectLesson}
        />
    {:else}
        <!-- Lesson View -->
        <div class="lesson-container">
            <GuidedLessonHeader
                lessonTitle={selectedLesson.title}
                onReturnToLessons={goBack}
            />

            <!-- Two-column layout: Content + Chat (desktop) -->
            <div class="lesson-layout">
                <GuidedLessonContentPanel lesson={selectedLesson} />

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
                    showScrollIndicator={scrollAnchor.showIndicator}
                    unseenCount={scrollAnchor.unseenCount}
                    onClear={clearChat}
                    onSend={handleSend}
                    onScroll={scrollAnchor.onUserScroll}
                    onJumpToBottom={scrollAnchor.jumpToBottom}
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
                showScrollIndicator={scrollAnchor.showIndicator}
                unseenCount={scrollAnchor.unseenCount}
                onToggle={toggleChatDrawer}
                onClose={closeChatDrawer}
                onClear={clearChat}
                onSend={handleSend}
                onScroll={scrollAnchor.onUserScroll}
                onJumpToBottom={scrollAnchor.jumpToBottom}
            >
                {#snippet messageRenderer({ message, index, isStreaming })}
                    {@const typedMessage = message as MessageWithCitations}
                    <div class="message-with-citations">
                        <MessageBubble
                            message={typedMessage}
                            {index}
                            {isStreaming}
                        />
                        {#if typedMessage.role === "assistant" && typedMessage.citations && typedMessage.citations.length > 0 && !typedMessage.isError}
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

    .lesson-container {
        flex: 1;
        display: flex;
        flex-direction: column;
        overflow: hidden;
        position: relative;
    }

    .lesson-layout {
        flex: 1;
        display: grid;
        grid-template-columns: 1fr 460px;
        grid-template-rows: 1fr;
        overflow: hidden;
        min-height: 0;
        max-height: 100%;
    }

    @media (max-width: 1280px) and (min-width: 1025px) {
        .lesson-layout {
            grid-template-columns: 1fr 400px;
        }
    }

    @media (max-width: 1024px) {
        .lesson-layout {
            display: block;
        }
    }
</style>
