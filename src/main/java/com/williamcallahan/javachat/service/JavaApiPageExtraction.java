package com.williamcallahan.javachat.service;

import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Represents the structured, indexable portions of one Java API HTML page.
 *
 * <p>Overview text remains page-level while member details retain their authoritative anchors,
 * preventing a later citation stage from guessing a member from unrelated prose.</p>
 *
 * @param disposition indexing decision derived from the Javadoc page kind
 * @param overviewText page-level documentation without a member anchor
 * @param anchoredSections member documentation in source DOM order
 */
public record JavaApiPageExtraction(
        JavaApiPageDisposition disposition, String overviewText, List<JavaApiAnchoredSection> anchoredSections) {

    /**
     * Enforces immutable extraction components and prevents excluded pages from carrying indexable text.
     *
     * @throws IllegalArgumentException when an excluded page carries documentation
     */
    public JavaApiPageExtraction {
        Objects.requireNonNull(disposition, "disposition");
        Objects.requireNonNull(overviewText, "overviewText");
        anchoredSections = List.copyOf(Objects.requireNonNull(anchoredSections, "anchoredSections"));
        if (disposition == JavaApiPageDisposition.EXCLUDED_CLASS_USE_PAGE
                && (!overviewText.isEmpty() || !anchoredSections.isEmpty())) {
            throw new IllegalArgumentException("excluded Java API pages must not carry indexable text");
        }
    }

    /**
     * Creates an indexable Java API page extraction.
     *
     * @param overviewText page-level documentation without a member anchor
     * @param anchoredSections member documentation in source DOM order
     * @return included page extraction
     */
    public static JavaApiPageExtraction included(String overviewText, List<JavaApiAnchoredSection> anchoredSections) {
        return new JavaApiPageExtraction(JavaApiPageDisposition.INCLUDED, overviewText, anchoredSections);
    }

    /**
     * Creates the explicit result for a Javadoc class-use page.
     *
     * @return excluded class-use page extraction
     */
    public static JavaApiPageExtraction excludedClassUsePage() {
        return new JavaApiPageExtraction(JavaApiPageDisposition.EXCLUDED_CLASS_USE_PAGE, "", List.of());
    }

    /**
     * Indicates whether this source page must be excluded from ingestion.
     *
     * @return true only for a class-use page
     */
    public boolean excluded() {
        return disposition == JavaApiPageDisposition.EXCLUDED_CLASS_USE_PAGE;
    }

    /**
     * Joins the page-level and member text for consumers that require one diagnostic string.
     *
     * <p>Ingestion preserves the structured components instead; this projection is only suitable
     * for non-indexing diagnostics such as content validation.</p>
     *
     * @return nonblank source text joined in DOM order, or empty for an excluded page
     */
    public String combinedText() {
        StringJoiner extractedText = new StringJoiner("\n\n");
        if (!overviewText.isBlank()) {
            extractedText.add(overviewText);
        }
        for (JavaApiAnchoredSection anchoredSection : anchoredSections) {
            extractedText.add(anchoredSection.text());
        }
        return extractedText.toString();
    }
}
