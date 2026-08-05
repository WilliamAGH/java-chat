import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, waitFor } from "@testing-library/svelte";

const { submitContactMessageMock } = vi.hoisted(() => {
  return { submitContactMessageMock: vi.fn() };
});

vi.mock("../services/contact", () => {
  return { submitContactMessage: submitContactMessageMock };
});

import ContactPage from "./ContactPage.svelte";

// Independent expectations keep accidental user-facing copy changes visible.
const EXPECTED_CONTACT_NAME_REQUIRED_GUIDANCE = "Enter your name";
const EXPECTED_CONTACT_EMAIL_INVALID_GUIDANCE = "Enter a valid email address";
const EXPECTED_CONTACT_MESSAGE_REQUIRED_GUIDANCE = "Enter a message";

function renderContactPage() {
  const renderedPage = render(ContactPage);
  const contactForm = renderedPage.container.querySelector("form");
  if (!(contactForm instanceof HTMLFormElement)) {
    throw new Error("Expected the contact page to render a form");
  }
  return { ...renderedPage, contactForm };
}

async function fillContactForm(renderedPage: ReturnType<typeof renderContactPage>): Promise<void> {
  await fireEvent.input(renderedPage.getByLabelText("Name"), {
    target: { value: "Ada Lovelace" },
  });
  await fireEvent.input(renderedPage.getByLabelText("Email"), {
    target: { value: "ada@example.com" },
  });
  await fireEvent.input(renderedPage.getByLabelText("Message"), {
    target: { value: "The chat stops streaming after a few messages." },
  });
}

describe("ContactPage spam guards", () => {
  beforeEach(() => {
    submitContactMessageMock.mockReset();
  });

  it("renders an off-screen honeypot field excluded from keyboard and assistive navigation", () => {
    const { container } = renderContactPage();
    const honeypotInput = container.querySelector('input[name="website"]');

    expect(honeypotInput).not.toBeNull();
    if (!(honeypotInput instanceof HTMLInputElement)) {
      throw new Error("Expected a honeypot input named website");
    }
    expect(honeypotInput).toHaveAttribute("tabindex", "-1");
    expect(honeypotInput).toHaveAttribute("autocomplete", "off");
    expect(honeypotInput).toHaveValue("");
    expect(honeypotInput.closest(".honeypot-field")).toHaveAttribute("aria-hidden", "true");
  });

  it("submits the honeypot value and a renderedAt timestamp captured at mount", async () => {
    submitContactMessageMock.mockResolvedValue({ success: true });
    const beforeMountEpochMs = Date.now();
    const renderedPage = renderContactPage();
    await fillContactForm(renderedPage);

    await fireEvent.submit(renderedPage.contactForm);

    await waitFor(() => expect(submitContactMessageMock).toHaveBeenCalledTimes(1));
    const postedSubmission = submitContactMessageMock.mock.calls[0][0];
    expect(postedSubmission).toEqual({
      name: "Ada Lovelace",
      email: "ada@example.com",
      message: "The chat stops streaming after a few messages.",
      website: "",
      renderedAt: expect.any(Number),
    });
    expect(postedSubmission.renderedAt).toBeGreaterThanOrEqual(beforeMountEpochMs);
  });
});

describe("ContactPage submission states", () => {
  beforeEach(() => {
    submitContactMessageMock.mockReset();
  });

  it("shows inline validation errors and skips the request for invalid input", async () => {
    const renderedPage = renderContactPage();
    await fireEvent.input(renderedPage.getByLabelText("Email"), {
      target: { value: "not-an-email" },
    });

    await fireEvent.submit(renderedPage.contactForm);

    await waitFor(() =>
      expect(renderedPage.container.querySelector("#contact-name-error")).not.toBeNull(),
    );
    expect(renderedPage.getByText(EXPECTED_CONTACT_NAME_REQUIRED_GUIDANCE)).toBeInTheDocument();
    expect(renderedPage.getByText(EXPECTED_CONTACT_EMAIL_INVALID_GUIDANCE)).toBeInTheDocument();
    expect(renderedPage.getByText(EXPECTED_CONTACT_MESSAGE_REQUIRED_GUIDANCE)).toBeInTheDocument();
    expect(renderedPage.container.querySelector("#contact-message-error")).not.toBeNull();
    expect(renderedPage.getByLabelText("Name")).toHaveAttribute("aria-invalid", "true");
    expect(renderedPage.getByLabelText("Email")).toHaveAttribute("aria-invalid", "true");
    expect(submitContactMessageMock).not.toHaveBeenCalled();
  });

  it("replaces the form with a success state after an accepted submission", async () => {
    submitContactMessageMock.mockResolvedValue({ success: true });
    const renderedPage = renderContactPage();
    await fillContactForm(renderedPage);

    await fireEvent.submit(renderedPage.contactForm);

    expect(await renderedPage.findByText("Message sent")).toBeInTheDocument();
    expect(renderedPage.container.querySelector("form")).toBeNull();
  });

  it("shows a friendly rate-limit notice when the backend returns 429", async () => {
    submitContactMessageMock.mockResolvedValue({
      success: false,
      error: { kind: "rate-limited" },
    });
    const renderedPage = renderContactPage();
    await fillContactForm(renderedPage);

    await fireEvent.submit(renderedPage.contactForm);

    expect(
      await renderedPage.findByText("Too many messages — please try again later."),
    ).toBeInTheDocument();
    expect(renderedPage.container.querySelector("form")).not.toBeNull();
  });

  it("surfaces the backend's reason for a rejected submission", async () => {
    submitContactMessageMock.mockResolvedValue({
      success: false,
      error: { kind: "rejected", message: "Message looks like spam" },
    });
    const renderedPage = renderContactPage();
    await fillContactForm(renderedPage);

    await fireEvent.submit(renderedPage.contactForm);

    expect(await renderedPage.findByText("Message looks like spam")).toBeInTheDocument();
  });
});

describe("ContactPage supporting content", () => {
  it("links the privacy policy and the privacy mailbox from the aside", () => {
    const renderedPage = renderContactPage();

    expect(renderedPage.getByRole("link", { name: "Privacy Policy" })).toHaveAttribute(
      "href",
      "/privacy",
    );
    expect(renderedPage.getByRole("link", { name: "privacy@javachat.ai" })).toHaveAttribute(
      "href",
      "mailto:privacy@javachat.ai",
    );
  });
});
