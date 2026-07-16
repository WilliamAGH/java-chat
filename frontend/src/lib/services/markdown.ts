import { Marked, type TokenizerExtension, type RendererExtension, type Tokens } from "marked";
import DOMPurify from "dompurify";

/**
 * Enrichment kinds with their display metadata.
 * Matches server-side EnrichmentPlaceholderizer for consistent rendering.
 */
const ENRICHMENT_KINDS: Record<string, { title: string; icon: string }> = {
  hint: {
    title: "Helpful Hints",
    icon: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2a7 7 0 0 0-7 7c0 2.59 1.47 4.84 3.63 6.02L9 18h6l.37-2.98A7.01 7.01 0 0 0 19 9a7 7 0 0 0-7-7zm-3 19h6v1H9v-1z"/></svg>',
  },
  background: {
    title: "Background Context",
    icon: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M4 6h16v2H4zM4 10h16v2H4zM4 14h16v2H4z"/></svg>',
  },
  reminder: {
    title: "Important Reminders",
    icon: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 22a2 2 0 0 0 2-2H10a2 2 0 0 0 2 2zm6-6v-5a6 6 0 0 0-4-5.65V4a2 2 0 0 0-4 0v1.35A6 6 0 0 0 6 11v5l-2 2v1h16v-1l-2-2z"/></svg>',
  },
  warning: {
    title: "Warning",
    icon: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2V7h2v7z"/></svg>',
  },
  example: {
    title: "Example",
    icon: '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2zm1 15h-2v-6h2zm0-8h-2V7h2z"/></svg>',
  },
};

interface EnrichmentToken extends Tokens.Generic {
  type: "enrichment";
  raw: string;
  kind: string;
  content: string;
  resolved: boolean;
}

type EnrichmentOpening = { kind: string; length: number };

/** Pattern matching code fence delimiters (3+ backticks or tildes at line start). */
const FENCE_PATTERN = /^[ \t]*(`{3,}|~{3,})/;
const FENCE_MIN_LENGTH = 3;
const NEWLINE = "\n";
const ASCII_DIGIT_START = 48;
const ASCII_DIGIT_END = 57;
const ASCII_UPPERCASE_START = 65;
const ASCII_UPPERCASE_END = 90;
const ASCII_LOWERCASE_START = 97;
const ASCII_LOWERCASE_END = 122;

type FenceMarker = { character: string; length: number };

function scanFenceMarker(src: string, index: number): FenceMarker | null {
  if (index < 0 || index >= src.length) {
    return null;
  }
  const markerChar = src[index];
  if (markerChar !== "`" && markerChar !== "~") {
    return null;
  }

  let markerLength = 0;
  while (index + markerLength < src.length && src[index + markerLength] === markerChar) {
    markerLength++;
  }

  if (markerLength < FENCE_MIN_LENGTH) {
    return null;
  }

  return { character: markerChar, length: markerLength };
}

function isFenceLanguageCharacter(character: string): boolean {
  if (character.length !== 1) {
    return false;
  }
  const charCode = character.charCodeAt(0);
  const isLowerAlpha = charCode >= ASCII_LOWERCASE_START && charCode <= ASCII_LOWERCASE_END;
  const isUpperAlpha = charCode >= ASCII_UPPERCASE_START && charCode <= ASCII_UPPERCASE_END;
  const isDigit = charCode >= ASCII_DIGIT_START && charCode <= ASCII_DIGIT_END;
  return isLowerAlpha || isUpperAlpha || isDigit || character === "-" || character === "_";
}

function isAttachedFenceStart(src: string, index: number): boolean {
  if (index <= 0 || index >= src.length) {
    return false;
  }
  return !/\s/.test(src[index - 1]);
}

function appendLineBreakIfNeeded(text: string): string {
  if (text.length === 0 || text.endsWith(NEWLINE)) {
    return text;
  }
  return `${text}${NEWLINE}`;
}

/** Result of consuming a fence marker and its trailing language tag or newline. */
type ConsumedFence = { text: string; nextCursor: number };

