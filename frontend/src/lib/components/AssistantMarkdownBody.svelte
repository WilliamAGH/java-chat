<script lang="ts">
  import { parseMarkdown } from '../services/markdown'
  import { applyJavaLanguageDetection } from '../services/javaLanguageDetection'
  import { createDebouncedHighlighter } from '../utils/highlight'

  let { markdown, isStreaming }: { markdown: string; isStreaming: boolean } = $props()

  let markdownElement: HTMLElement | null = $state(null)

  const { scheduleHighlight, cleanup: cleanupHighlighter } = createDebouncedHighlighter()

  let renderedMarkdownHtml = $derived(parseMarkdown(markdown, isStreaming))

  $effect(() => {
    if (renderedMarkdownHtml && markdownElement) {
      if (isStreaming) {
        return cleanupHighlighter
      }

      applyJavaLanguageDetection(markdownElement)
      scheduleHighlight(markdownElement, isStreaming)
    }

    return cleanupHighlighter
  })
</script>

<div class="assistant-content" bind:this={markdownElement}>
  {#if renderedMarkdownHtml}
    {@html renderedMarkdownHtml}
  {:else}
    <p>{markdown}</p>
  {/if}
  <span class={['cursor', isStreaming && 'visible']}></span>
</div>

<style>
  .assistant-content {
    overflow-wrap: break-word;
    word-break: break-word;
    font-size: var(--text-base);
    line-height: var(--leading-relaxed);
    color: var(--color-text-primary);
  }

  .assistant-content :global(p) {
    margin: 0 0 var(--space-4);
  }

  .assistant-content :global(p:last-child) {
    margin-bottom: 0;
  }

  .assistant-content :global(h1),
  .assistant-content :global(h2),
  .assistant-content :global(h3),
  .assistant-content :global(h4) {
    font-family: var(--font-serif);
    font-weight: 500;
    margin: var(--space-6) 0 var(--space-3);
    letter-spacing: var(--tracking-tight);
  }

  .assistant-content :global(h1:first-child),
  .assistant-content :global(h2:first-child),
  .assistant-content :global(h3:first-child) {
    margin-top: 0;
  }

  .assistant-content :global(h2) {
    font-size: var(--text-xl);
  }

  .assistant-content :global(h1) {
    font-size: var(--text-2xl);
  }

  .assistant-content :global(h3) {
    font-size: var(--text-lg);
  }

  .assistant-content :global(h4) {
    font-size: var(--text-base);
  }

  .assistant-content :global(ul),
  .assistant-content :global(ol) {
    margin: 0 0 var(--space-4);
    padding-left: var(--space-6);
  }

  .assistant-content :global(li) {
    margin-bottom: var(--space-2);
  }

  .assistant-content :global(li:last-child) {
    margin-bottom: 0;
  }

  .assistant-content :global(strong) {
    font-weight: 600;
    color: var(--color-text-primary);
  }

  .assistant-content :global(a) {
    color: var(--color-accent);
    text-decoration: underline;
    text-underline-offset: 2px;
  }

  .assistant-content :global(a:hover) {
    text-decoration-thickness: 2px;
  }

  .assistant-content :global(blockquote) {
    margin: var(--space-4) 0;
    padding: var(--space-3) var(--space-4);
    border-left: 3px solid var(--color-accent);
    background: var(--color-surface-subtle);
    border-radius: 0 var(--radius-md) var(--radius-md) 0;
    font-style: italic;
    color: var(--color-text-secondary);
  }

  .assistant-content :global(pre) {
    margin: var(--space-4) 0;
    padding: var(--space-4);
    background: var(--color-bg-primary);
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-lg);
    overflow-x: auto;
    font-size: var(--text-sm);
  }

  .assistant-content :global(pre code) {
    background: none;
    padding: 0;
    border: none;
    font-size: inherit;
    color: var(--color-text-primary);
  }

  .cursor {
    display: inline-block;
    width: 2px;
    height: 1.2em;
    background: var(--color-accent);
    margin-left: 2px;
    vertical-align: text-bottom;
    opacity: 0;
    transition: opacity 150ms ease-out;
  }

  .cursor.visible {
    opacity: 1;
    animation: typing-cursor 0.8s ease-in-out infinite;
  }

  @keyframes typing-cursor {
    0%, 100% { opacity: 1; }
    50% { opacity: 0; }
  }

  @media (max-width: 640px) {
    .assistant-content {
      font-size: var(--text-sm);
    }

    .assistant-content :global(pre) {
      padding: var(--space-3);
      font-size: var(--text-xs);
      margin: var(--space-3) calc(-1 * var(--space-3));
      border-radius: 0;
      border-left: none;
      border-right: none;
    }

    .assistant-content :global(code:not(pre code)) {
      font-size: 0.8em;
    }
  }
</style>
