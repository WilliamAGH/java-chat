import { Marked, type TokenizerExtension, type RendererExtension, type Tokens } from "marked";
import DOMPurify from "dompurify";
import enrichmentKindsManifest from "../../../../src/main/resources/enrichment-kinds.manifest?raw";

/**
 * Display metadata projected from the canonical enrichment-kind manifest.
 */
interface EnrichmentPresentation {
  title: string;
  iconHtml: string;
}

const ENRICHMENT_MANIFEST_FIELD_DELIMITER = "|";
const CARRIAGE_RETURN = "\r";
const NEWLINE = "\n";
const ZERO_WIDTH_SPACE_CODE_POINT = 0x200b;
const WORD_JOINER_CODE_POINT = 0x2060;

function parseEnrichmentKindsManifest(
  manifestSource: string,
): ReadonlyMap<string, EnrichmentPresentation> {
  if (!manifestSource) {
    throw new Error("Enrichment kind manifest must contain at least one row");
  }

  const manifestRows = manifestSource.split(NEWLINE);
  if (manifestRows.at(-1) === "") {
    manifestRows.pop();
  }

  const enrichmentPresentationsByToken = new Map<string, EnrichmentPresentation>();
  for (const [rowIndex, manifestRow] of manifestRows.entries()) {
    const normalizedManifestRow = manifestRow.endsWith(CARRIAGE_RETURN)
      ? manifestRow.slice(0, -1)
      : manifestRow;
    const [enrichmentToken, presentationTitle, iconHtml, ...unexpectedFields] =
      normalizedManifestRow.split(ENRICHMENT_MANIFEST_FIELD_DELIMITER);

    if (
      !isCanonicalEnrichmentToken(enrichmentToken) ||
      !presentationTitle ||
      presentationTitle !== presentationTitle.trim() ||
      !iconHtml.startsWith("<svg") ||
      !iconHtml.endsWith("</svg>") ||
      unexpectedFields.length > 0
    ) {
      throw new Error(`Malformed enrichment kind manifest row ${rowIndex + 1}`);
    }
    if (enrichmentPresentationsByToken.has(enrichmentToken)) {
      throw new Error(`Duplicate enrichment kind manifest token: ${enrichmentToken}`);
    }

    enrichmentPresentationsByToken.set(enrichmentToken, {
      title: presentationTitle,
      iconHtml,
    });
  }

  return enrichmentPresentationsByToken;
}

interface EnrichmentToken extends Tokens.Generic {
  type: "enrichment";
  raw: string;
  kind: string;
  content: string;
  resolved: boolean;
}

type EnrichmentOpening = { kind: string; length: number };

const FENCE_MIN_LENGTH = 3;
const ASCII_DIGIT_START = 48;
const ASCII_DIGIT_END = 57;
const ASCII_UPPERCASE_START = 65;
const ASCII_UPPERCASE_END = 90;
const ASCII_LOWERCASE_START = 97;
const ASCII_LOWERCASE_END = 122;
const COMMONMARK_MAX_FENCE_INDENTATION = 3;
const COMMONMARK_INDENTED_CODE_SPACES = 4;

type FenceMarker = { character: string; length: number };
type BacktickRun = { length: number };

function isCanonicalEnrichmentToken(enrichmentToken: string): boolean {
  if (!enrichmentToken || enrichmentToken !== enrichmentToken.trim()) {
    return false;
  }
  for (const tokenCharacter of enrichmentToken) {
    const tokenCharacterCode = tokenCharacter.charCodeAt(0);
    const isLowercaseAsciiLetter =
      tokenCharacterCode >= ASCII_LOWERCASE_START && tokenCharacterCode <= ASCII_LOWERCASE_END;
    if (!isLowercaseAsciiLetter && tokenCharacter !== "-") {
      return false;
    }
  }
  return true;
}

const ENRICHMENT_PRESENTATIONS_BY_TOKEN = parseEnrichmentKindsManifest(enrichmentKindsManifest);

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

function lineStartIndex(src: string, cursor: number): number {
  let lineStart = cursor;
  while (lineStart > 0 && src[lineStart - 1] !== NEWLINE) {
    lineStart--;
  }
  return lineStart;
}

function leadingSpaceCount(src: string, lineStart: number): number {
  let indentationSpaces = 0;
  while (lineStart + indentationSpaces < src.length && src[lineStart + indentationSpaces] === " ") {
    indentationSpaces++;
  }
  return indentationSpaces;
}

function isFenceAtCommonMarkIndentation(src: string, markerIndex: number): boolean {
  const currentLineStart = lineStartIndex(src, markerIndex);
  const indentationSpaces = markerIndex - currentLineStart;
  if (indentationSpaces > COMMONMARK_MAX_FENCE_INDENTATION) {
    return false;
  }
  for (
    let indentationIndex = currentLineStart;
    indentationIndex < markerIndex;
    indentationIndex++
  ) {
    if (src[indentationIndex] !== " ") {
      return false;
    }
  }
  return true;
}

