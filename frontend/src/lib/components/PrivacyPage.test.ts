import { render, fireEvent } from "@testing-library/svelte";
import { describe, expect, it, vi } from "vitest";
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

  it("links the contact form at every location that names the privacy mailbox", () => {
    const privacyPage = render(PrivacyPage);

    const mailboxCount = privacyPage.getAllByRole("link", { name: "privacy@javachat.ai" }).length;
    const contactFormLinks = privacyPage.getAllByRole("link", { name: /contact form/i });

    // Six mailbox mentions: general questions, deletion, CCPA, children, the
    // legal contact block, and the aside — each pairs with a contact-form link.
    expect(mailboxCount).toBe(6);
    expect(contactFormLinks.length).toBe(mailboxCount);
    for (const contactFormLink of contactFormLinks) {
      expect(contactFormLink).toHaveAttribute("href", "/contact");
    }
  });

  it("navigates to the contact page through the SPA handler without a reload", async () => {
    const onInternalNavigate = vi.fn();
    const privacyPage = render(PrivacyPage, { props: { onInternalNavigate } });

    const [firstContactFormLink] = privacyPage.getAllByRole("link", { name: /contact form/i });
    await fireEvent.click(firstContactFormLink);

    expect(onInternalNavigate).toHaveBeenCalledWith("/contact");
  });
});
