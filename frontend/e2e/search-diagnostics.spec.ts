import { expect, test } from "@playwright/test";
import { BrowserSearchFailureSchema } from "../src/lib/validation/schemas";

for (const failureKind of ["HTTP", "SSE"] as const) {
  test(`${failureKind} search failure reaches diagnostics without replaying the question`, async ({
    page,
  }) => {
    const searchRequests: string[] = [];
    await page.route("**/api/security/csrf", async (route) => route.fulfill({ status: 204 }));
    await page.route("**/api/diagnostics/search-failures", async (route) =>
      route.fulfill({ status: 202 }),
    );
    await page.route("**/api/chat/stream", async (route) => {
      searchRequests.push(route.request().url());
      await route.fulfill({
        status: failureKind === "HTTP" ? 429 : 200,
        headers: { "X-Request-ID": "browser-refusal-test", "Retry-After": "12" },
        contentType: failureKind === "HTTP" ? "application/json" : "text/event-stream",
        body:
          failureKind === "HTTP"
            ? JSON.stringify({ status: "error", message: "Search is temporarily rate limited" })
            : 'event:error\ndata:{"message":"Search is temporarily rate limited","code":"test.rate_limited","retryable":true}\n\n',
      });
    });
    await page.goto("/");
    const diagnosticSubmission = page.waitForRequest("**/api/diagnostics/search-failures");
    await page.getByLabel("Message input").fill("Explain Java records");
    await page.getByLabel("Message input").press("Enter");
    await expect(
      page.getByRole("status").getByText("Search is temporarily rate limited", { exact: true }),
    ).toBeVisible();
    const diagnosticRequest = await diagnosticSubmission;
    const parsedDiagnostic: unknown = diagnosticRequest.postDataJSON();
    const diagnostic = BrowserSearchFailureSchema.safeParse(parsedDiagnostic);
    if (!diagnostic.success) throw diagnostic.error;
    expect(diagnostic.data).toMatchObject({
      path: "/api/chat/stream",
      kind: failureKind,
      httpStatus: failureKind === "HTTP" ? 429 : 200,
      requestId: "browser-refusal-test",
    });
    expect(diagnosticRequest.postData()).not.toContain("Explain Java records");
    expect(searchRequests).toHaveLength(1);
  });
}
