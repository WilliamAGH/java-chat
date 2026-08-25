package com.williamcallahan.javachat.domain.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies source-file classification at the GitHub ingestion boundary.
 */
class SourceFileLanguageTest {

    @Test
    void classifiesMdxDocumentationAsIndexableMarkdown() {
        assertTrue(SourceFileLanguage.isIndexableFile("observability.mdx"));
        assertEquals("markdown", SourceFileLanguage.fromFileName("observability.mdx"));
        assertEquals("documentation", SourceFileLanguage.classifyDocType("observability.mdx"));
    }
}
