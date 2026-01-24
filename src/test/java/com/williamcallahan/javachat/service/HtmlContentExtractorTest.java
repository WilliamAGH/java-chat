package com.williamcallahan.javachat.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies HTML extraction preserves code formatting while normalizing prose whitespace.
 */
class HtmlContentExtractorTest {

    @Test
    void preservesCodeIndentationAndNormalizesProse() {
        String html = """
            <html><body>
              <pre>    int x = 1;\n\tint y = 2;\n</pre>
              <p>Text  with   spaces</p>
            </body></html>
            """;
        Document document = Jsoup.parse(html);
        HtmlContentExtractor extractor = new HtmlContentExtractor();

        String extractedText = extractor.extractCleanContent(document);

        assertTrue(extractedText.contains("```"), "Should include fenced code markers");
        assertTrue(extractedText.contains("    int x = 1;"), "Should preserve spaces in code blocks");
        assertTrue(extractedText.contains("\tint y = 2;"), "Should preserve tabs in code blocks");
        assertTrue(extractedText.contains("Text with spaces"), "Should normalize prose spacing");
    }
}
