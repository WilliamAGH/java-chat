import { marked, type TokenizerExtension, type RendererExtension, type Tokens } from 'marked'
import DOMPurify from 'dompurify'

/**
 * Enrichment kinds with their display metadata.
 * Matches server-side EnrichmentPlaceholderizer for consistent rendering.
 */
const ENRICHMENT_KINDS: Record<string, { title: string; icon: string }> = {
  hint: {
    title: 'Helpful Hints',
    icon: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2a7 7 0 0 0-7 7c0 2.59 1.47 4.84 3.63 6.02L9 18h6l.37-2.98A7.01 7.01 0 0 0 19 9a7 7 0 0 0-7-7zm-3 19h6v1H9v-1z"/></svg>'
  },
  background: {
    title: 'Background Context',
    icon: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M4 6h16v2H4zM4 10h16v2H4zM4 14h16v2H4z"/></svg>'
  },
  reminder: {
    title: 'Important Reminders',
    icon: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 22a2 2 0 0 0 2-2H10a2 2 0 0 0 2 2zm6-6v-5a6 6 0 0 0-4-5.65V4a2 2 0 0 0-4 0v1.35A6 6 0 0 0 6 11v5l-2 2v1h16v-1l-2-2z"/></svg>'
  },
  warning: {
    title: 'Warning',
    icon: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2V7h2v7z"/></svg>'
  },
  example: {
    title: 'Example',
    icon: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2zm1 15h-2v-6h2zm0-8h-2V7h2z"/></svg>'
  }
}

interface EnrichmentToken extends Tokens.Generic {
  type: 'enrichment'
  raw: string
  kind: string
  content: string
}

/** Pattern matching code fence delimiters (3+ backticks or tildes at line start). */
const FENCE_PATTERN = /^[ \t]*(`{3,}|~{3,})/

/**
 * Checks whether code fences in content are balanced.
 * Uses simple line-by-line scan with toggle semantics.
 */
function hasBalancedCodeFences(content: string): boolean {
  let depth = 0
  let openChar = ''
  let openLen = 0

  for (const line of content.split('\n')) {
    const match = line.match(FENCE_PATTERN)
    if (!match) continue

    const fence = match[1]
    if (depth === 0) {
      // Opening fence
      depth = 1
      openChar = fence[0]
      openLen = fence.length
    } else if (fence[0] === openChar && fence.length >= openLen) {
      // Matching closing fence
      depth = 0
      openChar = ''
      openLen = 0
    }
  }

  return depth === 0
}

/** Enrichment close marker. */
const ENRICHMENT_CLOSE = '}}'

/**
 * Finds the closing }} for an enrichment marker, skipping }} inside code fences.
 * Scans character-by-character, tracking fence state at line boundaries.
 */
function findEnrichmentClose(src: string, startIndex: number): number {
  let inFence = false
  let fenceChar = ''
  let fenceLen = 0

  for (let cursor = startIndex; cursor < src.length - 1; cursor++) {
    // At line boundaries, check for fence delimiters
    if (cursor === startIndex || src[cursor - 1] === '\n') {
      const lineMatch = src.slice(cursor).match(FENCE_PATTERN)
      if (lineMatch) {
        const fence = lineMatch[1]
        if (!inFence) {
          inFence = true
          fenceChar = fence[0]
          fenceLen = fence.length
        } else if (fence[0] === fenceChar && fence.length >= fenceLen) {
          inFence = false
          fenceChar = ''
          fenceLen = 0
        }
        cursor += fence.length - 1 // -1 because loop will increment
        continue
      }
    }

    // Check for closing marker only outside fences
    if (!inFence && src.slice(cursor, cursor + ENRICHMENT_CLOSE.length) === ENRICHMENT_CLOSE) {
      return cursor
    }
  }

  return -1
}

/**
 * Custom marked extension for enrichment markers.
 * Parses {{kind:content}} syntax and renders as styled cards.
 */
function createEnrichmentExtension(): TokenizerExtension & RendererExtension {
  return {
    name: 'enrichment',
    level: 'block',
    start(src: string) {
      return src.indexOf('{{')
    },
    tokenizer(src: string): EnrichmentToken | undefined {
      // Match opening {{kind: pattern
      const openingRule = /^\{\{(hint|warning|background|example|reminder):/
      const openingMatch = openingRule.exec(src)
      if (!openingMatch) {
        return undefined
      }

      const kind = openingMatch[1].toLowerCase()
      const contentStart = openingMatch[0].length

      // Find closing }} that's not inside a code fence
      const closeIndex = findEnrichmentClose(src, contentStart)
      if (closeIndex === -1) {
        return undefined
      }

      const content = src.slice(contentStart, closeIndex)
      const raw = src.slice(0, closeIndex + 2)

      return {
        type: 'enrichment',
        raw,
        kind,
        content: content.trim()
      }
    },
    renderer(token: Tokens.Generic): string {
      const enrichmentToken = token as EnrichmentToken
      const meta = ENRICHMENT_KINDS[enrichmentToken.kind]
      if (!meta) {
        return token.raw
      }

      const contentToRender = enrichmentToken.content

      // DIAGNOSTIC: Log enrichment content to identify malformed markdown
      if (import.meta.env.DEV) {
        const hasFences = contentToRender.includes('```') || contentToRender.includes('~~~')
        const isBalanced = hasBalancedCodeFences(contentToRender)
        if (hasFences && !isBalanced) {
          console.warn('[markdown] Unbalanced code fences in enrichment:', {
            kind: enrichmentToken.kind,
            content: contentToRender,
            raw: enrichmentToken.raw
          })
        }
      }

      // Render inner content as markdown
      // IMPORTANT: Use gfm but disable breaks to prevent fence interference
      const innerHtml = marked.parse(contentToRender, {
        async: false,
        gfm: true,
        breaks: false // Preserve fence detection accuracy
      }) as string

      return `<div class="inline-enrichment ${enrichmentToken.kind}" data-enrichment-type="${enrichmentToken.kind}">
<div class="inline-enrichment-header">${meta.icon}<span>${meta.title}</span></div>
<div class="enrichment-text">${innerHtml}</div>
</div>`
    }
  }
}

