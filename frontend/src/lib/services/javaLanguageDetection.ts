/** Keywords indicating Java code for auto-detection. */
const JAVA_KEYWORDS = [
  "public",
  "private",
  "class",
  "import",
  "void",
  "String",
  "int",
  "boolean",
] as const;

/** CSS class applied to detected Java code blocks for syntax highlighting. */
const JAVA_LANGUAGE_CLASS = "language-java";

/** Selector for unmarked code blocks eligible for language detection. */
const UNMARKED_CODE_SELECTOR = "pre > code:not([class])";

/** Adds Java highlighting metadata to unmarked code blocks that contain Java keywords. */
export function applyJavaLanguageDetection(container: HTMLElement | null | undefined): void {
  if (!container || typeof container.querySelectorAll !== "function") {
    if (import.meta.env.DEV) {
      console.warn(
        "[markdown] applyJavaLanguageDetection called with invalid container:",
        container,
      );
    }
    return;
  }

  const codeBlocks = container.querySelectorAll(UNMARKED_CODE_SELECTOR);
  codeBlocks.forEach((codeBlock) => {
    const codeText = codeBlock.textContent ?? "";
    if (JAVA_KEYWORDS.some((javaKeyword) => codeText.includes(javaKeyword))) {
      codeBlock.className = JAVA_LANGUAGE_CLASS;
    }
  });
}
