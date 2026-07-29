import { describe, it, expect, vi } from "vitest";
import { render, fireEvent } from "@testing-library/svelte";
import ChatInput from "./ChatInput.svelte";

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
  it("does not infer keyboard behavior from pointer capabilities", () => {
    const matchMediaSpy = vi.spyOn(window, "matchMedia");

    renderChatInput();

    expect(matchMediaSpy).not.toHaveBeenCalled();
    matchMediaSpy.mockRestore();
  });

  it("sends on Enter and labels the key 'send'", async () => {
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

  it("never sends on Shift+Enter", async () => {
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
  it("does not autofocus on mount", () => {
    const { messageInput } = renderChatInput();
    expect(document.activeElement).not.toBe(messageInput);
  });

  it("preserves focus naturally through a readonly Enter submission", async () => {
    const { messageInput, rerender } = renderChatInput();
    messageInput.focus();
    await typeMessage(messageInput, "Hello");
    await fireEvent.keyDown(messageInput, { key: "Enter" });

    await rerender({ disabled: true });
    expect(messageInput).toHaveAttribute("readonly");
    expect(document.activeElement).toBe(messageInput);
    await rerender({ disabled: false });

    expect(messageInput).not.toHaveAttribute("readonly");
    expect(document.activeElement).toBe(messageInput);
  });

  it("does not restore focus after an Enter submission loses focus", async () => {
    const { messageInput, rerender } = renderChatInput();
    messageInput.focus();
    await typeMessage(messageInput, "Hello");
    await fireEvent.keyDown(messageInput, { key: "Enter" });

    await rerender({ disabled: true });
    const transientFocusControl = document.createElement("button");
    document.body.append(transientFocusControl);
    transientFocusControl.focus();
    transientFocusControl.remove();
    await rerender({ disabled: false });

    expect(document.activeElement).not.toBe(messageInput);
  });

  it("does not restore focus after a send button submission", async () => {
    const renderedChatInput = renderChatInput();
    await typeMessage(renderedChatInput.messageInput, "Hello");
    await fireEvent.click(renderedChatInput.getByRole("button", { name: "Send message" }));

    await renderedChatInput.rerender({ disabled: true });
    const transientFocusControl = document.createElement("button");
    document.body.append(transientFocusControl);
    transientFocusControl.focus();
    transientFocusControl.remove();
    await renderedChatInput.rerender({ disabled: false });

    expect(document.activeElement).not.toBe(renderedChatInput.messageInput);
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
