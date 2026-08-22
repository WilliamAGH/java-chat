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
  "Source unavailable: jOOQ 3.20 official documentation. The jOOQ note below is general knowledge and is not verified by the Sources panel.";

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

    await expect(
      page.getByText("Source unavailable: jOOQ 3.20 official documentation."),
    ).toBeVisible();
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
