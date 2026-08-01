/**
 * Contact service for the support form.
 *
 * Submits the contact form to POST /api/contact and maps the fixed response
 * contract (202 accepted, 400 validation failure, 429 rate limited, 5xx
 * failure) onto a discriminated union so the UI can render each state.
 *
 * @see {@link ../validation/schemas.ts} for the request/response contract
 */

import { ContactAcceptedSchema, type ContactSubmission } from "../validation/schemas";
import { validateWithSchema } from "../validation/validate";
import { csrfHeader, extractApiErrorMessage, fetchWithCsrfRetry } from "./csrf";

const CONTACT_ENDPOINT = "/api/contact";
const CONTACT_LOG_LABEL = `submitContactMessage [${CONTACT_ENDPOINT}]`;

const HTTP_ACCEPTED_STATUS = 202;
const HTTP_VALIDATION_FAILURE_STATUS = 400;
const HTTP_RATE_LIMITED_STATUS = 429;

/** Server rejected the submission (HTTP 400); carries the backend's reason. */
interface ContactRejection {
  kind: "rejected";
  message: string;
}

/** Backend rate limiter refused the submission (HTTP 429). */
interface ContactRateLimited {
  kind: "rate-limited";
}

/** Network failure, malformed acknowledgement, or 5xx; the message was not accepted. */
interface ContactUnavailable {
  kind: "unavailable";
  message: string;
}

/** Why a contact submission did not reach the support inbox. */
export type ContactSubmitFailure = ContactRejection | ContactRateLimited | ContactUnavailable;

/** Submission outcome; never null, always explicit success/failure. */
export type ContactSubmitResult =
  | { success: true }
  | { success: false; error: ContactSubmitFailure };

/**
 * Reads and validates the 202 acknowledgement body.
 *
 * A malformed acknowledgement means the backend drifted from the contract, so
 * the submission is reported as failed rather than assumed delivered ([RC1f]).
 */
async function readAcceptedAcknowledgement(
  contactResponse: Response,
): Promise<ContactSubmitResult> {
  let acknowledgementJson: unknown;
  try {
    acknowledgementJson = await contactResponse.json();
  } catch (parseFailure) {
    console.error(`[${CONTACT_LOG_LABEL}] Acknowledgement JSON parse failed:`, parseFailure);
    return {
      success: false,
      error: { kind: "unavailable", message: "The server sent a malformed acknowledgement" },
    };
  }

  const acknowledgementValidation = validateWithSchema(
    ContactAcceptedSchema,
    acknowledgementJson,
    CONTACT_LOG_LABEL,
  );
  if (!acknowledgementValidation.success) {
    return {
      success: false,
      error: { kind: "unavailable", message: "The server sent an unexpected acknowledgement" },
    };
  }

  return { success: true };
}

/**
 * Submits the contact form to the backend.
 *
 * Returns a discriminated union describing the outcome; every failure path is
 * logged with the endpoint context before it reaches the caller.
 */
export async function submitContactMessage(
  submission: ContactSubmission,
): Promise<ContactSubmitResult> {
  let contactResponse: Response;
  try {
    contactResponse = await fetchWithCsrfRetry(
      CONTACT_ENDPOINT,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...csrfHeader(),
        },
        body: JSON.stringify(submission),
      },
      "submitContactMessage",
    );
  } catch (networkFailure) {
    const failureMessage =
      networkFailure instanceof Error ? networkFailure.message : "Network request failed";
    console.error(`[${CONTACT_LOG_LABEL}] Network error:`, networkFailure);
    return { success: false, error: { kind: "unavailable", message: failureMessage } };
  }

  if (contactResponse.status === HTTP_ACCEPTED_STATUS) {
    return readAcceptedAcknowledgement(contactResponse);
  }

  if (contactResponse.status === HTTP_VALIDATION_FAILURE_STATUS) {
    const serverMessage = await extractApiErrorMessage(contactResponse, "submitContactMessage");
    return {
      success: false,
      error: {
        kind: "rejected",
        message: serverMessage ?? "The server rejected this message",
      },
    };
  }

  if (contactResponse.status === HTTP_RATE_LIMITED_STATUS) {
    return { success: false, error: { kind: "rate-limited" } };
  }

  const unexpectedStatusMessage = `HTTP ${contactResponse.status}: ${contactResponse.statusText}`;
  console.error(`[${CONTACT_LOG_LABEL}] Unexpected status: ${unexpectedStatusMessage}`);
  return { success: false, error: { kind: "unavailable", message: unexpectedStatusMessage } };
}
