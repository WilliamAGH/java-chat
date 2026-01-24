import { marked } from 'marked'
import DOMPurify from 'dompurify'

interface StructuredMarkdownResponse {
  html: string
  citations?: Array<{
    url: string
    title: string
    snippet?: string
  }>
  enrichments?: Array<{
    type: string
    content: string
  }>
  processingTimeMs?: number
  isClean?: boolean
}

export interface RenderMarkdownOptions {
  preferClient?: boolean
  signal?: AbortSignal
}

/**
 * Distinguishes markdown rendering failures from network/abort errors.
 * Network errors trigger client fallback; render errors surface to caller.
 */
export class MarkdownRenderError extends Error {
  constructor(
    message: string,
    public readonly cause?: unknown
  ) {
    super(message)
    this.name = 'MarkdownRenderError'
  }
}

function isNetworkError(error: unknown): boolean {
  if (error instanceof TypeError && String(error.message).includes('fetch')) {
    return true
  }
  if (error instanceof DOMException && error.name === 'AbortError') {
    return false
  }
  return error instanceof Error && error.name === 'NetworkError'
}

/**
 * Render markdown to HTML using the server's structured endpoint.
 * Falls back to client-side rendering only for network failures.
 * Throws MarkdownRenderError for actual rendering failures.
 */
export async function renderMarkdown(
  content: string,
  options: RenderMarkdownOptions = {}
): Promise<string> {
  if (options.preferClient) {
    return clientRenderMarkdown(content)
  }

  try {
    const response = await fetch('/api/markdown/render/structured', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      signal: options.signal,
      body: JSON.stringify({ content })
    })

    if (response.ok) {
      const data: StructuredMarkdownResponse = await response.json()
      return processServerHtml(data.html)
    }

    // Non-2xx response - server rejected the request
    if (response.status >= 500) {
      // Server error - fall back to client rendering
      console.warn(`Server markdown rendering failed (${response.status}), using client fallback`)
      return clientRenderMarkdown(content)
    }
    // 4xx errors indicate malformed request - surface as error
    throw new MarkdownRenderError(`Server rejected markdown request: ${response.status}`)
  } catch (error) {
    if (options.signal?.aborted) {
      throw error
    }
    if (error instanceof MarkdownRenderError) {
      throw error
    }
    if (isNetworkError(error)) {
      console.warn('Server markdown rendering unavailable, using client fallback:', error)
      return clientRenderMarkdown(content)
    }
    throw new MarkdownRenderError('Unexpected markdown rendering failure', error)
  }
}

/**
 * Process server-rendered HTML with additional enhancements using DOM API
 * per AGENTS.md MD1: no regex for HTML processing
 */
function processServerHtml(html: string): string {
  // Sanitize server HTML first, then apply DOM-based transformations
  const sanitizedHtml = DOMPurify.sanitize(html, {
    USE_PROFILES: { html: true },
    ADD_ATTR: ['class', 'data-enrichment-type']
  })

  const template = document.createElement('template')
  template.innerHTML = sanitizedHtml

  // Ensure code blocks have proper language classes for highlighting
  const codeBlocks = template.content.querySelectorAll('pre > code')
  codeBlocks.forEach(code => {
    if (!code.className || code.className === '') {
      code.className = 'language-java'
    }
  })

  return template.innerHTML
}

/**
 * Client-side markdown rendering fallback with sanitization.
 * Used during streaming for faster rendering (avoids server round-trip).
 */
function clientRenderMarkdown(content: string): string {
  // Configure marked for safe, consistent output
  // breaks: true converts single newlines to <br> tags, preventing
  // LLM output from collapsing into a single paragraph blob
  marked.setOptions({
    gfm: true,
    breaks: true
  })

  // Render markdown and sanitize to prevent XSS
  const rawHtml = marked.parse(content) as string
  const sanitizedHtml = DOMPurify.sanitize(rawHtml, {
    USE_PROFILES: { html: true }
  })

  // Use DOM API to add language classes (per AGENTS.md MD1: no regex for HTML)
  const template = document.createElement('template')
  template.innerHTML = sanitizedHtml

  // Auto-detect Java code blocks that weren't explicitly marked
  const codeBlocks = template.content.querySelectorAll('pre > code:not([class])')
  codeBlocks.forEach(code => {
    const text = code.textContent ?? ''
    const javaKeywords = ['public', 'private', 'class', 'import', 'void', 'String', 'int', 'boolean']
    if (javaKeywords.some(kw => text.includes(kw))) {
      code.className = 'language-java'
    }
  })

  return template.innerHTML
}

/**
 * Simple text escaping for safety
 */
export function escapeHtml(text: string): string {
  const div = document.createElement('div')
  div.textContent = text
  return div.innerHTML
}
