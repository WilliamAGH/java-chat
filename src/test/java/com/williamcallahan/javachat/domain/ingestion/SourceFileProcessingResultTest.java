package com.williamcallahan.javachat.domain.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.williamcallahan.javachat.service.ingestion.LocalDocsFileOutcome;
import org.junit.jupiter.api.Test;

/**
 * Validates construction constraints on {@link SourceFileProcessingResult}.
 */
class SourceFileProcessingResultTest {

    @Test
    void validResultPreservesFields() {
        LocalDocsFileOutcome outcome = LocalDocsFileOutcome.processedFile();
        String fileUrl = "https://github.com/owner/repo/blob/main/src/Foo.java";

        SourceFileProcessingResult processingResult = new SourceFileProcessingResult(outcome, fileUrl);

        assertEquals(outcome, processingResult.outcome());
        assertEquals(fileUrl, processingResult.fileUrl());
    }

    @Test
    void nullOutcomeThrowsNullPointerException() {
        assertThrows(
                NullPointerException.class,
                () -> new SourceFileProcessingResult(null, "https://example.com/file.java"));
    }

    @Test
    void nullFileUrlThrowsNullPointerException() {
        assertThrows(
                NullPointerException.class,
                () -> new SourceFileProcessingResult(LocalDocsFileOutcome.skippedFile(), null));
    }

    @Test
    void blankFileUrlThrowsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new SourceFileProcessingResult(LocalDocsFileOutcome.skippedFile(), "  "));
    }
}
