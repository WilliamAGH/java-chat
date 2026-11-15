import { Marked } from "marked";
import { describe, expect, it, vi } from "vitest";
import { nestNumericListFences } from "./numericListFenceNesting";
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

    for (const lineSeparator of ["\n", "\r\n", "\r"]) {
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

  it("preserves bare fences past CommonMark closing indentation as content after a column-zero opening fence", () => {
    for (const fenceMarker of ["```", "~~~"]) {
      for (const closingOffset of [1, 2, 3, 4]) {
        const closingIndentation = " ".repeat(3 + closingOffset);
        const markdown = [
          "1. Template example",
          `${fenceMarker}text`,
          "template body",
          `${closingIndentation}${fenceMarker}`,
        ].join("\n");

        for (const isStreaming of [false, true]) {
          const renderedContainer = renderMarkdown(markdown, isStreaming);
          const nestedCodeBlocks = renderedContainer.querySelectorAll("ol > li pre > code");

          expect(nestedCodeBlocks).toHaveLength(1);
          expect(nestedCodeBlocks[0].textContent).toContain("template body");
          expect(nestedCodeBlocks[0].textContent?.includes(fenceMarker)).toBe(true);
          expect(renderedContainer.querySelectorAll(":scope > pre")).toHaveLength(0);
        }
      }
    }
  });

  it("keeps an indented bare fence used as content nested inside the list item", () => {
    const markdown = ["1. Example of nested:", "```text", "    ```", "more", "```"].join("\n");

    for (const isStreaming of [false, true]) {
      const renderedContainer = renderMarkdown(markdown, isStreaming);
      const nestedCodeBlocks = renderedContainer.querySelectorAll("ol > li pre > code");

      expect(nestedCodeBlocks).toHaveLength(1);
      expect(nestedCodeBlocks[0].textContent).toContain("```\nmore");
      expect(renderedContainer.querySelectorAll(":scope > pre")).toHaveLength(0);
      expect(renderedContainer.querySelectorAll(":scope > p")).toHaveLength(0);
    }
  });

  it("keeps indented bare-fence content nested for deep numeric markers", () => {
    for (const numericListMarker of ["14.", "123."]) {
      const requiredIndentation = " ".repeat(numericListMarker.length + 1);
      const markdown = [
        `${numericListMarker} Item`,
        "```text",
        `${requiredIndentation}\`\`\``,
        "more",
        "```",
      ].join("\n");

      for (const isStreaming of [false, true]) {
        const renderedContainer = renderMarkdown(markdown, isStreaming);
        const nestedCodeBlocks = renderedContainer.querySelectorAll("ol > li pre > code");
        const orderedLists = renderedContainer.querySelectorAll("ol");

        expect(orderedLists).toHaveLength(1);
        expect(nestedCodeBlocks).toHaveLength(1);
        expect(nestedCodeBlocks[0].textContent).toContain("```\nmore");
        expect(renderedContainer.querySelectorAll(":scope > pre")).toHaveLength(0);
        expect(renderedContainer.querySelectorAll(":scope > p")).toHaveLength(0);
      }
    }
  });

  it("still closes the block at CommonMark-valid closing indentation (offset 0 to 3)", () => {
    for (const fenceMarker of ["```", "~~~"]) {
      for (const closingSpaces of [0, 1, 2, 3]) {
        const markdown = [
          "1. Template example",
          `${fenceMarker}text`,
          "template body",
          `${" ".repeat(closingSpaces)}${fenceMarker}`,
        ].join("\n");

        for (const isStreaming of [false, true]) {
          const renderedContainer = renderMarkdown(markdown, isStreaming);
          const nestedCodeBlocks = renderedContainer.querySelectorAll("ol > li pre > code");

          expect(nestedCodeBlocks).toHaveLength(1);
          expect(nestedCodeBlocks[0].textContent).toContain("template body");
          expect(nestedCodeBlocks[0].textContent?.includes(fenceMarker)).toBe(false);
          expect(renderedContainer.querySelectorAll(":scope > pre")).toHaveLength(0);
          expect(renderedContainer.querySelectorAll(":scope > p")).toHaveLength(0);
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

  it("uses one structural lex pass for many ordered list markers", () => {
    const markdown = Array.from(
      { length: 200 },
      (_, listNumber) => `${listNumber + 1}. Template ${listNumber}\n\`\`\`text\n{{name}}\n\`\`\``,
    ).join("\n");
    const structuralMarkdownParser = new Marked({ gfm: true, breaks: true });
    const structuralLexSpy = vi.spyOn(structuralMarkdownParser, "lexer");

    const nestedMarkdown = nestNumericListFences(markdown, structuralMarkdownParser);

    expect(nestedMarkdown).toContain("1. Template 0\n   ```text\n   {{name}}\n   ```");
    expect(nestedMarkdown).toContain("200. Template 199\n     ```text\n     {{name}}\n     ```");
    expect(structuralLexSpy).toHaveBeenCalledOnce();
  });
});