/** Consumes an opening fence marker plus any language tag, ensuring a trailing newline. */
function consumeOpeningFence(content: string, cursor: number, marker: FenceMarker): ConsumedFence {
  let text = content.slice(cursor, cursor + marker.length);
  let pos = cursor + marker.length;

  while (pos < content.length && isFenceLanguageCharacter(content[pos])) {
    text += content[pos];
    pos++;
  }
  if (pos < content.length && content[pos] !== NEWLINE) {
    text += NEWLINE;
  }
  return { text, nextCursor: pos };
}

/** Consumes a closing fence marker, ensuring a trailing newline. */
function consumeClosingFence(content: string, cursor: number, marker: FenceMarker): ConsumedFence {
  const text = content.slice(cursor, cursor + marker.length);
  const pos = cursor + marker.length;
  const suffix = pos < content.length && content[pos] !== NEWLINE ? NEWLINE : "";
  return { text: text + suffix, nextCursor: pos };
}

/**
 * Repairs malformed fence placement commonly produced during streaming:
 * - attached starts like "Example:```java"
 * - attached closes like "```After"
 * - missing closing fence at end-of-stream
 */
function normalizeMarkdownForStreaming(content: string): string {
  if (!content) {
    return "";
  }

  let normalized = "";
  let inFence = false;
  let fenceChar = "";
  let fenceLength = 0;

  for (let cursor = 0; cursor < content.length; ) {
    const startOfLine = cursor === 0 || content[cursor - 1] === NEWLINE;
    const marker = scanFenceMarker(content, cursor);

    if (marker && !inFence && (startOfLine || isAttachedFenceStart(content, cursor))) {
      normalized = appendLineBreakIfNeeded(normalized);
      const consumed = consumeOpeningFence(content, cursor, marker);
      normalized += consumed.text;
      cursor = consumed.nextCursor;
      inFence = true;
      fenceChar = marker.character;
      fenceLength = marker.length;
      continue;
    }

    if (
      marker &&
      inFence &&
      startOfLine &&
      marker.character === fenceChar &&
      marker.length >= fenceLength
    ) {
      normalized = appendLineBreakIfNeeded(normalized);
      const consumed = consumeClosingFence(content, cursor, marker);
      normalized += consumed.text;
      cursor = consumed.nextCursor;
      inFence = false;
      fenceChar = "";
      fenceLength = 0;
      continue;
    }

    normalized += content[cursor];
    cursor++;
  }

  if (inFence && fenceChar) {
    normalized += `${NEWLINE}${fenceChar.repeat(Math.max(fenceLength, FENCE_MIN_LENGTH))}`;
  }

  return normalized;
}

/**
 * Checks whether code fences in content are balanced.
 * Uses simple line-by-line scan with toggle semantics.
 */
function hasBalancedCodeFences(content: string): boolean {
  let depth = 0;
  let openChar = "";
  let openLen = 0;
  for (const line of content.split("\n")) {
    const match = line.match(FENCE_PATTERN);
    if (!match) continue;
    const fence = match[1];
    if (depth === 0) {
      // Opening fence
      depth = 1;
      openChar = fence[0];
      openLen = fence.length;
    } else if (fence[0] === openChar && fence.length >= openLen) {
      // Matching closing fence
      depth = 0;
      openChar = "";
      openLen = 0;
    }
  }
  return depth === 0;
}

/** Enrichment close marker. */
const ENRICHMENT_CLOSE = "}}";

function hasUnmatchedOpeningBrace(enrichmentText: string): boolean {
  let braceDepth = 0;
  for (const character of enrichmentText) {
    if (character === "{") braceDepth++;
    else if (character === "}" && braceDepth > 0) braceDepth--;
  }
  return braceDepth > 0;
}

function readEnrichmentOpening(src: string, index: number): EnrichmentOpening | null {
  for (const kind of Object.keys(ENRICHMENT_KINDS)) {
    const opening = `{{${kind}:`;
    if (src.startsWith(opening, index)) {
      return { kind, length: opening.length };
    }
  }
  return null;
}

