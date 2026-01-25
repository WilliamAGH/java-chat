package com.williamcallahan.javachat.service.markdown;

import java.util.ArrayList;
import java.util.List;
import org.jsoup.nodes.Element;

/**
 * Parses inline list markers from flat text into structured list elements.
 *
 * <p>Recognizes patterns like "Key points: 1. First 2. Second" and converts them
 * to proper HTML list structures. Supports numeric (1. 2. 3.), roman (i. ii. iii.),
 * letter (a. b. c.), and bullet (-, *, +, •) markers.</p>
 *
 * <p>This is a stateless parser with all utility methods exposed statically.
 * Follow the pattern established by {@link CodeFenceStateTracker}.</p>
 */
final class InlineListParser {

    private static final int MAX_NESTED_DEPTH = 3;
    private static final int MIN_MARKER_COUNT = 2;
    private static final int BULLET_MARKER_WIDTH = 2;
    private static final int COLON_BACKTRACK_OFFSET = 2;

    private InlineListParser() {}

    /**
     * Attempts to convert inline list text to structured HTML elements.
     *
     * @param text raw text potentially containing inline list markers
     * @return conversion result with leading text, list elements, and trailing text; null if no list found
     */
    static Conversion tryConvert(String text) {
        Parse parse = Parse.tryParse(text);
        if (parse == null) {
            return null;
        }

        Element listElement = new Element(parse.primaryBlock().tagName());
        for (String entryLabel : parse.primaryBlock().entryLabels()) {
            listElement.appendChild(new Element("li").text(entryLabel));
        }

        List<Element> additionalLists = new ArrayList<>();
        for (String nestedSegment : parse.nestedSegments()) {
            Parse nestedParse = Parse.tryParse(nestedSegment);
            if (nestedParse == null) continue;
            Element nestedListElement = new Element(nestedParse.primaryBlock().tagName());
            for (String entryLabel : nestedParse.primaryBlock().entryLabels()) {
                nestedListElement.appendChild(new Element("li").text(entryLabel));
            }
            additionalLists.add(nestedListElement);
            additionalLists.addAll(renderNestedListsRecursively(nestedParse, 1));
        }

        return new Conversion(parse.leadingText(), listElement, additionalLists, parse.trailingText());
    }

    private static List<Element> renderNestedListsRecursively(Parse parse, int depth) {
        if (depth >= MAX_NESTED_DEPTH) {
            return List.of();
        }
        List<Element> listElements = new ArrayList<>();
        for (String nestedSegment : parse.nestedSegments()) {
            Parse nestedParse = Parse.tryParse(nestedSegment);
            if (nestedParse == null) continue;
            Element nestedListElement = new Element(nestedParse.primaryBlock().tagName());
            for (String entryLabel : nestedParse.primaryBlock().entryLabels()) {
                nestedListElement.appendChild(new Element("li").text(entryLabel));
            }
            listElements.add(nestedListElement);
            listElements.addAll(renderNestedListsRecursively(nestedParse, depth + 1));
        }
        return listElements;
    }

    /**
     * Result of converting inline text to a list structure.
     *
     * @param leadingText text before the first list marker
     * @param primaryListElement the main list element (ol or ul)
     * @param additionalListElements any nested lists extracted from items
     * @param trailingText text after the last list item
     */
    record Conversion(
            String leadingText,
            Element primaryListElement,
            List<Element> additionalListElements,
            String trailingText) {}

    /**
     * Represents a parsed inline list with its items and structure.
     *
     * @param tagName "ol" for ordered lists, "ul" for unordered
     * @param entryLabels the text content of each list entry
     */
    record Block(String tagName, List<String> entryLabels) {}

    /**
     * Result of parsing inline list markers from text.
     *
     * @param leadingText text before the first marker
     * @param primaryBlock the main list block
     * @param nestedSegments segments that may contain nested lists
     * @param trailingText text after the last item
     */
    record Parse(String leadingText, Block primaryBlock, List<String> nestedSegments, String trailingText) {
        static Parse tryParse(String input) {
            if (input == null) return null;
            String text = input.strip();
            if (text.isEmpty()) return null;

            Parse ordered = tryParseOrdered(text);
            if (ordered != null) return ordered;
            return tryParseBulleted(text);
        }

        private static Parse tryParseOrdered(String text) {
            Parse numeric = parseInlineListOrderedKind(text, InlineListOrderedKind.NUMERIC);
            if (numeric != null) return numeric;

            Parse roman = parseInlineListOrderedKind(text, InlineListOrderedKind.ROMAN_LOWER);
            if (roman != null) return roman;

            return parseInlineListOrderedKind(text, InlineListOrderedKind.LETTER_LOWER);
        }

