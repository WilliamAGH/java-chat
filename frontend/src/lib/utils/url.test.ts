import { describe, it, expect } from "vitest";
import {
  sanitizeUrl,
  buildFullUrl,
  deduplicateCitations,
  citationUrlIdentity,
  FALLBACK_SOURCE_LABEL,
  getCitationType,
  getDisplaySource,
} from "./url";

const PDF_CITATION_URL_CASES = [
  {
    scenario: "a query string",
    citationUrl: "https://example.com/Reference.pdf?edition=second",
    expectedSourceLabel: "Reference",
  },
  {
    scenario: "a page fragment",
    citationUrl: "https://example.com/Reference.pdf#page=7",
    expectedSourceLabel: "Reference",
  },
  {
    scenario: "both a query string and page fragment",
    citationUrl: "https://example.com/Reference.pdf?edition=second#page=7",
    expectedSourceLabel: "Reference",
  },
  {
    scenario: "an uppercase extension",
    citationUrl: "https://example.com/REFERENCE.PDF?edition=second#page=7",
    expectedSourceLabel: "REFERENCE",
  },
  {
    scenario: "a percent-encoded filename",
    citationUrl: "https://example.com/Think%20Java.pdf#page=1",
    expectedSourceLabel: "Think Java",
  },
  {
    scenario: "the live citation's literal-space filename and page fragment",
    citationUrl: "/pdfs/Think Java - 2nd Edition Book.pdf#page=42",
    expectedSourceLabel: "Think Java 2nd Edition Book",
  },
  {
    scenario: "a file URL",
    citationUrl: "file:///guides/Reference.pdf",
    expectedSourceLabel: "Reference",
  },
  {
    scenario: "a local URL path",
    citationUrl: "/guides/Reference.pdf",
    expectedSourceLabel: "Reference",
  },
  {
    scenario: "a relative URL path",
    citationUrl: "guides/Reference.pdf",
    expectedSourceLabel: "Reference",
  },
] as const;

describe("sanitizeUrl", () => {
  it("returns fallback for empty/null/undefined input", () => {
    expect(sanitizeUrl("")).toBe("#");
    expect(sanitizeUrl(null)).toBe("#");
    expect(sanitizeUrl(undefined)).toBe("#");
  });

  it("allows https URLs", () => {
    const url = "https://example.com/path";
    expect(sanitizeUrl(url)).toBe(url);
  });

  it("allows http URLs", () => {
    const url = "http://example.com/path";
    expect(sanitizeUrl(url)).toBe(url);
  });

  it("blocks javascript URLs", () => {
    expect(sanitizeUrl("javascript:alert(1)")).toBe("#");
  });

  it("blocks data URLs", () => {
    expect(sanitizeUrl("data:text/html,<script>alert(1)</script>")).toBe("#");
  });

  it("trims whitespace", () => {
    expect(sanitizeUrl("  https://example.com  ")).toBe("https://example.com");
  });
});

describe("buildFullUrl", () => {
  it("returns sanitized URL without anchor", () => {
    expect(buildFullUrl("https://example.com", undefined)).toBe("https://example.com");
    expect(buildFullUrl("https://example.com", "")).toBe("https://example.com");
  });

  it("appends anchor with hash", () => {
    expect(buildFullUrl("https://example.com", "section")).toBe("https://example.com#section");
  });

  it("preserves an encoded Java API anchor", () => {
    expect(buildFullUrl("https://example.com/String.html", "%3Cinit%3E()")).toBe(
      "https://example.com/String.html#%3Cinit%3E()",
    );
  });

  it("handles anchor - appends with hash separator", () => {
    // Note: buildFullUrl always prepends # to anchor, so #section becomes ##section
    // Callers should strip # from anchors before passing
    expect(buildFullUrl("https://example.com", "section")).toBe("https://example.com#section");
  });
});

