import { afterEach, describe, expect, it, vi } from "vitest";
import { BrowserSearchFailureSchema } from "../validation/schemas";
import { streamSse } from "./sse";

describe("search failure diagnostic transport", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it("retains one HTTP refusal report without replaying the search or transmitting its prompt", async () => {
    const submittedReports: string[] = [];
    const searchRequests: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string, options?: RequestInit) => {
        if (url === "/api/diagnostics/search-failures") {
          if (typeof options?.body !== "string")
            throw new Error("Expected a JSON diagnostic request body");
          submittedReports.push(options.body);
          return new Response(null, { status: 202 });
        }
        searchRequests.push(url);
        return new Response(null, {
          status: 429,
          headers: {
            "X-Request-ID": "refusal-report-test",
            "Retry-After": "12",
          },
        });
      }),
    );

    await expect(
      streamSse(
        "/api/chat/stream",
        { latest: "private search question" },
        { onText: vi.fn() },
        "chat.ts",
      ),
    ).rejects.toMatchObject({ httpStatus: 429 });
    await vi.waitFor(() => expect(submittedReports).toHaveLength(1));
    expect(searchRequests).toEqual(["/api/chat/stream"]);
    expect(submittedReports[0]).not.toContain("private search question");
    const diagnostic = BrowserSearchFailureSchema.safeParse(
      JSON.parse(submittedReports[0] ?? "null"),
    );
    if (!diagnostic.success) throw diagnostic.error;
    expect(diagnostic.data).toMatchObject({
      kind: "HTTP",
      path: "/api/chat/stream",
      httpStatus: 429,
      requestId: "refusal-report-test",
      retryAfterSeconds: 12,
    });
  });

  it("distinguishes an SSE error inside HTTP 200 from an HTTP refusal", async () => {
    const submittedReports: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string, options?: RequestInit) => {
        if (url === "/api/diagnostics/search-failures") {
          if (typeof options?.body !== "string")
            throw new Error("Expected a JSON diagnostic request body");
          submittedReports.push(options.body);
          return new Response(null, { status: 202 });
        }
        return new Response(
          'event:error\ndata:{"message":"Temporarily unavailable","code":"stream.provider_retryable_error","retryable":true}\n\n',
          { status: 200, headers: { "X-Request-ID": "sse-report-test" } },
        );
      }),
    );
    await expect(
      streamSse(
        "/api/guided/stream",
        { latest: "private question" },
        { onText: vi.fn() },
        "guided.ts",
      ),
    ).rejects.toThrow("Temporarily unavailable");
    await vi.waitFor(() => expect(submittedReports).toHaveLength(1));
    expect(JSON.parse(submittedReports[0] ?? "null")).toMatchObject({
      kind: "SSE",
      httpStatus: 200,
      requestId: "sse-report-test",
      code: "stream.provider_retryable_error",
    });
  });

  it("does not recursively report an unavailable diagnostic endpoint", async () => {
    const requestedPaths: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (url: string) => {
        requestedPaths.push(url);
        throw new TypeError("Connection unavailable");
      }),
    );
    vi.spyOn(console, "error").mockImplementation(() => undefined);
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    await expect(
      streamSse("/api/chat/stream", { latest: "question" }, { onText: vi.fn() }, "chat.ts"),
    ).rejects.toThrow("Couldn't reach the server");
    await vi.waitFor(() =>
      expect(requestedPaths).toEqual(["/api/chat/stream", "/api/diagnostics/search-failures"]),
    );
  });
});