function findEnrichmentStart(src: string): number {
  let openingIndex = -1;
  for (const kind of Object.keys(ENRICHMENT_KINDS)) {
    const candidateIndex = src.indexOf(`{{${kind}:`);
    if (candidateIndex >= 0 && (openingIndex < 0 || candidateIndex < openingIndex)) {
      openingIndex = candidateIndex;
    }
  }
  let precedingIndex = openingIndex - 1;
  while (precedingIndex >= 0 && " \t\r\n".includes(src[precedingIndex])) {
    precedingIndex--;
  }
  return src[precedingIndex] === "}" && src[precedingIndex - 1] !== "}"
    ? precedingIndex
    : openingIndex;
}

/**
 * Resolves the close marker position for a run of closing braces.
 * For runs like "}}}", this picks the final "}}" so a trailing content "}" is preserved.
 */
function resolveCloseIndexFromBraceRun(src: string, runStart: number): number {
  let runLength = 0;
  while (runStart + runLength < src.length && src[runStart + runLength] === "}") {
    runLength++;
  }
  if (runLength < ENRICHMENT_CLOSE.length) {
    return -1;
  }
  return runStart + (runLength - ENRICHMENT_CLOSE.length);
}

/** Finds the closing }} while rejecting unresolved nesting and fenced-code braces. */
function findEnrichmentClose(src: string, startIndex: number, isStreaming: boolean): number {
  let inFence = false;
  let fenceChar = "";
  let fenceLen = 0;
  for (let cursor = startIndex; cursor < src.length - 1; cursor++) {
    // At line boundaries, check for fence delimiters
    if (cursor === startIndex || src[cursor - 1] === "\n") {
      const lineMatch = src.slice(cursor).match(FENCE_PATTERN);
      if (lineMatch) {
        const fence = lineMatch[1];
        if (!inFence) {
          inFence = true;
          fenceChar = fence[0];
          fenceLen = fence.length;
        } else if (fence[0] === fenceChar && fence.length >= fenceLen) {
          inFence = false;
          fenceChar = "";
          fenceLen = 0;
        }
        cursor += fence.length - 1; // -1 because loop will increment
        continue;
      }
    }
    if (!inFence && readEnrichmentOpening(src, cursor)) {
      return -1;
    }
    if (!inFence && src[cursor] === "}") {
      const closeIndex = resolveCloseIndexFromBraceRun(src, cursor);
      if (closeIndex >= 0) {
        if (
          isStreaming &&
          closeIndex === cursor &&
          hasUnmatchedOpeningBrace(src.slice(startIndex, cursor))
        ) {
          continue;
        }
        return closeIndex;
      }
    }
  }

  return -1;
}

/**
 * Custom marked extension for enrichment markers.
 * Parses {{kind:content}} syntax and renders as styled cards.
 */
