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
        return enrichmentService.enrich(q, jdkVersion, snippets);
    }
}


