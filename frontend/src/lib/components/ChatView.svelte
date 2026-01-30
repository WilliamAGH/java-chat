<script lang="ts">
    import ChatInput from "./ChatInput.svelte";
    import WelcomeScreen from "./WelcomeScreen.svelte";
    import CitationPanel from "./CitationPanel.svelte";
    import MessageBubble from "./MessageBubble.svelte";
    import NewContentIndicator from "./NewContentIndicator.svelte";
    import StreamingMessagesList from "./StreamingMessagesList.svelte";
    import {
        streamChat,
        type ChatMessage,
        type Citation,
    } from "../services/chat";
    import { generateSessionId } from "../utils/session";
    import { createChatMessageId } from "../utils/chatMessageId";
    import { createStreamingState } from "../composables/createStreamingState.svelte";
    import { createScrollAnchor } from "../composables/createScrollAnchor.svelte";

    /** Extended message type that includes inline citations from the stream. */
    interface MessageWithCitations extends ChatMessage {
        /** Citations received inline from the SSE stream (eliminates separate API call). */
        citations?: Citation[];
    }

    // View-specific state
    let messages = $state<MessageWithCitations[]>([]);
    let messagesContainer: HTMLElement | null = $state(null);
    let activeStreamingMessageId = $state<string | null>(null);

    // Scroll indicator for new off-screen content during streaming
    const scrollAnchor = createScrollAnchor();

    // Attach scroll anchor to container when it mounts
    $effect(() => {
        scrollAnchor.attach(messagesContainer);
    });

    // Streaming state from composable (with 800ms status persistence)
    const streaming = createStreamingState({ statusClearDelayMs: 800 });

    // Cleanup timers on unmount
    $effect(() => {
        return () => {
            streaming.cleanup();
            scrollAnchor.cleanup();
        };
    });

    // Session ID for chat continuity
    const sessionId = generateSessionId("chat");

    let hasStreamingContent = $derived.by(() => {
        if (!streaming.isStreaming || !activeStreamingMessageId) return false;
        const activeMessage = messages.find(
            (existingMessage) =>
                existingMessage.messageId === activeStreamingMessageId,
        );
        return !!activeMessage?.content;
    });

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
                content: "",
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

    async function executeChatStream(
        userQuery: string,
        assistantMessageId: string,
    ): Promise<void> {
        try {
            await streamChat(
                sessionId,
                userQuery,
                (chunk) => {
                    ensureAssistantMessage(assistantMessageId);
                    updateAssistantMessage(
                        assistantMessageId,
                        (existingMessage) => ({
                            ...existingMessage,
                            content: existingMessage.content + chunk,
                        }),
                    );
                    scrollAnchor.onContentAdded();
                },
                {
                    onStatus: streaming.updateStatus,
                    onError: streaming.updateStatus,
                    onCitations: (citations) => {
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
            const errorMessage =
                error instanceof Error
                    ? error.message
                    : "Sorry, I encountered an error. Please try again.";
            ensureAssistantMessage(assistantMessageId);
            updateAssistantMessage(assistantMessageId, (existingMessage) => ({
                ...existingMessage,
                content: errorMessage,
                isError: true,
            }));
        }
    }

    async function handleSend(message: string): Promise<void> {
        if (!message.trim() || streaming.isStreaming) return;

        const userQuery = message.trim();

        // Add user message
        messages = [
            ...messages,
            {
                messageId: createChatMessageId("chat", sessionId),
                role: "user",
                content: userQuery,
                timestamp: Date.now(),
            },
        ];

        // Scroll once when user sends - no auto-scroll during streaming
        await scrollAnchor.scrollOnce();

        // Start streaming
        streaming.startStream();
        activeStreamingMessageId = createChatMessageId("chat", sessionId);

        try {
            const assistantMessageId = activeStreamingMessageId;
            if (!assistantMessageId) return;
            await executeChatStream(userQuery, assistantMessageId);
        } finally {
            streaming.finishStream();
            activeStreamingMessageId = null;
            // No final scroll - user maintains their position
        }
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
                {#if messages.length === 0 && !streaming.isStreaming}
                    <WelcomeScreen onSuggestionClick={handleSuggestionClick} />
                {:else}
                    <StreamingMessagesList
                        {messages}
                        isStreaming={streaming.isStreaming}
                        statusMessage={streaming.statusMessage}
                        statusDetails={streaming.statusDetails}
                        hasContent={hasStreamingContent}
                        streamingMessageId={activeStreamingMessageId}
                    >
                        {#snippet messageRenderer({
                            message,
                            index,
                            isStreaming,
                        })}
                            {@const typedMessage =
                                message as MessageWithCitations}
                            <div class="message-with-citations">
                                <MessageBubble
                                    message={typedMessage}
                                    {index}
                                    {isStreaming}
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
