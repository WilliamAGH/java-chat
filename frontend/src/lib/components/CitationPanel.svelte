<script lang="ts">
  import { fetchCitations, type Citation } from '../services/chat'

  /** Citation type for styling and icon selection. */
  type CitationType = 'pdf' | 'api-doc' | 'repo' | 'external' | 'local' | 'unknown'

  /** URL protocol and path constants. */
  const URL_SCHEME_HTTP = 'http://'
  const URL_SCHEME_HTTPS = 'https://'
  const LOCAL_PATH_PREFIX = '/'
  const ANCHOR_SEPARATOR = '#'
  const PDF_EXTENSION = '.pdf'
  const FALLBACK_LINK_TARGET = '#'
  const FALLBACK_SOURCE_LABEL = 'Source'

  /** Domain patterns that indicate API documentation sources. */
  const API_DOC_PATTERNS = ['docs.oracle.com', 'javadoc', '/api/', '/docs/api/'] as const

  /** Domain patterns that indicate repository sources. */
  const REPO_PATTERNS = ['github.com', 'gitlab.com', 'bitbucket.org'] as const

  /** Checks if URL is HTTP/HTTPS. */
  function isHttpUrl(url: string): boolean {
    return url.startsWith(URL_SCHEME_HTTP) || url.startsWith(URL_SCHEME_HTTPS)
  }

  /** Checks if URL contains any of the given patterns. */
  function matchesAnyPattern(url: string, patterns: readonly string[]): boolean {
    return patterns.some(pattern => url.includes(pattern))
  }

  /** Safe URL schemes for citation links. Rejects javascript:, data:, vbscript:, etc. */
  const SAFE_URL_SCHEMES = ['http:', 'https:'] as const

  /**
   * Validates that a URL uses a safe scheme (http/https) or is a relative path.
   * Returns the URL if safe, or the fallback value for dangerous schemes.
   */
  function sanitizeUrlScheme(url: string): string {
    if (!url) return FALLBACK_LINK_TARGET
    const trimmedUrl = url.trim()

    // Allow relative paths (start with / but not //)
    if (trimmedUrl.startsWith(LOCAL_PATH_PREFIX) && !trimmedUrl.startsWith('//')) {
      return trimmedUrl
    }

    // Check for safe schemes
    try {
      const parsedUrl = new URL(trimmedUrl)
      const scheme = parsedUrl.protocol.toLowerCase()
      if (SAFE_URL_SCHEMES.some(safe => scheme === safe)) {
        return trimmedUrl
      }
    } catch {
      // URL parsing failed - might be a relative path or malformed
      // Only allow if it doesn't look like a dangerous scheme
      const lowerUrl = trimmedUrl.toLowerCase()
      if (lowerUrl.includes(':') && !isHttpUrl(lowerUrl)) {
        return FALLBACK_LINK_TARGET
      }
      return trimmedUrl
    }

    // Dangerous scheme detected
    return FALLBACK_LINK_TARGET
  }

  /**
   * Determines citation type from URL for icon and styling.
   */
  function getCitationType(url: string): CitationType {
    if (!url) return 'unknown'
    const lowerUrl = url.toLowerCase()

    if (lowerUrl.endsWith(PDF_EXTENSION)) return 'pdf'
    if (isHttpUrl(lowerUrl)) {
      if (matchesAnyPattern(lowerUrl, API_DOC_PATTERNS)) return 'api-doc'
      if (matchesAnyPattern(lowerUrl, REPO_PATTERNS)) return 'repo'
      return 'external'
    }
    if (lowerUrl.startsWith(LOCAL_PATH_PREFIX)) return 'local'
    return 'unknown'
  }

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
  let isExpanded = $state(false)
  let pendingRequestId = $state(0)

  /**
   * Extracts display-friendly domain or filename from URL.
   */
  function getDisplaySource(url: string): string {
    if (!url) return FALLBACK_SOURCE_LABEL

    const lowerUrl = url.toLowerCase()

    // PDF filenames
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

    // HTTP URLs
    if (isHttpUrl(url)) {
      try {
        const urlObj = new URL(url)
        return urlObj.hostname.replace(/^www\./, '')
      } catch {
        return FALLBACK_SOURCE_LABEL
      }
    }

    // Local paths
    if (url.startsWith(LOCAL_PATH_PREFIX)) {
      const segments = url.split(LOCAL_PATH_PREFIX)
      return segments[segments.length - 1] || 'Local Resource'
    }

    return FALLBACK_SOURCE_LABEL
  }

  /**
   * Builds the full URL including anchor fragment if present.
   * Sanitizes the URL scheme to prevent XSS via javascript:, data:, etc.
   */
  function buildFullUrl(citation: Citation): string {
    if (!citation.url) return FALLBACK_LINK_TARGET

    const safeUrl = sanitizeUrlScheme(citation.url)
    if (safeUrl === FALLBACK_LINK_TARGET) return FALLBACK_LINK_TARGET

    if (citation.anchor && !safeUrl.includes(ANCHOR_SEPARATOR)) {
      return `${safeUrl}${ANCHOR_SEPARATOR}${citation.anchor}`
    }
    return safeUrl
  }

  function toggleExpand() {
    isExpanded = !isExpanded
  }

  // Fetch citations when query changes, with request tracking to prevent race conditions
  $effect(() => {
    const currentQuery = query
    if (!currentQuery || !currentQuery.trim()) {
      citations = []
      hasFetched = false
      fetchError = null
      isExpanded = false
      return
    }

    // Increment request ID to track this specific request
    const requestId = ++pendingRequestId
    hasFetched = false
    fetchError = null
    isExpanded = false

    fetchCitations(currentQuery)
      .then((result) => {
        // Ignore stale responses from superseded requests
        if (requestId !== pendingRequestId) return

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
        // Ignore errors from superseded requests
        if (requestId !== pendingRequestId) return

        const errorMessage = error instanceof Error ? error.message : 'Failed to fetch citations'
        fetchError = errorMessage
        citations = []
      })
      .finally(() => {
        // Only mark as fetched if this is still the current request
        if (requestId === pendingRequestId) {
          hasFetched = true
        }
      })
  })
