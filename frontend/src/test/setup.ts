import '@testing-library/jest-dom/vitest'

// Mock window.matchMedia for components that use media queries
Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false
  })
})

// jsdom doesn't implement scrollTo on elements; components use it for chat auto-scroll.
// oxlint-disable-next-line no-extend-native -- jsdom polyfill, not production code
Object.defineProperty(HTMLElement.prototype, 'scrollTo', {
  writable: true,
  value: () => {}
})

// requestAnimationFrame is used for post-update DOM adjustments; provide a safe fallback.
if (typeof window.requestAnimationFrame !== 'function') {
  window.requestAnimationFrame = (callback: FrameRequestCallback) => window.setTimeout(() => callback(performance.now()), 0)
}
