package com.williamcallahan.javachat.service.markdown;

import java.util.Optional;

/**
 * Scans for ordered list markers (numeric, letter, roman) in text.
 *
 * <p>Consolidates marker detection logic shared between {@link MarkdownNormalizer}
 * and {@link InlineListParser}. Supports:</p>
 * <ul>
 *   <li>Numeric markers: 1. 2. 3. or 1) 2) 3)</li>
 *   <li>Lowercase letter markers: a. b. c. or a) b) c)</li>
 *   <li>Lowercase roman numerals: i. ii. iii. or i) ii) iii)</li>
 * </ul>
 */
final class OrderedMarkerScanner {

    private static final int MAX_NUMERIC_DIGITS = 3;
    private static final int MAX_ROMAN_LENGTH = 6;

    private OrderedMarkerScanner() {}

    /**
     * Describes a detected ordered list marker.
     *
     * @param startIndex position where the marker begins
     * @param afterIndex position after the marker (start of content)
     * @param kind the type of ordered marker detected
     */
    record MarkerMatch(int startIndex, int afterIndex, InlineListOrderedKind kind) {}

    /**
     * Strategy for reading a specific type of ordered marker.
     */
    @FunctionalInterface
    private interface MarkerStrategy {
        /**
         * Attempts to read the marker sequence starting at the given index.
         *
         * @param text source text
         * @param index position to start reading
         * @return cursor position after the marker sequence, or -1 if not found
         */
        int readSequence(String text, int index);
    }

    /**
     * Checks if text starts with any ordered list marker per CommonMark spec.
     *
     * <p>Unlike {@link #scanAt}, this method enforces CommonMark's requirement
     * for whitespace after the marker delimiter. Text like "1.Foo" is rejected
     * because there's no space between "." and "Foo".</p>
     *
     * @param trimmedLine line with leading whitespace already removed
     * @return true if the line starts with a CommonMark-compliant ordered marker
     */
    static boolean startsWithOrderedMarker(String trimmedLine) {
        if (trimmedLine == null || trimmedLine.isEmpty()) {
            return false;
        }
        var match = scanAt(trimmedLine, 0);
        if (match.isEmpty()) {
            return false;
        }
        // CommonMark requires whitespace after marker delimiter (. or ))
        // Reject "1.Foo" but accept "1. Foo" or "1." at end of line
        for (int cursor = 0; cursor < trimmedLine.length(); cursor++) {
            char c = trimmedLine.charAt(cursor);
            if (c == '.' || c == ')') {
                // Found delimiter at position cursor
                int posAfterDelimiter = cursor + 1;
                // Valid if: at end of line, or next char is whitespace
                return posAfterDelimiter >= trimmedLine.length()
                        || Character.isWhitespace(trimmedLine.charAt(posAfterDelimiter));
            }
        }
        return false;
    }

    /**
     * Scans for an ordered marker at the specified position, trying all marker types.
     *
     * @param text source text to scan
     * @param index position to check for a marker
     * @return marker match if found, empty otherwise
     */
    static Optional<MarkerMatch> scanAt(String text, int index) {
        if (text == null || index < 0 || index >= text.length()) {
            return Optional.empty();
        }

        for (InlineListOrderedKind kind : InlineListOrderedKind.values()) {
            MarkerMatch match = tryReadMarker(text, index, kind);
            if (match != null) {
                return Optional.of(match);
            }
        }

        return Optional.empty();
    }

    /**
     * Scans for a specific kind of ordered marker at the specified position.
     *
     * @param text source text to scan
     * @param index position to check for a marker
     * @param kind the type of ordered marker to look for
     * @return marker match if found, empty otherwise
     */
    static Optional<MarkerMatch> scanAt(String text, int index, InlineListOrderedKind kind) {
        if (text == null || index < 0 || index >= text.length()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tryReadMarker(text, index, kind));
    }

    private static MarkerMatch tryReadMarker(String text, int index, InlineListOrderedKind kind) {
        MarkerStrategy strategy = strategyFor(kind);
        int sequenceEnd = strategy.readSequence(text, index);
        if (sequenceEnd < 0) return null;

        return finalizeMarker(text, index, sequenceEnd, kind);
    }

    private static MarkerStrategy strategyFor(InlineListOrderedKind kind) {
        return switch (kind) {
            case NUMERIC -> OrderedMarkerScanner::readNumericSequence;
            case LETTER_LOWER -> OrderedMarkerScanner::readLetterSequence;
            case ROMAN_LOWER -> OrderedMarkerScanner::readRomanSequence;
        };
    }

    private static MarkerMatch finalizeMarker(
            String text, int startIndex, int sequenceEnd, InlineListOrderedKind kind) {
        if (sequenceEnd >= text.length()) return null;

        char markerChar = text.charAt(sequenceEnd);
        if (markerChar != '.' && markerChar != ')') return null;

        // Reject version numbers like 1.8 for numeric markers
        if (kind == InlineListOrderedKind.NUMERIC
                && sequenceEnd + 1 < text.length()
                && Character.isDigit(text.charAt(sequenceEnd + 1))) {
            return null;
        }

        int afterIndex = sequenceEnd + 1;
        while (afterIndex < text.length() && Character.isWhitespace(text.charAt(afterIndex))) {
            afterIndex++;
        }

        return new MarkerMatch(startIndex, afterIndex, kind);
    }

    private static int readNumericSequence(String text, int index) {
        int cursor = index;
        int digitCount = 0;
        while (cursor < text.length() && Character.isDigit(text.charAt(cursor)) && digitCount < MAX_NUMERIC_DIGITS) {
            digitCount++;
            cursor++;
        }
        return digitCount > 0 ? cursor : -1;
    }

    private static int readLetterSequence(String text, int index) {
        if (index >= text.length()) return -1;
        char letter = text.charAt(index);
        return (letter >= 'a' && letter <= 'z') ? index + 1 : -1;
    }

    private static int readRomanSequence(String text, int index) {
        int cursor = index;
        int length = 0;
        while (cursor < text.length() && length < MAX_ROMAN_LENGTH) {
            char romanChar = text.charAt(cursor);
            if (romanChar != 'i' && romanChar != 'v' && romanChar != 'x') break;
            length++;
            cursor++;
        }
        return length > 0 ? cursor : -1;
    }
}
