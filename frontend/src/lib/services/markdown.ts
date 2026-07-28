import { Marked, type TokenizerExtension, type RendererExtension, type Tokens } from "marked";
import DOMPurify from "dompurify";

/** Display metadata owned by the browser renderer. */
interface EnrichmentPresentation {
  title: string;
  iconHtml: string;
}

const NEWLINE = "\n";
const ZERO_WIDTH_SPACE_CODE_POINT = 0x200b;
const WORD_JOINER_CODE_POINT = 0x2060;

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
const NUMERIC_LIST_MARKER_MAX_DIGITS = 9;
const STRUCTURAL_MARKDOWN_PARSER = new Marked({ gfm: true, breaks: true });

type FenceMarker = { character: string; length: number };
type BacktickRun = { length: number };

const ENRICHMENT_PRESENTATIONS_BY_TOKEN: ReadonlyMap<string, EnrichmentPresentation> = new Map([
  [
    "hint",
    {
      title: "Helpful Hints",
      iconHtml:
        '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2a7 7 0 0 0-7 7c0 2.59 1.47 4.84 3.63 6.02L9 18h6l.37-2.98A7.01 7.01 0 0 0 19 9a7 7 0 0 0-7-7zm-3 19h6v1H9v-1z"/></svg>',
    },
  ],
  [
    "background",
    {
      title: "Background Context",
      iconHtml:
        '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M4 6h16v2H4zM4 10h16v2H4zM4 14h16v2H4z"/></svg>',
    },
  ],
  [
    "reminder",
    {
      title: "Important Reminders",
      iconHtml:
        '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 22a2 2 0 0 0 2-2H10a2 2 0 0 0 2 2zm6-6v-5a6 6 0 0 0-4-5.65V4a2 2 0 0 0-4 0v1.35A6 6 0 0 0 6 11v5l-2 2v1h16v-1l-2-2z"/></svg>',
    },
  ],
  [
    "warning",
    {
      title: "Warning",
      iconHtml:
        '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2V7h2v7z"/></svg>',
    },
  ],
  [
    "example",
    {
      title: "Example",
      iconHtml:
        '<svg viewBox="0 0 24 24" fill="currentColor"><path d="M12 2a10 10 0 1 0 10 10A10 10 0 0 0 12 2zm1 15h-2v-6h2zm0-8h-2V7h2z"/></svg>',
    },
  ],
]);

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

function hasOnlyClosingFenceSpacing(src: string, suffixStartIndex: number): boolean {
  for (let cursor = suffixStartIndex; cursor < src.length; cursor++) {
    const character = src[cursor];
    if (character === NEWLINE || character === "\r") {
      return true;
    }
    if (character !== " " && character !== "\t") {
      return false;
    }
  }
  return true;
}

