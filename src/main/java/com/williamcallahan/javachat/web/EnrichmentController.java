package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.model.Enrichment;
import com.williamcallahan.javachat.service.EnrichmentService;
import com.williamcallahan.javachat.service.RetrievalService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
public class EnrichmentController {
    private final RetrievalService retrievalService;
    private final EnrichmentService enrichmentService;
    private final String jdkVersion;

    public EnrichmentController(RetrievalService retrievalService,
                                EnrichmentService enrichmentService,
                                @Value("${app.docs.jdk-version}") String jdkVersion) {
        this.retrievalService = retrievalService;
        this.enrichmentService = enrichmentService;
        this.jdkVersion = jdkVersion;
    }

    @GetMapping("/api/enrich")
    public Enrichment enrich(@RequestParam("q") String q) {
        var docs = retrievalService.retrieve(q);
        List<String> snippets = docs.stream().map(d -> d.getText()).limit(6).collect(Collectors.toList());
        return sanitize(enrichmentService.enrich(q, jdkVersion, snippets));
    }

    // New alias for consistent naming with chat routes
    @GetMapping("/api/chat/enrich")
    public Enrichment chatEnrich(@RequestParam("q") String q) {
        return enrich(q);
    }

    private Enrichment sanitize(Enrichment e) {
        if (e == null) return null;
        e.setHints(trimFilter(e.getHints()));
        e.setReminders(trimFilter(e.getReminders()));
        e.setBackground(trimFilter(e.getBackground()));
        return e;
    }

    private List<String> trimFilter(List<String> in) {
        if (in == null) return List.of();
        return in.stream()
                .map(s -> s == null ? "" : s.trim())
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}




