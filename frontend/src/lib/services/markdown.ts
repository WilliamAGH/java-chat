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

/**
 * Render markdown to HTML using the server's structured endpoint
 * Falls back to client-side rendering if server is unavailable
 */
export async function renderMarkdown(content: string): Promise<string> {
  try {
    // Try server-side structured rendering first
    const response = await fetch('/api/markdown/render/structured', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ content })
    })

    if (response.ok) {
      const data: StructuredMarkdownResponse = await response.json()
      return processServerHtml(data.html)
    }
  } catch (error) {
    // Log the error for visibility, then fall through to client-side rendering
    console.warn('Server markdown rendering unavailable, using client fallback:', error)
  }

  // Client-side fallback using marked
  return clientRenderMarkdown(content)
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
 * Client-side markdown rendering fallback with sanitization
 */
function clientRenderMarkdown(content: string): string {
  // Configure marked for safe, consistent output
  marked.setOptions({
    gfm: true,
    breaks: false
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
