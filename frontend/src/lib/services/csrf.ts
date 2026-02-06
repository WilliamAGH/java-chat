import { ApiErrorResponseSchema, type ApiErrorResponse } from "../validation/schemas";
import { validateWithSchema } from "../validation/validate";
import { pushToast } from "../stores/toastStore";

/** Spring Security's default CSRF cookie name for SPA clients. */
const CSRF_COOKIE_NAME = "XSRF-TOKEN";

/** Header name expected by Spring Security's CsrfTokenRequestAttributeHandler. */
const CSRF_HEADER_NAME = "X-XSRF-TOKEN";
const CSRF_REFRESH_ENDPOINT = "/api/security/csrf";

export const CSRF_EXPIRED_MESSAGE = "CSRF token expired. Refresh the page and retry the request.";
export const CSRF_INVALID_MESSAGE =
  "CSRF token missing or invalid. Refresh the page and retry the request.";
const CSRF_TOAST_MESSAGE = "Session expired";
const CSRF_TOAST_SUPPRESSION_MS = 12_000;

let refreshPromise: Promise<boolean> | null = null;
let lastToastAt = 0;

function readCookie(cookieName: string): string | null {
  if (typeof document === "undefined") {
    return null;
  }
  const cookieEntry = document.cookie
    .split(";")
    .map((entry) => entry.trim())
    .find((entry) => entry.startsWith(`${cookieName}=`));

  if (!cookieEntry) {
    return null;
  }

  const tokenText = cookieEntry.slice(cookieName.length + 1);
  if (!tokenText) {
    return null;
  }
  try {
    return decodeURIComponent(tokenText);
  } catch {
    return null;
  }
}

export function csrfHeader(): Record<string, string> {
  const tokenText = readCookie(CSRF_COOKIE_NAME);
  if (!tokenText) {
    return {};
  }
  return { [CSRF_HEADER_NAME]: tokenText };
}

async function readApiErrorResponse(
  response: Response,
  source: string,
): Promise<ApiErrorResponse | null> {
  const contentType = response.headers.get("content-type") ?? "";
  if (!contentType.includes("application/json")) {
    return null;
  }

  let payload: unknown;
  try {
    payload = await response.json();
  } catch (parseError) {
    console.error(`[${source}] Failed to parse CSRF error payload:`, parseError);
    return null;
  }

  const validation = validateWithSchema(ApiErrorResponseSchema, payload, `${source}:csrf-error`);
  if (!validation.success) {
    return null;
  }
  return validation.data;
}

export async function extractApiErrorMessage(
  response: Response,
  source: string,
): Promise<string | null> {
  const apiError = await readApiErrorResponse(response, source);
  if (!apiError) {
    return null;
  }
  const trimmedMessage = apiError.message.trim();
  return trimmedMessage ? trimmedMessage : null;
}

export function isRecoverableCsrfErrorMessage(messageText: string): boolean {
  const normalizedMessage = messageText.trim();
  return (
    normalizedMessage === CSRF_EXPIRED_MESSAGE ||
    normalizedMessage === CSRF_INVALID_MESSAGE ||
    normalizedMessage.includes(CSRF_EXPIRED_MESSAGE) ||
    normalizedMessage.includes(CSRF_INVALID_MESSAGE)
  );
}

function withCurrentCsrfHeader(init: RequestInit): RequestInit {
  const requestHeaders = new Headers(init.headers ?? undefined);
  requestHeaders.delete(CSRF_HEADER_NAME);

  const currentTokenHeader = csrfHeader()[CSRF_HEADER_NAME];
  if (currentTokenHeader) {
    requestHeaders.set(CSRF_HEADER_NAME, currentTokenHeader);
  }

  return {
    ...init,
    headers: requestHeaders,
  };
}

export async function refreshCsrfToken(): Promise<boolean> {
  if (typeof window === "undefined") {
    return false;
  }

  if (refreshPromise) {
    return refreshPromise;
  }

  refreshPromise = (async () => {
    try {
      const response = await fetch(CSRF_REFRESH_ENDPOINT, { method: "GET", cache: "no-store" });
      return response.ok;
    } catch (refreshError) {
      console.warn("[csrf] Failed to refresh CSRF token:", refreshError);
      return false;
    }
  })();

  try {
    return await refreshPromise;
  } finally {
    refreshPromise = null;
  }
}

export async function fetchWithCsrfRetry(
  input: RequestInfo | URL,
  init: RequestInit,
  source: string,
): Promise<Response> {
  const response = await fetch(input, withCurrentCsrfHeader(init));
  if (response.status !== 403) {
    return response;
  }

  if (init.signal?.aborted) {
    return response;
  }

  const apiError = await readApiErrorResponse(response.clone(), source);
  if (!apiError || !isRecoverableCsrfErrorMessage(apiError.message)) {
    return response;
  }

  const refreshed = await refreshCsrfToken();
  if (!refreshed) {
    maybeToastExpired();
    return response;
  }

  if (init.signal?.aborted) {
    return response;
  }

  const retriedResponse = await fetch(input, withCurrentCsrfHeader(init));
  if (retriedResponse.status !== 403) {
    return retriedResponse;
  }

  const retriedError = await readApiErrorResponse(retriedResponse.clone(), `${source}:retry`);
  if (retriedError && isRecoverableCsrfErrorMessage(retriedError.message)) {
    maybeToastExpired();
  }
  return retriedResponse;
}

function maybeToastExpired(): void {
  if (typeof window === "undefined") {
    return;
  }
  const now = Date.now();
  if (now - lastToastAt < CSRF_TOAST_SUPPRESSION_MS) {
    return;
  }
  lastToastAt = now;
  const actionHref = typeof window !== "undefined" ? window.location.href : "/";
  pushToast(CSRF_TOAST_MESSAGE, {
    severity: "error",
    action: {
      label: "Reload page",
      href: actionHref,
    },
  });
}
