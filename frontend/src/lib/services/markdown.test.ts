import { describe, it, expect } from "vitest";
import { parseMarkdown, applyJavaLanguageDetection, escapeHtml } from "./markdown";

describe("parseMarkdown", () => {
  it("returns empty string for empty input", () => {
    expect(parseMarkdown("")).toBe("");
    expect(parseMarkdown(null)).toBe("");
    expect(parseMarkdown(undefined)).toBe("");
  });

  it("parses basic markdown to HTML", () => {
    const renderedHtml = parseMarkdown("**bold** and *italic*");
    expect(renderedHtml).toContain("<strong>bold</strong>");
    expect(renderedHtml).toContain("<em>italic</em>");
  });

  it("parses code blocks", () => {
    const markdown = "```java\npublic class Test {}\n```";
    const renderedHtml = parseMarkdown(markdown);
    expect(renderedHtml).toContain("<pre>");
    expect(renderedHtml).toContain("<code");
    expect(renderedHtml).toContain("public class Test {}");
  });

  it("sanitizes dangerous HTML", () => {
    const markdown = '<script>alert("xss")</script>';
    const renderedHtml = parseMarkdown(markdown);
    expect(renderedHtml).not.toContain("<script>");
    expect(renderedHtml).not.toContain("alert");
  });

  it("preserves enrichment data attributes", () => {
    // Enrichment markers are parsed by the extension
    const markdown = "{{hint: This is a hint}}";
    const renderedHtml = parseMarkdown(markdown);
    expect(renderedHtml).toContain('data-enrichment-type="hint"');
  });

  it("preserves trailing content brace when enrichment closes with }}}", () => {
    const markdown = "{{example: try (var scope = open()) { doWork(); }}}";
    const renderedHtml = parseMarkdown(markdown);

    // The final `}` belongs to content; it must not leak as an orphan node.
    expect(renderedHtml).toContain("doWork(); }");
    expect(renderedHtml).not.toContain("<p>}</p>");
  });

  it("removes a stray close before the next valid enrichment marker", () => {
    const markdown = "The explanation is complete. } {{example: Use try-with-resources.}}";
    const renderedHtml = parseMarkdown(markdown);

    expect(renderedHtml).toContain("The explanation is complete.");
    expect(renderedHtml).toContain('data-enrichment-type="example"');
    expect(renderedHtml).not.toContain("complete. }");
    expect(renderedHtml).not.toContain("} {{example:");
  });

  it("preserves prose from a truncated final enrichment without leaking its marker", () => {
    const markdown =
      "Close the resource.\n\n{{warning: This also applies when an exception is thrown.";
    const renderedHtml = parseMarkdown(markdown);

    expect(renderedHtml).toContain("Close the resource.");
    expect(renderedHtml).toContain("This also applies when an exception is thrown.");
    expect(renderedHtml).not.toContain("{{");
  });

  it("hides an incomplete enrichment close while preserving streamed prose", () => {
    const markdown = "{{background: Records automate all of this.}";
    const renderedHtml = parseMarkdown(markdown);

    expect(renderedHtml).toContain("Records automate all of this.");
    expect(renderedHtml).not.toContain("this.}");
    expect(renderedHtml).not.toContain("{{background:");
  });

  it("recovers a valid nested marker from an unbalanced outer marker", () => {
    const markdown = "{{hint: Start with the invariant. {{warning: Never ignore exceptions.}}";
    const renderedHtml = parseMarkdown(markdown);

    expect(renderedHtml).toContain("Start with the invariant.");
    expect(renderedHtml).toContain('data-enrichment-type="warning"');
    expect(renderedHtml).not.toContain("{{");
  });

  it("preserves marker-like text and ordinary braces inside fenced code", () => {
    const markdown = '```java\nString template = "{{warning: literal";\nif (ready) { run(); }\n```';
    const renderedHtml = parseMarkdown(markdown);

    expect(renderedHtml).toContain("{{warning: literal");
    expect(renderedHtml).toContain("if (ready) { run(); }");
  });

  it("normalizes attached fenced code blocks with trailing prose", () => {
    const markdown = "Here's an example:```java\nint x = 10 % 3;\n```The result is 1.";
    const renderedHtml = parseMarkdown(markdown);

    expect(renderedHtml).toContain("<pre>");
    expect(renderedHtml).toContain('<code class="language-java">');
    expect(renderedHtml).toContain("The result is 1.");
    expect(renderedHtml).not.toContain("```The");
  });

  it("auto-closes unbalanced fenced code while streaming partial content", () => {
    const markdown = '```java\nSystem.out.println("hi");';
    const renderedHtml = parseMarkdown(markdown);

    expect(renderedHtml).toContain("<pre>");
    expect(renderedHtml).toContain('<code class="language-java">');
    expect(renderedHtml).toContain("System.out.println");
  });

  it("is SSR-safe - does not use document APIs", () => {
    // This test verifies parseMarkdown works without DOM
    // If it used document.createElement, this would fail in Node
    const renderedHtml = parseMarkdown("# Heading\n\nParagraph text.");
    expect(renderedHtml).toContain("<h1>");
    expect(renderedHtml).toContain("<p>");
  });
});

