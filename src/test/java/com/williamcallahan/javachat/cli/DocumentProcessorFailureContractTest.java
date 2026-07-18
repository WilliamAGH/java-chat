package com.williamcallahan.javachat.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import com.williamcallahan.javachat.application.ingestion.FileLimit;
import com.williamcallahan.javachat.domain.ingestion.IngestionLocalFailure;
import com.williamcallahan.javachat.domain.ingestion.IngestionLocalOutcome;
import com.williamcallahan.javachat.service.DocsIngestionService;
import com.williamcallahan.javachat.service.ProgressTracker;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Verifies that CLI completion is impossible when a selected documentation set has file failures. */
class DocumentProcessorFailureContractTest {
    private static final String DOCUMENT_PROCESSING_COMPLETE = "DOCUMENT PROCESSING COMPLETE";
    private static final String DOCUMENT_PROCESSING_FAILED = "DOCUMENT PROCESSING FAILED";
    private static final String TOTAL_PROCESSED_TWO_DOCUMENTS = "Total new documents processed: 2";
    private static final String TOTAL_DUPLICATES_ONE_DOCUMENT = "Total duplicates skipped: 1";
    private static final String TOTAL_FAILED_ONE_SET = "Documentation sets FAILED: 1";
    private static final String FAILURE_PHASE = "synthetic-ingestion";
    private static final String SENSITIVE_FAILURE_DETAILS = "api-key=synthetic-private-value";
    private static final String FORGED_LOG_LINE = "forged-log-line";
    private static final FileLimit EXPECTED_CLI_FILE_LIMIT = new FileLimit(Integer.MAX_VALUE);

    private final Logger documentProcessorLogger = (Logger) LoggerFactory.getLogger(DocumentProcessor.class);
    private ExpectedLogEvents documentProcessorLogEvents;

    @BeforeEach
    void captureDocumentProcessorLogs() {
        documentProcessorLogEvents = ExpectedLogEvents.capture(documentProcessorLogger);
    }

    @AfterEach
    void restoreDocumentProcessorLogs() {
        documentProcessorLogEvents.close();
    }

    @Test
    void failsCliAndSuppressesSuccessMarkerWhenDocumentationSetReportsFileFailure(@TempDir Path temporaryDirectory)
            throws IOException {
        Path failedDocumentationDirectory = temporaryDirectory.resolve("failed-documentation-set");
        Path successfulDocumentationDirectory = temporaryDirectory.resolve("successful-documentation-set");
        Path failedDocument = createEligibleDocument(failedDocumentationDirectory, "failed.html");
        String hostileFailurePath = failedDocument + "\r\n" + FORGED_LOG_LINE;
        createEligibleDocument(failedDocumentationDirectory, "unprocessed.html");
        createEligibleDocument(successfulDocumentationDirectory, "processed.html");
        createEligibleDocument(successfulDocumentationDirectory, "duplicate.html");

        DocsIngestionService ingestionService = mock(DocsIngestionService.class);
        ProgressTracker progressTracker = mock(ProgressTracker.class);
        when(progressTracker.formatPercent()).thenReturn("0%");
        when(ingestionService.ingestLocalDirectory(failedDocumentationDirectory.toString(), EXPECTED_CLI_FILE_LIMIT))
                .thenReturn(IngestionLocalOutcome.success(
                        1,
                        failedDocumentationDirectory.toString(),
                        List.of(new IngestionLocalFailure(
                                hostileFailurePath, FAILURE_PHASE, SENSITIVE_FAILURE_DETAILS))));
        when(ingestionService.ingestLocalDirectory(
                        successfulDocumentationDirectory.toString(), EXPECTED_CLI_FILE_LIMIT))
                .thenReturn(IngestionLocalOutcome.success(1, successfulDocumentationDirectory.toString(), List.of()));

        DocumentProcessor documentProcessor = new DocumentProcessor(ingestionService, progressTracker);
        String failedDocumentationDirectoryName = Objects.requireNonNull(
                        failedDocumentationDirectory.getFileName(), "failedDocumentationDirectory file name")
                .toString();
        String successfulDocumentationDirectoryName = Objects.requireNonNull(
                        successfulDocumentationDirectory.getFileName(), "successfulDocumentationDirectory file name")
                .toString();
        List<DocumentationSet> documentationSets = List.of(
                new DocumentationSet("Failed documentation set", failedDocumentationDirectoryName),
                new DocumentationSet("Successful documentation set", successfulDocumentationDirectoryName));

        DocumentProcessor.DocumentProcessingException thrown = assertThrows(
                DocumentProcessor.DocumentProcessingException.class,
                () -> documentProcessor.processDocumentationSets(temporaryDirectory, documentationSets));

        assertEquals("Document processing completed with 1 failed documentation set(s)", thrown.getMessage());
        verify(ingestionService).ingestLocalDirectory(failedDocumentationDirectory.toString(), EXPECTED_CLI_FILE_LIMIT);
        verify(ingestionService)
                .ingestLocalDirectory(successfulDocumentationDirectory.toString(), EXPECTED_CLI_FILE_LIMIT);
        assertTrue(containsLogMessage(DOCUMENT_PROCESSING_FAILED));
        assertTrue(containsLogMessage(TOTAL_PROCESSED_TWO_DOCUMENTS));
        assertTrue(containsLogMessage(TOTAL_DUPLICATES_ONE_DOCUMENT));
        assertTrue(containsLogMessage(TOTAL_FAILED_ONE_SET));
        assertTrue(containsLogMessage(
                "File failed (phase=" + FAILURE_PHASE + "): " + failedDocument + "??" + FORGED_LOG_LINE));
        assertFalse(containsPhysicalLineBreakInLogMessages());
        assertFalse(containsLogMessage(DOCUMENT_PROCESSING_COMPLETE));
        assertFalse(containsLogText(SENSITIVE_FAILURE_DETAILS));
    }

    private boolean containsLogMessage(final String expectedMessage) {
        return documentProcessorLogEvents.events().stream()
                .map(logEvent -> logEvent.getFormattedMessage())
                .anyMatch(expectedMessage::equals);
    }

    private boolean containsLogText(final String expectedText) {
        return documentProcessorLogEvents.events().stream()
                .map(logEvent -> logEvent.getFormattedMessage())
                .anyMatch(logMessage -> logMessage.contains(expectedText));
    }

    private boolean containsPhysicalLineBreakInLogMessages() {
        return documentProcessorLogEvents.events().stream()
                .map(logEvent -> logEvent.getFormattedMessage())
                .anyMatch(logMessage -> logMessage.indexOf('\r') >= 0 || logMessage.indexOf('\n') >= 0);
    }

    private static Path createEligibleDocument(final Path documentationDirectory, final String documentFileName)
            throws IOException {
        Files.createDirectories(documentationDirectory);
        Path documentationFile = documentationDirectory.resolve(documentFileName);
        Files.writeString(documentationFile, "<html><title>Test</title><body>Test</body></html>");
        return documentationFile;
    }
}
