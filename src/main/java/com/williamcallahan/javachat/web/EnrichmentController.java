package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.model.Enrichment;
import jakarta.annotation.security.PermitAll;
import com.williamcallahan.javachat.service.EnrichmentService;
import com.williamcallahan.javachat.service.RetrievalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
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
@PreAuthorize("permitAll()")
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
        return enrichmentService.enrich(query, jdkVersion, snippets).sanitized();
    }

    // New alias for consistent naming with chat routes
    /**
     * Provides the chat-aligned alias for enrichment generation.
     */
    @GetMapping("/api/chat/enrich")
    public Enrichment chatEnrich(@RequestParam("q") String query) {
        return enrich(query);
    }
}