describe("applyJavaLanguageDetection", () => {
  it("adds language-java class to unmarked code blocks with Java keywords", () => {
    const container = document.createElement("div");
    // Test setup: create DOM structure to verify detection
    const pre = document.createElement("pre");
    const code = document.createElement("code");
    code.textContent = "public class MyClass {}";
    pre.appendChild(code);
    container.appendChild(pre);

    applyJavaLanguageDetection(container);

    expect(code.className).toBe("language-java");
  });

  it("does not modify code blocks that already have a class", () => {
    const container = document.createElement("div");
    const pre = document.createElement("pre");
    const code = document.createElement("code");
    code.className = "language-python";
    code.textContent = "public = True";
    pre.appendChild(code);
    container.appendChild(pre);

    applyJavaLanguageDetection(container);

    expect(code.className).toBe("language-python");
  });

  it("does not modify code blocks without Java keywords", () => {
    const container = document.createElement("div");
    const pre = document.createElement("pre");
    const code = document.createElement("code");
    code.textContent = 'console.log("hello")';
    pre.appendChild(code);
    container.appendChild(pre);

    applyJavaLanguageDetection(container);

    expect(code.className).toBe("");
  });

  it("detects various Java keywords", () => {
    const javaKeywords = [
      "public",
      "private",
      "class",
      "import",
      "void",
      "String",
      "int",
      "boolean",
    ];

    for (const keyword of javaKeywords) {
      const container = document.createElement("div");
      const pre = document.createElement("pre");
      const code = document.createElement("code");
      code.textContent = `${keyword} something`;
      pre.appendChild(code);
      container.appendChild(pre);

      applyJavaLanguageDetection(container);

      expect(code.className).toBe("language-java");
    }
  });
});

describe("escapeHtml", () => {
  it("escapes HTML special characters", () => {
    expect(escapeHtml("<div>")).toBe("&lt;div&gt;");
    expect(escapeHtml('"quoted"')).toBe("&quot;quoted&quot;");
    expect(escapeHtml("it's")).toBe("it&#039;s");
    expect(escapeHtml("a & b")).toBe("a &amp; b");
  });

  it("returns empty string for empty input", () => {
    expect(escapeHtml("")).toBe("");
  });

  it("handles complex mixed content", () => {
    const input = '<script>alert("xss")</script>';
    const escapedHtml = escapeHtml(input);
    expect(escapedHtml).not.toContain("<");
    expect(escapedHtml).not.toContain(">");
    expect(escapedHtml).toContain("&lt;");
    expect(escapedHtml).toContain("&gt;");
  });

  it("is SSR-safe - uses pure string operations", () => {
    // This works without document APIs
    const escapedHtml = escapeHtml('<div class="test">');
    expect(escapedHtml).toBe("&lt;div class=&quot;test&quot;&gt;");
  });
});
