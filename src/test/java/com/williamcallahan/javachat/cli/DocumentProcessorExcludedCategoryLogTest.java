package com.williamcallahan.javachat.cli;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import com.williamcallahan.javachat.application.ingestion.FileLimit;
import com.williamcallahan.javachat.application.ingestion.LocalDocumentationIngestionUseCase;
import com.williamcallahan.javachat.domain.ingestion.IngestionBacklogStatus;
import com.williamcallahan.javachat.domain.ingestion.IngestionLocalOutcome;
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

/**
 * Verifies the CLI reports intentionally-excluded files as a distinct category instead of
 * conflating them with already-in-Qdrant duplicates.
 *
 * <p>Precondition: {@link IngestionBacklogStatus#skippedFiles()} must count only genuine
 * already-indexed duplicates while {@link IngestionBacklogStatus#excludedFiles()} counts
 * intentionally-excluded pages, so the operator-facing labels stay honest.
 */
class DocumentProcessorExcludedCategoryLogTest {
    private static final FileLimit EXPECTED_CLI_FILE_LIMIT = new FileLimit(Integer.MAX_VALUE);
    private static final String DOCUMENT_PROCESSING_COMPLETE = "DOCUMENT PROCESSING COMPLETE";
    private static final String TOTAL_PROCESSED_THREE_DOCUMENTS = "Total new documents processed: 3";
    private static final String TOTAL_DUPLICATES_THREE = "Total duplicates skipped: 3";
    private static final String TOTAL_EXCLUDED_THREE = "Total excluded files: 3";

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
    void reportsExcludedFilesSeparatelyFromAlreadyIndexedDuplicates(@TempDir Path temporaryDirectory)
            throws IOException {
        Path firstDocumentationDirectory = temporaryDirectory.resolve("java-api-with-class-use");
        Path secondDocumentationDirectory = temporaryDirectory.resolve("clean-documentation");
        createEligibleDocument(firstDocumentationDirectory, "page.html");
        createEligibleDocument(secondDocumentationDirectory, "page.html");

        LocalDocumentationIngestionUseCase ingestionService = mock(LocalDocumentationIngestionUseCase.class);
        ProgressTracker progressTracker = mock(ProgressTracker.class);
        when(progressTracker.formatPercent()).thenReturn("0%");
        when(ingestionService.ingestLocalDirectory(firstDocumentationDirectory.toString(), EXPECTED_CLI_FILE_LIMIT))
                .thenReturn(IngestionLocalOutcome.fromBacklog(
                        new IngestionBacklogStatus(
                                IngestionBacklogStatus.Lifecycle.COMPLETE,
                                6,
                                6,
                                1,
                                2,
                                3,
                                0,
                                0,
                                0,
                                firstDocumentationDirectory.toString()),
                        firstDocumentationDirectory.toString(),
                        List.of()));
        when(ingestionService.ingestLocalDirectory(secondDocumentationDirectory.toString(), EXPECTED_CLI_FILE_LIMIT))
                .thenReturn(IngestionLocalOutcome.fromBacklog(
                        new IngestionBacklogStatus(
                                IngestionBacklogStatus.Lifecycle.COMPLETE,
                                3,
                                3,
                                2,
                                1,
                                0,
                                0,
                                0,
                                0,
                                secondDocumentationDirectory.toString()),
                        secondDocumentationDirectory.toString(),
                        List.of()));

        DocumentProcessor documentProcessor = new DocumentProcessor(ingestionService, progressTracker);
        String firstDirectoryName = Objects.requireNonNull(
                        firstDocumentationDirectory.getFileName(), "firstDocumentationDirectory file name")
                .toString();
        String secondDirectoryName = Objects.requireNonNull(
                        secondDocumentationDirectory.getFileName(), "secondDocumentationDirectory file name")
                .toString();
        List<DocumentationSet> documentationSets = List.of(
                new DocumentationSet("Java API with class-use", firstDirectoryName, firstDirectoryName),
                new DocumentationSet("Clean documentation", secondDirectoryName, secondDirectoryName));

        documentProcessor.processDocumentationSets(temporaryDirectory, documentationSets);

        assertTrue(containsLogMessage(DOCUMENT_PROCESSING_COMPLETE));
        assertTrue(containsLogMessage("  Skipped 2 duplicate files (already in Qdrant)"));
        assertTrue(containsLogMessage("  Excluded 3 files (intentionally not indexed)"));
        assertTrue(containsLogMessage("  Skipped 1 duplicate files (already in Qdrant)"));
        assertTrue(containsLogMessage(TOTAL_PROCESSED_THREE_DOCUMENTS));
        assertTrue(containsLogMessage(TOTAL_DUPLICATES_THREE));
        assertTrue(containsLogMessage(TOTAL_EXCLUDED_THREE));
        assertFalse(containsLogMessage("  Skipped 5 duplicate files (already in Qdrant)"));
        assertFalse(containsLogMessage("Total duplicates skipped: 5"));
        assertFalse(containsLogMessage("Total duplicates skipped: 6"));
        assertFalse(containsLogMessage("  Excluded 0 files (intentionally not indexed)"));
    }

    private boolean containsLogMessage(final String expectedMessage) {
        return documentProcessorLogEvents.events().stream()
                .map(logEvent -> logEvent.getFormattedMessage())
                .anyMatch(expectedMessage::equals);
    }

    private static void createEligibleDocument(final Path documentationDirectory, final String documentFileName)
            throws IOException {
        Files.createDirectories(documentationDirectory);
        Files.writeString(documentationDirectory.resolve(documentFileName), "<html><title>Test</title></html>");
    }
}
