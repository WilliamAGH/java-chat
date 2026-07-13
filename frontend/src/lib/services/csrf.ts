import { ApiErrorResponseSchema, type ApiErrorResponse } from "../validation/schemas";
import { validateWithSchema } from "../validation/validate";
import { pushToast } from "../stores/toastStore";

/** Spring Security's default CSRF cookie name for SPA clients. */
const CSRF_COOKIE_NAME = "XSRF-TOKEN";

/** Header name expected by Spring Security's CsrfTokenRequestAttributeHandler. */
const CSRF_HEADER_NAME = "X-XSRF-TOKEN";
const CSRF_REFRESH_ENDPOINT = "/api/security/csrf";
const CSRF_FORBIDDEN_STATUS = 403;

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
    .map((cookieSegment) => cookieSegment.trim())
    .find((cookieSegment) => cookieSegment.startsWith(`${cookieName}=`));

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
    console.warn(`[csrf] Cookie ${cookieName} has malformed percent-encoding; ignoring token`);
  }
  return null;
}

export function csrfHeader(): Record<string, string> {
  const tokenText = readCookie(CSRF_COOKIE_NAME);
  if (!tokenText) {
    return {};
  }
  return { [CSRF_HEADER_NAME]: tokenText };
}

async function readCsrfError(
  httpResponse: Response,
  source: string,
): Promise<ApiErrorResponse | null> {
  const responseMediaType = httpResponse.headers.get("content-type") ?? "";
  if (!responseMediaType.includes("application/json")) {
    return null;
  }

  const parsedErrorDocument: unknown = await httpResponse.json().catch((parseError: unknown) => {
    console.error(`[${source}] Failed to parse CSRF error payload:`, parseError);
    return undefined;
  });
  if (parsedErrorDocument === undefined) {
    return null;
  }

  const csrfErrorValidation = validateWithSchema(
    ApiErrorResponseSchema,
    parsedErrorDocument,
    `${source}:csrf-error`,
  );
  if (!csrfErrorValidation.success) {
    return null;
  }
  return csrfErrorValidation.validated;
}

export async function extractApiErrorMessage(
  httpResponse: Response,
  source: string,
): Promise<string | null> {
  const apiError = await readCsrfError(httpResponse, source);
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

  refreshPromise = fetch(CSRF_REFRESH_ENDPOINT, { method: "GET", cache: "no-store" })
    .then((csrfRefreshResponse) => csrfRefreshResponse.ok)
    .catch((refreshError: unknown) => {
      console.warn("[csrf] Failed to refresh CSRF token:", refreshError);
      return false;
    });

  try {
    return await refreshPromise;
  } finally {
    refreshPromise = null;
  }
}

export async function fetchWithCsrfRetry(
  requestTarget: RequestInfo | URL,
  requestOptions: RequestInit,
  source: string,
): Promise<Response> {
  const initialHttpResponse = await fetch(requestTarget, withCurrentCsrfHeader(requestOptions));
  if (initialHttpResponse.status !== CSRF_FORBIDDEN_STATUS) {
    return initialHttpResponse;
  }

  if (requestOptions.signal?.aborted) {
    return initialHttpResponse;
  }

  const initialCsrfError = await readCsrfError(initialHttpResponse.clone(), source);
  if (!initialCsrfError || !isRecoverableCsrfErrorMessage(initialCsrfError.message)) {
    return initialHttpResponse;
  }

  const csrfTokenRefreshed = await refreshCsrfToken();
  if (!csrfTokenRefreshed) {
    maybeToastExpired();
    return initialHttpResponse;
  }

  if (requestOptions.signal?.aborted) {
    return initialHttpResponse;
  }

  const retriedHttpResponse = await fetch(requestTarget, withCurrentCsrfHeader(requestOptions));
  if (retriedHttpResponse.status !== CSRF_FORBIDDEN_STATUS) {
    return retriedHttpResponse;
  }

  const retriedCsrfError = await readCsrfError(retriedHttpResponse.clone(), `${source}:retry`);
  if (retriedCsrfError && isRecoverableCsrfErrorMessage(retriedCsrfError.message)) {
    maybeToastExpired();
  }
  return retriedHttpResponse;
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
