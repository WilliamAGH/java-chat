import { afterEach, describe, expect, it, vi } from 'vitest'
import { generateSessionId } from './session'

describe('generateSessionId', () => {
  afterEach(() => {
    vi.useRealTimers()
    vi.restoreAllMocks()
    vi.unstubAllGlobals()
  })

  it('uses crypto.randomUUID when available', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-02-09T12:00:00.000Z'))
    vi.stubGlobal('crypto', {
      randomUUID: () => 'uuid-test-value',
    } as unknown as Crypto)

    const sessionId = generateSessionId('chat')
    expect(sessionId).toBe('chat-1770638400000-uuid-test-value')
  })

  it('uses crypto.getRandomValues when randomUUID is unavailable', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-02-09T12:00:00.000Z'))
    vi.stubGlobal('crypto', {
      getRandomValues: (randomBytes: Uint8Array) => {
        randomBytes.fill(15)
        return randomBytes
      },
    } as unknown as Crypto)

    const sessionId = generateSessionId('chat')
    const sessionParts = sessionId.split('-')
    const randomSuffix = sessionParts[sessionParts.length - 1]
    expect(randomSuffix).toHaveLength(32)
    expect(/^[0-9a-f]+$/.test(randomSuffix)).toBe(true)
  })

  it('falls back to padded Math.random output when crypto is unavailable', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-02-09T12:00:00.000Z'))
    vi.stubGlobal('crypto', undefined)
    vi.spyOn(Math, 'random').mockReturnValue(0)

    const sessionId = generateSessionId('chat')
    const sessionParts = sessionId.split('-')
    const randomSuffix = sessionParts[sessionParts.length - 1]
    expect(sessionId.endsWith('-')).toBe(false)
    expect(randomSuffix).toHaveLength(12)
    expect(randomSuffix).toBe('000000000000')
  })
})