describe("deduplicateCitations", () => {
  it("returns empty array for empty input", () => {
    expect(deduplicateCitations([])).toEqual([]);
  });

  it("returns empty array for null/undefined input", () => {
    expect(deduplicateCitations(null)).toEqual([]);
    expect(deduplicateCitations(undefined)).toEqual([]);
  });

  it("removes duplicate URLs", () => {
    const citations = [
      { url: "https://a.com", title: "A" },
      { url: "https://b.com", title: "B" },
      { url: "https://a.com", title: "A duplicate" },
    ];
    const deduplicatedCitations = deduplicateCitations(citations);
    expect(deduplicatedCitations).toHaveLength(2);
    expect(deduplicatedCitations.map((citation) => citation.url)).toEqual([
      "https://a.com",
      "https://b.com",
    ]);
  });

  it("keeps first occurrence when deduplicating", () => {
    const citations = [
      { url: "https://a.com", title: "First" },
      { url: "https://a.com", title: "Second" },
    ];
    const deduplicatedCitations = deduplicateCitations(citations);
    expect(deduplicatedCitations[0].title).toBe("First");
  });

  it("preserves case-sensitive and encoded anchors while collapsing matching identities", () => {
    const citations = [
      {
        url: "HTTPS://DOCS.example.com/String.html",
        anchor: "Foo()",
        title: "String Foo method",
      },
      {
        url: "https://docs.example.com/string.html",
        anchor: "foo()",
        title: "String foo method",
      },
      {
        url: "https://docs.example.com/STRING.html",
        anchor: "Foo()",
        title: "Duplicate String Foo method",
      },
      {
        url: "https://docs.example.com/string.html",
        anchor: "%3Cinit%3E()",
        title: "String constructor",
      },
      {
        url: "https://docs.example.com/STRING.html",
        anchor: "%3Cinit%3E()",
        title: "Duplicate constructor citation",
      },
    ];

    const deduplicatedCitations = deduplicateCitations(citations);

    expect(deduplicatedCitations.map((citation) => citation.anchor)).toEqual([
      "Foo()",
      "foo()",
      "%3Cinit%3E()",
    ]);
    expect(deduplicatedCitations.map((citation) => citation.title)).toEqual([
      "String Foo method",
      "String foo method",
      "String constructor",
    ]);
  });

  it("normalizes only the base URL in citation identities", () => {
    const uppercaseAnchorIdentity = citationUrlIdentity(
      "HTTPS://DOCS.example.com/String.html",
      "Foo()",
    );
    const lowercaseAnchorIdentity = citationUrlIdentity(
      "https://docs.example.com/string.html",
      "foo()",
    );

    expect(uppercaseAnchorIdentity).toBe("https://docs.example.com/string.html#Foo()");
    expect(lowercaseAnchorIdentity).toBe("https://docs.example.com/string.html#foo()");
    expect(uppercaseAnchorIdentity).not.toBe(lowercaseAnchorIdentity);
  });
});

describe("getCitationType", () => {
  it("detects PDF files", () => {
    expect(getCitationType("https://example.com/doc.pdf")).toBe("pdf");
    expect(getCitationType("https://example.com/DOC.PDF")).toBe("pdf");
  });

  it("detects API documentation", () => {
    expect(getCitationType("https://docs.oracle.com/javase/8/docs/api/")).toBe("api-doc");
    expect(getCitationType("https://developer.mozilla.org/api/something")).toBe("api-doc");
  });

  it("detects repositories", () => {
    expect(getCitationType("https://github.com/user/repo")).toBe("repo");
    expect(getCitationType("https://gitlab.com/user/repo")).toBe("repo");
  });

  it("returns external for generic URLs", () => {
    expect(getCitationType("https://example.com")).toBe("external");
    expect(getCitationType("https://blog.example.com/post")).toBe("external");
  });
});

describe("PDF citation URL metadata", () => {
  for (const pdfCitationExpectation of PDF_CITATION_URL_CASES) {
    it(`uses the PDF icon and filename for ${pdfCitationExpectation.scenario}`, () => {
      expect(getCitationType(pdfCitationExpectation.citationUrl)).toBe("pdf");
      expect(getDisplaySource(pdfCitationExpectation.citationUrl)).toBe(
        pdfCitationExpectation.expectedSourceLabel,
      );
    });
  }
});

describe("getDisplaySource", () => {
  it("extracts hostname from URL", () => {
    expect(getDisplaySource("https://docs.oracle.com/javase/8/")).toBe("docs.oracle.com");
    expect(getDisplaySource("HTTPS://EXAMPLE.COM/guide")).toBe("example.com");
  });

  it("returns fallback label for invalid URLs", () => {
    expect(getDisplaySource("not-a-url")).toBe("Source");
    expect(getDisplaySource("")).toBe("Source");
    expect(getDisplaySource(null)).toBe("Source");
  });

  it("returns the fallback label for malformed percent-encoded PDF filenames", () => {
    expect(getDisplaySource("https://example.com/Think%ZZJava.pdf")).toBe(FALLBACK_SOURCE_LABEL);
  });
});
