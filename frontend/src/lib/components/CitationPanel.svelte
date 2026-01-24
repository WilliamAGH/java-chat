<script lang="ts">
  import { fetchCitations, type Citation } from '../services/chat'

  /** Citation type for styling and icon selection. */
  type CitationType = 'pdf' | 'api-doc' | 'repo' | 'external' | 'local' | 'unknown'

  /** URL scheme prefixes for protocol detection. */
  const URL_SCHEME_HTTP = 'http://'
  const URL_SCHEME_HTTPS = 'https://'
  const LOCAL_PATH_PREFIX = '/'
  const ANCHOR_SEPARATOR = '#'
  const PDF_EXTENSION = '.pdf'
  const FALLBACK_LINK_TARGET = '#'

  /** Domain patterns that indicate API documentation sources. */
  const API_DOC_PATTERNS = ['docs.oracle.com', 'javadoc', '/api/', '/docs/api/'] as const

  /** Domain patterns that indicate repository sources. */
  const REPO_PATTERNS = ['github.com', 'gitlab.com', 'bitbucket.org'] as const

  interface Props {
    /** The user query to fetch citations for. */
    query: string
    /** Whether to show the panel (controls visibility without unmounting). */
    visible?: boolean
  }

  let { query, visible = true }: Props = $props()

  let citations = $state<Citation[]>([])
  let hasFetched = $state(false)
  let fetchError = $state<string | null>(null)

  /**
   * Checks if URL matches any pattern in a list.
   */
  function matchesPatterns(url: string, patterns: readonly string[]): boolean {
    return patterns.some(pattern => url.includes(pattern))
  }

  /**
   * Checks if URL is an HTTP/HTTPS URL.
   */
  function isHttpUrl(url: string): boolean {
    return url.startsWith(URL_SCHEME_HTTP) || url.startsWith(URL_SCHEME_HTTPS)
  }

  /**
   * Determines citation type from URL for icon and styling.
   * Uses pattern matching against known documentation and repository domains.
   */
  function getCitationType(url: string): CitationType {
    if (!url) return 'unknown'

    const lowerUrl = url.toLowerCase()

    if (lowerUrl.endsWith(PDF_EXTENSION)) {
      return 'pdf'
    }

    if (isHttpUrl(lowerUrl)) {
      if (matchesPatterns(lowerUrl, API_DOC_PATTERNS)) return 'api-doc'
      if (matchesPatterns(lowerUrl, REPO_PATTERNS)) return 'repo'
      return 'external'
    }

    if (lowerUrl.startsWith(LOCAL_PATH_PREFIX)) {
      return 'local'
    }

    return 'unknown'
  }

  /**
   * Extracts display-friendly domain or filename from URL.
   * Returns a sensible fallback on parse failure.
   */
  function getDisplaySource(url: string): string {
    if (!url) return 'Source'

    const lowerUrl = url.toLowerCase()

    // Handle PDF filenames - no URL parsing needed
    if (lowerUrl.endsWith(PDF_EXTENSION)) {
      const segments = url.split(LOCAL_PATH_PREFIX)
      const filename = segments[segments.length - 1]
      const cleanName = filename
        .replace(/\.pdf$/i, '')
        .replace(/[-_]/g, ' ')
        .replace(/\s+/g, ' ')
        .trim()
      return cleanName || 'PDF Document'
    }

    // Handle HTTP URLs
    if (isHttpUrl(url)) {
      try {
        const urlObj = new URL(url)
        return urlObj.hostname.replace(/^www\./, '')
      } catch {
        return 'Source'
      }
    }

    // Handle local paths - no URL parsing needed
    if (url.startsWith(LOCAL_PATH_PREFIX)) {
      const segments = url.split(LOCAL_PATH_PREFIX)
      return segments[segments.length - 1] || 'Local Resource'
    }

    return 'Source'
  }

  /**
   * Builds the full URL including anchor fragment if present.
   */
  function buildFullUrl(citation: Citation): string {
    if (!citation.url) return FALLBACK_LINK_TARGET
    if (citation.anchor && !citation.url.includes(ANCHOR_SEPARATOR)) {
      return `${citation.url}${ANCHOR_SEPARATOR}${citation.anchor}`
    }
    return citation.url
  }

  // Fetch citations when query changes
  $effect(() => {
    const currentQuery = query
    if (!currentQuery || !currentQuery.trim()) {
      citations = []
      hasFetched = false
      fetchError = null
      return
    }

    hasFetched = false
    fetchError = null

    fetchCitations(currentQuery)
      .then((result) => {
        // Deduplicate by URL
        const seen = new Set<string>()
        citations = result.filter((citation) => {
          const key = citation.url?.toLowerCase() ?? ''
          if (seen.has(key)) return false
          seen.add(key)
          return true
        })
      })
      .catch((error: unknown) => {
        const errorMessage = error instanceof Error ? error.message : 'Failed to fetch citations'
        fetchError = errorMessage
        citations = []
      })
      .finally(() => {
        hasFetched = true
      })
  })
