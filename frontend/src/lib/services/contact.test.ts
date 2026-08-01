import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  CONTACT_MESSAGE_MAX_LENGTH,
  CONTACT_NAME_MAX_LENGTH,
  ContactSubmissionSchema,
  type ContactSubmission,
} from "../validation/schemas";
import { submitContactMessage } from "./contact";

const CONTACT_ENDPOINT = "/api/contact";

function createValidSubmission(): ContactSubmission {
  return {
    name: "Ada Lovelace",
    email: "ada@example.com",
    message: "The streaming chat stops responding after a few messages.",
    website: "",
    renderedAt: Date.now(),
  };
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

function lastPostedSubmission(): unknown {
  const [, requestInit] = vi.mocked(fetch).mock.calls.at(-1) ?? [];
  if (!requestInit?.body || typeof requestInit.body !== "string") {
    throw new Error("Expected the contact request to carry a JSON string body");
  }
  return JSON.parse(requestInit.body);
}

describe("ContactSubmissionSchema", () => {
  it("accepts a well-formed submission", () => {
    const validation = ContactSubmissionSchema.safeParse(createValidSubmission());

    expect(validation.success).toBe(true);
  });

  it("trims surrounding whitespace from name and message", () => {
    const validation = ContactSubmissionSchema.safeParse({
      ...createValidSubmission(),
      name: "  Ada Lovelace  ",
      message: "  Hello support  ",
    });

    if (!validation.success) {
      throw new Error("Expected the trimmed submission to validate");
    }
    expect(validation.data.name).toBe("Ada Lovelace");
    expect(validation.data.message).toBe("Hello support");
  });

  it("rejects an empty name", () => {
    const validation = ContactSubmissionSchema.safeParse({
      ...createValidSubmission(),
      name: "   ",
    });

    expect(validation.success).toBe(false);
  });

  it("rejects a name beyond the contract maximum", () => {
    const validation = ContactSubmissionSchema.safeParse({
      ...createValidSubmission(),
      name: "a".repeat(CONTACT_NAME_MAX_LENGTH + 1),
    });

    expect(validation.success).toBe(false);
  });

  it("rejects a malformed email", () => {
    const validation = ContactSubmissionSchema.safeParse({
      ...createValidSubmission(),
      email: "not-an-email",
    });

    expect(validation.success).toBe(false);
  });

  it("rejects an empty message and one beyond the contract maximum", () => {
    expect(
      ContactSubmissionSchema.safeParse({ ...createValidSubmission(), message: "" }).success,
    ).toBe(false);
    expect(
      ContactSubmissionSchema.safeParse({
        ...createValidSubmission(),
        message: "a".repeat(CONTACT_MESSAGE_MAX_LENGTH + 1),
      }).success,
    ).toBe(false);
  });

  it("rejects a missing or non-integer renderedAt timestamp", () => {
    const { renderedAt: _omitted, ...withoutRenderedAt } = createValidSubmission();

    expect(ContactSubmissionSchema.safeParse(withoutRenderedAt).success).toBe(false);
    expect(
      ContactSubmissionSchema.safeParse({ ...createValidSubmission(), renderedAt: 1.5 }).success,
    ).toBe(false);
    expect(
      ContactSubmissionSchema.safeParse({ ...createValidSubmission(), renderedAt: 0 }).success,
    ).toBe(false);
  });
});

describe("submitContactMessage", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("posts the submission with the honeypot and renderedAt fields", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(202, { status: "accepted" }));
    const submission = createValidSubmission();

    const submitOutcome = await submitContactMessage(submission);

    expect(submitOutcome).toEqual({ success: true });
    expect(vi.mocked(fetch)).toHaveBeenCalledTimes(1);
    const [requestTarget, requestInit] = vi.mocked(fetch).mock.calls[0];
    expect(requestTarget).toBe(CONTACT_ENDPOINT);
    expect(requestInit?.method).toBe("POST");
    expect(lastPostedSubmission()).toEqual(submission);
  });

  it("maps a 400 validation failure to a rejected result with the server message", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      jsonResponse(400, { status: "error", message: "Message looks like spam" }),
    );

    const submitOutcome = await submitContactMessage(createValidSubmission());

    expect(submitOutcome).toEqual({
      success: false,
      error: { kind: "rejected", message: "Message looks like spam" },
    });
  });

  it("falls back to a generic rejection when the 400 body has no message", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(new Response("nope", { status: 400 }));

    const submitOutcome = await submitContactMessage(createValidSubmission());

    expect(submitOutcome).toEqual({
      success: false,
      error: { kind: "rejected", message: "The server rejected this message" },
    });
  });

  it("maps a 429 response to a rate-limited result", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(new Response(null, { status: 429 }));

    const submitOutcome = await submitContactMessage(createValidSubmission());

    expect(submitOutcome).toEqual({ success: false, error: { kind: "rate-limited" } });
  });

  it("maps a 5xx response to an unavailable result", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(
      new Response(null, { status: 500, statusText: "Internal Server Error" }),
    );

    const submitOutcome = await submitContactMessage(createValidSubmission());

    expect(submitOutcome).toEqual({
      success: false,
      error: { kind: "unavailable", message: "HTTP 500: Internal Server Error" },
    });
  });

  it("maps a malformed 202 acknowledgement to an unavailable result", async () => {
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(202, { status: "ok" }));
    const consoleErrorSpy = vi.spyOn(console, "error").mockImplementation(() => {});

    const submitOutcome = await submitContactMessage(createValidSubmission());

    expect(submitOutcome).toEqual({
      success: false,
      error: { kind: "unavailable", message: "The server sent an unexpected acknowledgement" },
    });
    expect(consoleErrorSpy).toHaveBeenCalled();
    consoleErrorSpy.mockRestore();
  });

  it("maps a network failure to an unavailable result", async () => {
    vi.mocked(fetch).mockRejectedValueOnce(new Error("connection reset"));
    const consoleErrorSpy = vi.spyOn(console, "error").mockImplementation(() => {});

    const submitOutcome = await submitContactMessage(createValidSubmission());

    expect(submitOutcome).toEqual({
      success: false,
      error: { kind: "unavailable", message: "connection reset" },
    });
    expect(consoleErrorSpy).toHaveBeenCalled();
    consoleErrorSpy.mockRestore();
  });
});
