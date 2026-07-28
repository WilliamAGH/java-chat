import { describe, expect, it } from "vitest";
import { parseMarkdown } from "./markdown";

function renderMarkdown(markdown: string, isStreaming: boolean): HTMLDivElement {
  const renderedContainer = document.createElement("div");
  renderedContainer.innerHTML = parseMarkdown(markdown, isStreaming);
  return renderedContainer;
}

describe("numeric list fence nesting", () => {
  it("keeps fenced template code inside numeric list items", () => {
    for (const numericListMarker of ["1.", "12.", "100.", "123456789."]) {
      const requiredIndentation = " ".repeat(numericListMarker.length + 1);
      for (const sourceIndentation of ["", requiredIndentation]) {
        const markdown = [
          `${numericListMarker} Template example`,
          `${sourceIndentation}\`\`\`text`,
          `${sourceIndentation}{{name}}`,
          `${sourceIndentation}Goodbye`,
          `${sourceIndentation}\`\`\``,
        ].join("\n");

        for (const isStreaming of [false, true]) {
          const renderedContainer = renderMarkdown(markdown, isStreaming);
          const nestedCodeBlocks = renderedContainer.querySelectorAll("ol > li pre > code");

          expect(nestedCodeBlocks).toHaveLength(1);
          expect(nestedCodeBlocks[0].textContent).toContain("{{name}}\nGoodbye");
          expect(renderedContainer.querySelectorAll(":scope > pre")).toHaveLength(0);
        }
      }
    }
  });

  it("keeps fence-like lines literal inside nested template code", () => {
    const markdownLines = [
      "1. Template example",
      "```text",
      "{{name}}",
      "```ruby",
      "Goodbye",
      "```",
    ];

    for (const lineSeparator of ["\n", "\r\n"]) {
      const markdown = markdownLines.join(lineSeparator);
      for (const isStreaming of [false, true]) {
        const renderedContainer = renderMarkdown(markdown, isStreaming);
        const nestedCodeBlocks = renderedContainer.querySelectorAll("ol > li pre > code");

        expect(nestedCodeBlocks).toHaveLength(1);
        expect(nestedCodeBlocks[0].textContent).toContain("{{name}}\n```ruby\nGoodbye");
        expect(renderedContainer.querySelectorAll(":scope > pre")).toHaveLength(0);
      }
    }
  });

  it("does not reparent fences after numeral-looking paragraph text", () => {
    const markdown = [
      "Before explanation.",
      "123456789. This remains paragraph text.",
      "```java",
      "int answer = 1;",
      "```",
    ].join("\n");

    for (const isStreaming of [false, true]) {
      const renderedContainer = renderMarkdown(markdown, isStreaming);

      expect(renderedContainer.querySelectorAll("ol")).toHaveLength(0);
      expect(renderedContainer.querySelector("p")?.textContent).toContain(
        "123456789. This remains paragraph text.",
      );
      expect(renderedContainer.querySelectorAll(":scope > pre > code")).toHaveLength(1);
    }
  });

  it("keeps mixed-indentation backtick and tilde fences in one nested block", () => {
    for (const fenceMarker of ["```", "~~~"]) {
      for (const sourceIndentation of [
        { opening: "  ", closing: "     " },
        { opening: "      ", closing: "   " },
      ]) {
        const markdown = [
          "1. Template example",
          `${sourceIndentation.opening}${fenceMarker}text`,
          `${sourceIndentation.opening}{{name}}`,
          `${sourceIndentation.closing}${fenceMarker}`,
        ].join("\n");

        for (const isStreaming of [false, true]) {
          const renderedContainer = renderMarkdown(markdown, isStreaming);

          expect(renderedContainer.querySelectorAll("ol > li pre > code")).toHaveLength(1);
          expect(renderedContainer.querySelectorAll(":scope > pre")).toHaveLength(0);
        }
      }
    }
  });

  it("repairs fences in consecutive list items and after headings", () => {
    const consecutiveItems = [
      "1. First",
      "```text",
      "first",
      "```",
      "2. Second",
      "```text",
      "second",
      "```",
    ].join("\n");
    const afterHeading = [
      "# Template heading",
      "14. Template example",
      "```text",
      "{{name}}",
      "```",
    ].join("\n");

    for (const isStreaming of [false, true]) {
      const consecutiveContainer = renderMarkdown(consecutiveItems, isStreaming);
      const headingContainer = renderMarkdown(afterHeading, isStreaming);

      expect(consecutiveContainer.querySelectorAll("ol > li pre > code")).toHaveLength(2);
      expect(consecutiveContainer.querySelectorAll(":scope > pre")).toHaveLength(0);
      expect(headingContainer.querySelector("h1")?.textContent).toBe("Template heading");
      expect(headingContainer.querySelectorAll("ol > li pre > code")).toHaveLength(1);
    }
  });

  it("keeps fenced code inside ordered lists nested under unordered items", () => {
    const markdown = [
      "- Outer item",
      "  1. Nested template",
      "  ```text",
      "  {{name}}",
      "  ```",
    ].join("\n");

    for (const isStreaming of [false, true]) {
      const renderedContainer = renderMarkdown(markdown, isStreaming);

      expect(renderedContainer.querySelectorAll("ul > li > ol > li pre > code")).toHaveLength(1);
      expect(renderedContainer.querySelectorAll("ul > li > pre")).toHaveLength(0);
    }
  });
});
