package com.williamcallahan.javachat.service.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.williamcallahan.javachat.domain.markdown.MarkdownEnrichment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies enrichment markers are handled safely around code fences.
 */
class EnrichmentPlaceholderizerTest {

    private EnrichmentPlaceholderizer placeholderizer;

    @BeforeEach
    void setUp() {
        MutableDataSet options = new MutableDataSet();
        Parser parser = Parser.builder(options).build();
        HtmlRenderer renderer = HtmlRenderer.builder(options).build();
        placeholderizer = new EnrichmentPlaceholderizer(parser, renderer);
    }

    @Test
    void shouldIgnoreEnrichmentInTildeFence() {
        String markdown = "~~~\n{{hint: Should be ignored}}\n~~~";
        List<MarkdownEnrichment> enrichments = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();

        String processedMarkdown =
                placeholderizer.extractAndPlaceholderizeEnrichments(markdown, enrichments, placeholders);

        assertFalse(processedMarkdown.contains("ENRICHMENT_"), "Enrichment should not be processed inside tilde fence");
        assertTrue(processedMarkdown.contains("{{hint:"), "Original text should be preserved");
    }

    @Test
    void shouldProcessValidEnrichment() {
        String markdown = "{{hint: valid}}";
        List<MarkdownEnrichment> enrichments = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();

        String processedMarkdown =
                placeholderizer.extractAndPlaceholderizeEnrichments(markdown, enrichments, placeholders);

        assertTrue(processedMarkdown.contains("ENRICHMENT_"), "Enrichment should be processed");
        assertFalse(processedMarkdown.contains("{{hint:"), "Original text should be replaced");
    }

    @Test
    void shouldDiscardUnicodeWhitespaceOnlyEnrichmentBeforeCreatingPlaceholder() {
        String markdown = "{{hint: \t\u00A0\u2003\u3000\n}}";
        List<MarkdownEnrichment> enrichments = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();

        String processedMarkdown =
                placeholderizer.extractAndPlaceholderizeEnrichments(markdown, enrichments, placeholders);

        assertTrue(processedMarkdown.isEmpty(), "Blank marker should be consumed without rendered text");
        assertTrue(enrichments.isEmpty(), "Blank marker should not create a domain enrichment");
        assertTrue(placeholders.isEmpty(), "Blank marker should not create a render placeholder");
    }

    @Test
    void shouldIgnoreEnrichmentInsideInlineCode() {
        String markdown = "Use `{{hint: inline}}` to show markers.";
        List<MarkdownEnrichment> enrichments = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();

        String processedMarkdown =
                placeholderizer.extractAndPlaceholderizeEnrichments(markdown, enrichments, placeholders);

        assertFalse(processedMarkdown.contains("ENRICHMENT_"), "Inline code should not be placeholderized");
        assertTrue(processedMarkdown.contains("`{{hint: inline}}`"), "Inline code markers should remain intact");
    }

    @Test
    void shouldProcessEnrichmentAfterMultilineInlineCodeContainingFenceLikeBackticks() {
        String markdown = String.join("\n", "`code", "```java", "more` {{hint: visible}}");
        List<MarkdownEnrichment> enrichments = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();

        String placeholderMarkdown =
                placeholderizer.extractAndPlaceholderizeEnrichments(markdown, enrichments, placeholders);

        assertTrue(placeholderMarkdown.contains("ENRICHMENT_"));
        assertEquals(1, enrichments.size());
        assertEquals(1, placeholders.size());
    }

    @Test
    void shouldProcessEnrichmentContainingMultilineInlineCodeWithFenceLikeBackticks() {
        String markdown = String.join("\n", "{{hint: `code", "```java", "more`}}");
        List<MarkdownEnrichment> enrichments = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();

        String placeholderMarkdown =
                placeholderizer.extractAndPlaceholderizeEnrichments(markdown, enrichments, placeholders);

        assertTrue(placeholderMarkdown.contains("ENRICHMENT_"));
        assertEquals(1, enrichments.size());
        assertEquals(1, placeholders.size());
    }
}
