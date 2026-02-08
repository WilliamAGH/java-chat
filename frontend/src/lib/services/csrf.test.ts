import { afterEach, describe, expect, it, vi } from "vitest";
import {
  CSRF_EXPIRED_MESSAGE,
  CSRF_INVALID_MESSAGE,
  csrfHeader,
  fetchWithCsrfRetry,
  isRecoverableCsrfErrorMessage,
  refreshCsrfToken,
} from "./csrf";

const CSRF_COOKIE_NAME = "XSRF-TOKEN";
const CSRF_HEADER_NAME = "X-XSRF-TOKEN";
const CSRF_REFRESH_ENDPOINT = "/api/security/csrf";

function setCsrfCookie(tokenText: string): void {
  document.cookie = `${CSRF_COOKIE_NAME}=${tokenText}; path=/`;
}

function clearCsrfCookie(): void {
  document.cookie = `${CSRF_COOKIE_NAME}=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/`;
}

function csrfErrorResponse(messageText: string): Response {
  return new Response(JSON.stringify({ status: "error", message: messageText }), {
    status: 403,
    headers: { "content-type": "application/json" },
  });
}

describe("csrf helpers", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    clearCsrfCookie();
  });

  it("refreshes token from backend CSRF endpoint", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }));
    vi.stubGlobal("fetch", fetchMock);

    const refreshed = await refreshCsrfToken();

    expect(refreshed).toBe(true);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(fetchMock).toHaveBeenCalledWith(
      CSRF_REFRESH_ENDPOINT,
      expect.objectContaining({ method: "GET", cache: "no-store" }),
    );
  });

  it("retries invalid CSRF response with a refreshed token header", async () => {
    setCsrfCookie("stale-token");

    const fetchMock = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const requestUrl =
        typeof input === "string" ? input : input instanceof URL ? input.toString() : input.url;

      if (requestUrl === "/api/chat/clear") {
        const headers = new Headers(init?.headers ?? undefined);
        if (headers.get(CSRF_HEADER_NAME) === "stale-token") {
          return csrfErrorResponse(CSRF_INVALID_MESSAGE);
        }
        if (headers.get(CSRF_HEADER_NAME) === "fresh-token") {
          return new Response(null, { status: 200 });
        }
      }

      if (requestUrl === CSRF_REFRESH_ENDPOINT) {
        setCsrfCookie("fresh-token");
        return new Response(null, { status: 200 });
      }

      return new Response(null, { status: 500 });
    });
    vi.stubGlobal("fetch", fetchMock);

    const response = await fetchWithCsrfRetry(
      "/api/chat/clear",
      {
        method: "POST",
        headers: {
          ...csrfHeader(),
        },
      },
      "csrf.test.ts",
    );

    expect(response.status).toBe(200);
    expect(fetchMock).toHaveBeenCalledTimes(3);
    const retriedHeaders = new Headers(
      (fetchMock.mock.calls[2][1] as RequestInit).headers ?? undefined,
    );
    expect(retriedHeaders.get(CSRF_HEADER_NAME)).toBe("fresh-token");
  });

  it("does not retry non-CSRF 403 responses", async () => {
    const fetchMock = vi.fn().mockResolvedValue(csrfErrorResponse("Forbidden"));
    vi.stubGlobal("fetch", fetchMock);

    const response = await fetchWithCsrfRetry(
      "/api/chat/clear",
      {
        method: "POST",
      },
      "csrf.test.ts",
    );

    expect(response.status).toBe(403);
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("classifies both CSRF expiry and invalid-token messages as recoverable", () => {
    expect(isRecoverableCsrfErrorMessage(CSRF_EXPIRED_MESSAGE)).toBe(true);
    expect(isRecoverableCsrfErrorMessage(CSRF_INVALID_MESSAGE)).toBe(true);
    expect(isRecoverableCsrfErrorMessage(`Failed: ${CSRF_INVALID_MESSAGE}`)).toBe(true);
    expect(isRecoverableCsrfErrorMessage("Forbidden")).toBe(false);
  });
});
