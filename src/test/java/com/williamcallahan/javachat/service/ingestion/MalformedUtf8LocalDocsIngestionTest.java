package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.williamcallahan.javachat.domain.ingestion.IngestionLocalFailure;
import com.williamcallahan.javachat.service.ChunkProcessingService;
import com.williamcallahan.javachat.service.ContentHasher;
import com.williamcallahan.javachat.service.FileIngestionMarkerStore;
import com.williamcallahan.javachat.service.FileIngestionMarkerStore.FileIngestionRecord;
import com.williamcallahan.javachat.service.FileOperationsService;
import com.williamcallahan.javachat.service.HtmlContentExtractor;
import com.williamcallahan.javachat.service.HybridVectorService;
import com.williamcallahan.javachat.service.LocalStoreService;
import com.williamcallahan.javachat.service.PdfContentExtractor;
import com.williamcallahan.javachat.service.ProgressTracker;
import com.williamcallahan.javachat.service.QdrantCollectionRouter;
import com.williamcallahan.javachat.support.logging.ExpectedLogEvents;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

/** Proves invalid UTF-8 becomes an observable local-ingestion failure before storage. */
class MalformedUtf8LocalDocsIngestionTest {

    private static final String NON_UTF8_HTML = "<html><body>caf\u00e9</body></html>";
    private static final String UTF8_DIAGNOSTIC_HINT = "file encoding issue - not valid UTF-8";

    private final Logger ingestionProcessorLogger =
            (Logger) LoggerFactory.getLogger(LocalDocsFileIngestionProcessor.class);
    private ExpectedLogEvents ingestionProcessorLogEvents;

    @BeforeEach
    void captureIngestionProcessorEvents() {
        ingestionProcessorLogEvents = ExpectedLogEvents.capture(ingestionProcessorLogger);
    }

    @AfterEach
    void stopCapturingIngestionProcessorEvents() {
        ingestionProcessorLogEvents.close();
    }

    @Test
    void shouldRejectMalformedUtf8BeforePruningPriorCorpusOrIndexing(@TempDir Path temporaryDirectory)
            throws IOException {
        Path localDocsRoot = temporaryDirectory.resolve("data").resolve("docs");
        Path malformedHtmlFile = localDocsRoot.resolve("malformed.html");
        Files.createDirectories(localDocsRoot);
        Files.writeString(malformedHtmlFile, NON_UTF8_HTML, StandardCharsets.ISO_8859_1);

        ChunkProcessingService chunkProcessingService = mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = mock(HybridVectorService.class);
        LocalStoreService localStoreService = mock(LocalStoreService.class);
        FileIngestionMarkerStore fileIngestionMarkerStore = mock(FileIngestionMarkerStore.class);
        IngestedFilePruneService ingestedFilePruneService = mock(IngestedFilePruneService.class);
        FileIngestionRecord changedPriorIngestionRecord = new FileIngestionRecord(
                0,
                0,
                "prior-ingestion-fingerprint",
                LocalDocsFileIngestionProcessor.LOCAL_DOCS_EXTRACTION_SEMANTICS_VERSION,
                "documentation",
                List.of("prior-document-hash"));
        when(fileIngestionMarkerStore.readFileIngestionRecord(anyString()))
                .thenReturn(Optional.of(changedPriorIngestionRecord));
        when(hybridVectorService.resolveCollectionName(any())).thenReturn("documentation");
        when(hybridVectorService.countPointsForUrl(anyString(), anyString())).thenReturn(1L);

        LocalDocsFileIngestionProcessor ingestionProcessor = new LocalDocsFileIngestionProcessor(
                new FileContentServices(
                        new HtmlContentExtractor(),
                        mock(PdfContentExtractor.class),
                        new FileOperationsService(),
                        mock(PdfTitleExtractor.class),
                        new HtmlContentGuard(),
                        mock(IngestionQuarantineService.class)),
                new IngestionStorageServices(
                        hybridVectorService,
                        chunkProcessingService,
                        new ContentHasher(),
                        localStoreService,
                        fileIngestionMarkerStore,
                        new QdrantCollectionRouter()),
                mock(ProgressTracker.class),
                new IngestionProvenanceDeriver(),
                new LocalIngestionFailureFactory(),
                ingestedFilePruneService);

        LocalDocsFileOutcome processingOutcome = ingestionProcessor.process(localDocsRoot, malformedHtmlFile);

        assertFalse(processingOutcome.processed());
        IngestionLocalFailure ingestionFailure = processingOutcome.failure().orElseThrow();
        assertEquals(malformedHtmlFile.toString(), ingestionFailure.filePath());
        assertEquals("html-read", ingestionFailure.phase());
        assertTrue(ingestionFailure.details().contains("MalformedInputException"));
        assertTrue(ingestionFailure.details().contains(UTF8_DIAGNOSTIC_HINT));
        assertTrue(ingestionProcessorLogEvents.events().stream()
                .anyMatch(logEvent -> logEvent.getLevel() == Level.ERROR
                        && logEvent.getFormattedMessage().contains("MalformedInputException")));
        verify(hybridVectorService).resolveCollectionName(any());
        verify(fileIngestionMarkerStore).readFileIngestionRecord(anyString());
        verifyNoMoreInteractions(hybridVectorService);
        verifyNoMoreInteractions(localStoreService);
        verifyNoMoreInteractions(fileIngestionMarkerStore);
        verifyNoInteractions(chunkProcessingService, ingestedFilePruneService);
    }
}
