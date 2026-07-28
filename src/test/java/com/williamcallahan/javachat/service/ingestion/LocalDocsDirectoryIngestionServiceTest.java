package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.application.ingestion.FileLimit;
import com.williamcallahan.javachat.domain.ingestion.IngestionBacklogStatus;
import com.williamcallahan.javachat.domain.ingestion.IngestionLocalOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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
        LocalIngestionRunStore ingestionRunStore = availableRunStore(configuredDocumentationRoot, selectedSourceRoot);
        when(fileProcessor.processBatch(
                        selectedSourceRoot.toRealPath(), java.util.List.of(documentationFile.toRealPath())))
                .thenReturn(java.util.List.of(LocalDocsFileOutcome.processedFile()));
        LocalDocsDirectoryIngestionService directoryIngestionService = new LocalDocsDirectoryIngestionService(
                fileProcessor, ingestionRunStore, configuredDocumentationRoot.toString());

        IngestionLocalOutcome ingestionOutcome =
                directoryIngestionService.ingestLocalDirectory(selectedSourceRoot.toString(), new FileLimit(1));

        assertEquals(1, ingestionOutcome.backlog().processedFiles());
        assertEquals(
                IngestionBacklogStatus.Lifecycle.COMPLETE,
                ingestionOutcome.backlog().lifecycle());
        assertEquals("kotlin", ingestionOutcome.backlog().directory());
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
        LocalIngestionRunStore ingestionRunStore = mock(LocalIngestionRunStore.class);
        LocalDocsDirectoryIngestionService directoryIngestionService = new LocalDocsDirectoryIngestionService(
                fileProcessor, ingestionRunStore, configuredDocumentationRoot.toString());

        assertThrows(
                IllegalArgumentException.class,
                () -> directoryIngestionService.ingestLocalDirectory(
                        outsideDocumentationRoot.toString(), new FileLimit(1)));
        verifyNoInteractions(fileProcessor);
        verifyNoInteractions(ingestionRunStore);
    }

    @Test
    void skippedFileConsumesInspectionLimit(@TempDir Path temporaryDirectory) throws IOException {
        Path configuredDocumentationRoot = temporaryDirectory.resolve("arbitrary-corpus");
        Path selectedSourceRoot = configuredDocumentationRoot.resolve("kotlin");
        Files.createDirectories(selectedSourceRoot);
        Files.writeString(selectedSourceRoot.resolve("first.html"), "<html>First</html>");
        Files.writeString(selectedSourceRoot.resolve("second.html"), "<html>Second</html>");
        LocalDocsFileIngestionProcessor fileProcessor = mock(LocalDocsFileIngestionProcessor.class);
        LocalIngestionRunStore ingestionRunStore = availableRunStore(configuredDocumentationRoot, selectedSourceRoot);
        when(fileProcessor.processBatch(
                        org.mockito.ArgumentMatchers.eq(selectedSourceRoot.toRealPath()),
                        org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(java.util.List.of(LocalDocsFileOutcome.skippedFile()));
        LocalDocsDirectoryIngestionService directoryIngestionService = new LocalDocsDirectoryIngestionService(
                fileProcessor, ingestionRunStore, configuredDocumentationRoot.toString());

        IngestionLocalOutcome ingestionOutcome =
                directoryIngestionService.ingestLocalDirectory(selectedSourceRoot.toString(), new FileLimit(1));

        assertEquals(0, ingestionOutcome.backlog().processedFiles());
        assertEquals(1, ingestionOutcome.backlog().skippedFiles());
        assertEquals(1, ingestionOutcome.backlog().pendingFiles());
        assertEquals(
                IngestionBacklogStatus.Lifecycle.PARTIAL,
                ingestionOutcome.backlog().lifecycle());
        ArgumentCaptor<java.util.List<Path>> inspectedFilesCaptor = ArgumentCaptor.captor();
        verify(fileProcessor)
                .processBatch(
                        org.mockito.ArgumentMatchers.eq(selectedSourceRoot.toRealPath()),
                        inspectedFilesCaptor.capture());
        assertEquals(1, inspectedFilesCaptor.getValue().size());
    }

    @Test
    void retainsLaterFileAfterFileLocalFailure(@TempDir Path temporaryDirectory) throws IOException {
        Path configuredDocumentationRoot = temporaryDirectory.resolve("arbitrary-corpus");
        Path selectedSourceRoot = configuredDocumentationRoot.resolve("java");
        Files.createDirectories(selectedSourceRoot);
        Files.writeString(selectedSourceRoot.resolve("first.html"), "<html>First</html>");
        Files.writeString(selectedSourceRoot.resolve("second.html"), "<html>Second</html>");
        LocalDocsFileIngestionProcessor fileProcessor = mock(LocalDocsFileIngestionProcessor.class);
        LocalIngestionRunStore ingestionRunStore = availableRunStore(configuredDocumentationRoot, selectedSourceRoot);
        when(fileProcessor.processBatch(
                        org.mockito.ArgumentMatchers.eq(selectedSourceRoot.toRealPath()),
                        org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(LocalDocsFileOutcome.failedFile(
                        new com.williamcallahan.javachat.domain.ingestion.IngestionLocalFailure(
                                "first.html", "html-read", "malformed input"))));
        LocalDocsDirectoryIngestionService directoryIngestionService = new LocalDocsDirectoryIngestionService(
                fileProcessor, ingestionRunStore, configuredDocumentationRoot.toString());

        IngestionLocalOutcome ingestionOutcome =
                directoryIngestionService.ingestLocalDirectory(selectedSourceRoot.toString(), new FileLimit(2));

        assertEquals(1, ingestionOutcome.backlog().failedFiles());
        assertEquals(0, ingestionOutcome.backlog().processedFiles());
        assertEquals(1, ingestionOutcome.backlog().pendingFiles());
        assertEquals(0, ingestionOutcome.backlog().skippedFiles());
        assertEquals(
                IngestionBacklogStatus.Lifecycle.PARTIAL,
                ingestionOutcome.backlog().lifecycle());
    }

    @Test
    void retriesFailedPrefixBeforeLaterFilesAcrossRuns(@TempDir Path temporaryDirectory) throws IOException {
        Path configuredDocumentationRoot = temporaryDirectory.resolve("arbitrary-corpus");
        Path selectedSourceRoot = configuredDocumentationRoot.resolve("java");
        Files.createDirectories(selectedSourceRoot);
        Path firstDocumentationFile = selectedSourceRoot.resolve("first.html");
        Path secondDocumentationFile = selectedSourceRoot.resolve("second.html");
        Files.writeString(firstDocumentationFile, "<html>First</html>");
        Files.writeString(secondDocumentationFile, "<html>Second</html>");
        LocalDocsFileIngestionProcessor fileProcessor = mock(LocalDocsFileIngestionProcessor.class);
        LocalIngestionRunStore ingestionRunStore = availableRunStore(configuredDocumentationRoot, selectedSourceRoot);
        AtomicReference<IngestionBacklogStatus> persistedBacklog = new AtomicReference<>();
        when(ingestionRunStore.read(
                        org.mockito.ArgumentMatchers.eq(selectedSourceRoot.toRealPath()),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(persistedBacklog.get()));
        org.mockito.Mockito.doAnswer(invocation -> {
                    persistedBacklog.set(invocation.getArgument(1, IngestionBacklogStatus.class));
                    return null;
                })
                .when(ingestionRunStore)
                .write(
                        org.mockito.ArgumentMatchers.eq(selectedSourceRoot.toRealPath()),
                        org.mockito.ArgumentMatchers.any(IngestionBacklogStatus.class),
                        org.mockito.ArgumentMatchers.anyString());
        when(fileProcessor.processBatch(
                        org.mockito.ArgumentMatchers.eq(selectedSourceRoot.toRealPath()),
                        org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(
                        List.of(LocalDocsFileOutcome.failedFile(
                                new com.williamcallahan.javachat.domain.ingestion.IngestionLocalFailure(
                                        firstDocumentationFile.toString(), "html-read", "malformed input"))),
                        List.of(LocalDocsFileOutcome.processedFile()));
        LocalDocsDirectoryIngestionService directoryIngestionService = new LocalDocsDirectoryIngestionService(
                fileProcessor, ingestionRunStore, configuredDocumentationRoot.toString());

        IngestionLocalOutcome firstOutcome =
                directoryIngestionService.ingestLocalDirectory(selectedSourceRoot.toString(), new FileLimit(2));
        IngestionLocalOutcome secondOutcome =
                directoryIngestionService.ingestLocalDirectory(selectedSourceRoot.toString(), new FileLimit(1));

        ArgumentCaptor<List<Path>> inspectedFilesCaptor = ArgumentCaptor.captor();
        verify(fileProcessor, org.mockito.Mockito.times(2))
                .processBatch(
                        org.mockito.ArgumentMatchers.eq(selectedSourceRoot.toRealPath()),
                        inspectedFilesCaptor.capture());
        List<Path> expectedFileOrder =
                List.of(firstDocumentationFile.toRealPath(), secondDocumentationFile.toRealPath());
        assertEquals(expectedFileOrder, inspectedFilesCaptor.getAllValues().get(0));
        assertEquals(
                List.of(firstDocumentationFile.toRealPath()),
                inspectedFilesCaptor.getAllValues().get(1));
        assertEquals(
                IngestionBacklogStatus.Lifecycle.PARTIAL, firstOutcome.backlog().lifecycle());
        assertEquals(1, secondOutcome.backlog().processedFiles());
        assertEquals(1, secondOutcome.backlog().pendingFiles());
        assertEquals(
                IngestionBacklogStatus.Lifecycle.PARTIAL,
                secondOutcome.backlog().lifecycle());
    }

    @Test
    void retainsUnattemptedFilesAfterRunTerminalFailure(@TempDir Path temporaryDirectory) throws IOException {
        Path configuredDocumentationRoot = temporaryDirectory.resolve("arbitrary-corpus");
        Path selectedSourceRoot = configuredDocumentationRoot.resolve("java");
        Files.createDirectories(selectedSourceRoot);
        Files.writeString(selectedSourceRoot.resolve("first.html"), "<html>First</html>");
        Files.writeString(selectedSourceRoot.resolve("second.html"), "<html>Second</html>");
        LocalDocsFileIngestionProcessor fileProcessor = mock(LocalDocsFileIngestionProcessor.class);
        LocalIngestionRunStore ingestionRunStore = availableRunStore(configuredDocumentationRoot, selectedSourceRoot);
        when(fileProcessor.processBatch(
                        org.mockito.ArgumentMatchers.eq(selectedSourceRoot.toRealPath()),
                        org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(LocalDocsFileOutcome.failedFile(
                        new com.williamcallahan.javachat.domain.ingestion.IngestionLocalFailure(
                                "first.html", "embedding-unavailable", "unavailable"))));
        LocalDocsDirectoryIngestionService directoryIngestionService = new LocalDocsDirectoryIngestionService(
                fileProcessor, ingestionRunStore, configuredDocumentationRoot.toString());

        IngestionLocalOutcome ingestionOutcome =
                directoryIngestionService.ingestLocalDirectory(selectedSourceRoot.toString(), new FileLimit(2));

        assertEquals(1, ingestionOutcome.backlog().failedFiles());
        assertEquals(1, ingestionOutcome.backlog().pendingFiles());
        assertEquals(
                IngestionBacklogStatus.Lifecycle.PARTIAL,
                ingestionOutcome.backlog().lifecycle());
    }

    @Test
    void resumesAtNextPendingFileAcrossBoundedRuns(@TempDir Path temporaryDirectory) throws IOException {
        Path configuredDocumentationRoot = temporaryDirectory.resolve("arbitrary-corpus");
        Path selectedSourceRoot = configuredDocumentationRoot.resolve("java");
        Files.createDirectories(selectedSourceRoot);
        Path firstDocumentationFile = selectedSourceRoot.resolve("first.html");
        Path secondDocumentationFile = selectedSourceRoot.resolve("second.html");
        Files.writeString(firstDocumentationFile, "<html>First</html>");
        Files.writeString(secondDocumentationFile, "<html>Second</html>");
        LocalDocsFileIngestionProcessor fileProcessor = mock(LocalDocsFileIngestionProcessor.class);
        LocalIngestionRunStore ingestionRunStore = availableRunStore(configuredDocumentationRoot, selectedSourceRoot);
        when(fileProcessor.processBatch(
                        org.mockito.ArgumentMatchers.eq(selectedSourceRoot.toRealPath()),
                        org.mockito.ArgumentMatchers.anyList()))
                .thenReturn(List.of(LocalDocsFileOutcome.skippedFile()));
        when(ingestionRunStore.read(
                        org.mockito.ArgumentMatchers.eq(selectedSourceRoot.toRealPath()),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(
                        Optional.empty(),
                        Optional.of(new IngestionBacklogStatus(
                                IngestionBacklogStatus.Lifecycle.PARTIAL, 2, 1, 0, 1, 0, 1, 0, "java")));
        LocalDocsDirectoryIngestionService directoryIngestionService = new LocalDocsDirectoryIngestionService(
                fileProcessor, ingestionRunStore, configuredDocumentationRoot.toString());

        directoryIngestionService.ingestLocalDirectory(selectedSourceRoot.toString(), new FileLimit(1));
        IngestionLocalOutcome secondOutcome =
                directoryIngestionService.ingestLocalDirectory(selectedSourceRoot.toString(), new FileLimit(1));

        ArgumentCaptor<List<Path>> inspectedFilesCaptor = ArgumentCaptor.captor();
        verify(fileProcessor, org.mockito.Mockito.times(2))
                .processBatch(
                        org.mockito.ArgumentMatchers.eq(selectedSourceRoot.toRealPath()),
                        inspectedFilesCaptor.capture());
        assertEquals(
                List.of(firstDocumentationFile.toRealPath()),
                inspectedFilesCaptor.getAllValues().get(0));
        assertEquals(
                List.of(secondDocumentationFile.toRealPath()),
                inspectedFilesCaptor.getAllValues().get(1));
        assertEquals(
                IngestionBacklogStatus.Lifecycle.COMPLETE,
                secondOutcome.backlog().lifecycle());
    }

    private static LocalIngestionRunStore availableRunStore(Path configuredDocumentationRoot, Path selectedSourceRoot)
            throws IOException {
        LocalIngestionRunStore ingestionRunStore = mock(LocalIngestionRunStore.class);
        LocalIngestionRunStore.RunClaim runClaim = mock(LocalIngestionRunStore.RunClaim.class);
        when(ingestionRunStore.claim(selectedSourceRoot.toRealPath())).thenReturn(runClaim);
        when(ingestionRunStore.read(
                        org.mockito.ArgumentMatchers.eq(selectedSourceRoot.toRealPath()),
                        org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Optional.empty());
        return ingestionRunStore;
    }
}
