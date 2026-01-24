package com.williamcallahan.javachat.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies crawl snapshot handling preserves raw HTML and link discovery.
 */
class DocsIngestionServiceTest {

    @Test
    void capturesRawHtmlAndDiscoversLinksBeforeMutation() {
        String baseUrl = "https://docs.example.com/root/";
        String html = """
            <html><body>
              <nav><a href="/root/hidden">Hidden</a></nav>
              <main><a href="https://docs.example.com/root/visible">Visible</a></main>
            </body></html>
            """;

        DocsIngestionService.CrawlPageSnapshot snapshot =
            DocsIngestionService.prepareCrawlPageSnapshot(baseUrl, html);

        List<String> discoveredLinks = snapshot.discoveredLinks();
        assertEquals(html, snapshot.rawHtml(), "Should preserve the raw HTML snapshot");
        assertTrue(discoveredLinks.contains("https://docs.example.com/root/hidden"),
            "Should include navigation links");
        assertTrue(discoveredLinks.contains("https://docs.example.com/root/visible"),
            "Should include content links");
    }
}
