import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, fireEvent } from "@testing-library/svelte";
import { tick } from "svelte";
import CitationPanel from "./CitationPanel.svelte";
import type { Citation } from "../services/chat";

const SINGLE_CITATION: Citation[] = [
  {
    url: "https://example.com/pdfs/think-java.pdf",
    title: "Think Java: How to Think Like a Computer Scientist",
    snippet: "Think Java 2nd Edition Book",
  },
];

const LIVE_PDF_CITATION: Citation[] = [
  {
    url: "/pdfs/Think Java - 2nd Edition Book.pdf",
    anchor: "page=42",
    title: "Think Java: How to Think Like a Computer Scientist",
    snippet: "Think Java 2nd Edition Book",
  },
];

const SHARED_PAGE_CITATIONS: Citation[] = [
  {
    url: "HTTPS://DOCS.example.com/String.html",
    anchor: "Foo()",
    title: "String Foo method",
    snippet: "Returns the Foo member.",
  },
  {
    url: "https://docs.example.com/string.html",
    anchor: "foo()",
    title: "String foo method",
    snippet: "Returns the foo member.",
  },
  {
    url: "https://docs.example.com/STRING.html",
    anchor: "Foo()",
    title: "Duplicate String Foo method",
    snippet: "Duplicates the Foo method citation.",
  },
  {
    url: "https://docs.example.com/string.html",
    anchor: "%3Cinit%3E()",
    title: "String constructor",
    snippet: "Creates a new string.",
  },
  {
    url: "https://docs.example.com/STRING.html",
    anchor: "%3Cinit%3E()",
    title: "Duplicate constructor citation",
    snippet: "Duplicates the constructor citation.",
  },
];

describe("CitationPanel", () => {
  // The prototype polyfill lives in src/test/setup.ts; spying here captures calls
  // from the panel revealing the expanded list inside its scroll container.
  const scrollIntoViewSpy = vi.spyOn(HTMLElement.prototype, "scrollIntoView");

  beforeEach(() => {
    scrollIntoViewSpy.mockClear();
  });

  it("expands the citation list when the trigger is clicked", async () => {
    const { getByRole, container } = render(CitationPanel, {
      props: { citations: SINGLE_CITATION },
    });

    const trigger = getByRole("button", { name: /1 source/i });
    expect(container.querySelector(".citation-list")).toBeNull();

    await fireEvent.click(trigger);
    await tick();

    expect(trigger).toHaveAttribute("aria-expanded", "true");
    expect(container.querySelector(".citation-list")).not.toBeNull();
  });

  it("scrolls the expanded list into view so it is never hidden below the fold", async () => {
    const { getByRole } = render(CitationPanel, {
      props: { citations: SINGLE_CITATION },
    });

    await fireEvent.click(getByRole("button", { name: /1 source/i }));
    await tick();

    expect(scrollIntoViewSpy).toHaveBeenCalledWith(expect.objectContaining({ block: "nearest" }));
  });

  it("does not scroll when collapsing the list", async () => {
    const { getByRole } = render(CitationPanel, {
      props: { citations: SINGLE_CITATION },
    });

    const trigger = getByRole("button", { name: /1 source/i });
    await fireEvent.click(trigger);
    await tick();
    scrollIntoViewSpy.mockClear();

    await fireEvent.click(trigger);
    await tick();

    expect(scrollIntoViewSpy).not.toHaveBeenCalled();
  });

  it("uses a decoded PDF source label without changing the citation href or page anchor", async () => {
    const { getByRole } = render(CitationPanel, {
      props: { citations: LIVE_PDF_CITATION },
    });

    await fireEvent.click(getByRole("button", { name: /1 source/i }));
    await tick();

    const citationLink = getByRole("link", {
      name: /Think Java: How to Think Like a Computer Scientist.*Think Java 2nd Edition Book/,
    });
    expect(citationLink).not.toHaveAccessibleName(/%20/);
    expect(citationLink).toHaveAttribute("href", "/pdfs/Think Java - 2nd Edition Book.pdf#page=42");
  });

  it("renders case-sensitive anchors separately while collapsing matching citation identities", async () => {
    const { getByRole, queryByText } = render(CitationPanel, {
      props: { citations: SHARED_PAGE_CITATIONS },
    });

    await fireEvent.click(getByRole("button", { name: /3 sources/i }));
    await tick();

    expect(getByRole("link", { name: /String Foo method/ })).toHaveAttribute(
      "href",
      "HTTPS://DOCS.example.com/String.html#Foo()",
    );
    expect(getByRole("link", { name: /String foo method/ })).toHaveAttribute(
      "href",
      "https://docs.example.com/string.html#foo()",
    );
    expect(getByRole("link", { name: /String constructor/ })).toHaveAttribute(
      "href",
      "https://docs.example.com/string.html#%3Cinit%3E()",
    );
    expect(queryByText("Duplicate String Foo method")).toBeNull();
    expect(queryByText("Duplicate constructor citation")).toBeNull();
  });
});
