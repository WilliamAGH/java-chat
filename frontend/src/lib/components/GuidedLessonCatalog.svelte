<script lang="ts">
    import type { GuidedLesson } from "../services/guided";
    import ThinkingIndicator from "./ThinkingIndicator.svelte";

    const LESSON_ENTRY_STAGGER_MS = 50;

    interface Props {
        lessons: GuidedLesson[];
        isLoading: boolean;
        errorMessage: string | null;
        onRetry: () => void;
        onSelect: (lesson: GuidedLesson) => void;
    }

    let { lessons, isLoading, errorMessage, onRetry, onSelect }: Props = $props();
</script>

<div class="toc-container">
    <div class="toc-inner">
        <div class="toc-header">
            <h1 class="toc-title">
                <span class="title-serif">Learn</span>
                <span class="title-accent">Java</span>
            </h1>
            <p class="toc-subtitle">
                Learn Java programming interactively with live lessons, real
                documentation, and AI. Select a topic to begin!
            </p>
        </div>

        {#if isLoading}
            <div class="loading-state">
                <ThinkingIndicator statusMessage="Loading lessons" />
            </div>
        {:else if errorMessage}
            <div class="error-state">
                <p>{errorMessage}</p>
                <button type="button" class="retry-btn" onclick={onRetry}
                    >Try Again</button
                >
            </div>
        {:else}
            <div class="lessons-grid">
                {#each lessons as lesson, lessonIndex (lesson.slug)}
                    <button
                        type="button"
                        class="lesson-card"
                        onclick={() => onSelect(lesson)}
                        style:animation-delay={`${lessonIndex * LESSON_ENTRY_STAGGER_MS}ms`}
                    >
                        <span class="lesson-number">{lessonIndex + 1}</span>
                        <div class="lesson-info">
                            <span class="lesson-title">{lesson.title}</span>
                            <span class="lesson-summary">{lesson.summary}</span>
                        </div>
                        <svg
                            class="lesson-arrow"
                            viewBox="0 0 20 20"
                            fill="currentColor"
                            aria-hidden="true"
                        >
                            <path
                                fill-rule="evenodd"
                                d="M3 10a.75.75 0 0 1 .75-.75h10.638L10.23 5.29a.75.75 0 1 1 1.04-1.08l5.5 5.25a.75.75 0 0 1 0 1.08l-5.5 5.25a.75.75 0 1 1-1.04-1.08l4.158-3.96H3.75A.75.75 0 0 1 3 10Z"
                                clip-rule="evenodd"
                            />
                        </svg>
                    </button>
                {/each}
            </div>
        {/if}
    </div>
</div>

<style>
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
        opacity: 0.6;
        transition: all var(--duration-fast) var(--ease-out);
    }

    @media (hover: hover) and (pointer: fine) {
        .lesson-arrow {
            opacity: 0;
            transform: translateX(-8px);
        }
    }

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
    }
</style>
