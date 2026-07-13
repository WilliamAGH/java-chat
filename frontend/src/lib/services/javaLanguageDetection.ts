/** Keywords indicating Java code for auto-detection. */
const JAVA_KEYWORDS = new Set<string>([
  "public",
  "private",
  "class",
  "import",
  "void",
  "String",
  "int",
  "boolean",
]);

/** Java's identifier-ignorable ISO control character ranges. */
const JAVA_IDENTIFIER_IGNORABLE_CONTROL_RANGES = "\\u0000-\\u0008\\u000E-\\u001B\\u007F-\\u009F";

/** Matches Java identifier parts, including identifier-ignorable characters. */
const JAVA_IDENTIFIER_PART_PATTERN = new RegExp(
  `[\\p{L}\\p{Nl}\\p{Sc}\\p{Pc}\\p{Nd}\\p{Mc}\\p{Mn}\\p{Cf}${JAVA_IDENTIFIER_IGNORABLE_CONTROL_RANGES}]`,
  "u",
);

/** CSS class applied to detected Java code blocks for syntax highlighting. */
const JAVA_LANGUAGE_CLASS = "language-java";

/** Selector for unmarked code blocks eligible for language detection. */
const UNMARKED_CODE_SELECTOR = "pre > code:not([class])";

/** Determines whether the code text contains a standalone Java keyword token. */
function containsJavaKeyword(codeText: string): boolean {
  let javaIdentifier = "";

  for (const codeCharacter of codeText) {
    if (JAVA_IDENTIFIER_PART_PATTERN.test(codeCharacter)) {
      javaIdentifier += codeCharacter;
      continue;
    }

    if (JAVA_KEYWORDS.has(javaIdentifier)) {
      return true;
    }

    javaIdentifier = "";
  }

  return JAVA_KEYWORDS.has(javaIdentifier);
}

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
    if (containsJavaKeyword(codeText)) {
      codeBlock.className = JAVA_LANGUAGE_CLASS;
    }
  });
}
