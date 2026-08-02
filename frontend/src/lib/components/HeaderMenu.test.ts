import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { fireEvent, render } from "@testing-library/svelte";
import HeaderMenu from "./HeaderMenu.svelte";
import { initializeThemePreference, themePreference } from "../composables/themePreference.svelte";

beforeEach(() => {
  localStorage.clear();
  document.documentElement.removeAttribute("data-theme");
  initializeThemePreference();
});

afterEach(() => {
  localStorage.clear();
  document.documentElement.removeAttribute("data-theme");
});

describe("HeaderMenu color scheme choices", () => {
  it("offers system, light, and dark choices with system selected by default", () => {
    const { getByRole } = render(HeaderMenu, { props: { currentView: "chat" } });

    expect(getByRole("button", { name: "System color scheme" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(getByRole("button", { name: "Light color scheme" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
    expect(getByRole("button", { name: "Dark color scheme" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
  });

  it("applies and persists an explicit selection", async () => {
    const { getByRole } = render(HeaderMenu, { props: { currentView: "chat" } });

    await fireEvent.click(getByRole("button", { name: "Dark color scheme" }));

    expect(themePreference.preference).toBe("dark");
    expect(localStorage.getItem("java-chat-theme-preference")).toBe("dark");
    expect(document.documentElement.dataset["theme"]).toBe("dark");
    expect(getByRole("button", { name: "Dark color scheme" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(getByRole("button", { name: "System color scheme" })).toHaveAttribute(
      "aria-pressed",
      "false",
    );
  });
});

describe("HeaderMenu panel behavior", () => {
  it("opens and closes the panel from the trigger", async () => {
    const { getByRole, container } = render(HeaderMenu, { props: { currentView: "chat" } });
    const trigger = getByRole("button", { name: "Settings and pages menu" });
    const panel = container.querySelector(".menu-panel");

    expect(trigger).toHaveAttribute("aria-haspopup", "menu");
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(panel).not.toHaveClass("open");

    await fireEvent.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(panel).toHaveClass("open");

    await fireEvent.click(trigger);
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(panel).not.toHaveClass("open");
  });

  it("closes the panel on Escape and on outside click", async () => {
    const { getByRole, container } = render(HeaderMenu, { props: { currentView: "chat" } });
    const trigger = getByRole("button", { name: "Settings and pages menu" });
    const panel = container.querySelector(".menu-panel");

    await fireEvent.click(trigger);
    expect(panel).toHaveClass("open");

    await fireEvent.keyDown(document.body, { key: "Escape" });
    expect(panel).not.toHaveClass("open");

    await fireEvent.click(trigger);
    expect(panel).toHaveClass("open");

    await fireEvent.click(document.body);
    expect(panel).not.toHaveClass("open");
  });
});

describe("HeaderMenu page links", () => {
  it("links Privacy and Contact with real hrefs and marks the current view", () => {
    const { getByRole } = render(HeaderMenu, { props: { currentView: "privacy" } });

    const privacyLink = getByRole("menuitem", { name: "Privacy" });
    expect(privacyLink).toHaveAttribute("href", "/privacy");
    expect(privacyLink).toHaveAttribute("aria-current", "page");

    const contactLink = getByRole("menuitem", { name: "Contact" });
    expect(contactLink).toHaveAttribute("href", "/contact");
    expect(contactLink).not.toHaveAttribute("aria-current");
  });

  it("switches the bound view and closes the panel when a link is clicked", async () => {
    const { getByRole, container } = render(HeaderMenu, { props: { currentView: "chat" } });

    await fireEvent.click(getByRole("button", { name: "Settings and pages menu" }));
    await fireEvent.click(getByRole("menuitem", { name: "Contact" }));

    expect(container.querySelector(".menu-panel")).not.toHaveClass("open");
    const contactLink = getByRole("menuitem", { name: "Contact" });
    expect(contactLink).toHaveAttribute("aria-current", "page");
    expect(getByRole("menuitem", { name: "Privacy" })).not.toHaveAttribute("aria-current");
  });

  it("leaves modified clicks to the browser so links can open in a new tab", async () => {
    const { getByRole } = render(HeaderMenu, { props: { currentView: "chat" } });

    await fireEvent.click(getByRole("button", { name: "Settings and pages menu" }));
    const contactLink = getByRole("menuitem", { name: "Contact" });
    const clickEvent = new MouseEvent("click", { bubbles: true, cancelable: true, metaKey: true });
    await fireEvent(contactLink, clickEvent);

    expect(clickEvent.defaultPrevented).toBe(false);
    expect(contactLink).not.toHaveAttribute("aria-current");
  });
});
