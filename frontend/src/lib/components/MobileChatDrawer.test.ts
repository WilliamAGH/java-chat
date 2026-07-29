import { afterEach, describe, expect, it, vi } from "vitest";
import { render, waitFor } from "@testing-library/svelte";
import MobileChatDrawer from "./MobileChatDrawer.svelte";

class VisualViewportStub extends EventTarget implements VisualViewport {
  readonly offsetLeft = 0;
  readonly pageLeft = 0;
  readonly pageTop = 0;
  readonly width = 390;
  onresize: ((this: VisualViewport, event: Event) => unknown) | null = null;
  onscroll: ((this: VisualViewport, event: Event) => unknown) | null = null;

  constructor(
    public height: number,
    public offsetTop: number,
    public scale: number,
  ) {
    super();
  }
}

const originalInnerHeightDescriptor = Object.getOwnPropertyDescriptor(window, "innerHeight");
const originalVisualViewportDescriptor = Object.getOwnPropertyDescriptor(window, "visualViewport");

function stubViewport(visualViewport: VisualViewport): void {
  Object.defineProperty(window, "innerHeight", {
    configurable: true,
    value: 800,
  });
  Object.defineProperty(window, "visualViewport", {
    configurable: true,
    value: visualViewport,
  });
}

function renderOpenDrawer() {
  return render(MobileChatDrawer, {
    props: {
      isOpen: true,
      messages: [],
      isStreaming: false,
      statusMessage: "",
      statusDetails: "",
      citationWarning: null,
      hasContent: false,
      title: "Lesson chat",
      emptyStateSubject: "Java",
      placeholder: "Ask about Java",
      onToggle: vi.fn(),
      onClose: vi.fn(),
      onClear: vi.fn(),
      onSend: vi.fn(),
      onScroll: vi.fn(),
    },
  });
}

describe("MobileChatDrawer visual viewport positioning", () => {
  afterEach(() => {
    if (originalInnerHeightDescriptor) {
      Object.defineProperty(window, "innerHeight", originalInnerHeightDescriptor);
    }
    if (originalVisualViewportDescriptor) {
      Object.defineProperty(window, "visualViewport", originalVisualViewportDescriptor);
    } else {
      Reflect.deleteProperty(window, "visualViewport");
    }
  });

  it("moves the fixed drawer above the obscured layout bottom", async () => {
    stubViewport(new VisualViewportStub(500, 20, 1));
    const renderedDrawer = renderOpenDrawer();
    const chatDialog = renderedDrawer.getByRole("dialog", { name: "Lesson chat" });

    await waitFor(() => {
      expect(chatDialog).toHaveStyle({
        height: "500px",
        transform: "translateY(-280px)",
      });
    });
  });

  it("does not force focus into the message input when opened", () => {
    stubViewport(new VisualViewportStub(800, 0, 1));
    const renderedDrawer = renderOpenDrawer();
    const messageInput = renderedDrawer.getByLabelText("Message input");

    expect(document.activeElement).not.toBe(messageInput);
  });

  it("does not interpret pinch zoom as a software keyboard", async () => {
    stubViewport(new VisualViewportStub(400, 0, 2));
    const renderedDrawer = renderOpenDrawer();
    const chatDialog = renderedDrawer.getByRole("dialog", { name: "Lesson chat" });

    await waitFor(() => {
      expect(chatDialog.style.height).toBe("");
      expect(chatDialog.style.transform).toBe("");
    });
  });

  it("pins and releases the drawer when a keyboard opens during pinch zoom", async () => {
    const visualViewport = new VisualViewportStub(400, 0, 2);
    stubViewport(visualViewport);
    const renderedDrawer = renderOpenDrawer();
    const chatDialog = renderedDrawer.getByRole("dialog", { name: "Lesson chat" });

    visualViewport.height = 250;
    visualViewport.offsetTop = 50;
    visualViewport.dispatchEvent(new Event("resize"));
    await waitFor(() => {
      expect(chatDialog).toHaveStyle({
        height: "250px",
        transform: "translateY(-500px)",
      });
    });

    visualViewport.height = 400;
    visualViewport.offsetTop = 0;
    visualViewport.dispatchEvent(new Event("resize"));
    await waitFor(() => {
      expect(chatDialog.style.height).toBe("");
      expect(chatDialog.style.transform).toBe("");
    });
  });
});