function isIndentedCodeLine(src: string, lineStart: number): boolean {
  return (
    src[lineStart] === "\t" || leadingSpaceCount(src, lineStart) >= COMMONMARK_INDENTED_CODE_SPACES
  );
}

function scanFenceAfterCommonMarkIndentation(
  src: string,
  lineStart: number,
): { marker: FenceMarker; markerIndex: number } | null {
  const indentationSpaces = leadingSpaceCount(src, lineStart);
  if (indentationSpaces > COMMONMARK_MAX_FENCE_INDENTATION) {
    return null;
  }
  const markerIndex = lineStart + indentationSpaces;
  const marker = scanFenceMarker(src, markerIndex);
  return marker ? { marker, markerIndex } : null;
}

function scanBacktickRun(src: string, index: number): BacktickRun | null {
  if (index < 0 || index >= src.length || src[index] !== "`") {
    return null;
  }

  let runLength = 0;
  while (index + runLength < src.length && src[index + runLength] === "`") {
    runLength++;
  }

  return { length: runLength };
}

function hasClosingBacktickRun(
  src: string,
  openingIndex: number,
  openingRunLength: number,
): boolean {
  let searchIndex = openingIndex + openingRunLength;
  while (searchIndex < src.length) {
    const nextBacktickIndex = src.indexOf("`", searchIndex);
    if (nextBacktickIndex < 0) {
      return false;
    }

    const candidateRun = scanBacktickRun(src, nextBacktickIndex);
    if (!candidateRun) {
      return false;
    }
    if (candidateRun.length === openingRunLength) {
      return true;
    }
    searchIndex = nextBacktickIndex + candidateRun.length;
  }

  return false;
}

/** Tracks fenced and inline code so enrichment delimiters remain literal inside code regions. */
class MarkdownCodeRegionState {
  private inFence = false;
  private fenceCharacter = "";
  private fenceLength = 0;
  private inInlineCode = false;
  private inlineBacktickLength = 0;

  isInsideFence(): boolean {
    return this.inFence;
  }

  isInsideInlineCode(): boolean {
    return this.inInlineCode;
  }

  enterFence(marker: FenceMarker): void {
    this.inFence = true;
    this.fenceCharacter = marker.character;
    this.fenceLength = marker.length;
  }

  exitFence(): void {
    this.inFence = false;
    this.fenceCharacter = "";
    this.fenceLength = 0;
  }

  wouldCloseFence(marker: FenceMarker): boolean {
    return (
      this.inFence && marker.character === this.fenceCharacter && marker.length >= this.fenceLength
    );
  }

  openFence(): FenceMarker | null {
    if (!this.inFence) {
      return null;
    }
    return { character: this.fenceCharacter, length: this.fenceLength };
  }

  processBacktickRun(src: string, cursor: number, backtickRun: BacktickRun): void {
    if (this.inFence) {
      return;
    }
    if (!this.inInlineCode && !hasClosingBacktickRun(src, cursor, backtickRun.length)) {
      return;
    }

    if (!this.inInlineCode) {
      this.inInlineCode = true;
      this.inlineBacktickLength = backtickRun.length;
    } else if (backtickRun.length === this.inlineBacktickLength) {
      this.inInlineCode = false;
      this.inlineBacktickLength = 0;
    }
  }
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
  return src[index - 1].trim().length > 0;
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
  const codeRegionState = new MarkdownCodeRegionState();

  for (let cursor = 0; cursor < content.length; ) {
    const startOfLine = cursor === 0 || content[cursor - 1] === NEWLINE;
    if (
      startOfLine &&
      !codeRegionState.isInsideFence() &&
      !codeRegionState.isInsideInlineCode() &&
      isIndentedCodeLine(content, cursor)
    ) {
      const lineEnd = content.indexOf(NEWLINE, cursor);
      const nextLineStart = lineEnd < 0 ? content.length : lineEnd + 1;
      normalized += content.slice(cursor, nextLineStart);
      cursor = nextLineStart;
      continue;
    }

    const marker = scanFenceMarker(content, cursor);
    const fenceAtCommonMarkIndentation =
      marker !== null && isFenceAtCommonMarkIndentation(content, cursor);

    if (marker && !codeRegionState.isInsideInlineCode()) {
      if (
        !codeRegionState.isInsideFence() &&
        (fenceAtCommonMarkIndentation || isAttachedFenceStart(content, cursor))
      ) {
        if (!fenceAtCommonMarkIndentation) {
          normalized = appendLineBreakIfNeeded(normalized);
        }
        const consumed = consumeOpeningFence(content, cursor, marker);
        normalized += consumed.text;
        cursor = consumed.nextCursor;
        codeRegionState.enterFence(marker);
        continue;
      }

      if (
        codeRegionState.isInsideFence() &&
        fenceAtCommonMarkIndentation &&
        codeRegionState.wouldCloseFence(marker)
      ) {
        const consumed = consumeClosingFence(content, cursor, marker);
        normalized += consumed.text;
        cursor = consumed.nextCursor;
        codeRegionState.exitFence();
        continue;
      }
    }

    if (!codeRegionState.isInsideFence()) {
      const backtickRun = scanBacktickRun(content, cursor);
      if (backtickRun) {
        codeRegionState.processBacktickRun(content, cursor, backtickRun);
        normalized += content.slice(cursor, cursor + backtickRun.length);
        cursor += backtickRun.length;
        continue;
      }
    }

    normalized += content[cursor];
    cursor++;
  }

