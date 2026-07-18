import { afterEach, describe, expect, it, vi } from "vitest";
import { highlightCodeBlocks } from "./highlight";

const LESSON_LANGUAGE_EXAMPLES = [
  ["kotlin", "data class Greeting(val message: String)"],
  ["scala", "enum Greeting:\n  case Hello"],
  ["groovy", 'def greeting = "Hello"'],
  ["clojure", '(def greeting "Hello")'],
  ["properties", "server.port=8080"],
] as const;

function createLessonCodeBlock(
  language: string,
  sourceCode: string,
): {
  lessonContainer: HTMLDivElement;
  codeBlock: HTMLElement;
} {
  const lessonContainer = document.createElement("div");
  const codeBlock = document.createElement("code");
  codeBlock.className = `language-${language}`;
  codeBlock.textContent = sourceCode;
  const preformattedBlock = document.createElement("pre");
  preformattedBlock.append(codeBlock);
  lessonContainer.append(preformattedBlock);
  return { lessonContainer, codeBlock };
}

describe("highlightCodeBlocks", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it.each(LESSON_LANGUAGE_EXAMPLES)("highlights %s lesson fences", async (language, sourceCode) => {
    const { lessonContainer, codeBlock } = createLessonCodeBlock(language, sourceCode);

    await highlightCodeBlocks(lessonContainer);

    expect(codeBlock).toHaveClass("hljs");
    expect(codeBlock).toHaveClass(`language-${language}`);
    expect(codeBlock.innerHTML).not.toBe(sourceCode);
  });

  it("renders text lesson fences without unsupported-language warnings", async () => {
    const consoleLogMock = vi.spyOn(console, "log").mockImplementation(() => undefined);
    const lessonTrace = "factorial(4)\\n= 4 * factorial(3)";
    const { lessonContainer, codeBlock } = createLessonCodeBlock("text", lessonTrace);

    await highlightCodeBlocks(lessonContainer);

    expect(consoleLogMock).not.toHaveBeenCalled();
    expect(codeBlock).toHaveClass("hljs");
    expect(codeBlock.textContent).toBe(lessonTrace);
  });

  it("renders properties lesson fences without unsupported-language warnings", async () => {
    const consoleLogMock = vi.spyOn(console, "log").mockImplementation(() => undefined);
    const propertiesSource = "server.port=8080";
    const { lessonContainer, codeBlock } = createLessonCodeBlock("properties", propertiesSource);

    await highlightCodeBlocks(lessonContainer);

    expect(consoleLogMock).not.toHaveBeenCalled();
    expect(codeBlock).toHaveClass("hljs");
    expect(codeBlock.textContent).toBe(propertiesSource);
  });
});
