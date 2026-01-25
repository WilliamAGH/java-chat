package com.williamcallahan.javachat.service.markdown;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.williamcallahan.javachat.domain.markdown.MarkdownEnrichment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

        String processedMarkdown = placeholderizer.extractAndPlaceholderizeEnrichments(markdown, enrichments, placeholders);

        assertFalse(processedMarkdown.contains("ENRICHMENT_"), "Enrichment should not be processed inside tilde fence");
        assertTrue(processedMarkdown.contains("{{hint:"), "Original text should be preserved");
    }

    @Test
    void shouldProcessValidEnrichment() {
        String markdown = "{{hint: valid}}";
        List<MarkdownEnrichment> enrichments = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();

        String processedMarkdown = placeholderizer.extractAndPlaceholderizeEnrichments(markdown, enrichments, placeholders);

        assertTrue(processedMarkdown.contains("ENRICHMENT_"), "Enrichment should be processed");
        assertFalse(processedMarkdown.contains("{{hint:"), "Original text should be replaced");
    }

    @Test
    void shouldIgnoreEnrichmentInsideInlineCode() {
        String markdown = "Use `{{hint: inline}}` to show markers.";
        List<MarkdownEnrichment> enrichments = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();

        String processedMarkdown = placeholderizer.extractAndPlaceholderizeEnrichments(markdown, enrichments, placeholders);

        assertFalse(processedMarkdown.contains("ENRICHMENT_"), "Inline code should not be placeholderized");
        assertTrue(processedMarkdown.contains("`{{hint: inline}}`"), "Inline code markers should remain intact");
    }
}
