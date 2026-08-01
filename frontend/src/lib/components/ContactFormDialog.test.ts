import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, waitFor } from "@testing-library/svelte";

const { submitContactMessageMock } = vi.hoisted(() => {
  return { submitContactMessageMock: vi.fn() };
});

vi.mock("../services/contact", () => {
  return { submitContactMessage: submitContactMessageMock };
});

import ContactFormDialog from "./ContactFormDialog.svelte";

function renderOpenDialog(onClose = vi.fn()) {
  const renderedDialog = render(ContactFormDialog, {
    props: { isOpen: true, onClose },
  });
  const contactForm = renderedDialog.container.querySelector("form");
  if (!(contactForm instanceof HTMLFormElement)) {
    throw new Error("Expected the contact dialog to render a form");
  }
  return { ...renderedDialog, contactForm, onClose };
}

async function fillContactForm(renderedDialog: ReturnType<typeof renderOpenDialog>): Promise<void> {
  await fireEvent.input(renderedDialog.getByLabelText("Name"), {
    target: { value: "Ada Lovelace" },
  });
  await fireEvent.input(renderedDialog.getByLabelText("Email"), {
    target: { value: "ada@example.com" },
  });
  await fireEvent.input(renderedDialog.getByLabelText("Message"), {
    target: { value: "The chat stops streaming after a few messages." },
  });
}

describe("ContactFormDialog spam guards", () => {
  beforeEach(() => {
    submitContactMessageMock.mockReset();
  });

  it("renders an off-screen honeypot field excluded from keyboard and assistive navigation", () => {
    const { container } = renderOpenDialog();
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

  it("submits the honeypot value and a renderedAt timestamp captured at open", async () => {
    submitContactMessageMock.mockResolvedValue({ success: true });
    const beforeOpenEpochMs = Date.now();
    const renderedDialog = renderOpenDialog();
    await fillContactForm(renderedDialog);

    await fireEvent.submit(renderedDialog.contactForm);

    await waitFor(() => expect(submitContactMessageMock).toHaveBeenCalledTimes(1));
    const postedSubmission = submitContactMessageMock.mock.calls[0][0];
    expect(postedSubmission).toEqual({
      name: "Ada Lovelace",
      email: "ada@example.com",
      message: "The chat stops streaming after a few messages.",
      website: "",
      renderedAt: expect.any(Number),
    });
    expect(postedSubmission.renderedAt).toBeGreaterThanOrEqual(beforeOpenEpochMs);
  });
});

describe("ContactFormDialog submission states", () => {
  beforeEach(() => {
    submitContactMessageMock.mockReset();
  });

  it("shows inline validation errors and skips the request for invalid input", async () => {
    const renderedDialog = renderOpenDialog();
    await fireEvent.input(renderedDialog.getByLabelText("Email"), {
      target: { value: "not-an-email" },
    });

    await fireEvent.submit(renderedDialog.contactForm);

    await waitFor(() =>
      expect(renderedDialog.container.querySelector("#contact-name-error")).not.toBeNull(),
    );
    expect(renderedDialog.container.querySelector("#contact-message-error")).not.toBeNull();
    expect(renderedDialog.getByLabelText("Name")).toHaveAttribute("aria-invalid", "true");
    expect(renderedDialog.getByLabelText("Email")).toHaveAttribute("aria-invalid", "true");
    expect(submitContactMessageMock).not.toHaveBeenCalled();
  });

  it("replaces the form with a success state after an accepted submission", async () => {
    submitContactMessageMock.mockResolvedValue({ success: true });
    const renderedDialog = renderOpenDialog();
    await fillContactForm(renderedDialog);

    await fireEvent.submit(renderedDialog.contactForm);

    expect(await renderedDialog.findByText("Message sent")).toBeInTheDocument();
    expect(renderedDialog.container.querySelector("form")).toBeNull();
  });

  it("shows a friendly rate-limit notice when the backend returns 429", async () => {
    submitContactMessageMock.mockResolvedValue({
      success: false,
      error: { kind: "rate-limited" },
    });
    const renderedDialog = renderOpenDialog();
    await fillContactForm(renderedDialog);

    await fireEvent.submit(renderedDialog.contactForm);

    expect(
      await renderedDialog.findByText("Too many messages — please try again later."),
    ).toBeInTheDocument();
    expect(renderedDialog.container.querySelector("form")).not.toBeNull();
  });

  it("surfaces the backend's reason for a rejected submission", async () => {
    submitContactMessageMock.mockResolvedValue({
      success: false,
      error: { kind: "rejected", message: "Message looks like spam" },
    });
    const renderedDialog = renderOpenDialog();
    await fillContactForm(renderedDialog);

    await fireEvent.submit(renderedDialog.contactForm);

    expect(await renderedDialog.findByText("Message looks like spam")).toBeInTheDocument();
  });
});
