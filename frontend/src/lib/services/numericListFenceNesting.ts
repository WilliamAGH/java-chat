import type { Marked, Token } from "marked";

const NEWLINE = "\n";
const FENCE_MIN_LENGTH = 3;
const ASCII_DIGIT_START = 48;
const ASCII_DIGIT_END = 57;
const COMMONMARK_MAX_FENCE_INDENTATION = 3;
const COMMONMARK_INDENTED_CODE_SPACES = 4;
const NUMERIC_LIST_MARKER_MAX_DIGITS = 9;

interface FenceMarker {
  character: string;
  length: number;
}

class ListFenceState {
  private openingMarker: FenceMarker | null = null;

  isOpen(): boolean {
    return this.openingMarker !== null;
  }

  open(marker: FenceMarker): void {
    this.openingMarker = marker;
  }

  closesWith(markdownLine: string, markerIndex: number, marker: FenceMarker): boolean {
    return (
      this.openingMarker !== null &&
      marker.character === this.openingMarker.character &&
      marker.length >= this.openingMarker.length &&
      hasOnlyClosingFenceSpacing(markdownLine, markerIndex + marker.length)
    );
  }

  close(): void {
    this.openingMarker = null;
  }

  unfinishedMarker(): FenceMarker | null {
    return this.openingMarker;
  }
}

function scanFenceMarker(markdownText: string, markerIndex: number): FenceMarker | null {
  if (markerIndex < 0 || markerIndex >= markdownText.length) {
    return null;
  }
  const markerCharacter = markdownText[markerIndex];
  if (markerCharacter !== "`" && markerCharacter !== "~") {
    return null;
  }

  let markerLength = 0;
  while (
    markerIndex + markerLength < markdownText.length &&
    markdownText[markerIndex + markerLength] === markerCharacter
  ) {
    markerLength++;
  }
  return markerLength >= FENCE_MIN_LENGTH
    ? { character: markerCharacter, length: markerLength }
    : null;
}

function leadingSpaceCount(markdownLine: string): number {
  let indentationSpaces = 0;
  while (indentationSpaces < markdownLine.length && markdownLine[indentationSpaces] === " ") {
    indentationSpaces++;
  }
  return indentationSpaces;
}

function hasOnlyClosingFenceSpacing(markdownLine: string, suffixStartIndex: number): boolean {
  for (let cursor = suffixStartIndex; cursor < markdownLine.length; cursor++) {
    const character = markdownLine[cursor];
    if (character === "\r") {
      return cursor === markdownLine.length - 1;
    }
    if (character !== " " && character !== "\t") {
      return false;
    }
  }
  return true;
}

function numericOrderedListContinuationIndentation(markdownLine: string): number | null {
  const indentationSpaces = leadingSpaceCount(markdownLine);
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

  let firstTextIndex = markerEndIndex;
  while (
    firstTextIndex < markdownLine.length &&
    (markdownLine[firstTextIndex] === " " || markdownLine[firstTextIndex] === "\t")
  ) {
    firstTextIndex++;
  }
  if (firstTextIndex >= markdownLine.length) {
    return markerEndIndex + 1;
  }
  const markerPadding = firstTextIndex - markerEndIndex;
  return markerEndIndex + (markerPadding > COMMONMARK_INDENTED_CODE_SPACES ? 1 : markerPadding);
}

function scanFenceWithinListContinuation(
  markdownLine: string,
  maximumIndentation: number,
): { marker: FenceMarker; markerIndex: number } | null {
  const markerIndex = leadingSpaceCount(markdownLine);
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

/**
 * Keeps streamed fences attached to numeric list items that Marked has already identified.
 */
export function nestNumericListFences(markdownText: string): string {
  const lineFeedMarkdown = markdownText.replaceAll("\r\n", NEWLINE).replaceAll("\r", NEWLINE);
  const markdownLines = lineFeedMarkdown.split(NEWLINE);
  const parsedListMarkerLines = parsedNumericListMarkerLines(lineFeedMarkdown);
  const nestedLines: string[] = [];
  const fenceState = new ListFenceState();
  let awaitedContinuationIndentation: number | null = null;
  let bodyIndentation = "";
  let targetIndentation = 0;
  let maximumSourceIndentation = COMMONMARK_MAX_FENCE_INDENTATION;

  for (let lineIndex = 0; lineIndex < markdownLines.length; lineIndex++) {
    const markdownLine = markdownLines[lineIndex];
    if (fenceState.isOpen()) {
      const fenceCandidate = scanFenceWithinListContinuation(
        markdownLine,
        maximumSourceIndentation,
      );
      if (
        fenceCandidate &&
        fenceState.closesWith(markdownLine, fenceCandidate.markerIndex, fenceCandidate.marker)
      ) {
        nestedLines.push(
          `${" ".repeat(targetIndentation)}${markdownLine.slice(fenceCandidate.markerIndex)}`,
        );
        fenceState.close();
        bodyIndentation = "";
        targetIndentation = 0;
        maximumSourceIndentation = COMMONMARK_MAX_FENCE_INDENTATION;
      } else {
        nestedLines.push(`${bodyIndentation}${markdownLine}`);
      }
      continue;
    }

    const continuationIndentation = numericOrderedListContinuationIndentation(markdownLine);
    if (continuationIndentation !== null) {
      const nextLine = markdownLines[lineIndex + 1];
      const nextLineStartsFence =
        nextLine !== undefined &&
        scanFenceWithinListContinuation(
          nextLine,
          continuationIndentation + COMMONMARK_MAX_FENCE_INDENTATION,
        ) !== null;
      awaitedContinuationIndentation =
        nextLineStartsFence && parsedListMarkerLines.has(lineIndex)
          ? continuationIndentation
          : null;
      nestedLines.push(markdownLine);
      continue;
    }

    if (awaitedContinuationIndentation !== null) {
      const fenceCandidate = scanFenceWithinListContinuation(
        markdownLine,
        awaitedContinuationIndentation + COMMONMARK_MAX_FENCE_INDENTATION,
      );
      if (fenceCandidate) {
        bodyIndentation = " ".repeat(
          Math.max(0, awaitedContinuationIndentation - fenceCandidate.markerIndex),
        );
        targetIndentation = awaitedContinuationIndentation;
        maximumSourceIndentation =
          awaitedContinuationIndentation +
          COMMONMARK_MAX_FENCE_INDENTATION -
          bodyIndentation.length;
        fenceState.open(fenceCandidate.marker);
        awaitedContinuationIndentation = null;
        nestedLines.push(
          `${" ".repeat(targetIndentation)}${markdownLine.slice(fenceCandidate.markerIndex)}`,
        );
        continue;
      }
      awaitedContinuationIndentation = null;
    }
    nestedLines.push(markdownLine);
  }

  const unfinishedMarker = fenceState.unfinishedMarker();
  if (unfinishedMarker) {
    nestedLines.push(
      `${" ".repeat(targetIndentation)}${unfinishedMarker.character.repeat(
        Math.max(unfinishedMarker.length, FENCE_MIN_LENGTH),
      )}`,
    );
  }
  return nestedLines.join(NEWLINE);
}
