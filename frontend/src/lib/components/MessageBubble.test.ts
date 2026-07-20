import { describe, it, expect } from "vitest";
import { render } from "@testing-library/svelte";
import MessageBubble from "./MessageBubble.svelte";
import { CSRF_INVALID_MESSAGE } from "../services/csrf";

describe("MessageBubble", () => {
  it("does not render copy action for user messages", () => {
    const { container } = render(MessageBubble, {
      props: {
        message: { messageId: "msg-test-user", role: "user", messageText: "Hello", timestamp: 1 },
        index: 0,
      },
    });

    expect(container.querySelector(".bubble-actions")).toBeNull();
  });

  it("renders copy action for assistant messages", () => {
    const { container, getByRole } = render(MessageBubble, {
      props: {
        message: {
          messageId: "msg-test-assistant",
          role: "assistant",
          messageText: "Hello",
          timestamp: 1,
        },
        index: 0,
      },
    });

    expect(container.querySelector(".bubble-actions")).not.toBeNull();
    expect(getByRole("button", { name: /copy message/i, hidden: true })).toBeInTheDocument();
  });

  it("renders refresh button for CSRF assistant errors", () => {
    const { getByRole } = render(MessageBubble, {
      props: {
        message: {
          messageId: "msg-test-csrf-error",
          role: "assistant",
          messageText: CSRF_INVALID_MESSAGE,
          timestamp: 1,
          isError: true,
        },
        index: 0,
      },
    });

    expect(getByRole("button", { name: /refresh and retry/i })).toBeInTheDocument();
  });

  it("renders partial assistant text with a distinct stream error alert", () => {
    const { getByRole, getByText } = render(MessageBubble, {
      props: {
        message: {
          messageId: "msg-test-partial-stream-error",
          role: "assistant",
          messageText: "Partial response",
          streamErrorMessage: "The provider ended the stream",
          timestamp: 1,
        },
        index: 0,
      },
    });

    expect(getByText("Partial response")).toBeInTheDocument();
    expect(getByRole("alert")).toHaveTextContent("The provider ended the stream");
  });

  it("does not expose a Unicode-blank enrichment marker as fallback text", () => {
    const { container } = render(MessageBubble, {
      props: {
        message: {
          messageId: "msg-test-blank-enrichment",
          role: "assistant",
          messageText: "{{hint:\u3000}}",
          timestamp: 1,
        },
        index: 0,
      },
    });

    expect(container.textContent).not.toContain("{{hint:");
    expect(container.querySelector(".inline-enrichment")).toBeNull();
  });
});
