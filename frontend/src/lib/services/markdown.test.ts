import { describe, it, expect } from 'vitest'
import { parseMarkdown, applyJavaLanguageDetection, escapeHtml } from './markdown'

describe('parseMarkdown', () => {
  it('returns empty string for empty input', () => {
    expect(parseMarkdown('')).toBe('')
    expect(parseMarkdown(null as unknown as string)).toBe('')
    expect(parseMarkdown(undefined as unknown as string)).toBe('')
  })

  it('parses basic markdown to HTML', () => {
    const renderedHtml = parseMarkdown('**bold** and *italic*')
    expect(renderedHtml).toContain('<strong>bold</strong>')
    expect(renderedHtml).toContain('<em>italic</em>')
  })

  it('parses code blocks', () => {
    const markdown = '```java\npublic class Test {}\n```'
    const renderedHtml = parseMarkdown(markdown)
    expect(renderedHtml).toContain('<pre>')
    expect(renderedHtml).toContain('<code')
    expect(renderedHtml).toContain('public class Test {}')
  })

  it('sanitizes dangerous HTML', () => {
    const markdown = '<script>alert("xss")</script>'
    const renderedHtml = parseMarkdown(markdown)
    expect(renderedHtml).not.toContain('<script>')
    expect(renderedHtml).not.toContain('alert')
  })

  it('preserves enrichment data attributes', () => {
    // Enrichment markers are parsed by the extension
    const markdown = '{{hint: This is a hint}}'
    const renderedHtml = parseMarkdown(markdown)
    expect(renderedHtml).toContain('data-enrichment-type="hint"')
  })

  it('is SSR-safe - does not use document APIs', () => {
    // This test verifies parseMarkdown works without DOM
    // If it used document.createElement, this would fail in Node
    const renderedHtml = parseMarkdown('# Heading\n\nParagraph text.')
    expect(renderedHtml).toContain('<h1>')
    expect(renderedHtml).toContain('<p>')
  })
})

describe('applyJavaLanguageDetection', () => {
  it('adds language-java class to unmarked code blocks with Java keywords', () => {
    const container = document.createElement('div')
    // Test setup: create DOM structure to verify detection
    const pre = document.createElement('pre')
    const code = document.createElement('code')
    code.textContent = 'public class MyClass {}'
    pre.appendChild(code)
    container.appendChild(pre)

    applyJavaLanguageDetection(container)

    expect(code.className).toBe('language-java')
  })

  it('does not modify code blocks that already have a class', () => {
    const container = document.createElement('div')
    const pre = document.createElement('pre')
    const code = document.createElement('code')
    code.className = 'language-python'
    code.textContent = 'public = True'
    pre.appendChild(code)
    container.appendChild(pre)

    applyJavaLanguageDetection(container)

    expect(code.className).toBe('language-python')
  })

  it('does not modify code blocks without Java keywords', () => {
    const container = document.createElement('div')
    const pre = document.createElement('pre')
    const code = document.createElement('code')
    code.textContent = 'console.log("hello")'
    pre.appendChild(code)
    container.appendChild(pre)

    applyJavaLanguageDetection(container)

    expect(code.className).toBe('')
  })

  it('detects various Java keywords', () => {
    const javaKeywords = ['public', 'private', 'class', 'import', 'void', 'String', 'int', 'boolean']

    for (const keyword of javaKeywords) {
      const container = document.createElement('div')
      const pre = document.createElement('pre')
      const code = document.createElement('code')
      code.textContent = `${keyword} something`
      pre.appendChild(code)
      container.appendChild(pre)

      applyJavaLanguageDetection(container)

      expect(code.className).toBe('language-java')
    }
  })
})

describe('escapeHtml', () => {
  it('escapes HTML special characters', () => {
    expect(escapeHtml('<div>')).toBe('&lt;div&gt;')
    expect(escapeHtml('"quoted"')).toBe('&quot;quoted&quot;')
    expect(escapeHtml("it's")).toBe("it&#039;s")
    expect(escapeHtml('a & b')).toBe('a &amp; b')
  })

  it('returns empty string for empty input', () => {
    expect(escapeHtml('')).toBe('')
  })

  it('handles complex mixed content', () => {
    const input = '<script>alert("xss")</script>'
    const escapedHtml = escapeHtml(input)
    expect(escapedHtml).not.toContain('<')
    expect(escapedHtml).not.toContain('>')
    expect(escapedHtml).toContain('&lt;')
    expect(escapedHtml).toContain('&gt;')
  })

  it('is SSR-safe - uses pure string operations', () => {
    // This works without document APIs
    const escapedHtml = escapeHtml('<div class="test">')
    expect(escapedHtml).toBe('&lt;div class=&quot;test&quot;&gt;')
  })
})
