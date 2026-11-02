import { BrowserSearchFailureSchema, type BrowserSearchFailure } from "../validation/schemas";
import { validateWithSchema } from "../validation/validate";
import { fetchWithCsrfRetry } from "./csrf";

/** Sends one browser observation into the existing application log transport, without replaying searches. */
export async function reportSearchFailure(failure: BrowserSearchFailure): Promise<void> {
  const diagnosticValidation = validateWithSchema(
    BrowserSearchFailureSchema,
    failure,
    "search-diagnostics",
  );
  if (!diagnosticValidation.success) {
    return;
  }
  try {
    const diagnosticReply = await fetchWithCsrfRetry(
      "/api/diagnostics/search-failures",
      {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(diagnosticValidation.validated),
        keepalive: true,
      },
      "search-diagnostics",
    );
    if (!diagnosticReply.ok) {
      console.warn("Search diagnostics were rejected", { status: diagnosticReply.status });
    }
  } catch (diagnosticFailure) {
    // A failed report must neither replace the search's original error nor recursively report itself.
    console.warn("Search diagnostics could not reach the server", diagnosticFailure);
  }
}
