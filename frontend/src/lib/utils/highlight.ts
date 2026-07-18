import type { HLJSApi, LanguageFn } from "highlight.js";

/**
 * Syntax highlighting utilities using highlight.js.
 *
 * Provides lazy-loaded, debounced syntax highlighting for code blocks.
 * Languages are loaded on-demand to minimize initial bundle size.
 */

/** Selector for unhighlighted code blocks within a container. */
const UNHIGHLIGHTED_CODE_SELECTOR = "pre code:not(.hljs)";

/** Track whether languages have been registered to avoid re-registration. */
let languagesRegistered = false;

/** Cached highlight.js instance after first load. */
let highlightJsInstance: HLJSApi | null = null;

/**
 * Registers a language definition only when the shared highlighter does not already know it.
 */
function registerLanguageIfMissing(
  highlightJs: HLJSApi,
  languageName: string,
  languageDefinition: LanguageFn,
): void {
  if (!highlightJs.getLanguage(languageName)) {
    highlightJs.registerLanguage(languageName, languageDefinition);
  }
}

/**
 * Dynamically imports highlight.js core and registers all supported languages.
 * Uses singleton pattern to avoid redundant imports and registrations.
 *
 * @returns Promise resolving to the highlight.js instance
 */
async function loadHighlightJs(): Promise<HLJSApi> {
  if (highlightJsInstance && languagesRegistered) {
    return highlightJsInstance;
  }

  const [
    highlightJsModule,
    java,
    xml,
    json,
    bash,
    plaintext,
    properties,
    kotlin,
    scala,
    groovy,
    clojure,
  ] = await Promise.all([
    import("highlight.js/lib/core"),
    import("highlight.js/lib/languages/java"),
    import("highlight.js/lib/languages/xml"),
    import("highlight.js/lib/languages/json"),
    import("highlight.js/lib/languages/bash"),
    import("highlight.js/lib/languages/plaintext"),
    import("highlight.js/lib/languages/properties"),
    import("highlight.js/lib/languages/kotlin"),
    import("highlight.js/lib/languages/scala"),
    import("highlight.js/lib/languages/groovy"),
    import("highlight.js/lib/languages/clojure"),
  ]);

  const highlightJs = highlightJsModule.default;
  highlightJsInstance = highlightJs;

  // Register languages only once
  if (!languagesRegistered) {
    registerLanguageIfMissing(highlightJs, "java", java.default);
    registerLanguageIfMissing(highlightJs, "xml", xml.default);
    registerLanguageIfMissing(highlightJs, "json", json.default);
    registerLanguageIfMissing(highlightJs, "bash", bash.default);
    registerLanguageIfMissing(highlightJs, "plaintext", plaintext.default);
    registerLanguageIfMissing(highlightJs, "properties", properties.default);
    registerLanguageIfMissing(highlightJs, "kotlin", kotlin.default);
    registerLanguageIfMissing(highlightJs, "scala", scala.default);
    registerLanguageIfMissing(highlightJs, "groovy", groovy.default);
    registerLanguageIfMissing(highlightJs, "clojure", clojure.default);
    languagesRegistered = true;
  }

  return highlightJs;
}

/**
 * Highlights all unhighlighted code blocks within a container element.
 *
 * @param container - DOM element containing code blocks to highlight
 * @returns Promise that resolves when highlighting is complete
 */
export async function highlightCodeBlocks(container: HTMLElement): Promise<void> {
  const codeBlocks = container.querySelectorAll<HTMLElement>(UNHIGHLIGHTED_CODE_SELECTOR);
  if (codeBlocks.length === 0) {
    return;
  }

  const highlightJs = await loadHighlightJs();
  codeBlocks.forEach((block) => {
    highlightJs.highlightElement(block);
  });
}

/**
 * Configuration for debounced highlighting behavior.
 */
export interface HighlightConfig {
  /** Delay in ms during active streaming (longer to batch updates). */
  streamingDelay: number;
  /** Delay in ms after streaming completes (shorter for quick finalization). */
  settledDelay: number;
}

/** Default highlighting delays matching MessageBubble behavior. */
export const DEFAULT_HIGHLIGHT_CONFIG: HighlightConfig = {
  streamingDelay: 300,
  settledDelay: 50,
} as const;

/**
 * Creates a debounced highlighting function with automatic cleanup.
 *
 * @param config - Optional delay configuration
 * @returns Object with highlight function and cleanup function
 */
export function createDebouncedHighlighter(config: HighlightConfig = DEFAULT_HIGHLIGHT_CONFIG) {
  let timer: ReturnType<typeof setTimeout> | null = null;

  /**
   * Schedules highlighting for a container element.
   *
   * @param container - Element containing code blocks
   * @param isStreaming - Whether content is actively streaming
   */
  function scheduleHighlight(container: HTMLElement | null, isStreaming: boolean): void {
    if (!container) return;

    // Clear pending highlight
    if (timer) {
      clearTimeout(timer);
    }

    const delay = isStreaming ? config.streamingDelay : config.settledDelay;

    timer = setTimeout(() => {
      highlightCodeBlocks(container).catch((highlightError: unknown) => {
        // Log with context for debugging - highlighting failures are non-fatal
        // (content remains readable, just without syntax coloring)
        console.warn("[highlight] Code highlighting failed:", {
          error: highlightError instanceof Error ? highlightError.message : String(highlightError),
          codeBlockCount: container.querySelectorAll(UNHIGHLIGHTED_CODE_SELECTOR).length,
        });
      });
    }, delay);
  }

  /**
   * Cancels any pending highlight operation.
   * Call this on component unmount or when content changes.
   */
  function cleanup(): void {
    if (timer) {
      clearTimeout(timer);
      timer = null;
    }
  }

  return { scheduleHighlight, cleanup };
}