function hasTrailingFenceProse(src: string, suffixStartIndex: number): boolean {
  let firstVisibleIndex = suffixStartIndex;
  while (
    firstVisibleIndex < src.length &&
    (src[firstVisibleIndex] === " " || src[firstVisibleIndex] === "\t")
  ) {
    firstVisibleIndex++;
  }
  if (
    firstVisibleIndex >= src.length ||
    src[firstVisibleIndex] === NEWLINE ||
    src[firstVisibleIndex] === "\r"
  ) {
    return false;
  }

  const firstVisibleCharacter = src[firstVisibleIndex];
  const firstVisibleCode = firstVisibleCharacter.charCodeAt(0);
  const startsWithLetter =
    (firstVisibleCode >= ASCII_LOWERCASE_START && firstVisibleCode <= ASCII_LOWERCASE_END) ||
    (firstVisibleCode >= ASCII_UPPERCASE_START && firstVisibleCode <= ASCII_UPPERCASE_END);
  const startsWithDigit =
    firstVisibleCode >= ASCII_DIGIT_START && firstVisibleCode <= ASCII_DIGIT_END;
  if (!startsWithLetter && !startsWithDigit) {
    return false;
  }

  for (let cursor = firstVisibleIndex + 1; cursor < src.length; cursor++) {
    const character = src[cursor];
    if (character === NEWLINE || character === "\r") {
      break;
    }
    if (character === " " || character === "\t") {
      return true;
    }
  }
  return firstVisibleCode >= ASCII_UPPERCASE_START && firstVisibleCode <= ASCII_UPPERCASE_END;
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
  private attachedFenceOpening = false;
  private inInlineCode = false;
  private inlineBacktickLength = 0;

  isInsideFence(): boolean {
    return this.inFence;
  }

  isInsideInlineCode(): boolean {
    return this.inInlineCode;
  }

  enterFence(marker: FenceMarker, attachedFenceOpening: boolean): void {
    this.inFence = true;
    this.fenceCharacter = marker.character;
    this.fenceLength = marker.length;
    this.attachedFenceOpening = attachedFenceOpening;
  }

  exitFence(): void {
    this.inFence = false;
    this.fenceCharacter = "";
    this.fenceLength = 0;
    this.attachedFenceOpening = false;
  }

  wouldCloseFence(src: string, markerIndex: number, marker: FenceMarker): boolean {
    const matchesOpeningFence =
      this.inFence && marker.character === this.fenceCharacter && marker.length >= this.fenceLength;
    if (!matchesOpeningFence) {
      return false;
    }

    const suffixStartIndex = markerIndex + marker.length;
    if (hasOnlyClosingFenceSpacing(src, suffixStartIndex)) {
      return true;
    }
    return this.attachedFenceOpening && hasTrailingFenceProse(src, suffixStartIndex);
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
        codeRegionState.enterFence(marker, !fenceAtCommonMarkIndentation);
        continue;
      }

      if (
        codeRegionState.isInsideFence() &&
        fenceAtCommonMarkIndentation &&
        codeRegionState.wouldCloseFence(content, cursor, marker)
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

function numericOrderedListContinuationIndentation(markdownLine: string): number | null {
  const indentationSpaces = leadingSpaceCount(markdownLine, 0);
  if (indentationSpaces > COMMONMARK_MAX_FENCE_INDENTATION) {
    return null;
  }

  let cursor = indentationSpaces;
  let digitCount = 0;
  while (
    cursor < markdownLine.length &&
    digitCount < NUMERIC_LIST_MARKER_MAX_DIGITS &&
    markdownLine.charCodeAt(cursor) >= ASCII_DIGIT_START &&
    markdownLine.charCodeAt(cursor) <= ASCII_DIGIT_END
  ) {
    cursor++;
    digitCount++;
  }
  if (
    digitCount === 0 ||
    (cursor < markdownLine.length &&
      markdownLine.charCodeAt(cursor) >= ASCII_DIGIT_START &&
      markdownLine.charCodeAt(cursor) <= ASCII_DIGIT_END)
  ) {
    return null;
  }

  if (markdownLine[cursor] !== "." && markdownLine[cursor] !== ")") {
    return null;
  }
  const markerEndIndex = cursor + 1;
  if (markerEndIndex >= markdownLine.length) {
    return markerEndIndex + 1;
  }
  if (markdownLine[markerEndIndex] !== " " && markdownLine[markerEndIndex] !== "\t") {
    return null;
  }

  let contentStartIndex = markerEndIndex;
  while (
    contentStartIndex < markdownLine.length &&
    (markdownLine[contentStartIndex] === " " || markdownLine[contentStartIndex] === "\t")
  ) {
    contentStartIndex++;
  }
  if (contentStartIndex >= markdownLine.length) {
    return markerEndIndex + 1;
  }
  const markerPadding = contentStartIndex - markerEndIndex;
  return markerEndIndex + (markerPadding > COMMONMARK_INDENTED_CODE_SPACES ? 1 : markerPadding);
}

function scanFenceWithinListContinuation(
  markdownLine: string,
  maximumIndentation: number,
): { marker: FenceMarker; markerIndex: number } | null {
  const markerIndex = leadingSpaceCount(markdownLine, 0);
  if (markerIndex > maximumIndentation) {
    return null;
  }
  const marker = scanFenceMarker(markdownLine, markerIndex);
  return marker ? { marker, markerIndex } : null;
}

function countLineBreaks(markdownText: string, startIndex: number, endIndex: number): number {
  let lineBreakCount = 0;
  for (let cursor = startIndex; cursor < endIndex; cursor++) {
    if (markdownText[cursor] === NEWLINE) {
      lineBreakCount++;
    }
  }
  return lineBreakCount;
}

function parsedNumericListMarkerLines(markdownText: string): ReadonlySet<number> {
  const markerLines = new Set<number>();
  const structuralTokens = STRUCTURAL_MARKDOWN_PARSER.lexer(markdownText);
  let sourceCursor = 0;
  let sourceLine = 0;

  for (const structuralToken of structuralTokens) {
    const tokenStartIndex = markdownText.startsWith(structuralToken.raw, sourceCursor)
      ? sourceCursor
      : markdownText.indexOf(structuralToken.raw, sourceCursor);
    if (tokenStartIndex < 0) {
      continue;
    }
    sourceLine += countLineBreaks(markdownText, sourceCursor, tokenStartIndex);

    if (structuralToken.type === "list" && structuralToken.ordered) {
      let listCursor = 0;
      let listLine = sourceLine;
      for (const orderedListMember of structuralToken.items) {
        const memberStartIndex = structuralToken.raw.indexOf(orderedListMember.raw, listCursor);
        if (memberStartIndex < 0) {
          continue;
        }
        listLine += countLineBreaks(structuralToken.raw, listCursor, memberStartIndex);
        markerLines.add(listLine);
        const memberEndIndex = memberStartIndex + orderedListMember.raw.length;
        listLine += countLineBreaks(structuralToken.raw, memberStartIndex, memberEndIndex);
        listCursor = memberEndIndex;
      }
    }

    const tokenEndIndex = tokenStartIndex + structuralToken.raw.length;
    sourceLine += countLineBreaks(markdownText, tokenStartIndex, tokenEndIndex);
    sourceCursor = tokenEndIndex;
  }

  return markerLines;
}

function indentFenceFollowingNumericListHeader(markdownText: string): string {
  const markdownLines = markdownText.split(NEWLINE);
  const parsedListMarkerLines = parsedNumericListMarkerLines(markdownText);
  const indentedLines: string[] = [];
  const nestedFenceState = new MarkdownCodeRegionState();
  let awaitedListContinuationIndentation: number | null = null;
  let nestedFenceIndentation = "";
  let nestedFenceTargetIndentation = 0;
  let nestedFenceMaximumSourceIndentation = COMMONMARK_MAX_FENCE_INDENTATION;

  for (let lineIndex = 0; lineIndex < markdownLines.length; lineIndex++) {
    const markdownLine = markdownLines[lineIndex];
    if (nestedFenceState.isInsideFence()) {
      const fenceCandidate = scanFenceWithinListContinuation(
        markdownLine,
        nestedFenceMaximumSourceIndentation,
      );
      const closesNestedFence =
        fenceCandidate &&
        nestedFenceState.wouldCloseFence(
          markdownLine,
          fenceCandidate.markerIndex,
          fenceCandidate.marker,
        );
      if (closesNestedFence) {
        indentedLines.push(
          `${" ".repeat(nestedFenceTargetIndentation)}${markdownLine.slice(
            fenceCandidate.markerIndex,
          )}`,
        );
        nestedFenceState.exitFence();
        nestedFenceIndentation = "";
        nestedFenceTargetIndentation = 0;
        nestedFenceMaximumSourceIndentation = COMMONMARK_MAX_FENCE_INDENTATION;
      } else {
        indentedLines.push(`${nestedFenceIndentation}${markdownLine}`);
      }
      continue;
    }

    const listContinuationIndentation = numericOrderedListContinuationIndentation(markdownLine);
    if (listContinuationIndentation !== null) {
      const nextLine = markdownLines[lineIndex + 1];
      const nextLineStartsFence =
        nextLine !== undefined &&
        scanFenceWithinListContinuation(
          nextLine,
          listContinuationIndentation + COMMONMARK_MAX_FENCE_INDENTATION,
        ) !== null;
      awaitedListContinuationIndentation =
        nextLineStartsFence && parsedListMarkerLines.has(lineIndex)
          ? listContinuationIndentation
          : null;
      indentedLines.push(markdownLine);
      continue;
    }

    if (awaitedListContinuationIndentation !== null) {
      const fenceCandidate = scanFenceWithinListContinuation(
        markdownLine,
        awaitedListContinuationIndentation + COMMONMARK_MAX_FENCE_INDENTATION,
      );
      if (fenceCandidate) {
        nestedFenceIndentation = " ".repeat(
          Math.max(0, awaitedListContinuationIndentation - fenceCandidate.markerIndex),
        );
        nestedFenceTargetIndentation = awaitedListContinuationIndentation;
        nestedFenceMaximumSourceIndentation =
          awaitedListContinuationIndentation +
          COMMONMARK_MAX_FENCE_INDENTATION -
          nestedFenceIndentation.length;
        nestedFenceState.enterFence(fenceCandidate.marker, false);
        awaitedListContinuationIndentation = null;
        indentedLines.push(
          `${" ".repeat(nestedFenceTargetIndentation)}${markdownLine.slice(
            fenceCandidate.markerIndex,
          )}`,
        );
        continue;
      }
      awaitedListContinuationIndentation = null;
    }

    indentedLines.push(markdownLine);
  }

  const unfinishedNestedFence = nestedFenceState.openFence();
  if (unfinishedNestedFence) {
    indentedLines.push(
      `${" ".repeat(nestedFenceTargetIndentation)}${unfinishedNestedFence.character.repeat(
        Math.max(unfinishedNestedFence.length, FENCE_MIN_LENGTH),
      )}`,
    );
  }

  return indentedLines.join(NEWLINE);
}

function prepareMarkdownForParsing(markdownText: string): string {
  return normalizeMarkdownForStreaming(indentFenceFollowingNumericListHeader(markdownText));
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
          codeRegionState.enterFence(marker, false);
        } else if (codeRegionState.wouldCloseFence(src, markerIndex, marker)) {
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
        return markdownParser.parse(prepareMarkdownForParsing(unresolvedContent), {
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

      const normalizedEnrichmentMarkdown = prepareMarkdownForParsing(enrichmentMarkdown);

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
  markdownParser.use({
    renderer: {
      html(token: Tokens.HTML | Tokens.Tag): string {
        return escapeHtml(token.text);
      },
    },
    extensions: [createEnrichmentExtension(isStreaming, markdownParser)],
  });
  return markdownParser;
}

const COMPLETE_MARKDOWN_PARSER = createMarkdownParser(false);
const STREAMING_MARKDOWN_PARSER = createMarkdownParser(true);

/**
 * Parse markdown to sanitized HTML. SSR-safe - no DOM APIs used.
 * Uses DOMPurify for sanitization. Use this in `$derived` for reactive markdown rendering.
 *
 * @param markdownText - The markdown content to parse. Null/undefined returns empty string.
 * @param isStreaming - When true, handles incomplete enrichment markers gracefully during
 *   streaming (e.g., "{{hint: some text}" without closing braces). Defaults to false.
 * @returns Sanitized HTML, an empty string for empty input, or escaped source text after a parse failure.
 */
export function parseMarkdown(
  markdownText: string | null | undefined,
  isStreaming = false,
): string {
  if (!markdownText) {
    return "";
  }

  const normalizedContent = prepareMarkdownForParsing(markdownText);

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