// Configure marked once at module load
marked.use({
  gfm: true,
  breaks: true,
  extensions: [createEnrichmentExtension()]
})

/** Keywords indicating Java code for auto-detection. */
const JAVA_KEYWORDS = ['public', 'private', 'class', 'import', 'void', 'String', 'int', 'boolean'] as const

/** CSS class applied to detected Java code blocks for syntax highlighting. */
const JAVA_LANGUAGE_CLASS = 'language-java'

/** Selector for unmarked code blocks eligible for language detection. */
const UNMARKED_CODE_SELECTOR = 'pre > code:not([class])'

/**
 * Parse markdown to sanitized HTML. SSR-safe - no DOM APIs used.
 * Uses DOMPurify for sanitization. Use this in `$derived` for reactive markdown rendering.
 *
 * @throws Never throws - returns empty string on parse failure and logs error in dev mode
 */
export function parseMarkdown(content: string): string {
  if (!content) {
    return ''
  }

  // DIAGNOSTIC: Log content with unbalanced fences before parsing
  if (import.meta.env.DEV) {
    const hasFences = content.includes('```') || content.includes('~~~')
    if (hasFences && !hasBalancedCodeFences(content)) {
      console.warn('[markdown] Unbalanced code fences in input:', {
        contentLength: content.length,
        contentPreview: content.slice(0, 500),
        contentEnd: content.slice(-200)
      })
    }
  }

  try {
    const rawHtml = marked.parse(content, { async: false }) as string

    return DOMPurify.sanitize(rawHtml, {
      USE_PROFILES: { html: true },
      ADD_ATTR: ['class', 'data-enrichment-type']
    })
  } catch (parseError) {
    console.error('[markdown] Failed to parse markdown content:', parseError)
    return ''
  }
}

/**
 * Auto-detect Java code blocks and add language class for highlighting.
 * Client-side only - call this in `$effect` after content is mounted.
 *
 * @param container - DOM element containing rendered markdown. Must be a valid HTMLElement.
 * @throws Never throws - logs warning if container is invalid and returns early
 */
export function applyJavaLanguageDetection(container: HTMLElement | null | undefined): void {
  if (!container || typeof container.querySelectorAll !== 'function') {
    if (import.meta.env.DEV) {
      console.warn('[markdown] applyJavaLanguageDetection called with invalid container:', container)
    }
    return
  }

  const codeBlocks = container.querySelectorAll(UNMARKED_CODE_SELECTOR)
  codeBlocks.forEach(code => {
    const text = code.textContent ?? ''
    if (JAVA_KEYWORDS.some(kw => text.includes(kw))) {
      code.className = JAVA_LANGUAGE_CLASS
    }
  })
}

/**
 * Escape text for safe HTML insertion. SSR-safe - pure string operations.
 */
export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#039;')
}
