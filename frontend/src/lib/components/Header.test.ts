import { describe, expect, it } from "vitest";
import { render } from "@testing-library/svelte";
import Header from "./Header.svelte";

describe("Header navigation accessibility", () => {
  it("names icon-only mobile navigation tabs", () => {
    const { getByRole } = render(Header, {
      props: { currentView: "chat" },
    });

    expect(getByRole("tab", { name: "Chat" })).toHaveAttribute("aria-selected", "true");
    expect(getByRole("tab", { name: "Learn" })).toHaveAttribute("aria-selected", "false");
  });
});
