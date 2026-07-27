import "@testing-library/jest-dom/vitest";

// Mock window.matchMedia for components that use media queries
Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
    dispatchEvent: () => false,
  }),
});

// jsdom doesn't implement scrollTo on elements; components use it for chat auto-scroll.
// oxlint-disable-next-line no-extend-native -- jsdom polyfill, not production code
Object.defineProperty(HTMLElement.prototype, "scrollTo", {
  writable: true,
  value: () => {},
});

// jsdom doesn't implement scrollIntoView; CitationPanel uses it to reveal the expanded list.
// oxlint-disable-next-line no-extend-native -- jsdom polyfill, not production code
Object.defineProperty(HTMLElement.prototype, "scrollIntoView", {
  writable: true,
  value: () => {},
});

if (typeof HTMLDialogElement.prototype.showModal !== "function") {
  // oxlint-disable-next-line no-extend-native -- jsdom polyfill, not production code
  Object.defineProperty(HTMLDialogElement.prototype, "showModal", {
    writable: true,
    value(this: HTMLDialogElement): void {
      this.setAttribute("open", "");
    },
  });
}

if (typeof HTMLDialogElement.prototype.close !== "function") {
  // oxlint-disable-next-line no-extend-native -- jsdom polyfill, not production code
  Object.defineProperty(HTMLDialogElement.prototype, "close", {
    writable: true,
    value(this: HTMLDialogElement): void {
      const wasOpen = this.open;
      this.removeAttribute("open");
      if (!wasOpen) {
        return;
      }

      this.dispatchEvent(new Event("close"));
    },
  });
}

// requestAnimationFrame is used for post-update DOM adjustments; provide a safe fallback.
if (typeof window.requestAnimationFrame !== "function") {
  window.requestAnimationFrame = (callback: FrameRequestCallback) =>
    window.setTimeout(() => callback(performance.now()), 0);
}
