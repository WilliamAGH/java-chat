<script lang="ts">
    let { title, canClear, onClear, onClose, variant }: {
        title: string;
        canClear: boolean;
        onClear: () => void;
        onClose?: () => void;
        variant: "panel" | "drawer";
    } = $props();
</script>

<div class={["lesson-chat-header", { drawer: variant === "drawer" }]}>
    <div class="chat-title">
        <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="1.5"
            aria-hidden="true"
        >
            <path
                d="M7.5 8.25h9m-9 3H12m-9.75 1.51c0 1.6 1.123 2.994 2.707 3.227 1.129.166 2.27.293 3.423.379.35.026.67.21.865.501L12 21l2.755-4.133a1.14 1.14 0 0 1 .865-.501 48.172 48.172 0 0 0 3.423-.379c1.584-.233 2.707-1.626 2.707-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0 0 12 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018Z"
            />
        </svg>
        <span>{title}</span>
    </div>
    {#if canClear || onClose}
        <div class="chat-actions">
            {#if canClear}
                <button type="button" onclick={onClear} title="Clear chat" aria-label="Clear chat">
                    <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
                        <path
                            fill-rule="evenodd"
                            d="M8.75 1A2.75 2.75 0 0 0 6 3.75v.443c-.795.077-1.584.176-2.365.298a.75.75 0 1 0 .23 1.482l.149-.022.841 10.518A2.75 2.75 0 0 0 7.596 19h4.807a2.75 2.75 0 0 0 2.742-2.53l.841-10.519.149.023a.75.75 0 0 0 .23-1.482A41.03 41.03 0 0 0 14 4.193V3.75A2.75 2.75 0 0 0 11.25 1h-2.5ZM10 4c.84 0 1.673.025 2.5.075V3.75c0-.69-.56-1.25-1.25-1.25h-2.5c-.69 0-1.25.56-1.25 1.25v.325C8.327 4.025 9.16 4 10 4ZM8.58 7.72a.75.75 0 0 0-1.5.06l.3 7.5a.75.75 0 1 0 1.5-.06l-.3-7.5Zm4.34.06a.75.75 0 1 0-1.5-.06l-.3 7.5a.75.75 0 1 0 1.5.06l.3-7.5Z"
                            clip-rule="evenodd"
                        />
                    </svg>
                </button>
            {/if}
            {#if onClose}
                <button type="button" onclick={onClose} aria-label="Close chat">
                    <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
                        <path
                            d="M6.28 5.22a.75.75 0 0 0-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 1 0 1.06 1.06L10 11.06l3.72 3.72a.75.75 0 1 0 1.06-1.06L11.06 10l3.72-3.72a.75.75 0 0 0-1.06-1.06L10 8.94 6.28 5.22Z"
                        />
                    </svg>
                </button>
            {/if}
        </div>
    {/if}
</div>

<style>
    .lesson-chat-header {
        flex-shrink: 0;
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

    .chat-title {
        flex: 1;
        display: flex;
        align-items: center;
        gap: var(--space-2);
    }

    .chat-title span {
        flex: 1;
    }

    .chat-title svg {
        flex-shrink: 0;
        width: 18px;
        height: 18px;
        color: var(--color-accent);
    }

    .chat-actions {
        flex-shrink: 0;
        display: flex;
        align-items: center;
        gap: var(--space-2);
    }

    button {
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
        transition: background-color var(--duration-fast) var(--ease-out), color var(--duration-fast) var(--ease-out);
    }

    button:hover {
        background: var(--color-bg-tertiary);
        color: var(--color-text-secondary);
    }

    button svg {
        width: 16px;
        height: 16px;
    }

    .drawer {
        justify-content: space-between;
        gap: 0;
        padding: var(--space-4);
        background: transparent;
    }

    .drawer .chat-title {
        flex: initial;
        font-size: var(--text-base);
        color: var(--color-text-primary);
    }

    .drawer .chat-title svg,
    .drawer button svg {
        width: 20px;
        height: 20px;
    }

    .drawer .chat-title span {
        flex: initial;
    }

    .drawer .chat-title svg,
    .drawer .chat-actions,
    .drawer button {
        flex-shrink: 1;
    }

    .drawer button {
        width: 44px;
        height: 44px;
        color: var(--color-text-secondary);
    }

    .drawer button:hover {
        color: var(--color-text-primary);
    }
</style>
