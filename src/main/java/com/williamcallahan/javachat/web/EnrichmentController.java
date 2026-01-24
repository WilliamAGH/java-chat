package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.model.Enrichment;
import jakarta.annotation.security.PermitAll;
import com.williamcallahan.javachat.service.EnrichmentService;
import com.williamcallahan.javachat.service.RetrievalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Exposes enrichment marker generation backed by retrieval and enrichment services.
 */
@RestController
@PermitAll
public class EnrichmentController {
    private final RetrievalService retrievalService;
    private final EnrichmentService enrichmentService;
    private final String jdkVersion;

    /**
     * Creates the enrichment controller using retrieval for context and a service to synthesize enrichment markers.
     */
    public EnrichmentController(RetrievalService retrievalService,
                                EnrichmentService enrichmentService,
                                @Value("${app.docs.jdk-version}") String jdkVersion) {
        this.retrievalService = retrievalService;
        this.enrichmentService = enrichmentService;
        this.jdkVersion = jdkVersion;
    }

    /**
     * Returns enrichment markers for a query using retrieved context snippets.
     */
    @GetMapping("/api/enrich")
    public Enrichment enrich(@RequestParam("q") String query) {
        var docs = retrievalService.retrieve(query);
        List<String> snippets = docs.stream().map(doc -> doc.getText()).limit(6).collect(Collectors.toList());
        return sanitize(enrichmentService.enrich(query, jdkVersion, snippets));
    }

    // New alias for consistent naming with chat routes
    /**
     * Provides the chat-aligned alias for enrichment generation.
     */
    @GetMapping("/api/chat/enrich")
    public Enrichment chatEnrich(@RequestParam("q") String query) {
        return enrich(query);
    }

    private Enrichment sanitize(Enrichment enrichment) {
        if (enrichment == null) {
            Enrichment emptyEnrichment = new Enrichment();
            emptyEnrichment.setHints(List.of());
            emptyEnrichment.setReminders(List.of());
            emptyEnrichment.setBackground(List.of());
            return emptyEnrichment;
        }
        enrichment.setHints(trimFilter(enrichment.getHints()));
        enrichment.setReminders(trimFilter(enrichment.getReminders()));
        enrichment.setBackground(trimFilter(enrichment.getBackground()));
        return enrichment;
    }

    private List<String> trimFilter(List<String> rawEntries) {
        if (rawEntries == null) return List.of();
        return rawEntries.stream()
                .map(entry -> entry == null ? "" : entry.trim())
                .filter(entry -> !entry.isEmpty())
                .collect(Collectors.toList());
    }
}