</script>

{#if visible && hasFetched && fetchError}
  <aside class="citation-panel citation-panel--error" aria-label="Citation error">
    <div class="citation-error">
      <span class="citation-error-text">Unable to load sources</span>
    </div>
  </aside>
{:else if visible && hasFetched && citations.length > 0}
  <aside class="citation-panel" aria-label="Sources">
    <div class="citation-header">
      <svg class="citation-icon" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
        <path fill-rule="evenodd" d="M4.5 2A1.5 1.5 0 0 0 3 3.5v13A1.5 1.5 0 0 0 4.5 18h11a1.5 1.5 0 0 0 1.5-1.5V7.621a1.5 1.5 0 0 0-.44-1.06l-4.12-4.122A1.5 1.5 0 0 0 11.378 2H4.5Zm2.25 8.5a.75.75 0 0 0 0 1.5h6.5a.75.75 0 0 0 0-1.5h-6.5Zm0 3a.75.75 0 0 0 0 1.5h6.5a.75.75 0 0 0 0-1.5h-6.5Z" clip-rule="evenodd"/>
      </svg>
      <span class="citation-title">Sources ({citations.length})</span>
    </div>

    <ul class="citation-list">
      {#each citations as citation (citation.url)}
        {@const citationType = getCitationType(citation.url)}
        {@const displaySource = getDisplaySource(citation.url)}
        <li class="citation-item" data-type={citationType}>
          <a
            href={buildFullUrl(citation)}
            target="_blank"
            rel="noopener noreferrer"
            class="citation-link"
          >
            <span class="citation-type-icon">
              {#if citationType === 'pdf'}
                <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
                  <path fill-rule="evenodd" d="M4.5 2A1.5 1.5 0 0 0 3 3.5v13A1.5 1.5 0 0 0 4.5 18h11a1.5 1.5 0 0 0 1.5-1.5V7.621a1.5 1.5 0 0 0-.44-1.06l-4.12-4.122A1.5 1.5 0 0 0 11.378 2H4.5ZM10 8a.75.75 0 0 1 .75.75v1.5h1.5a.75.75 0 0 1 0 1.5h-1.5v1.5a.75.75 0 0 1-1.5 0v-1.5h-1.5a.75.75 0 0 1 0-1.5h1.5v-1.5A.75.75 0 0 1 10 8Z" clip-rule="evenodd"/>
                </svg>
              {:else if citationType === 'api-doc'}
                <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
                  <path d="M10.75 2.75a.75.75 0 0 0-1.5 0v8.614L6.295 8.235a.75.75 0 1 0-1.09 1.03l4.25 4.5a.75.75 0 0 0 1.09 0l4.25-4.5a.75.75 0 0 0-1.09-1.03l-2.955 3.129V2.75Z"/>
                  <path d="M3.5 12.75a.75.75 0 0 0-1.5 0v2.5A2.75 2.75 0 0 0 4.75 18h10.5A2.75 2.75 0 0 0 18 15.25v-2.5a.75.75 0 0 0-1.5 0v2.5c0 .69-.56 1.25-1.25 1.25H4.75c-.69 0-1.25-.56-1.25-1.25v-2.5Z"/>
                </svg>
              {:else if citationType === 'repo'}
                <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
                  <path fill-rule="evenodd" d="M6.28 5.22a.75.75 0 0 1 0 1.06L2.56 10l3.72 3.72a.75.75 0 0 1-1.06 1.06L.97 10.53a.75.75 0 0 1 0-1.06l4.25-4.25a.75.75 0 0 1 1.06 0Zm7.44 0a.75.75 0 0 1 1.06 0l4.25 4.25a.75.75 0 0 1 0 1.06l-4.25 4.25a.75.75 0 0 1-1.06-1.06L17.44 10l-3.72-3.72a.75.75 0 0 1 0-1.06ZM11.377 2.011a.75.75 0 0 1 .612.867l-2.5 14.5a.75.75 0 0 1-1.478-.255l2.5-14.5a.75.75 0 0 1 .866-.612Z" clip-rule="evenodd"/>
                </svg>
              {:else}
                <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
                  <path d="M12.232 4.232a2.5 2.5 0 0 1 3.536 3.536l-1.225 1.224a.75.75 0 0 0 1.061 1.06l1.224-1.224a4 4 0 0 0-5.656-5.656l-3 3a4 4 0 0 0 .225 5.865.75.75 0 0 0 .977-1.138 2.5 2.5 0 0 1-.142-3.667l3-3Z"/>
                  <path d="M11.603 7.963a.75.75 0 0 0-.977 1.138 2.5 2.5 0 0 1 .142 3.667l-3 3a2.5 2.5 0 0 1-3.536-3.536l1.225-1.224a.75.75 0 0 0-1.061-1.06l-1.224 1.224a4 4 0 1 0 5.656 5.656l3-3a4 4 0 0 0-.225-5.865Z"/>
                </svg>
              {/if}
            </span>
            <span class="citation-content">
              <span class="citation-name">{citation.title || displaySource}</span>
              <span class="citation-source">{displaySource}</span>
            </span>
            <svg class="citation-external" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path fill-rule="evenodd" d="M4.25 5.5a.75.75 0 0 0-.75.75v8.5c0 .414.336.75.75.75h8.5a.75.75 0 0 0 .75-.75v-4a.75.75 0 0 1 1.5 0v4A2.25 2.25 0 0 1 12.75 17h-8.5A2.25 2.25 0 0 1 2 14.75v-8.5A2.25 2.25 0 0 1 4.25 4h5a.75.75 0 0 1 0 1.5h-5Z" clip-rule="evenodd"/>
              <path fill-rule="evenodd" d="M6.194 12.753a.75.75 0 0 0 1.06.053L16.5 4.44v2.81a.75.75 0 0 0 1.5 0v-4.5a.75.75 0 0 0-.75-.75h-4.5a.75.75 0 0 0 0 1.5h2.553l-9.056 8.194a.75.75 0 0 0-.053 1.06Z" clip-rule="evenodd"/>
            </svg>
          </a>
          {#if citation.snippet}
            <p class="citation-snippet">{citation.snippet}</p>
          {/if}
        </li>
      {/each}
    </ul>
  </aside>
{/if}

<style>
  .citation-panel {
    margin-top: var(--space-4);
    padding: var(--space-3);
    background: var(--color-surface-subtle);
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-lg);
    animation: fade-in var(--duration-normal) var(--ease-out);
  }

  .citation-panel--error {
    border-color: var(--color-border-subtle);
    background: var(--color-surface-subtle);
  }

  .citation-error {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    color: var(--color-text-tertiary);
    font-size: var(--text-xs);
  }

  .citation-error-text {
    font-style: italic;
  }

  @keyframes fade-in {
    from { opacity: 0; transform: translateY(4px); }
    to { opacity: 1; transform: translateY(0); }
  }

  .citation-header {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    margin-bottom: var(--space-3);
    padding-bottom: var(--space-2);
    border-bottom: 1px solid var(--color-border-subtle);
  }

  .citation-icon {
    width: 16px;
    height: 16px;
    color: var(--color-accent);
  }

  .citation-title {
    font-size: var(--text-xs);
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: var(--tracking-wider);
    color: var(--color-text-secondary);
  }

  .citation-list {
    list-style: none;
    margin: 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: var(--space-2);
  }

  .citation-item {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
  }

  .citation-link {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2);
    background: var(--color-bg-primary);
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-md);
    text-decoration: none;
    transition: all var(--duration-fast) var(--ease-out);
  }

  .citation-link:hover {
    background: var(--color-bg-secondary);
    border-color: var(--color-accent-muted);
  }

  .citation-link:hover .citation-external {
    opacity: 1;
  }

  .citation-type-icon {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 28px;
    height: 28px;
    background: var(--color-accent-subtle);
    border-radius: var(--radius-sm);
    color: var(--color-accent);
  }

  .citation-type-icon svg {
    width: 16px;
    height: 16px;
  }

  /* Type-specific icon colors */
  .citation-item[data-type="pdf"] .citation-type-icon {
    background: rgba(220, 38, 38, 0.1);
    color: rgb(220, 38, 38);
  }

  .citation-item[data-type="api-doc"] .citation-type-icon {
    background: rgba(37, 99, 235, 0.1);
    color: rgb(37, 99, 235);
  }

  .citation-item[data-type="repo"] .citation-type-icon {
    background: rgba(22, 163, 74, 0.1);
    color: rgb(22, 163, 74);
  }

  .citation-content {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 2px;
  }

  .citation-name {
    font-size: var(--text-sm);
    font-weight: 500;
    color: var(--color-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .citation-source {
    font-size: var(--text-xs);
    color: var(--color-text-tertiary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .citation-external {
    flex-shrink: 0;
    width: 14px;
    height: 14px;
    color: var(--color-text-muted);
    opacity: 0;
    transition: opacity var(--duration-fast) var(--ease-out);
  }

  .citation-snippet {
    margin: 0;
    padding: 0 var(--space-2);
    font-size: var(--text-xs);
    color: var(--color-text-tertiary);
    line-height: var(--leading-relaxed);
    overflow: hidden;
    text-overflow: ellipsis;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
  }

  /* Mobile */
  @media (max-width: 640px) {
    .citation-panel {
      padding: var(--space-2);
    }

    .citation-link {
      padding: var(--space-2);
    }

    .citation-type-icon {
      width: 24px;
      height: 24px;
    }

    .citation-type-icon svg {
      width: 14px;
      height: 14px;
    }

    .citation-external {
      opacity: 1;
    }

    .citation-snippet {
      display: none;
    }
  }
</style>
