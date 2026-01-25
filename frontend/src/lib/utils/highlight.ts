/**
 * Syntax highlighting utilities using highlight.js.
 *
 * Provides lazy-loaded, debounced syntax highlighting for code blocks.
 * Languages are loaded on-demand to minimize initial bundle size.
 */

/** Selector for unhighlighted code blocks within a container. */
const UNHIGHLIGHTED_CODE_SELECTOR = 'pre code:not(.hljs)'

/** Track whether languages have been registered to avoid re-registration. */
let languagesRegistered = false

/** Cached highlight.js instance after first load. */
let hljsInstance: typeof import('highlight.js/lib/core').default | null = null

/**
 * Dynamically imports highlight.js core and registers all supported languages.
 * Uses singleton pattern to avoid redundant imports and registrations.
 *
 * @returns Promise resolving to the highlight.js instance
 */
async function loadHighlightJs(): Promise<typeof import('highlight.js/lib/core').default> {
  if (hljsInstance && languagesRegistered) {
    return hljsInstance
  }

  const [hljs, java, xml, json, bash] = await Promise.all([
    import('highlight.js/lib/core'),
    import('highlight.js/lib/languages/java'),
    import('highlight.js/lib/languages/xml'),
    import('highlight.js/lib/languages/json'),
    import('highlight.js/lib/languages/bash')
  ])

  hljsInstance = hljs.default

  // Register languages only once
  if (!languagesRegistered) {
    if (!hljsInstance.getLanguage('java')) hljsInstance.registerLanguage('java', java.default)
    if (!hljsInstance.getLanguage('xml')) hljsInstance.registerLanguage('xml', xml.default)
    if (!hljsInstance.getLanguage('json')) hljsInstance.registerLanguage('json', json.default)
    if (!hljsInstance.getLanguage('bash')) hljsInstance.registerLanguage('bash', bash.default)
    languagesRegistered = true
  }

  return hljsInstance
}

/**
 * Highlights all unhighlighted code blocks within a container element.
 *
 * @param container - DOM element containing code blocks to highlight
 * @returns Promise that resolves when highlighting is complete
 */
export async function highlightCodeBlocks(container: HTMLElement): Promise<void> {
  const codeBlocks = container.querySelectorAll(UNHIGHLIGHTED_CODE_SELECTOR)
  if (codeBlocks.length === 0) {
    return
  }

  const hljs = await loadHighlightJs()
  codeBlocks.forEach((block) => {
    hljs.highlightElement(block as HTMLElement)
  })
}

/**
 * Configuration for debounced highlighting behavior.
 */
export interface HighlightConfig {
  /** Delay in ms during active streaming (longer to batch updates). */
  streamingDelay: number
  /** Delay in ms after streaming completes (shorter for quick finalization). */
  settledDelay: number
}

/** Default highlighting delays matching MessageBubble behavior. */
export const DEFAULT_HIGHLIGHT_CONFIG: HighlightConfig = {
  streamingDelay: 300,
  settledDelay: 50
} as const

/**
 * Creates a debounced highlighting function with automatic cleanup.
 *
 * @param config - Optional delay configuration
 * @returns Object with highlight function and cleanup function
 */
export function createDebouncedHighlighter(config: HighlightConfig = DEFAULT_HIGHLIGHT_CONFIG) {
  let timer: ReturnType<typeof setTimeout> | null = null

  /**
   * Schedules highlighting for a container element.
   *
   * @param container - Element containing code blocks
   * @param isStreaming - Whether content is actively streaming
   */
  function scheduleHighlight(container: HTMLElement | null, isStreaming: boolean): void {
    if (!container) return

    // Clear pending highlight
    if (timer) {
      clearTimeout(timer)
    }

    const delay = isStreaming ? config.streamingDelay : config.settledDelay

    timer = setTimeout(() => {
      highlightCodeBlocks(container).catch((highlightError: unknown) => {
        // Log with context for debugging - highlighting failures are non-fatal
        // (content remains readable, just without syntax coloring)
        console.warn('[highlight] Code highlighting failed:', {
          error: highlightError instanceof Error ? highlightError.message : String(highlightError),
          codeBlockCount: container.querySelectorAll(UNHIGHLIGHTED_CODE_SELECTOR).length
        })
      })
    }, delay)
  }

  /**
   * Cancels any pending highlight operation.
   * Call this on component unmount or when content changes.
   */
  function cleanup(): void {
    if (timer) {
      clearTimeout(timer)
      timer = null
    }
  }

  return { scheduleHighlight, cleanup }
}
