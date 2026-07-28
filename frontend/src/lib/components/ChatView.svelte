<script lang="ts">
    import ChatInput from "./ChatInput.svelte";
    import WelcomeScreen from "./WelcomeScreen.svelte";
    import CitationPanel from "./CitationPanel.svelte";
    import MessageBubble from "./MessageBubble.svelte";
    import NewContentIndicator from "./NewContentIndicator.svelte";
    import StreamingMessagesList from "./StreamingMessagesList.svelte";
    import {
        hasVisibleChatMessageText,
        streamChat,
    } from "../services/chat";
    import { StreamFailureError } from "../services/sse";
    import { createChatMessageId } from "../utils/chatMessageId";
    import { createStreamingState } from "../composables/createStreamingState.svelte";
    import { createScrollAnchor } from "../composables/createScrollAnchor.svelte";
    import {
        chatSession,
        type ChatSessionMessage,
    } from "../composables/chatSession.svelte";

    // Transcript and session id live in the module-level session store so
    // switching between the Chat and Learn tabs does not discard them.
    let messagesContainer: HTMLElement | null = $state(null);
    let activeStreamingMessageId = $state<string | null>(null);
    let activeChatStreamController: AbortController | null = null;

    // Scroll indicator for new off-screen content during streaming
    const scrollAnchor = createScrollAnchor();

    // Attach scroll anchor to container when it mounts
    $effect(() => {
        scrollAnchor.attach(messagesContainer);
    });

    // Streaming state from composable (with 800ms status persistence)
    const streaming = createStreamingState({ statusClearDelayMs: 800 });

    function cancelInFlightChatStream(): void {
        activeChatStreamController?.abort();
        activeChatStreamController = null;
    }

    function isActiveChatStream(
        chatStreamController: AbortController,
    ): boolean {
        return (
            activeChatStreamController === chatStreamController &&
            !chatStreamController.signal.aborted
        );
    }

    // Cleanup the active stream and timers on unmount
    $effect(() => {
        return () => {
            cancelInFlightChatStream();
            streaming.cleanup();
            scrollAnchor.cleanup();
        };
    });

    // Session ID for chat continuity (survives view switches via the store)
    const sessionId = $derived(chatSession.sessionId);

    let hasStreamingContent = $derived.by(() => {
        if (!streaming.isStreaming || !activeStreamingMessageId) return false;
        const activeMessage = chatSession.messages.find(
            (existingMessage) =>
                existingMessage.messageId === activeStreamingMessageId,
        );
        return hasVisibleChatMessageText(activeMessage?.messageText ?? "");
    });

    function findMessageIndex(messageId: string): number {
        return chatSession.messages.findIndex(
            (existingMessage) => existingMessage.messageId === messageId,
        );
    }

    function ensureAssistantMessage(messageId: string): void {
        if (findMessageIndex(messageId) >= 0) return;
        chatSession.messages = [
            ...chatSession.messages,
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
        updater: (message: ChatSessionMessage) => ChatSessionMessage,
    ): void {
        const targetIndex = findMessageIndex(messageId);
        if (targetIndex < 0) return;

        const existingMessage = chatSession.messages[targetIndex];
        const updatedMessage = updater(existingMessage);

        chatSession.messages = [
            ...chatSession.messages.slice(0, targetIndex),
            updatedMessage,
            ...chatSession.messages.slice(targetIndex + 1),
        ];
    }

    async function executeChatStream(
        userQuery: string,
        assistantMessageId: string,
        chatStreamController: AbortController,
    ): Promise<void> {
        try {
            await streamChat(
                sessionId,
                userQuery,
                (chunk) => {
                    if (!isActiveChatStream(chatStreamController)) return;
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
                {
                    signal: chatStreamController.signal,
                    onStatus: (streamStatus) => {
                        if (!isActiveChatStream(chatStreamController)) return;
                        streaming.updateStatus(streamStatus);
                    },
                    onProvider: (providerEvent) => {
                        if (!isActiveChatStream(chatStreamController)) return;
                        streaming.updateProvider(providerEvent);
                    },
                    onError: (streamError) => {
                        if (!isActiveChatStream(chatStreamController)) return;
                        streaming.updateStatus(streamError);
                    },
                    onCitations: (citations) => {
                        if (!isActiveChatStream(chatStreamController)) return;
                        ensureAssistantMessage(assistantMessageId);
                        updateAssistantMessage(
                            assistantMessageId,
                            (existingMessage) => ({
                                ...existingMessage,
                                citations,
                            }),
                        );
                    },
                },
            );
        } catch (error) {
            if (!isActiveChatStream(chatStreamController)) return;
            streaming.failStream();
            const errorMessage =
                error instanceof Error
                    ? error.message
                    : "Sorry, I encountered an error. Please try again.";
            const errorDetails =
                error instanceof StreamFailureError
                    ? error.details
                    : undefined;
            const errorRetryable =
                error instanceof StreamFailureError &&
                error.retryable === true;
            ensureAssistantMessage(assistantMessageId);
            updateAssistantMessage(assistantMessageId, (existingMessage) =>
                hasVisibleChatMessageText(existingMessage.messageText)
                    ? {
                          ...existingMessage,
                          streamErrorMessage: errorMessage,
                          errorDetails,
                          errorRetryable,
                      }
                    : {
                          ...existingMessage,
                          messageText: errorMessage,
                          isError: true,
                          errorDetails,
                          errorRetryable,
                      },
            );
        }
    }

    async function streamAssistantResponse(userQuery: string): Promise<void> {
        cancelInFlightChatStream();
        const chatStreamController = new AbortController();
        activeChatStreamController = chatStreamController;

        try {
            // Scroll once when user sends - no auto-scroll during streaming
            await scrollAnchor.scrollOnce();
            if (!isActiveChatStream(chatStreamController)) return;

            // Start streaming
            streaming.startStream();
            const assistantMessageId = createChatMessageId("chat", sessionId);
            activeStreamingMessageId = assistantMessageId;

            // Track new message for scroll indicator (counts messages, not chunks)
            scrollAnchor.onNewMessageStarted();

            await executeChatStream(
                userQuery,
                assistantMessageId,
                chatStreamController,
            );
        } finally {
            if (activeChatStreamController === chatStreamController) {
                activeChatStreamController = null;
                if (!chatStreamController.signal.aborted) {
                    if (streaming.isStreaming) {
                        streaming.finishStream();
                    }
                    activeStreamingMessageId = null;
                    // Give successful responses and terminal errors the same
                    // final reveal after their DOM content has settled.
                    await scrollAnchor.revealFinalContentIfFollowing();
                }
            }
        }
    }

    async function handleSend(message: string): Promise<void> {
        if (!message.trim() || streaming.isStreaming) return;

        const userQuery = message.trim();

        // Add user message
        chatSession.messages = [
            ...chatSession.messages,
            {
                messageId: createChatMessageId("chat", sessionId),
                role: "user",
                messageText: userQuery,
                timestamp: Date.now(),
            },
        ];

        await streamAssistantResponse(userQuery);
    }

    /**
     * Re-runs the question that produced a failed assistant message.
     * The failed bubble is removed and the existing user message is reused,
     * so the transcript reads as one continuous conversation.
     */
    async function handleRetryMessage(
        failedMessage: ChatSessionMessage,
    ): Promise<void> {
        if (streaming.isStreaming) return;
        const failedIndex = findMessageIndex(failedMessage.messageId);
        if (failedIndex < 0) return;

        const precedingUserMessage = chatSession.messages
            .slice(0, failedIndex)
            .findLast((candidateMessage) => candidateMessage.role === "user");
        if (!precedingUserMessage) return;

        chatSession.messages = chatSession.messages.filter(
            (existingMessage) =>
                existingMessage.messageId !== failedMessage.messageId,
        );
        await streamAssistantResponse(precedingUserMessage.messageText);
    }

    function handleSuggestionClick(suggestion: string) {
        handleSend(suggestion);
    }
</script>

<div class="chat-view">
    <div class="messages-wrapper">
        <div
            class="messages-container"
            bind:this={messagesContainer}
            onscroll={scrollAnchor.onUserScroll}
        >
            <div class="messages-inner">
                {#if chatSession.messages.length === 0 && !streaming.isStreaming}
                    <WelcomeScreen onSuggestionClick={handleSuggestionClick} />
                {:else}
                    <StreamingMessagesList
                        messages={chatSession.messages}
                        isStreaming={streaming.isStreaming}
                        statusMessage={streaming.statusMessage}
                        statusDetails={streaming.statusDetails}
                        citationWarning={streaming.citationWarning}
                        hasContent={hasStreamingContent}
                        streamingMessageId={activeStreamingMessageId}
                    >
                        {#snippet messageRenderer({
                            message,
                            index,
                            isStreaming,
                        })}
                            {@const typedMessage =
                                message as ChatSessionMessage}
                            <div class="message-with-citations">
                                <MessageBubble
                                    message={typedMessage}
                                    {index}
                                    {isStreaming}
                                    onRetry={handleRetryMessage}
                                />
                                {#if typedMessage.role === "assistant" && typedMessage.citations && typedMessage.citations.length > 0 && !typedMessage.isError}
                                    <CitationPanel
                                        citations={typedMessage.citations}
                                    />
                                {/if}
                            </div>
                        {/snippet}
                    </StreamingMessagesList>
                {/if}
            </div>
        </div>

        <NewContentIndicator
            visible={scrollAnchor.showIndicator}
            unseenCount={scrollAnchor.unseenCount}
            onJumpToBottom={scrollAnchor.jumpToBottom}
        />
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

    .messages-wrapper {
        flex: 1;
        position: relative;
        overflow: hidden;
    }

    .messages-container {
        height: 100%;
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
