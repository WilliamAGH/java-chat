<script lang="ts">
  import CitationPanel from './CitationPanel.svelte'
  import type { Citation } from '../services/chat'

  interface Props {
    citations: Citation[]
    loaded: boolean
    error: string | null
    slug: string
  }

  let { citations, loaded, error, slug }: Props = $props()
</script>

{#if loaded && error}
  <div class="lesson-citations lesson-citations--error">
    <span class="lesson-citations-error">Unable to load lesson sources</span>
  </div>
{:else if loaded && citations.length > 0}
  <div class="lesson-citations">
    <CitationPanel citations={citations} panelId={`lesson-citations-${slug}`} />
  </div>
{/if}

<style>
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
</style>

