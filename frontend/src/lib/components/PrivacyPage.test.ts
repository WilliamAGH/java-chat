import { render } from "@testing-library/svelte";
import { describe, expect, it } from "vitest";
import PrivacyPage from "./PrivacyPage.svelte";

describe("PrivacyPage", () => {
  it("renders Java Chat's current data practices and privacy contact", () => {
    const privacyPage = render(PrivacyPage);

    expect(privacyPage.getByRole("heading", { level: 1, name: "Privacy Policy" })).toBeVisible();
    expect(privacyPage.getByText("Effective July 31, 2026")).toBeVisible();
    expect(
      privacyPage.getByText(/server memory under a randomly generated session identifier/),
    ).toBeVisible();
    expect(privacyPage.getByText(/Named providers are current examples/)).toBeVisible();
    expect(privacyPage.getByText(/including Sentry and Langfuse/)).toBeVisible();
    expect(privacyPage.getByText(/including Simple Analytics, Clicky, and PostHog/)).toBeVisible();
    expect(privacyPage.queryByText(/BitFab/i)).toBeNull();
    expect(privacyPage.queryByText(/uploaded files/i)).toBeNull();
    expect(privacyPage.queryByText(/shared chat/i)).toBeNull();

    const privacyContacts = privacyPage.getAllByRole("link", { name: "privacy@javachat.ai" });
    expect(privacyContacts.length).toBeGreaterThan(0);
    expect(privacyContacts[0]).toHaveAttribute("href", "mailto:privacy@javachat.ai");
  });
});
