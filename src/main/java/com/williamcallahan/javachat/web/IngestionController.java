package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.service.DocsIngestionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ingest")
public class IngestionController {
    private final DocsIngestionService docsIngestionService;

    public IngestionController(DocsIngestionService docsIngestionService) {
        this.docsIngestionService = docsIngestionService;
    }

    @PostMapping
    public String ingest(@RequestParam(name = "maxPages", defaultValue = "100") int maxPages) throws Exception {
        docsIngestionService.crawlAndIngest(maxPages);
        return "OK";
    }
}