function createEnrichmentExtension(
  isStreaming: boolean,
  markdownParser: Marked,
): TokenizerExtension & RendererExtension {
  return {
    name: "enrichment",
    level: "block",
    start(src: string) {
      return findEnrichmentStart(src);
    },
    tokenizer(src: string): EnrichmentToken | undefined {
      if (src[0] === "}") {
        return { type: "enrichment", raw: "}", kind: "", content: "", resolved: false };
      }

      const opening = readEnrichmentOpening(src, 0);
      if (!opening) {
        return undefined;
      }

      const contentStart = opening.length;

      const closeIndex = findEnrichmentClose(src, contentStart, isStreaming);
      if (closeIndex === -1) {
        const shouldHideTrailingBrace =
          src.endsWith("}") && !hasUnmatchedOpeningBrace(src.slice(contentStart, -1));
        return {
          type: "enrichment",
          raw: src,
          kind: opening.kind,
          content: src
            .slice(contentStart, shouldHideTrailingBrace ? src.length - 1 : src.length)
            .trim(),
          resolved: false,
        };
      }

      const content = src.slice(contentStart, closeIndex);
      const raw = src.slice(0, closeIndex + ENRICHMENT_CLOSE.length);

      return {
        type: "enrichment",
        raw,
        kind: opening.kind,
        content: content.trim(),
        resolved: true,
      };
    },
    renderer(token: Tokens.Generic): string {
      if (token.type !== "enrichment") {
        return token.raw;
      }

      if (token.resolved !== true) {
        const unresolvedContent = typeof token.content === "string" ? token.content : "";
        return markdownParser.parse(normalizeMarkdownForStreaming(unresolvedContent), {
          async: false,
          gfm: true,
          breaks: false,
        });
      }

      const kind = typeof token.kind === "string" ? token.kind : "";
      const enrichmentMarkdown = typeof token.content === "string" ? token.content : "";
      const enrichmentPresentation = ENRICHMENT_KINDS[kind];
      if (!enrichmentPresentation) {
        return token.raw;
      }
      if (enrichmentMarkdown.length === 0) {
        return "";
      }

      const normalizedEnrichmentMarkdown = normalizeMarkdownForStreaming(enrichmentMarkdown);

      // DIAGNOSTIC: Log enrichment content to identify malformed markdown
      if (import.meta.env.DEV) {
        const hasFences =
          normalizedEnrichmentMarkdown.includes("```") ||
          normalizedEnrichmentMarkdown.includes("~~~");
        const isBalanced = hasBalancedCodeFences(normalizedEnrichmentMarkdown);
        if (hasFences && !isBalanced) {
          console.warn("[markdown] Unbalanced code fences in enrichment:", {
            kind,
            content: normalizedEnrichmentMarkdown,
            raw: token.raw,
          });
        }
      }

      // Render inner content as markdown
      // IMPORTANT: Use gfm but disable breaks to prevent fence interference
      const innerHtml = markdownParser.parse(normalizedEnrichmentMarkdown, {
        async: false,
        gfm: true,
        breaks: false, // Preserve fence detection accuracy
      });

      return `<div class="inline-enrichment ${kind}" data-enrichment-type="${kind}">
  <div class="inline-enrichment-header">${enrichmentPresentation.icon}<span>${enrichmentPresentation.title}</span></div>
  <div class="enrichment-text">${innerHtml}</div>
</div>`;
    },
  };
}
function createMarkdownParser(isStreaming: boolean): Marked {
  const markdownParser = new Marked({ gfm: true, breaks: true });
  markdownParser.use({ extensions: [createEnrichmentExtension(isStreaming, markdownParser)] });
  return markdownParser;
}

const COMPLETE_MARKDOWN_PARSER = createMarkdownParser(false);
const STREAMING_MARKDOWN_PARSER = createMarkdownParser(true);

/**
 * Parse markdown to sanitized HTML. SSR-safe - no DOM APIs used.
 * Uses DOMPurify for sanitization. Use this in `$derived` for reactive markdown rendering.
 *
 * @throws Never throws - returns escaped source text on parse failure
 */
export function parseMarkdown(
  markdownText: string | null | undefined,
  isStreaming = false,
): string {
  if (!markdownText) {
    return "";
  }

  const normalizedContent = normalizeMarkdownForStreaming(markdownText);

  // DIAGNOSTIC: Log content with unbalanced fences before parsing
  if (import.meta.env.DEV) {
    const hasFences = normalizedContent.includes("```") || normalizedContent.includes("~~~");
    if (hasFences && !hasBalancedCodeFences(normalizedContent)) {
      console.warn("[markdown] Unbalanced code fences in input:", {
        contentLength: normalizedContent.length,
        contentPreview: normalizedContent.slice(0, 500),
        contentEnd: normalizedContent.slice(-200),
      });
    }
  }

  try {
    const markdownParser = isStreaming ? STREAMING_MARKDOWN_PARSER : COMPLETE_MARKDOWN_PARSER;
    const rawHtml = markdownParser.parse(normalizedContent, { async: false });

    return DOMPurify.sanitize(rawHtml, {
      USE_PROFILES: { html: true },
      ADD_ATTR: ["class", "data-enrichment-type"],
    });
  } catch (parseError) {
    console.error("[markdown] Failed to parse markdown content:", parseError);
    return escapeHtml(markdownText);
  }
}

/**
 * Escape text for safe HTML insertion. SSR-safe - pure string operations.
 */
export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}