</script>

{#if visible && hasFetched && !fetchError && citations.length > 0}
  <div class="citation-disclosure">
    <button
      type="button"
      class="citation-trigger"
      class:citation-trigger--expanded={isExpanded}
      onclick={toggleExpand}
      aria-expanded={isExpanded}
      aria-controls="citation-list"
    >
      <svg class="citation-trigger-icon" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
        <path d="M2 4a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V4Zm3.5 1a.5.5 0 0 0-.5.5v.5a.5.5 0 0 0 .5.5h.5a.5.5 0 0 0 .5-.5v-.5a.5.5 0 0 0-.5-.5h-.5Zm3 0a.5.5 0 0 0 0 1h2a.5.5 0 0 0 0-1h-2Zm-3 3a.5.5 0 0 0-.5.5v.5a.5.5 0 0 0 .5.5h.5a.5.5 0 0 0 .5-.5v-.5a.5.5 0 0 0-.5-.5h-.5Zm3 0a.5.5 0 0 0 0 1h2a.5.5 0 0 0 0-1h-2Zm-3 3a.5.5 0 0 0-.5.5v.5a.5.5 0 0 0 .5.5h.5a.5.5 0 0 0 .5-.5v-.5a.5.5 0 0 0-.5-.5h-.5Zm3 0a.5.5 0 0 0 0 1h2a.5.5 0 0 0 0-1h-2Z"/>
      </svg>
      <span class="citation-trigger-text">{citations.length} source{citations.length !== 1 ? 's' : ''}</span>
      <svg class="citation-trigger-chevron" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
        <path fill-rule="evenodd" d="M4.22 6.22a.75.75 0 0 1 1.06 0L8 8.94l2.72-2.72a.75.75 0 1 1 1.06 1.06l-3.25 3.25a.75.75 0 0 1-1.06 0L4.22 7.28a.75.75 0 0 1 0-1.06Z" clip-rule="evenodd"/>
      </svg>
    </button>

    {#if isExpanded}
      <ul id="citation-list" class="citation-list" role="list">
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
              <span class="citation-type-badge">
                {#if citationType === 'pdf'}
                  <svg viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
                    <path fill-rule="evenodd" d="M4 2a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2V6.414a1 1 0 0 0-.293-.707L10.293 2.293A1 1 0 0 0 9.586 2H4Zm1 7a.5.5 0 0 1 .5-.5h5a.5.5 0 0 1 0 1h-5A.5.5 0 0 1 5 9Zm.5 1.5a.5.5 0 0 0 0 1h3a.5.5 0 0 0 0-1h-3Z" clip-rule="evenodd"/>
                  </svg>
                {:else if citationType === 'api-doc'}
                  <svg viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
                    <path d="M1 4.5A1.5 1.5 0 0 1 2.5 3h11A1.5 1.5 0 0 1 15 4.5v.75a.75.75 0 0 1-1.5 0V4.5H2.5v7h5.25a.75.75 0 0 1 0 1.5H2.5A1.5 1.5 0 0 1 1 11.5v-7Z"/>
                    <path d="M13.28 7.22a.75.75 0 0 1 0 1.06l-2.5 2.5a.75.75 0 1 1-1.06-1.06L11.44 8l-1.72-1.72a.75.75 0 0 1 1.06-1.06l2.5 2.5Z"/>
                  </svg>
                {:else if citationType === 'repo'}
                  <svg viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
                    <path d="M5.22 2.22a.75.75 0 0 1 1.06 0l2.5 2.5a.75.75 0 0 1 0 1.06l-2.5 2.5a.75.75 0 0 1-1.06-1.06L7.19 5.25 5.22 3.28a.75.75 0 0 1 0-1.06Zm5.56 0a.75.75 0 0 1 0 1.06L8.81 5.25l1.97 1.97a.75.75 0 0 1-1.06 1.06l-2.5-2.5a.75.75 0 0 1 0-1.06l2.5-2.5a.75.75 0 0 1 1.06 0ZM3 10.75A.75.75 0 0 1 3.75 10h8.5a.75.75 0 0 1 0 1.5h-8.5a.75.75 0 0 1-.75-.75Zm.75 2.25a.75.75 0 0 0 0 1.5h5.5a.75.75 0 0 0 0-1.5h-5.5Z"/>
                  </svg>
                {:else}
                  <svg viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
                    <path d="M8.914 2.586a2 2 0 0 1 2.828 0l1.672 1.672a2 2 0 0 1 0 2.828l-5.5 5.5a2 2 0 0 1-1.414.586H3.75a.75.75 0 0 1-.75-.75v-2.75a2 2 0 0 1 .586-1.414l5.328-5.672Zm2 .828a.5.5 0 0 0-.707 0L4.878 8.743a.5.5 0 0 0-.128.215l-.5 1.5 1.5-.5a.5.5 0 0 0 .215-.128l5.329-5.672a.5.5 0 0 0 0-.707l-.38-.037Z"/>
                  </svg>
                {/if}
              </span>
              <span class="citation-text">
                <span class="citation-title">{citation.title && citation.title.trim() ? citation.title : displaySource}</span>
                <span class="citation-domain">{displaySource}</span>
              </span>
              <svg class="citation-arrow" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true">
                <path fill-rule="evenodd" d="M8.22 2.97a.75.75 0 0 1 1.06 0l4.25 4.25a.75.75 0 0 1 0 1.06l-4.25 4.25a.75.75 0 0 1-1.06-1.06l2.97-2.97H3a.75.75 0 0 1 0-1.5h8.19L8.22 4.03a.75.75 0 0 1 0-1.06Z" clip-rule="evenodd"/>
              </svg>
            </a>
          </li>
        {/each}
      </ul>
    {/if}
  </div>
{/if}

<style>
  /* Component-scoped design tokens */
  .citation-disclosure {
    --citation-trigger-gap: var(--space-1, 6px);
    --citation-trigger-padding-y: var(--space-1, 6px);
    --citation-trigger-padding-x: var(--space-2, 10px);
    --citation-trigger-radius: var(--radius-full, 999px);
    --citation-trigger-font-size: var(--text-xs, 12px);
    --citation-transition-fast: var(--duration-fast, 100ms);
    --citation-transition-normal: var(--duration-normal, 150ms);
    --citation-list-gap: var(--space-0, 2px);
    --citation-link-padding-y: var(--space-2, 8px);
    --citation-link-padding-x: var(--space-2, 10px);
    --citation-badge-size: 20px;
    --citation-badge-radius: var(--radius-sm, 4px);
    --citation-icon-size-sm: 12px;
    --citation-icon-size-md: 14px;
    margin-top: var(--space-3);
  }

  /* Trigger button - minimal pill design */
  .citation-trigger {
    display: inline-flex;
    align-items: center;
    gap: var(--citation-trigger-gap);
    padding: var(--citation-trigger-padding-y) var(--citation-trigger-padding-x);
    background: transparent;
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--citation-trigger-radius);
    font-family: var(--font-sans);
    font-size: var(--citation-trigger-font-size);
    font-weight: 500;
    color: var(--color-text-secondary);
    cursor: pointer;
    transition: all var(--citation-transition-fast) ease-out;
  }

  .citation-trigger:hover {
    background: var(--color-bg-secondary);
    border-color: var(--color-border-default);
    color: var(--color-text-primary);
  }

  .citation-trigger--expanded {
    background: var(--color-bg-secondary);
    border-color: var(--color-accent-muted);
    color: var(--color-accent);
  }

  .citation-trigger-icon {
    width: var(--citation-icon-size-md);
    height: var(--citation-icon-size-md);
    opacity: 0.7;
  }

  .citation-trigger-text {
    letter-spacing: -0.01em;
  }

  .citation-trigger-chevron {
    width: var(--citation-icon-size-md);
    height: var(--citation-icon-size-md);
    opacity: 0.5;
    transition: transform var(--citation-transition-normal) ease-out;
  }

  .citation-trigger--expanded .citation-trigger-chevron {
    transform: rotate(180deg);
    opacity: 0.8;
  }

  /* Expanded list */
  .citation-list {
    list-style: none;
    margin: var(--space-2) 0 0;
    padding: 0;
    display: flex;
    flex-direction: column;
    gap: var(--citation-list-gap);
    animation: slide-down var(--citation-transition-normal) ease-out;
  }

  @keyframes slide-down {
    from {
      opacity: 0;
      transform: translateY(-4px);
    }
    to {
      opacity: 1;
      transform: translateY(0);
    }
  }

  .citation-item {
    display: block;
  }

  .citation-link {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--citation-link-padding-y) var(--citation-link-padding-x);
    background: var(--color-bg-primary);
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-md);
    text-decoration: none;
    transition: all var(--citation-transition-fast) ease-out;
  }

  .citation-link:hover {
    background: var(--color-bg-secondary);
    border-color: var(--color-accent-muted);
  }

  .citation-link:hover .citation-arrow {
    opacity: 1;
    transform: translateX(2px);
  }

  /* Type badge - small colored dot/icon */
  .citation-type-badge {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    justify-content: center;
    width: var(--citation-badge-size);
    height: var(--citation-badge-size);
    background: var(--color-accent-subtle);
    border-radius: var(--citation-badge-radius);
    color: var(--color-accent);
  }

  .citation-type-badge svg {
    width: var(--citation-icon-size-sm);
    height: var(--citation-icon-size-sm);
  }

  /* Type-specific colors */
  .citation-item[data-type="pdf"] .citation-type-badge {
    background: rgba(220, 38, 38, 0.08);
    color: rgb(185, 28, 28);
  }

  .citation-item[data-type="api-doc"] .citation-type-badge {
    background: rgba(37, 99, 235, 0.08);
    color: rgb(29, 78, 216);
  }

  .citation-item[data-type="repo"] .citation-type-badge {
    background: rgba(22, 163, 74, 0.08);
    color: rgb(21, 128, 61);
  }

  .citation-text {
    flex: 1;
    min-width: 0;
    display: flex;
    flex-direction: column;
    gap: 1px;
  }

  .citation-title {
    font-size: var(--text-sm, 13px);
    font-weight: 500;
    color: var(--color-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    line-height: var(--leading-tight, 1.3);
  }

  .citation-domain {
    font-size: var(--text-xs, 11px);
    color: var(--color-text-tertiary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .citation-arrow {
    flex-shrink: 0;
    width: var(--citation-icon-size-md);
    height: var(--citation-icon-size-md);
    color: var(--color-text-muted);
    opacity: 0;
    transition: all var(--citation-transition-fast) ease-out;
  }

  /* Mobile */
  @media (max-width: 640px) {
    .citation-trigger {
      padding: var(--space-2) var(--space-3);
      font-size: var(--text-sm, 13px);
    }

    .citation-link {
      padding: var(--space-2) var(--space-3);
    }

    .citation-arrow {
      opacity: 0.5;
    }
  }

  /* Dark mode refinements */
  @media (prefers-color-scheme: dark) {
    .citation-item[data-type="pdf"] .citation-type-badge {
      background: rgba(248, 113, 113, 0.12);
      color: rgb(248, 113, 113);
    }

    .citation-item[data-type="api-doc"] .citation-type-badge {
      background: rgba(96, 165, 250, 0.12);
      color: rgb(96, 165, 250);
    }

    .citation-item[data-type="repo"] .citation-type-badge {
      background: rgba(74, 222, 128, 0.12);
      color: rgb(74, 222, 128);
    }
  }
</style>