        private static Parse parseInlineListOrderedKind(String text, InlineListOrderedKind kind) {
            List<Marker> markers = findOrderedMarkers(text, kind);
            if (markers.size() < MIN_MARKER_COUNT) return null;

            int firstMarkerIndex = markers.get(0).markerStartIndex();
            String leading = text.substring(0, firstMarkerIndex).trim();

            List<String> entryLabels = new ArrayList<>();
            List<String> nestedSegments = new ArrayList<>();
            String trailingText = "";
            for (int markerIndex = 0; markerIndex < markers.size(); markerIndex++) {
                int contentStart = markers.get(markerIndex).contentStartIndex();
                int nextMarkerStart = markerIndex + 1 < markers.size()
                        ? markers.get(markerIndex + 1).markerStartIndex()
                        : text.length();
                String rawEntryText =
                        text.substring(contentStart, nextMarkerStart).trim();
                if (rawEntryText.isEmpty()) continue;

                boolean isLastMarker = markerIndex == markers.size() - 1;
                EntryTextSplit entryTextSplit =
                        isLastMarker ? extractTrailingText(rawEntryText) : new EntryTextSplit(rawEntryText, "");
                ParsedEntry parsedEntry = splitNestedList(entryTextSplit.entryText());
                entryLabels.add(parsedEntry.label());
                if (parsedEntry.nestedSegment() != null
                        && !parsedEntry.nestedSegment().isBlank()) {
                    nestedSegments.add(parsedEntry.nestedSegment());
                }
                if (!entryTextSplit.trailingText().isBlank()) {
                    trailingText = entryTextSplit.trailingText();
                }
            }

            if (entryLabels.size() < MIN_MARKER_COUNT) return null;

            Block primaryBlock = new Block("ol", entryLabels);
            return new Parse(leading, primaryBlock, nestedSegments, trailingText);
        }

        private static Parse tryParseBulleted(String text) {
            InlineListBulletKind bulletKind = findFirstInlineListBulletKind(text);
            if (bulletKind == null) return null;

            List<Marker> markers = findBulletMarkers(text, bulletKind);
            if (markers.size() < MIN_MARKER_COUNT) return null;

            int firstMarkerIndex = markers.get(0).markerStartIndex();
            String leading = text.substring(0, firstMarkerIndex).trim();

            List<String> entryLabels = new ArrayList<>();
            List<String> nestedSegments = new ArrayList<>();
            String trailingText = "";
            for (int markerIndex = 0; markerIndex < markers.size(); markerIndex++) {
                int contentStart = markers.get(markerIndex).contentStartIndex();
                int nextMarkerStart = markerIndex + 1 < markers.size()
                        ? markers.get(markerIndex + 1).markerStartIndex()
                        : text.length();
                String rawEntryText =
                        text.substring(contentStart, nextMarkerStart).trim();
                if (rawEntryText.isEmpty()) continue;
                boolean isLastMarker = markerIndex == markers.size() - 1;
                EntryTextSplit entryTextSplit =
                        isLastMarker ? extractTrailingText(rawEntryText) : new EntryTextSplit(rawEntryText, "");
                ParsedEntry parsedEntry = splitNestedList(entryTextSplit.entryText());
                entryLabels.add(parsedEntry.label());
                if (parsedEntry.nestedSegment() != null
                        && !parsedEntry.nestedSegment().isBlank()) {
                    nestedSegments.add(parsedEntry.nestedSegment());
                }
                if (!entryTextSplit.trailingText().isBlank()) {
                    trailingText = entryTextSplit.trailingText();
                }
            }

            if (entryLabels.size() < MIN_MARKER_COUNT) return null;

            Block primaryBlock = new Block("ul", entryLabels);
            return new Parse(leading, primaryBlock, nestedSegments, trailingText);
        }

        private static InlineListBulletKind findFirstInlineListBulletKind(String text) {
            for (int index = 0; index < text.length(); index++) {
                char character = text.charAt(index);
                InlineListBulletKind kind = bulletKind(character);
                if (kind == null) continue;
                if (isBulletListIntro(text, index) && hasSecondBulletMarker(text, kind, index + 1)) {
                    return kind;
                }
            }
            return null;
        }

        private static boolean hasSecondBulletMarker(String text, InlineListBulletKind kind, int startIndex) {
            for (int index = startIndex; index < text.length(); index++) {
                if (text.charAt(index) == kind.markerChar() && isBulletMarker(text, index, kind)) {
                    return true;
                }
            }
            return false;
        }

        private static InlineListBulletKind bulletKind(char character) {
            return switch (character) {
                case '-' -> InlineListBulletKind.DASH;
                case '*' -> InlineListBulletKind.ASTERISK;
                case '+' -> InlineListBulletKind.PLUS;
                case '•' -> InlineListBulletKind.BULLET;
                default -> null;
            };
        }

        private static boolean isBulletListIntro(String text, int markerIndex) {
            if (markerIndex == 0) return true;
            char previousChar = text.charAt(markerIndex - 1);
            return previousChar == ':'
                    || previousChar == '\n'
                    || (previousChar == ' '
                            && markerIndex >= COLON_BACKTRACK_OFFSET
                            && text.charAt(markerIndex - COLON_BACKTRACK_OFFSET) == ':');
        }

        private static boolean isBulletMarker(String text, int markerIndex, InlineListBulletKind kind) {
            return text.charAt(markerIndex) == kind.markerChar()
                    && markerIndex + 1 < text.length()
                    && text.charAt(markerIndex + 1) == ' ';
        }

