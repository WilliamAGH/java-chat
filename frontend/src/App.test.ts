import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render } from "@testing-library/svelte";
import { tick } from "svelte";
import { pageMetadataForPath } from "./lib/services/pageMetadata";

const refreshCsrfTokenMock = vi.fn(async () => true);
const CHAT_CANONICAL_PATH = "/";
const LEARN_CANONICAL_PATH = "/learn";

vi.mock("./lib/services/csrf", async () => {
  const actualCsrfService =
    await vi.importActual<typeof import("./lib/services/csrf")>("./lib/services/csrf");
  return {
    ...actualCsrfService,
    refreshCsrfToken: refreshCsrfTokenMock,
  };
});

function expectCurrentRouteMetadata(expectedCanonicalPath: string): void {
  const expectedPageMetadata = pageMetadataForPath(globalThis.location.pathname);
  const expectedCanonicalUrl = `${globalThis.location.origin}${expectedCanonicalPath}`;

  expect(document.title).toBe(expectedPageMetadata.title);
  expect(
    document.head
      .querySelector<HTMLLinkElement>('link[data-seo="canonical"]')
      ?.getAttribute("href"),
  ).toBe(expectedCanonicalUrl);
  expect(
    document.head
      .querySelector<HTMLMetaElement>('meta[property="og:title"]')
      ?.getAttribute("content"),
  ).toBe(expectedPageMetadata.title);
  expect(
    document.head
      .querySelector<HTMLMetaElement>('meta[property="og:description"]')
      ?.getAttribute("content"),
  ).toBe(expectedPageMetadata.description);
  expect(
    document.head
      .querySelector<HTMLMetaElement>('meta[property="og:url"]')
      ?.getAttribute("content"),
  ).toBe(expectedCanonicalUrl);
  expect(
    document.head
      .querySelector<HTMLMetaElement>('meta[name="twitter:title"]')
      ?.getAttribute("content"),
  ).toBe(expectedPageMetadata.title);
  expect(
    document.head
      .querySelector<HTMLMetaElement>('meta[name="twitter:description"]')
      ?.getAttribute("content"),
  ).toBe(expectedPageMetadata.description);
  expect(
    document.head.querySelector<HTMLScriptElement>("#java-chat-structured-data")?.textContent,
  ).toContain(`"url":"${expectedCanonicalUrl}"`);
}

beforeEach(() => {
  refreshCsrfTokenMock.mockClear();
  globalThis.history.replaceState({}, "", "/");
  document.head
    .querySelectorAll(
      'meta[name="description"], link[data-seo="canonical"], meta[property^="og:"], meta[name^="twitter:"], #java-chat-structured-data',
    )
    .forEach((documentMetadata) => documentMetadata.remove());
  document.title = "";
});

describe("App CSRF preflight", () => {
  it("refreshes CSRF token on app mount", async () => {
    const App = (await import("./App.svelte")).default;
    render(App);

    expect(refreshCsrfTokenMock).toHaveBeenCalledTimes(1);
  });
});

describe("App route synchronization", () => {
  it("honors a direct learn route with a trailing slash", async () => {
    globalThis.history.replaceState({}, "", "/learn/");
    const App = (await import("./App.svelte")).default;
    const application = render(App);

    expect(await application.findByRole("heading", { name: "Learn Java" })).toBeInTheDocument();
    expect(application.getByRole("tab", { name: "Learn" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(globalThis.location.pathname).toBe("/learn/");
    expectCurrentRouteMetadata(LEARN_CANONICAL_PATH);
  });

  it("preserves a direct guided route while synchronizing its metadata", async () => {
    globalThis.history.replaceState({}, "", "/guided");
    const App = (await import("./App.svelte")).default;
    const application = render(App);

    expect(await application.findByRole("heading", { name: "Learn Java" })).toBeInTheDocument();
    expect(application.getByRole("tab", { name: "Learn" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(globalThis.location.pathname).toBe("/guided");
    expectCurrentRouteMetadata(LEARN_CANONICAL_PATH);
  });

  it("recovers an unimplemented direct learn descendant to the canonical learn route", async () => {
    globalThis.history.replaceState({}, "", "/learn/not-a-lesson/");
    const App = (await import("./App.svelte")).default;
    const application = render(App);

    expect(await application.findByRole("heading", { name: "Learn Java" })).toBeInTheDocument();
    expect(application.getByRole("tab", { name: "Learn" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(globalThis.location.pathname).toBe(LEARN_CANONICAL_PATH);
    expectCurrentRouteMetadata(LEARN_CANONICAL_PATH);
  });

  it("honors a direct chat alias", async () => {
    globalThis.history.replaceState({}, "", "/chat");
    const App = (await import("./App.svelte")).default;
    const application = render(App);

    await tick();

    expect(application.getByRole("tab", { name: "Chat" })).toHaveAttribute("aria-selected", "true");
    expect(globalThis.location.pathname).toBe("/chat");
    expectCurrentRouteMetadata(CHAT_CANONICAL_PATH);
  });

  it("projects an unknown route to the chat canonical metadata", async () => {
    globalThis.history.replaceState({}, "", "/unknown-route/");
    const App = (await import("./App.svelte")).default;
    const application = render(App);

    await tick();

    expect(application.getByRole("tab", { name: "Chat" })).toHaveAttribute("aria-selected", "true");
    expect(globalThis.location.pathname).toBe("/unknown-route/");
    expectCurrentRouteMetadata(CHAT_CANONICAL_PATH);
  });

  it("synchronizes tab selection, canonical paths, and document metadata", async () => {
    const App = (await import("./App.svelte")).default;
    const application = render(App);

    await fireEvent.click(application.getByRole("tab", { name: "Learn" }));
    expect(globalThis.location.pathname).toBe("/learn");
    expectCurrentRouteMetadata(LEARN_CANONICAL_PATH);

    await fireEvent.click(application.getByRole("tab", { name: "Chat" }));
    expect(globalThis.location.pathname).toBe("/");
    expectCurrentRouteMetadata(CHAT_CANONICAL_PATH);
  });

  it("restores the selected tab and metadata when browser history changes", async () => {
    const App = (await import("./App.svelte")).default;
    const application = render(App);

    globalThis.history.replaceState({}, "", "/guided/");
    globalThis.dispatchEvent(new PopStateEvent("popstate"));

    expect(await application.findByRole("tab", { name: "Learn" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expectCurrentRouteMetadata(LEARN_CANONICAL_PATH);

    globalThis.history.replaceState({}, "", "/chat/");
    globalThis.dispatchEvent(new PopStateEvent("popstate"));

    expect(await application.findByRole("tab", { name: "Chat" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expectCurrentRouteMetadata(CHAT_CANONICAL_PATH);
  });

  it("recovers an unimplemented guided descendant on browser history navigation", async () => {
    const App = (await import("./App.svelte")).default;
    const application = render(App);

    globalThis.history.replaceState({}, "", "/guided/not-a-lesson/");
    globalThis.dispatchEvent(new PopStateEvent("popstate"));

    expect(await application.findByRole("heading", { name: "Learn Java" })).toBeInTheDocument();
    expect(application.getByRole("tab", { name: "Learn" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(globalThis.location.pathname).toBe(LEARN_CANONICAL_PATH);
    expectCurrentRouteMetadata(LEARN_CANONICAL_PATH);
  });
});
