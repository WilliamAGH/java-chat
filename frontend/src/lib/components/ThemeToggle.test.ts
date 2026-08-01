import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { fireEvent, render } from "@testing-library/svelte";
import ThemeToggle from "./ThemeToggle.svelte";
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

describe("ThemeToggle", () => {
  it("offers system, light, and dark choices with system selected by default", () => {
    const { getByRole } = render(ThemeToggle);

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
    const { getByRole } = render(ThemeToggle);

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
