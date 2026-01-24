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

        String result = placeholderizer.extractAndPlaceholderizeEnrichments(markdown, enrichments, placeholders);

        assertFalse(result.contains("ENRICHMENT_"), "Enrichment should not be processed inside tilde fence");
        assertTrue(result.contains("{{hint:"), "Original text should be preserved");
    }

    @Test
    void shouldProcessValidEnrichment() {
        String markdown = "{{hint: valid}}";
        List<MarkdownEnrichment> enrichments = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();

        String result = placeholderizer.extractAndPlaceholderizeEnrichments(markdown, enrichments, placeholders);

        assertTrue(result.contains("ENRICHMENT_"), "Enrichment should be processed");
        assertFalse(result.contains("{{hint:"), "Original text should be replaced");
    }
}
