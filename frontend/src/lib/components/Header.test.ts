import { afterEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render } from "@testing-library/svelte";
import Header from "./Header.svelte";
import { clerkAuthentication } from "../composables/clerkAuthentication.svelte";

afterEach(() => {
  clerkAuthentication.isLoaded = false;
  clerkAuthentication.signedInUser = null;
});

describe("Header navigation accessibility", () => {
  it("names icon-only mobile navigation buttons and marks the current view", () => {
    const { getByRole } = render(Header, {
      props: { currentView: "chat", onContactOpen: vi.fn() },
    });

    expect(getByRole("button", { name: "Chat" })).toHaveAttribute("aria-current", "page");
    expect(getByRole("button", { name: "Learn" })).not.toHaveAttribute("aria-current");
    expect(getByRole("button", { name: "Privacy" })).not.toHaveAttribute("aria-current");
  });

  it("marks privacy as the current public view", () => {
    const { getByRole } = render(Header, {
      props: { currentView: "privacy", onContactOpen: vi.fn() },
    });

    expect(getByRole("button", { name: "Privacy" })).toHaveAttribute("aria-current", "page");
    expect(getByRole("button", { name: "Chat" })).not.toHaveAttribute("aria-current");
  });

  it("opens the contact dialog without marking a view", async () => {
    const onContactOpen = vi.fn();
    const { getByRole } = render(Header, {
      props: { currentView: "chat", onContactOpen },
    });

    const contactTab = getByRole("button", { name: "Contact" });
    expect(contactTab).not.toHaveAttribute("aria-current");
    expect(contactTab).toHaveAttribute("aria-haspopup", "dialog");

    await fireEvent.click(contactTab);

    expect(onContactOpen).toHaveBeenCalledTimes(1);
    expect(getByRole("button", { name: "Chat" })).toHaveAttribute("aria-current", "page");
  });
});

describe("Header authentication controls", () => {
  it("hides auth controls until Clerk has loaded, avoiding a signed-out flicker", () => {
    const { queryByRole } = render(Header, {
      props: { currentView: "chat", onContactOpen: vi.fn() },
    });

    expect(queryByRole("button", { name: "Sign in" })).toBeNull();
    expect(queryByRole("button", { name: "Sign up" })).toBeNull();
  });

  it("offers sign-in and sign-up once Clerk is loaded and no user is signed in", () => {
    clerkAuthentication.isLoaded = true;

    const { getByRole } = render(Header, {
      props: { currentView: "chat", onContactOpen: vi.fn() },
    });

    expect(getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    expect(getByRole("button", { name: "Sign up" })).toBeInTheDocument();
  });
});
