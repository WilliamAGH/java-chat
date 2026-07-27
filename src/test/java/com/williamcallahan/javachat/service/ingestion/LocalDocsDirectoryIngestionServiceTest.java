package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.domain.ingestion.IngestionLocalOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

/** Verifies caller-selected ingestion roots remain inside the configured documentation mirror. */
class LocalDocsDirectoryIngestionServiceTest {

    @Test
    void ingestsArbitraryConfiguredMirrorRoot(@TempDir Path temporaryDirectory) throws IOException {
        Path configuredDocumentationRoot = temporaryDirectory.resolve("arbitrary-corpus");
        Path selectedSourceRoot = configuredDocumentationRoot.resolve("kotlin");
        Files.createDirectories(selectedSourceRoot);
        Path documentationFile = selectedSourceRoot.resolve("index.html");
        Files.writeString(documentationFile, "<html>Kotlin 2.4.10</html>");
        LocalDocsFileIngestionProcessor fileProcessor = mock(LocalDocsFileIngestionProcessor.class);
        when(fileProcessor.processBatch(
                        selectedSourceRoot.toRealPath(), java.util.List.of(documentationFile.toRealPath())))
                .thenReturn(java.util.List.of(LocalDocsFileOutcome.processedFile()));
        LocalDocsDirectoryIngestionService directoryIngestionService =
                new LocalDocsDirectoryIngestionService(fileProcessor, configuredDocumentationRoot.toString());

        IngestionLocalOutcome ingestionOutcome =
                directoryIngestionService.ingestLocalDirectory(selectedSourceRoot.toString(), 1);

        assertEquals(1, ingestionOutcome.processed());
        verify(fileProcessor)
                .processBatch(selectedSourceRoot.toRealPath(), java.util.List.of(documentationFile.toRealPath()));
    }

    @Test
    void rejectsReadableDirectoryOutsideConfiguredMirror(@TempDir Path temporaryDirectory) throws IOException {
        Path configuredDocumentationRoot = temporaryDirectory.resolve("configured-corpus");
        Path outsideDocumentationRoot = temporaryDirectory.resolve("private-files");
        Files.createDirectories(configuredDocumentationRoot);
        Files.createDirectories(outsideDocumentationRoot);
        Files.writeString(outsideDocumentationRoot.resolve("private.html"), "<html>Private</html>");
        LocalDocsFileIngestionProcessor fileProcessor = mock(LocalDocsFileIngestionProcessor.class);
        LocalDocsDirectoryIngestionService directoryIngestionService =
                new LocalDocsDirectoryIngestionService(fileProcessor, configuredDocumentationRoot.toString());

        assertThrows(
                IllegalArgumentException.class,
                () -> directoryIngestionService.ingestLocalDirectory(outsideDocumentationRoot.toString(), 1));
        verifyNoInteractions(fileProcessor);
    }

    @Test
    void skippedFileConsumesInspectionLimit(@TempDir Path temporaryDirectory) throws IOException {
        Path configuredDocumentationRoot = temporaryDirectory.resolve("arbitrary-corpus");
        Path selectedSourceRoot = configuredDocumentationRoot.resolve("kotlin");
        Files.createDirectories(selectedSourceRoot);
        Files.writeString(selectedSourceRoot.resolve("first.html"), "<html>First</html>");
        Files.writeString(selectedSourceRoot.resolve("second.html"), "<html>Second</html>");
        LocalDocsFileIngestionProcessor fileProcessor = mock(LocalDocsFileIngestionProcessor.class);
        when(fileProcessor.processBatch(
                        org.mockito.ArgumentMatchers.eq(selectedSourceRoot.toRealPath()),
                        org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(java.util.List.of(LocalDocsFileOutcome.skippedFile()));
        LocalDocsDirectoryIngestionService directoryIngestionService =
                new LocalDocsDirectoryIngestionService(fileProcessor, configuredDocumentationRoot.toString());

        IngestionLocalOutcome ingestionOutcome =
                directoryIngestionService.ingestLocalDirectory(selectedSourceRoot.toString(), 1);

        assertEquals(0, ingestionOutcome.processed());
        ArgumentCaptor<java.util.List<Path>> inspectedFilesCaptor = ArgumentCaptor.captor();
        verify(fileProcessor)
                .processBatch(
                        org.mockito.ArgumentMatchers.eq(selectedSourceRoot.toRealPath()),
                        inspectedFilesCaptor.capture());
        assertEquals(1, inspectedFilesCaptor.getValue().size());
    }
}
