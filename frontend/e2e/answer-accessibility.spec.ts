import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

const CHAT_STREAM_ENDPOINT = "**/api/chat/stream";
const CSRF_ENDPOINT = "**/api/security/csrf";
const THEME_PREFERENCE_STORAGE_KEY = "java-chat-theme-preference";

const LONG_SOURCE_REQUEST = Array.from(
  { length: 36 },
  (_, requestLineIndex) =>
    `Line ${requestLineIndex + 1}: compare Java 25 cancellation, Spring 7.0.7 transactions, and jOOQ 3.20 using exact official sources.`,
).join("\n");

const ANSWER_TEXT =
  "The retained Java and Spring records support this `transaction` summary.\n\n" +
  "Note: Matching source documents for jOOQ 3.20 were not available to JavaChat, so this answer uses the model's general knowledge.";

const CITATIONS = [
  {
    url: "https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/concurrent/CompletableFuture.html",
    title: "CompletableFuture (Java SE 25 & JDK 25)",
    snippet: "Java 25 cancellation reference",
  },
  {
    url: "https://docs.spring.io/spring-framework/reference/data-access/transaction.html",
    title: "Spring Framework Transaction Management",
    snippet: "Spring transaction reference",
  },
];

async function openAnswerScreen(page: Page, theme: "dark" | "light"): Promise<void> {
  await page.addInitScript(
    ({ storageKey, selectedTheme }) => localStorage.setItem(storageKey, selectedTheme),
    { storageKey: THEME_PREFERENCE_STORAGE_KEY, selectedTheme: theme },
  );
  await page.route(CSRF_ENDPOINT, async (route) => {
    await route.fulfill({ status: 204 });
  });
  await page.route(CHAT_STREAM_ENDPOINT, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "text/event-stream",
      body:
        `event: text\ndata: ${JSON.stringify({ text: ANSWER_TEXT })}\n\n` +
        `event: citation\ndata: ${JSON.stringify(CITATIONS)}\n\n`,
    });
  });

  await page.goto("/");
  await page.getByLabel("Message input").fill(LONG_SOURCE_REQUEST);
  await page.getByLabel("Message input").press("Enter");
  await expect(page.getByRole("button", { name: "2 sources" })).toBeVisible();
}

for (const theme of ["dark", "light"] as const) {
  test(`${theme} answer screen exposes source coverage and passes WCAG A/AA`, async ({ page }) => {
    const pageErrors: Error[] = [];
    page.on("pageerror", (pageError) => pageErrors.push(pageError));

    await openAnswerScreen(page, theme);

    await expect(page.getByText(/Matching source documents for jOOQ 3\.20/)).toBeVisible();
    await page.getByRole("button", { name: "2 sources" }).click();
    await expect(page.getByRole("link", { name: /CompletableFuture/ })).toHaveAttribute(
      "href",
      /docs\.oracle\.com\/en\/java\/javase\/25/,
    );
    await expect(
      page.getByRole("link", { name: /Spring Framework Transaction Management/ }),
    ).toHaveAttribute("href", /docs\.spring\.io\/spring-framework\/reference/);

    const userBubble = page.locator(".message.user .bubble");
    await userBubble.focus();
    await expect(userBubble).toBeFocused();
    await userBubble.press("PageDown");
    await expect.poll(() => userBubble.evaluate((bubble) => bubble.scrollTop)).toBeGreaterThan(0);

    const accessibilityScan = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
      .analyze();
    expect(accessibilityScan.violations).toEqual([]);
    expect(pageErrors).toEqual([]);
  });
}

test("multi-turn chat exposes uniquely named message landmarks", async ({ page }) => {
  await openAnswerScreen(page, "dark");
  await page.getByLabel("Message input").fill("Summarize that comparison in one sentence.");
  await page.getByLabel("Message input").press("Enter");
  await expect(page.locator(".message.user")).toHaveCount(2);

  const accessibilityScan = await new AxeBuilder({ page }).withRules(["landmark-unique"]).analyze();
  expect(accessibilityScan.violations).toEqual([]);
});

test("CLI authorization keeps one top-level main landmark", async ({ page }) => {
  await page.goto(
    "/cli/authorize?port=49152&state=0123456789abcdefghijklmnopqrstuvwxyz_AB&label=workstation",
  );
  await expect(page.getByRole("heading", { name: "Authorize JavaChat CLI" })).toBeVisible();

  const accessibilityScan = await new AxeBuilder({ page })
    .withRules(["landmark-main-is-top-level", "landmark-no-duplicate-main"])
    .analyze();
  expect(accessibilityScan.violations).toEqual([]);
});
