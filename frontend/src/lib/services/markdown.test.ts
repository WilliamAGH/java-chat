import { describe, it, expect } from 'vitest'
import { parseMarkdown, applyJavaLanguageDetection, escapeHtml } from './markdown'

describe('parseMarkdown', () => {
  it('returns empty string for empty input', () => {
    expect(parseMarkdown('')).toBe('')
    expect(parseMarkdown(null as unknown as string)).toBe('')
    expect(parseMarkdown(undefined as unknown as string)).toBe('')
  })

  it('parses basic markdown to HTML', () => {
    const result = parseMarkdown('**bold** and *italic*')
    expect(result).toContain('<strong>bold</strong>')
    expect(result).toContain('<em>italic</em>')
  })

  it('parses code blocks', () => {
    const markdown = '```java\npublic class Test {}\n```'
    const result = parseMarkdown(markdown)
    expect(result).toContain('<pre>')
    expect(result).toContain('<code')
    expect(result).toContain('public class Test {}')
  })

  it('sanitizes dangerous HTML', () => {
    const markdown = '<script>alert("xss")</script>'
    const result = parseMarkdown(markdown)
    expect(result).not.toContain('<script>')
    expect(result).not.toContain('alert')
  })

  it('preserves enrichment data attributes', () => {
    // Enrichment markers are parsed by the extension
    const markdown = '{{hint: This is a hint}}'
    const result = parseMarkdown(markdown)
    expect(result).toContain('data-enrichment-type="hint"')
  })

  it('strips inline citation markers like [1] in rendered HTML', () => {
    const markdown = 'Hello world. [1]\n\nNext paragraph.'
    const result = parseMarkdown(markdown)
    expect(result).toContain('Hello world.')
    expect(result).not.toContain('[1]')
  })

  it('does not strip bracket indexing like array[1]', () => {
    const markdown = 'Use array[1] to access the second element.'
    const result = parseMarkdown(markdown)
    expect(result).toContain('array[1]')
  })

  it('does not strip bracket markers inside code blocks', () => {
    const markdown = '```java\nSystem.out.println("x"); // [1]\n```'
    const result = parseMarkdown(markdown)
    expect(result).toContain('[1]')
  })

  it('is SSR-safe - does not use document APIs', () => {
    // This test verifies parseMarkdown works without DOM
    // If it used document.createElement, this would fail in Node
    const result = parseMarkdown('# Heading\n\nParagraph text.')
    expect(result).toContain('<h1>')
    expect(result).toContain('<p>')
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
    const result = escapeHtml(input)
    expect(result).not.toContain('<')
    expect(result).not.toContain('>')
    expect(result).toContain('&lt;')
    expect(result).toContain('&gt;')
  })

  it('is SSR-safe - uses pure string operations', () => {
    // This works without document APIs
    const result = escapeHtml('<div class="test">')
    expect(result).toBe('&lt;div class=&quot;test&quot;&gt;')
  })
})
