package com.williamcallahan.javachat.domain.markdown;

/**
 * Base interface for structured enrichment elements.
 * This replaces regex-based enrichment processing with type-safe objects.
 *
 * Note: Named MarkdownEnrichment to avoid conflict with existing model.Enrichment class.
 */
public sealed interface MarkdownEnrichment permits Hint, Warning, Background, Example, Reminder {

    /**
     * Gets the enrichment type identifier.
     * @return type string
     */
    String type();

    /**
     * Gets the enrichment content.
     * @return content string
     */
    String content();

    /**
     * Gets the enrichment priority for rendering order.
     * @return priority level
     */
    EnrichmentPriority priority();

    /**
     * Gets the position in the document where this enrichment was found.
     * @return document position
     */
    int position();

    /**
     * Reports whether enrichment text would render without a visible character.
     *
     * <p>{@link String#isBlank()} intentionally excludes non-breaking spaces such as U+00A0.
     * Enrichment cards also treat Unicode separator characters recognized by
     * {@link Character#isSpaceChar(int)} as blank.</p>
     *
     * @return {@code true} for null, empty, Java-whitespace-only, or Unicode-space-only text
     */
    static boolean isBlankEnrichmentText(String enrichmentText) {
        return enrichmentText == null
                || enrichmentText.codePoints().allMatch(MarkdownEnrichment::isWhitespaceOrSpaceCharacter);
    }

    private static boolean isWhitespaceOrSpaceCharacter(int codePoint) {
        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint)
                || isZeroWidthNoBreakSpace(codePoint);
    }

    private static boolean isZeroWidthNoBreakSpace(int codePoint) {
        return codePoint == '\uFEFF';
    }
}
