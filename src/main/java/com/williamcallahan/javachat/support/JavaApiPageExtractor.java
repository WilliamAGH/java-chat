package com.williamcallahan.javachat.support;

import com.williamcallahan.javachat.domain.javaapi.JavadocMemberAnchor;
import com.williamcallahan.javachat.service.JavaApiAnchoredSection;
import com.williamcallahan.javachat.service.JavaApiPageExtraction;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * Extracts structured overview and member documentation from Java API pages.
 *
 * <p>Page classification and member identity are Javadoc-specific responsibilities. Generic HTML
 * cleanup remains supplied by the owning HTML extractor so both paths retain identical filtering
 * and whitespace behavior without duplicating those rules.</p>
 */
public final class JavaApiPageExtractor {

    private static final String JAVA_API_CLASS_DECLARATION_PAGE_CLASS = "class-declaration-page";
    private static final String JAVA_API_CLASS_USE_PAGE_CLASS = "class-use-page";
    private static final String JAVA_API_CLASS_TITLE_SELECTOR = "main > .header .title, .header .title, h1.title";
    private static final String JAVA_API_CLASS_DESCRIPTION_SELECTOR =
            "main > section.class-description, section.class-description";
    private static final String JAVA_API_MEMBER_DETAIL_SELECTOR = "body.class-declaration-page section.detail[id]";

    private final Function<Document, String> genericHtmlTextExtractor;
    private final Consumer<Element> nonContentElementRemover;
    private final UnaryOperator<String> noiseFilter;
    private final Predicate<String> excessiveNoiseDetector;

    /**
     * Creates the focused extractor with the generic cleanup operations shared by HTML ingestion.
     *
     * @param genericHtmlTextExtractor extracts generic text from a mutable document clone
     * @param nonContentElementRemover removes navigation and other non-content descendants
     * @param noiseFilter filters known noise and normalizes prose whitespace
     * @param excessiveNoiseDetector identifies text dominated by known navigation noise
     */
    public JavaApiPageExtractor(
            Function<Document, String> genericHtmlTextExtractor,
            Consumer<Element> nonContentElementRemover,
            UnaryOperator<String> noiseFilter,
            Predicate<String> excessiveNoiseDetector) {
        this.genericHtmlTextExtractor = Objects.requireNonNull(genericHtmlTextExtractor, "genericHtmlTextExtractor");
        this.nonContentElementRemover = Objects.requireNonNull(nonContentElementRemover, "nonContentElementRemover");
        this.noiseFilter = Objects.requireNonNull(noiseFilter, "noiseFilter");
        this.excessiveNoiseDetector = Objects.requireNonNull(excessiveNoiseDetector, "excessiveNoiseDetector");
    }

    /**
     * Extracts one Java API page while retaining exact member DOM identifiers in source order.
     *
     * @param document parsed Java API HTML document
     * @return included overview/member sections or an explicit class-use exclusion
     */
    public JavaApiPageExtraction extract(Document document) {
        Document extractionDocument =
                Objects.requireNonNull(document, "document").clone();
        Element documentBody = Objects.requireNonNull(extractionDocument.body(), "document.body");
        if (documentBody.hasClass(JAVA_API_CLASS_USE_PAGE_CLASS)) {
            return JavaApiPageExtraction.excludedClassUsePage();
        }
        if (!documentBody.hasClass(JAVA_API_CLASS_DECLARATION_PAGE_CLASS)) {
            return JavaApiPageExtraction.included(extractUnanchoredOverview(extractionDocument), List.of());
        }

        String overviewText = extractClassOverview(extractionDocument);
        List<JavaApiAnchoredSection> anchoredSections = extractAnchoredSections(extractionDocument);
        return JavaApiPageExtraction.included(overviewText, anchoredSections);
    }

    private String extractClassOverview(Document extractionDocument) {
        StringBuilder overviewText = new StringBuilder();
        appendElementText(overviewText, extractionDocument.selectFirst(JAVA_API_CLASS_TITLE_SELECTOR));
        appendElementText(overviewText, extractionDocument.selectFirst(JAVA_API_CLASS_DESCRIPTION_SELECTOR));
        return noiseFilter.apply(overviewText.toString()).trim();
    }

    private String extractUnanchoredOverview(Document extractionDocument) {
        StringBuilder overviewText = new StringBuilder();
        appendElementText(overviewText, extractionDocument.selectFirst(JAVA_API_CLASS_TITLE_SELECTOR));
        appendText(overviewText, genericHtmlTextExtractor.apply(extractionDocument));
        return noiseFilter.apply(overviewText.toString()).trim();
    }

    private List<JavaApiAnchoredSection> extractAnchoredSections(Document extractionDocument) {
        List<JavaApiAnchoredSection> anchoredSections = new ArrayList<>();
        for (Element memberDetail : extractionDocument.select(JAVA_API_MEMBER_DETAIL_SELECTOR)) {
            String memberText = extractMemberText(memberDetail);
            if (!memberText.isBlank()) {
                JavadocMemberAnchor memberAnchor = new JavadocMemberAnchor(memberDetail.id());
                anchoredSections.add(new JavaApiAnchoredSection(memberAnchor, memberText));
            }
        }
        return List.copyOf(anchoredSections);
    }

    private String extractMemberText(Element memberDetail) {
        Element extractionDetail = memberDetail.clone();
        nonContentElementRemover.accept(extractionDetail);
        Element signatureElement = extractionDetail.selectFirst(".member-signature");
        String signatureText =
                signatureElement == null ? "" : signatureElement.text().trim();
        String detailText = extractionDetail.text().trim();
        if (!signatureText.isBlank() && !detailText.contains(signatureText)) {
            detailText = signatureText + "\n\n" + detailText;
        }
        return noiseFilter.apply(detailText).trim();
    }

    private void appendElementText(StringBuilder overviewText, Element sourceElement) {
        if (sourceElement == null) {
            return;
        }
        Element extractionElement = sourceElement.clone();
        nonContentElementRemover.accept(extractionElement);
        appendText(overviewText, extractionElement.text());
    }

    private void appendText(StringBuilder overviewText, String sourceText) {
        String sectionText = sourceText.trim();
        if (sectionText.isBlank() || excessiveNoiseDetector.test(sectionText)) {
            return;
        }
        if (!overviewText.isEmpty()) {
            overviewText.append("\n\n");
        }
        overviewText.append(sectionText);
    }
}