        private static List<Marker> findBulletMarkers(String text, InlineListBulletKind kind) {
            List<Marker> markers = new ArrayList<>();
            boolean hasIntro = false;
            for (int index = 0; index < text.length(); index++) {
                if (text.charAt(index) != kind.markerChar()) continue;
                if (!hasIntro) {
                    if (!isBulletListIntro(text, index)) continue;
                    if (!isBulletMarker(text, index, kind)) continue;
                    hasIntro = true;
                    markers.add(new Marker(index, index + BULLET_MARKER_WIDTH));
                    continue;
                }
                if (isBulletMarker(text, index, kind)) {
                    markers.add(new Marker(index, index + BULLET_MARKER_WIDTH));
                }
            }
            return markers;
        }

        private static List<Marker> findOrderedMarkers(String text, InlineListOrderedKind kind) {
            List<Marker> markers = new ArrayList<>();
            int index = 0;
            while (index < text.length()) {
                Marker marker = tryReadOrderedMarkerAt(text, index, kind);
                if (marker != null) {
                    markers.add(marker);
                    index = marker.contentStartIndex();
                    continue;
                }
                index++;
            }
            return markers;
        }

        private static Marker tryReadOrderedMarkerAt(String text, int index, InlineListOrderedKind kind) {
            if (index < 0 || index >= text.length()) return null;
            if (!isMarkerBoundary(text, index)) return null;

            OrderedMarkerScanner.MarkerMatch match =
                    OrderedMarkerScanner.scanAt(text, index, kind).orElse(null);
            if (match == null) return null;
            if (!isContentStartValid(text, match.afterIndex())) return null;

            return new Marker(match.startIndex(), match.afterIndex());
        }

        private static boolean isMarkerBoundary(String text, int index) {
            if (index == 0) return true;
            char previousChar = text.charAt(index - 1);
            return !Character.isLetterOrDigit(previousChar);
        }

        private static boolean isContentStartValid(String text, int contentStart) {
            return contentStart < text.length() && !Character.isWhitespace(text.charAt(contentStart));
        }

        private static ParsedEntry splitNestedList(String rawEntryText) {
            String trimmed = rawEntryText.trim();
            int colonIndex = trimmed.indexOf(':');
            if (colonIndex > 0 && colonIndex < trimmed.length() - 1) {
                String labelPart = trimmed.substring(0, colonIndex).trim();
                String tail = trimmed.substring(colonIndex + 1).trim();
                if (Parse.tryParse(tail) != null) {
                    return new ParsedEntry(normalizeEntryLabel(labelPart), tail);
                }
            }
            return new ParsedEntry(normalizeEntryLabel(trimmed), null);
        }

        private static String normalizeEntryLabel(String raw) {
            String label = raw == null ? "" : raw.trim();
            int colonIndex = label.indexOf(':');
            if (colonIndex > 0) {
                label = label.substring(0, colonIndex).trim();
            }
            while (!label.isEmpty()) {
                char last = label.charAt(label.length() - 1);
                if (last == '.' || last == '!' || last == '?') {
                    label = label.substring(0, label.length() - 1).trim();
                } else {
                    break;
                }
            }
            return label;
        }

        private static EntryTextSplit extractTrailingText(String rawEntryText) {
            if (rawEntryText == null || rawEntryText.isBlank()) {
                return new EntryTextSplit("", "");
            }
            int trailingStart = findTrailingTextStart(rawEntryText);
            if (trailingStart < 0) {
                return new EntryTextSplit(rawEntryText, "");
            }
            String entryText = rawEntryText.substring(0, trailingStart).trim();
            String trailingText = rawEntryText.substring(trailingStart).trim();
            return new EntryTextSplit(entryText, trailingText);
        }

        private static int findTrailingTextStart(String rawEntryText) {
            int trailingStart = -1;
            for (int index = 0; index < rawEntryText.length(); index++) {
                char token = rawEntryText.charAt(index);
                if (token == '.' || token == '!' || token == '?') {
                    if (index + 1 < rawEntryText.length()) {
                        int candidateStart = index + 1;
                        boolean sawWhitespace = false;
                        while (candidateStart < rawEntryText.length()
                                && Character.isWhitespace(rawEntryText.charAt(candidateStart))) {
                            sawWhitespace = true;
                            candidateStart++;
                        }
                        if (!sawWhitespace) {
                            continue; // punctuation inside token (e.g., 1.8, e.g.)
                        }
                        if (candidateStart < rawEntryText.length()) {
                            trailingStart = candidateStart;
                        }
                    }
                    break;
                }
            }
            return trailingStart;
        }
    }

    /**
     * Position information for a detected marker.
     *
     * @param markerStartIndex index where the marker begins
     * @param contentStartIndex index where the item content begins
     */
    private record Marker(int markerStartIndex, int contentStartIndex) {}

    /**
     * Result of splitting an item into its label and optional nested content.
     *
     * @param label the cleaned item label
     * @param nestedSegment text that may contain a nested list, or null
     */
    private record ParsedEntry(String label, String nestedSegment) {}

    private record EntryTextSplit(String entryText, String trailingText) {}
}
