<script lang="ts">
    import { untrack } from "svelte";
    import type { Citation } from "../services/chat";
    import {
        fetchGuidedLessonCitations,
        streamLessonContent,
        type GuidedLesson,
    } from "../services/guided";
    import { applyJavaLanguageDetection } from "../services/javaLanguageDetection";
    import { parseMarkdown } from "../services/markdown";
    import { highlightCodeBlocks } from "../utils/highlight";
    import { deduplicateCitations } from "../utils/url";
    import LessonCitations from "./LessonCitations.svelte";
    import ThinkingIndicator from "./ThinkingIndicator.svelte";

    let { lesson }: { lesson: GuidedLesson } = $props();

    let lessonMarkdown = $state("");
    let lessonError = $state<string | null>(null);
    let isLessonLoading = $state(false);
    let lessonCitations = $state<Citation[]>([]);
    let lessonCitationsError = $state<string | null>(null);
    let areLessonCitationsLoaded = $state(false);
    let lessonContentElement: HTMLElement | null = $state(null);
    let lessonContentPanelElement: HTMLElement | null = $state(null);
    let lessonContentAbortController: AbortController | null = null;
    let lessonContentStreamVersion = 0;

    const renderedLesson = $derived(
        lessonMarkdown ? parseMarkdown(lessonMarkdown) : "",
    );

    $effect(() => {
        const lessonSlug = lesson.slug;
        void untrack(() => loadLessonContent(lessonSlug));
        return cancelInFlightLessonContentStream;
    });

    $effect(() => {
        const contentElement = lessonContentElement;
        if (!renderedLesson || !contentElement) return;

        let isHighlightingCancelled = false;
        applyJavaLanguageDetection(contentElement);
        highlightCodeBlocks(contentElement).catch((highlightError) => {
            if (!isHighlightingCancelled) {
                console.warn(
                    "[GuidedLessonContentPanel] Code highlighting failed:",
                    highlightError,
                );
            }
        });
        return () => {
            isHighlightingCancelled = true;
        };
    });

    function cancelInFlightLessonContentStream(): void {
        lessonContentStreamVersion++;
        lessonContentAbortController?.abort();
        lessonContentAbortController = null;
    }

    function isActiveLessonContentRequest(
        lessonSlug: string,
        activeLessonContentStreamVersion: number,
        lessonContentAbortSignal: AbortSignal,
    ): boolean {
        return (
            lesson.slug === lessonSlug &&
            lessonContentStreamVersion === activeLessonContentStreamVersion &&
            !lessonContentAbortSignal.aborted
        );
    }

    async function loadLessonContent(lessonSlug: string): Promise<void> {
        cancelInFlightLessonContentStream();
        const activeLessonContentStreamVersion = lessonContentStreamVersion;
        lessonContentAbortController = new AbortController();
        const lessonContentAbortSignal = lessonContentAbortController.signal;

        isLessonLoading = true;
        lessonMarkdown = "";
        lessonError = null;
        lessonCitations = [];
        lessonCitationsError = null;
        areLessonCitationsLoaded = false;

        try {
            let hasReceivedLessonChunk = false;
            await streamLessonContent(lessonSlug, {
                signal: lessonContentAbortSignal,
                onChunk: (lessonChunk) => {
                    if (
                        !isActiveLessonContentRequest(
                            lessonSlug,
                            activeLessonContentStreamVersion,
                            lessonContentAbortSignal,
                        )
                    )
                        return;
                    lessonMarkdown += lessonChunk;
                    if (!hasReceivedLessonChunk) {
                        hasReceivedLessonChunk = true;
                        isLessonLoading = false;
                    }
                },
                onError: (streamError) => {
                    if (
                        !isActiveLessonContentRequest(
                            lessonSlug,
                            activeLessonContentStreamVersion,
                            lessonContentAbortSignal,
                        )
                    )
                        return;
                    lessonError = streamError.message;
                },
            });

            if (
                !isActiveLessonContentRequest(
                    lessonSlug,
                    activeLessonContentStreamVersion,
                    lessonContentAbortSignal,
                )
            )
                return;
            void loadLessonCitations(
                lessonSlug,
                activeLessonContentStreamVersion,
                lessonContentAbortSignal,
            );
        } catch (error) {
            if (
                !isActiveLessonContentRequest(
                    lessonSlug,
                    activeLessonContentStreamVersion,
                    lessonContentAbortSignal,
                )
            )
                return;
            lessonError =
                error instanceof Error
                    ? error.message
                    : "Failed to load lesson";
            lessonMarkdown = "";
        } finally {
            if (
                isActiveLessonContentRequest(
                    lessonSlug,
                    activeLessonContentStreamVersion,
                    lessonContentAbortSignal,
                )
            ) {
                isLessonLoading = false;
                lessonContentAbortController = null;
            }
        }
    }

    async function loadLessonCitations(
        lessonSlug: string,
        activeLessonContentStreamVersion: number,
        lessonContentAbortSignal: AbortSignal,
    ): Promise<void> {
        const lessonPanelScrollTop = lessonContentPanelElement?.scrollTop ?? 0;
        try {
            const citationFetchResult =
                await fetchGuidedLessonCitations(lessonSlug);
            if (
                !isActiveLessonContentRequest(
                    lessonSlug,
                    activeLessonContentStreamVersion,
                    lessonContentAbortSignal,
                )
            )
                return;

            if (citationFetchResult.success) {
                lessonCitations = deduplicateCitations(
                    citationFetchResult.citations,
                );
            } else {
                lessonCitationsError = citationFetchResult.error;
            }
            areLessonCitationsLoaded = true;
            requestAnimationFrame(() => {
                if (
                    lessonContentPanelElement &&
                    lessonPanelScrollTop > 0
                ) {
                    lessonContentPanelElement.scrollTop = lessonPanelScrollTop;
                }
            });
        } catch (error) {
            if (
                !isActiveLessonContentRequest(
                    lessonSlug,
                    activeLessonContentStreamVersion,
                    lessonContentAbortSignal,
                )
            )
                return;
            lessonCitationsError =
                error instanceof Error
                    ? error.message
                    : "Failed to load lesson sources";
            areLessonCitationsLoaded = true;
        }
    }
</script>

<div class="lesson-content-panel" bind:this={lessonContentPanelElement}>
    {#if isLessonLoading}
        <div class="loading-state">
            <ThinkingIndicator statusMessage="Loading lesson" />
        </div>
    {:else if lessonError}
        <div class="error-state">
            <p>{lessonError}</p>
            <button
                type="button"
                class="retry-btn"
                onclick={() => loadLessonContent(lesson.slug)}>Try Again</button
            >
        </div>
    {:else}
        <div class="lesson-content" bind:this={lessonContentElement}>
            {@html renderedLesson}
        </div>
        <LessonCitations
            citations={lessonCitations}
            loaded={areLessonCitationsLoaded}
            error={lessonCitationsError}
            slug={lesson.slug}
        />
    {/if}
</div>

<style>
    .lesson-content-panel {
        position: relative;
        min-height: 0;
        overflow-y: auto;
        overflow-anchor: none;
        padding: var(--space-6);
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

    .lesson-content :global(h1) {
        font-size: var(--text-2xl);
    }

    .lesson-content :global(h2) {
        font-size: var(--text-xl);
    }

    .lesson-content :global(h3) {
        font-size: var(--text-lg);
    }

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

    @media (max-width: 1024px) {
        .lesson-content-panel {
            height: 100%;
            overflow-y: auto;
        }
    }

    @media (max-width: 768px) {
        .lesson-content-panel {
            padding: var(--space-4);
        }
    }
</style>
