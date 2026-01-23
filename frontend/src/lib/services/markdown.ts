import { marked } from 'marked'

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
  } catch {
    // Fall through to client-side rendering
  }

  // Client-side fallback using marked
  return clientRenderMarkdown(content)
}

/**
 * Process server-rendered HTML with additional enhancements
 */
function processServerHtml(html: string): string {
  // The server already handles most processing
  // Just ensure code blocks have proper language classes
  return html
    .replace(/<pre><code>/g, '<pre><code class="language-java">')
    .replace(/<pre><code class=""/g, '<pre><code class="language-java"')
}

/**
 * Client-side markdown rendering fallback
 */
function clientRenderMarkdown(content: string): string {
  // Configure marked for safe, consistent output
  marked.setOptions({
    gfm: true,
    breaks: false
  })

  // Render markdown
  let html = marked.parse(content) as string

  // Auto-detect Java code blocks that weren't explicitly marked
  html = html.replace(/<pre><code>([^<]*(?:public|private|class|import|void|String|int|boolean)[^<]*)<\/code><\/pre>/g,
    '<pre><code class="language-java">$1</code></pre>')

  return html
}

/**
 * Simple text escaping for safety
 */
export function escapeHtml(text: string): string {
  const div = document.createElement('div')
  div.textContent = text
  return div.innerHTML
}
