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
});
