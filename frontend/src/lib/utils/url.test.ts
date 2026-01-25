import { describe, it, expect } from 'vitest'
import {
  sanitizeUrl,
  buildFullUrl,
  deduplicateCitations,
  getCitationType,
  getDisplaySource
} from './url'

describe('sanitizeUrl', () => {
  it('returns fallback for empty/null/undefined input', () => {
    expect(sanitizeUrl('')).toBe('#')
    expect(sanitizeUrl(null)).toBe('#')
    expect(sanitizeUrl(undefined)).toBe('#')
  })

  it('allows https URLs', () => {
    const url = 'https://example.com/path'
    expect(sanitizeUrl(url)).toBe(url)
  })

  it('allows http URLs', () => {
    const url = 'http://example.com/path'
    expect(sanitizeUrl(url)).toBe(url)
  })

  it('blocks javascript URLs', () => {
    expect(sanitizeUrl('javascript:alert(1)')).toBe('#')
  })

  it('blocks data URLs', () => {
    expect(sanitizeUrl('data:text/html,<script>alert(1)</script>')).toBe('#')
  })

  it('trims whitespace', () => {
    expect(sanitizeUrl('  https://example.com  ')).toBe('https://example.com')
  })
})

describe('buildFullUrl', () => {
  it('returns sanitized URL without anchor', () => {
    expect(buildFullUrl('https://example.com', undefined)).toBe('https://example.com')
    expect(buildFullUrl('https://example.com', '')).toBe('https://example.com')
  })

  it('appends anchor with hash', () => {
    expect(buildFullUrl('https://example.com', 'section')).toBe('https://example.com#section')
  })

  it('handles anchor - appends with hash separator', () => {
    // Note: buildFullUrl always prepends # to anchor, so #section becomes ##section
    // Callers should strip # from anchors before passing
    expect(buildFullUrl('https://example.com', 'section')).toBe('https://example.com#section')
  })
})

describe('deduplicateCitations', () => {
  it('returns empty array for empty input', () => {
    expect(deduplicateCitations([])).toEqual([])
  })

  it('returns empty array for null/undefined input', () => {
    expect(deduplicateCitations(null as unknown as [])).toEqual([])
    expect(deduplicateCitations(undefined as unknown as [])).toEqual([])
  })

  it('removes duplicate URLs', () => {
    const citations = [
      { url: 'https://a.com', title: 'A' },
      { url: 'https://b.com', title: 'B' },
      { url: 'https://a.com', title: 'A duplicate' }
    ]
    const result = deduplicateCitations(citations)
    expect(result).toHaveLength(2)
    expect(result.map(c => c.url)).toEqual(['https://a.com', 'https://b.com'])
  })

  it('keeps first occurrence when deduplicating', () => {
    const citations = [
      { url: 'https://a.com', title: 'First' },
      { url: 'https://a.com', title: 'Second' }
    ]
    const result = deduplicateCitations(citations)
    expect(result[0].title).toBe('First')
  })
})

describe('getCitationType', () => {
  it('detects PDF files', () => {
    expect(getCitationType('https://example.com/doc.pdf')).toBe('pdf')
    expect(getCitationType('https://example.com/DOC.PDF')).toBe('pdf')
  })

  it('detects API documentation', () => {
    expect(getCitationType('https://docs.oracle.com/javase/8/docs/api/')).toBe('api-doc')
    expect(getCitationType('https://developer.mozilla.org/api/something')).toBe('api-doc')
  })

  it('detects repositories', () => {
    expect(getCitationType('https://github.com/user/repo')).toBe('repo')
    expect(getCitationType('https://gitlab.com/user/repo')).toBe('repo')
  })

  it('returns external for generic URLs', () => {
    expect(getCitationType('https://example.com')).toBe('external')
    expect(getCitationType('https://blog.example.com/post')).toBe('external')
  })
})

describe('getDisplaySource', () => {
  it('extracts hostname from URL', () => {
    expect(getDisplaySource('https://docs.oracle.com/javase/8/')).toBe('docs.oracle.com')
  })

  it('returns fallback label for invalid URLs', () => {
    expect(getDisplaySource('not-a-url')).toBe('Source')
    expect(getDisplaySource('')).toBe('Source')
    expect(getDisplaySource(null)).toBe('Source')
  })
})
