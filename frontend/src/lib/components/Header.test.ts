import { afterEach, describe, expect, it } from "vitest";
import { render } from "@testing-library/svelte";
import Header from "./Header.svelte";
import { clerkAuthentication } from "../composables/clerkAuthentication.svelte";

afterEach(() => {
  clerkAuthentication.isLoaded = false;
  clerkAuthentication.signedInUser = null;
});

describe("Header navigation accessibility", () => {
  it("limits the main navigation to the learning surfaces and marks the current view", () => {
    const { getByRole, queryByRole } = render(Header, {
      props: { currentView: "chat" },
    });

    expect(getByRole("button", { name: "Chat" })).toHaveAttribute("aria-current", "page");
    expect(getByRole("button", { name: "Learn" })).not.toHaveAttribute("aria-current");

    const navigation = getByRole("navigation", { name: "Main navigation" });
    expect(navigation.querySelectorAll("button").length).toBe(2);

    // Public pages and the color scheme live in the unified header menu, not
    // the main navigation.
    expect(queryByRole("button", { name: "Privacy" })).toBeNull();
    expect(queryByRole("button", { name: "Contact" })).toBeNull();
    expect(getByRole("button", { name: "Settings and pages menu" })).toBeInTheDocument();
  });

  it("marks learn as the current public view", () => {
    const { getByRole } = render(Header, {
      props: { currentView: "learn" },
    });

    expect(getByRole("button", { name: "Learn" })).toHaveAttribute("aria-current", "page");
    expect(getByRole("button", { name: "Chat" })).not.toHaveAttribute("aria-current");
  });
});

describe("Header authentication controls", () => {
  it("hides auth controls until Clerk has loaded, avoiding a signed-out flicker", () => {
    const { queryByRole } = render(Header, {
      props: { currentView: "chat" },
    });

    expect(queryByRole("button", { name: "Sign in" })).toBeNull();
    expect(queryByRole("button", { name: "Sign up" })).toBeNull();
  });

  it("offers sign-in and sign-up once Clerk is loaded and no user is signed in", () => {
    clerkAuthentication.isLoaded = true;

    const { getAllByRole } = render(Header, {
      props: { currentView: "chat" },
    });

    // Narrow viewports swap the text buttons for an icon-only sign-in via CSS;
    // jsdom never applies Svelte's injected component styles, so both variants
    // match role queries here even though only one is visible in a browser.
    expect(getAllByRole("button", { name: "Sign in" }).length).toBeGreaterThan(0);
    expect(getAllByRole("button", { name: "Sign up" }).length).toBeGreaterThan(0);
  });
});
