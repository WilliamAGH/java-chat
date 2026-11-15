<script lang="ts">
    import type { ChatMessage, Citation } from "../services/chat";
    import type { CitationPartialFailureStatus } from "../validation/schemas";
    import ChatInput from "./ChatInput.svelte";
    import LessonChatHeader from "./LessonChatHeader.svelte";
    import CitationPanel from "./CitationPanel.svelte";
    import MessageBubble from "./MessageBubble.svelte";
    import NewContentIndicator from "./NewContentIndicator.svelte";
    import StreamingMessagesList from "./StreamingMessagesList.svelte";

    /** Chat message type enriched with streamed citations. */
    interface MessageWithCitations extends ChatMessage {
        citations?: Citation[];
    }

    interface Props {
        messages: MessageWithCitations[];
        isStreaming: boolean;
        statusMessage: string;
        statusDetails: string;
        citationWarning: CitationPartialFailureStatus | null;
        hasContent: boolean;
        streamingMessageId: string | null;
        lessonTitle: string;
        /** Whether to show the new content indicator. */
        showScrollIndicator?: boolean;
        /** Number of unseen content updates. */
        unseenCount?: number;
        onClear: () => void;
        onSend: (message: string) => void;
        /** Re-runs the question behind a failed, retryable assistant message. */
        onRetry?: (message: ChatMessage) => void;
        onScroll: () => void;
        onJumpToBottom?: () => void;
    }

    let {
        messages,
        isStreaming,
        statusMessage,
        statusDetails,
        citationWarning,
        hasContent,
        streamingMessageId,
        lessonTitle,
        showScrollIndicator = false,
        unseenCount = 0,
        onClear,
        onSend,
        onRetry,
        onScroll,
        onJumpToBottom,
    }: Props = $props();

    let messagesContainer: HTMLElement | null = $state(null);

    /** Exposes the messages container element for external scroll management. */
    export function getMessagesContainer(): HTMLElement | null {
        return messagesContainer;
    }

    function handleJumpToBottom(): void {
        onJumpToBottom?.();
    }
</script>

<div class="chat-panel chat-panel--desktop">
    <LessonChatHeader
        title="Ask about this lesson"
        canClear={messages.length > 0}
        {onClear}
        variant="panel"
    />

    <div class="messages-wrapper">
        <div
            class="messages-container"
            bind:this={messagesContainer}
            onscroll={onScroll}
        >
            {#if messages.length === 0 && !isStreaming}
                <div class="chat-empty">
                    <p>Have questions about <strong>{lessonTitle}</strong>?</p>
                    <p class="hint">
                        Ask anything about the concepts in this lesson.
                    </p>
                </div>
            {:else}
                <StreamingMessagesList
                    {messages}
                    {isStreaming}
                    {statusMessage}
                    {statusDetails}
                    {citationWarning}
                    {hasContent}
                    {streamingMessageId}
                >
                    {#snippet messageRenderer({ message, index, isStreaming })}
                        {@const typedMessage = message as MessageWithCitations}
                        <div class="message-with-citations">
                            <MessageBubble
                                message={typedMessage}
                                {index}
                                {isStreaming}
                                {onRetry}
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

        <NewContentIndicator
            visible={showScrollIndicator}
            {unseenCount}
            onJumpToBottom={handleJumpToBottom}
        />
    </div>

    <ChatInput
        {onSend}
        disabled={isStreaming}
        placeholder="Ask about this lesson..."
    />
</div>

<style>
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
        content: "";
        position: absolute;
        top: 0;
        left: 0;
        right: 0;
        height: 2px;
        background: linear-gradient(
            90deg,
            var(--color-accent-muted) 0%,
            var(--color-accent) 50%,
            var(--color-accent-muted) 100%
        );
        opacity: 0.6;
        z-index: 1;
    }

    .messages-wrapper {
        flex: 1;
        position: relative;
        min-height: 0; /* Critical: allows overflow scroll to work in flexbox */
        overflow: hidden;
    }

    .messages-container {
        height: 100%;
        overflow-y: auto;
        overflow-x: hidden;
        padding: var(--space-4);
    }

    @media (prefers-reduced-motion: no-preference) {
        .messages-container {
            scroll-behavior: smooth;
        }
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

    .message-with-citations {
        display: flex;
        flex-direction: column;
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

    /* Hide desktop chat panel on smaller screens (mobile uses MobileChatDrawer) */
    @media (max-width: 1024px) {
        .chat-panel--desktop {
            display: none;
        }
    }
</style>
