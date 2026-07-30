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
    const copyButton = getByRole("button", { name: /copy message/i });
    expect(copyButton).toBeVisible();

    copyButton.focus();
    expect(copyButton).toHaveFocus();
  });

  it("reserves the copy action layout without exposing it while an assistant message is streaming", () => {
    const { container, getByRole, queryByRole } = render(MessageBubble, {
      props: {
        message: {
          messageId: "msg-test-streaming-assistant",
          role: "assistant",
          messageText: "Partial answer",
          timestamp: 1,
        },
        index: 0,
        isStreaming: true,
      },
    });

    expect(container.querySelector(".bubble-actions")).not.toBeNull();
    expect(queryByRole("button", { name: /copy message/i })).toBeNull();
    expect(getByRole("button", { name: /copy message/i, hidden: true })).toBeDisabled();
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

  it("hides the copy action on error bubbles", () => {
    const { container } = render(MessageBubble, {
      props: {
        message: {
          messageId: "msg-test-error-no-copy",
          role: "assistant",
          messageText: "Response preparation timed out",
          timestamp: 1,
          isError: true,
        },
        index: 0,
      },
    });

    expect(container.querySelector(".bubble-actions")).toBeNull();
  });

  it("renders error details and a retry action for retryable failures", async () => {
    const retriedMessages: string[] = [];
    const { getByRole, getByText } = render(MessageBubble, {
      props: {
        message: {
          messageId: "msg-test-retryable-error",
          role: "assistant",
          messageText: "Response preparation timed out",
          timestamp: 1,
          isError: true,
          errorDetails: "Java documentation retrieval did not complete in time. Please retry.",
          errorRetryable: true,
        },
        index: 0,
        onRetry: (retriedMessage: { messageId: string }) => {
          retriedMessages.push(retriedMessage.messageId);
        },
      },
    });

    expect(
      getByText("Java documentation retrieval did not complete in time. Please retry."),
    ).toBeInTheDocument();

    const retryButton = getByRole("button", { name: /^retry$/i });
    retryButton.click();
    expect(retriedMessages).toEqual(["msg-test-retryable-error"]);
  });

  it("hides the retry action for non-retryable failures", () => {
    const { queryByRole } = render(MessageBubble, {
      props: {
        message: {
          messageId: "msg-test-fatal-error",
          role: "assistant",
          messageText: "Something went wrong",
          timestamp: 1,
          isError: true,
          errorRetryable: false,
        },
        index: 0,
        onRetry: () => {},
      },
    });

    expect(queryByRole("button", { name: /^retry$/i })).toBeNull();
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
