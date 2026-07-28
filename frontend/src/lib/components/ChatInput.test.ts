import { describe, it, expect, vi, afterEach } from "vitest";
import { render, fireEvent } from "@testing-library/svelte";
import ChatInput from "./ChatInput.svelte";

function stubFinePointer(isFinePointer: boolean): void {
  Object.defineProperty(window, "matchMedia", {
    writable: true,
    value: (query: string) => ({
      matches: query === "(pointer: fine)" ? isFinePointer : false,
      media: query,
      onchange: null,
      addListener: () => {},
      removeListener: () => {},
      addEventListener: () => {},
      removeEventListener: () => {},
      dispatchEvent: () => false,
    }),
  });
}

function renderChatInput(onSend = vi.fn<(message: string) => void>()) {
  const renderedChatInput = render(ChatInput, { props: { onSend } });
  const messageInput = renderedChatInput.getByLabelText("Message input");
  if (!(messageInput instanceof HTMLTextAreaElement)) {
    throw new Error("Expected message input element to be a textarea");
  }
  return { ...renderedChatInput, messageInput, onSend };
}

function stubTextareaMeasurement(messageInput: HTMLTextAreaElement, scrollHeightPx: number): void {
  Object.defineProperty(messageInput, "scrollHeight", {
    configurable: true,
    value: scrollHeightPx,
  });
}

async function typeMessage(messageInput: HTMLTextAreaElement, typedMessage: string): Promise<void> {
  await fireEvent.input(messageInput, { target: { value: typedMessage } });
}

describe("ChatInput keyboard behavior", () => {
  afterEach(() => {
    stubFinePointer(true);
  });

  it("sends on Enter and labels the key 'send' with a hardware keyboard", async () => {
    const { messageInput, onSend } = renderChatInput();
    expect(messageInput).toHaveAttribute("enterkeyhint", "send");

    await typeMessage(messageInput, "Hello");
    const enterEvent = new KeyboardEvent("keydown", {
      bubbles: true,
      cancelable: true,
      key: "Enter",
    });
    await fireEvent(messageInput, enterEvent);

    expect(enterEvent.defaultPrevented).toBe(true);
    expect(onSend).toHaveBeenCalledWith("Hello");
    expect(messageInput).toHaveValue("");
  });

  it("inserts a newline on Return and labels the key 'enter' on touch devices", async () => {
    stubFinePointer(false);
    const { messageInput, onSend } = renderChatInput();
    expect(messageInput).toHaveAttribute("enterkeyhint", "enter");

    await typeMessage(messageInput, "Hello");
    const returnEvent = new KeyboardEvent("keydown", {
      bubbles: true,
      cancelable: true,
      key: "Enter",
    });
    await fireEvent(messageInput, returnEvent);

    expect(returnEvent.defaultPrevented).toBe(false);
    expect(onSend).not.toHaveBeenCalled();
    expect(messageInput).toHaveValue("Hello");
  });

  it("never sends on Shift+Enter, even with a hardware keyboard", async () => {
    const { messageInput, onSend } = renderChatInput();

    await typeMessage(messageInput, "Hello");
    const shiftEnterEvent = new KeyboardEvent("keydown", {
      bubbles: true,
      cancelable: true,
      key: "Enter",
      shiftKey: true,
    });
    await fireEvent(messageInput, shiftEnterEvent);

    expect(shiftEnterEvent.defaultPrevented).toBe(false);
    expect(onSend).not.toHaveBeenCalled();
  });
});

describe("ChatInput focus behavior", () => {
  afterEach(() => {
    stubFinePointer(true);
  });

  it("autofocuses on mount with a hardware keyboard", () => {
    const { messageInput } = renderChatInput();
    expect(document.activeElement).toBe(messageInput);
  });

  it("does not autofocus on touch devices", () => {
    stubFinePointer(false);
    const { messageInput } = renderChatInput();
    expect(document.activeElement).not.toBe(messageInput);
  });
});

describe("ChatInput auto-resize", () => {
  it("grows to the content height without collapsing first", async () => {
    const { messageInput } = renderChatInput();
    stubTextareaMeasurement(messageInput, 120);

    await typeMessage(messageInput, "A question that wraps onto more lines");

    expect(messageInput.style.height).toBe("120px");
  });

  it("shrinks when text is deleted", async () => {
    const { messageInput } = renderChatInput();
    stubTextareaMeasurement(messageInput, 120);
    await typeMessage(messageInput, "A question that wraps onto more lines");
    expect(messageInput.style.height).toBe("120px");

    stubTextareaMeasurement(messageInput, 48);
    await typeMessage(messageInput, "Hi");

    expect(messageInput.style.height).toBe("48px");
  });

  it("does not resize while an IME composition is active", async () => {
    const { messageInput } = renderChatInput();
    stubTextareaMeasurement(messageInput, 300);

    await fireEvent(messageInput, new InputEvent("input", { bubbles: true, isComposing: true }));

    expect(messageInput.style.height).toBe("");
  });

  it("caps growth at the CSS max-height", async () => {
    const originalGetComputedStyle = window.getComputedStyle.bind(window);
    const computedStyleSpy = vi
      .spyOn(window, "getComputedStyle")
      .mockImplementation((element: Element, pseudoElt?: string | null) => {
        const computedStyles = originalGetComputedStyle(element, pseudoElt);
        Object.defineProperty(computedStyles, "maxHeight", { value: "200px" });
        return computedStyles;
      });
    try {
      const { messageInput } = renderChatInput();
      stubTextareaMeasurement(messageInput, 500);

      await typeMessage(messageInput, "A very long message");

      expect(messageInput.style.height).toBe("200px");
    } finally {
      computedStyleSpy.mockRestore();
    }
  });

  it("resets the height after a message is sent", async () => {
    const { messageInput, onSend } = renderChatInput();
    stubTextareaMeasurement(messageInput, 120);
    await typeMessage(messageInput, "A question that wraps onto more lines");
    expect(messageInput.style.height).toBe("120px");

    await fireEvent.keyDown(messageInput, { key: "Enter" });

    expect(onSend).toHaveBeenCalled();
    expect(messageInput.style.height).toBe("auto");
  });
});
