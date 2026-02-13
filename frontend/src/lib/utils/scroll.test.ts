import { describe, it, expect, vi, beforeEach } from 'vitest'
import { isNearBottom, scrollToBottom } from './scroll'

/**
 * Mocks window.matchMedia for testing prefers-reduced-motion behavior.
 */
function mockMatchMedia(prefersReducedMotion: boolean): void {
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    value: vi.fn().mockImplementation((query: string) => ({
      matches: query === '(prefers-reduced-motion: reduce)' ? prefersReducedMotion : false,
      media: query,
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  })
}

beforeEach(() => {
  // Default to no reduced motion preference
  mockMatchMedia(false)
})

describe('isNearBottom', () => {
  it('returns true when container is null', () => {
    expect(isNearBottom(null)).toBe(true)
  })

  it('returns true when scrolled to bottom', () => {
    const container = createMockContainer({
      scrollTop: 900,
      scrollHeight: 1000,
      clientHeight: 100
    })
    expect(isNearBottom(container)).toBe(true)
  })

  it('returns true when within threshold of bottom', () => {
    const container = createMockContainer({
      scrollTop: 850,
      scrollHeight: 1000,
      clientHeight: 100
    })
    // 1000 - 850 - 100 = 50, which is less than default threshold (100)
    expect(isNearBottom(container)).toBe(true)
  })

  it('returns false when far from bottom', () => {
    const container = createMockContainer({
      scrollTop: 0,
      scrollHeight: 1000,
      clientHeight: 100
    })
    // 1000 - 0 - 100 = 900, which is greater than threshold
    expect(isNearBottom(container)).toBe(false)
  })

  it('respects custom threshold', () => {
    const container = createMockContainer({
      scrollTop: 700,
      scrollHeight: 1000,
      clientHeight: 100
    })
    // 1000 - 700 - 100 = 200
    expect(isNearBottom(container, 150)).toBe(false)
    expect(isNearBottom(container, 250)).toBe(true)
  })
})

describe('scrollToBottom', () => {
  it('does nothing when container is null', async () => {
    await expect(scrollToBottom(null, true)).resolves.toBeUndefined()
  })

  it('does nothing when shouldScroll is false', async () => {
    const container = createMockContainer({
      scrollTop: 0,
      scrollHeight: 1000,
      clientHeight: 100
    })
    const scrollToSpy = vi.spyOn(container, 'scrollTo')

    await scrollToBottom(container, false)

    expect(scrollToSpy).not.toHaveBeenCalled()
  })

  it('scrolls smoothly when prefers-reduced-motion is not set', async () => {
    mockMatchMedia(false) // User prefers motion
    const container = createMockContainer({
      scrollTop: 0,
      scrollHeight: 1000,
      clientHeight: 100
    })
    const scrollToSpy = vi.spyOn(container, 'scrollTo')

    await scrollToBottom(container, true)

    expect(scrollToSpy).toHaveBeenCalledWith({
      top: 1000,
      behavior: 'smooth'
    })
  })

  it('scrolls instantly when prefers-reduced-motion is set', async () => {
    mockMatchMedia(true) // User prefers reduced motion
    const container = createMockContainer({
      scrollTop: 0,
      scrollHeight: 1000,
      clientHeight: 100
    })
    const scrollToSpy = vi.spyOn(container, 'scrollTo')

    await scrollToBottom(container, true)

    expect(scrollToSpy).toHaveBeenCalledWith({
      top: 1000,
      behavior: 'auto'
    })
  })
})

describe('isNearBottom threshold', () => {
  it('uses default threshold of 100 pixels', () => {
    const container = createMockContainer({
      scrollTop: 850,
      scrollHeight: 1000,
      clientHeight: 100
    })
    // 1000 - 850 - 100 = 50, within default threshold of 100
    expect(isNearBottom(container)).toBe(true)

    const farContainer = createMockContainer({
      scrollTop: 700,
      scrollHeight: 1000,
      clientHeight: 100
    })
    // 1000 - 700 - 100 = 200, outside default threshold
    expect(isNearBottom(farContainer)).toBe(false)
  })
})

/**
 * Creates a mock HTMLElement with scroll properties.
 */
function createMockContainer(props: {
  scrollTop: number
  scrollHeight: number
  clientHeight: number
}): HTMLElement {
  const element = document.createElement('div')
  Object.defineProperty(element, 'scrollTop', { value: props.scrollTop, writable: true })
  Object.defineProperty(element, 'scrollHeight', { value: props.scrollHeight })
  Object.defineProperty(element, 'clientHeight', { value: props.clientHeight })
  element.scrollTo = vi.fn()
  return element
}