  const unfinishedFence = codeRegionState.openFence();
  if (unfinishedFence) {
    normalized += `${NEWLINE}${unfinishedFence.character.repeat(
      Math.max(unfinishedFence.length, FENCE_MIN_LENGTH),
    )}`;
  }

  return normalized;
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
  for (const kind of ENRICHMENT_PRESENTATIONS_BY_TOKEN.keys()) {
    const opening = `{{${kind}:`;
    if (src.startsWith(opening, index)) {
      return { kind, length: opening.length };
    }
  }
  return null;
}

function findEnrichmentStart(src: string): number {
  let openingIndex = -1;
  for (const kind of ENRICHMENT_PRESENTATIONS_BY_TOKEN.keys()) {
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

/** Finds the closing }} while keeping delimiters inside fenced and inline code literal. */
function findEnrichmentClose(src: string, startIndex: number, isStreaming: boolean): number {
  const codeRegionState = new MarkdownCodeRegionState();
  for (let cursor = startIndex; cursor < src.length; ) {
    const startOfLine = cursor === startIndex || src[cursor - 1] === NEWLINE;
    if (startOfLine && !codeRegionState.isInsideInlineCode()) {
      if (!codeRegionState.isInsideFence() && isIndentedCodeLine(src, cursor)) {
        const lineEnd = src.indexOf(NEWLINE, cursor);
        cursor = lineEnd < 0 ? src.length : lineEnd + 1;
        continue;
      }

      const fenceCandidate = scanFenceAfterCommonMarkIndentation(src, cursor);
      if (fenceCandidate) {
        const { marker, markerIndex } = fenceCandidate;
        if (!codeRegionState.isInsideFence()) {
          codeRegionState.enterFence(marker);
        } else if (codeRegionState.wouldCloseFence(marker)) {
          codeRegionState.exitFence();
        }
        cursor = markerIndex + marker.length;
        continue;
      }
    }

    if (!codeRegionState.isInsideFence()) {
      const backtickRun = scanBacktickRun(src, cursor);
      if (backtickRun) {
        codeRegionState.processBacktickRun(src, cursor, backtickRun);
        cursor += backtickRun.length;
        continue;
      }
    }

    if (
      !codeRegionState.isInsideFence() &&
      !codeRegionState.isInsideInlineCode() &&
      src[cursor] === "}"
    ) {
      const closeIndex = resolveCloseIndexFromBraceRun(src, cursor);
      if (closeIndex >= 0) {
        if (
          isStreaming &&
          closeIndex === cursor &&
          hasUnmatchedOpeningBrace(src.slice(startIndex, cursor))
        ) {
          cursor++;
          continue;
        }
        return closeIndex;
      }
    }

    cursor++;
  }

  return -1;
}

function isBlankEnrichmentText(enrichmentMarkdown: string): boolean {
  for (const character of enrichmentMarkdown) {
    const codePoint = character.codePointAt(0);
    const isBlankCharacter =
      character.trim().length === 0 ||
      codePoint === ZERO_WIDTH_SPACE_CODE_POINT ||
      codePoint === WORD_JOINER_CODE_POINT;

    if (!isBlankCharacter) return false;
  }

  return true;
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
      const enrichmentPresentation = ENRICHMENT_PRESENTATIONS_BY_TOKEN.get(kind);
      if (!enrichmentPresentation) {
        return token.raw;
      }
      if (isBlankEnrichmentText(enrichmentMarkdown)) {
        return "";
      }

      const normalizedEnrichmentMarkdown = normalizeMarkdownForStreaming(enrichmentMarkdown);

      // Render inner content as markdown
      // IMPORTANT: Use gfm but disable breaks to prevent fence interference
      const innerHtml = markdownParser.parse(normalizedEnrichmentMarkdown, {
        async: false,
        gfm: true,
        breaks: false, // Preserve fence detection accuracy
      });

      return `<div class="inline-enrichment ${kind}" data-enrichment-type="${kind}">
  <div class="inline-enrichment-header">${enrichmentPresentation.iconHtml}<span>${enrichmentPresentation.title}</span></div>
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

  if (import.meta.env.DEV && normalizedContent !== markdownText) {
    for (let markerIndex = 0; markerIndex < markdownText.length; markerIndex++) {
      const opening = readEnrichmentOpening(markdownText, markerIndex);
      if (!opening) {
        continue;
      }
      const rawLength = markdownText.length - markerIndex;
      console.warn("[markdown] Repaired enrichment markdown structure", {
        kind: opening.kind,
        contentLength: Math.max(0, rawLength - opening.length - ENRICHMENT_CLOSE.length),
        rawLength,
      });
      break;
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
