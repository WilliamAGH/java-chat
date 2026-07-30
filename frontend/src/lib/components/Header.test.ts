import { afterEach, describe, expect, it } from "vitest";
import { render } from "@testing-library/svelte";
import Header from "./Header.svelte";
import { clerkAuthentication } from "../composables/clerkAuthentication.svelte";

afterEach(() => {
  clerkAuthentication.isLoaded = false;
  clerkAuthentication.signedInUser = null;
});

describe("Header navigation accessibility", () => {
  it("names icon-only mobile navigation buttons and marks the current view", () => {
    const { getByRole } = render(Header, {
      props: { currentView: "chat" },
    });

    expect(getByRole("button", { name: "Chat" })).toHaveAttribute("aria-current", "page");
    expect(getByRole("button", { name: "Learn" })).not.toHaveAttribute("aria-current");
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

    const { getByRole } = render(Header, {
      props: { currentView: "chat" },
    });

    expect(getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    expect(getByRole("button", { name: "Sign up" })).toBeInTheDocument();
  });
});
