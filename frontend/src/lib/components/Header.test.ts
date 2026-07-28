import { describe, expect, it } from "vitest";
import { render } from "@testing-library/svelte";
import Header from "./Header.svelte";

describe("Header navigation accessibility", () => {
  it("names icon-only mobile navigation buttons and marks the current view", () => {
    const { getByRole } = render(Header, {
      props: { currentView: "chat" },
    });

    expect(getByRole("button", { name: "Chat" })).toHaveAttribute("aria-current", "page");
    expect(getByRole("button", { name: "Learn" })).not.toHaveAttribute("aria-current");
  });
});
