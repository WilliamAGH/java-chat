package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.domain.ingestion.GitHubRepoMetadata;
import com.williamcallahan.javachat.domain.ingestion.GitHubRepositoryIdentity;
import com.williamcallahan.javachat.domain.ingestion.SourceFileProcessingResult;
import com.williamcallahan.javachat.service.ChunkProcessingService;
import com.williamcallahan.javachat.service.ContentHasher;
import com.williamcallahan.javachat.service.FileIngestionMarkerStore;
import com.williamcallahan.javachat.service.FileIngestionMarkerStore.FileIngestionRecord;
import com.williamcallahan.javachat.service.HybridVectorService;
import com.williamcallahan.javachat.service.LocalStoreService;
import com.williamcallahan.javachat.service.ProgressTracker;
import com.williamcallahan.javachat.service.QdrantCollectionRouter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.ai.document.Document;

/**
 * Verifies incremental GitHub source ingestion behavior for changed and unchanged files.
 */
class SourceCodeFileIngestionProcessorTest {
    private static final String PRIOR_COLLECTION_NAME = "prior-collection";
    private static final String TARGET_COLLECTION_NAME = "target-collection";

    @Test
    void changedFilePrunesAndForcesReindex(@TempDir Path temporaryDirectory) throws IOException {
        ChunkProcessingService chunkProcessingService = Mockito.mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        LocalStoreService localStoreService = Mockito.mock(LocalStoreService.class);
        FileIngestionMarkerStore fileMarkerStore = Mockito.mock(FileIngestionMarkerStore.class);
        ContentHasher contentHasher = Mockito.mock(ContentHasher.class);
        ProgressTracker progressTracker = Mockito.mock(ProgressTracker.class);
        IngestedFilePruneService ingestedFilePruneService = Mockito.mock(IngestedFilePruneService.class);

        SourceCodeFileIngestionProcessor ingestionProcessor = new SourceCodeFileIngestionProcessor(
                new IngestionStorageServices(
                        hybridVectorService,
                        chunkProcessingService,
                        contentHasher,
                        localStoreService,
                        fileMarkerStore,
                        Mockito.mock(QdrantCollectionRouter.class)),
                progressTracker,
                ingestedFilePruneService);

        Path repositoryRoot = temporaryDirectory.resolve("repository");
        Path sourceDirectory = repositoryRoot.resolve("src");
        Files.createDirectories(sourceDirectory);
        Path sourceFilePath = sourceDirectory.resolve("Main.java");
        Files.writeString(sourceFilePath, "package demo; class Main {}", StandardCharsets.UTF_8);

        GitHubRepoMetadata repositoryMetadata = new GitHubRepoMetadata(
                repositoryRoot.toString(),
                GitHubRepositoryIdentity.of("openai", "java-chat"),
                TARGET_COLLECTION_NAME,
                "main",
                "abcdef123456",
                "MIT",
                "Example repository");

        String sourceUrl = "https://github.com/openai/java-chat/blob/main/src/Main.java";
        long fileSizeBytes = Files.size(sourceFilePath);
        long lastModifiedMillis = Files.getLastModifiedTime(sourceFilePath).toMillis();
        FileIngestionRecord previousFileRecord = new FileIngestionRecord(
                fileSizeBytes, lastModifiedMillis, "old-fingerprint", "", PRIOR_COLLECTION_NAME, List.of("oldhash"));

        when(contentHasher.sha256(sourceFilePath)).thenReturn("new-fingerprint");
        when(fileMarkerStore.readFileIngestionRecord(sourceUrl)).thenReturn(Optional.of(previousFileRecord));
        when(progressTracker.formatPercent()).thenReturn("100%");

        Document indexedDocument = new Document("point-1", "package demo; class Main {}", new HashMap<>());
        indexedDocument.getMetadata().put("hash", "newhash");

        ChunkProcessingService.ChunkProcessingOutcome chunkProcessingOutcome =
                new ChunkProcessingService.ChunkProcessingOutcome(List.of(indexedDocument), List.of("newhash"), 1, 0);
        when(chunkProcessingService.processAndStoreChunksForce(
                        anyString(), eq(sourceUrl), eq("Main.java"), anyString()))
                .thenReturn(chunkProcessingOutcome);

        SourceFileProcessingResult sourceFileProcessing =
                ingestionProcessor.process(repositoryRoot, sourceFilePath, repositoryMetadata, TARGET_COLLECTION_NAME);

        assertTrue(sourceFileProcessing.outcome().processed());
        assertEquals(sourceUrl, sourceFileProcessing.fileUrl());
        verify(ingestedFilePruneService)
                .pruneCollectionFileStrict(PRIOR_COLLECTION_NAME, sourceUrl, previousFileRecord);
        verify(chunkProcessingService)
                .processAndStoreChunksForce(anyString(), eq(sourceUrl), eq("Main.java"), anyString());
        verify(chunkProcessingService, never())
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
        ArgumentCaptor<FileIngestionRecord> ingestionRecordCaptor = ArgumentCaptor.forClass(FileIngestionRecord.class);
        verify(fileMarkerStore).markFileIngested(eq(sourceUrl), ingestionRecordCaptor.capture());
        assertEquals("new-fingerprint", ingestionRecordCaptor.getValue().ingestionFingerprint());
        assertTrue(ingestionRecordCaptor.getValue().extractionSemanticsVersion().isBlank());
        assertEquals(TARGET_COLLECTION_NAME, ingestionRecordCaptor.getValue().collectionName());
        assertEquals(List.of("newhash"), ingestionRecordCaptor.getValue().chunkHashes());
    }

    @Test
    void unchangedFileWithMissingPointCoverageForcesReindex(@TempDir Path temporaryDirectory) throws IOException {
        ChunkProcessingService chunkProcessingService = Mockito.mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        LocalStoreService localStoreService = Mockito.mock(LocalStoreService.class);
        FileIngestionMarkerStore fileMarkerStore = Mockito.mock(FileIngestionMarkerStore.class);
        ContentHasher contentHasher = Mockito.mock(ContentHasher.class);
        ProgressTracker progressTracker = Mockito.mock(ProgressTracker.class);
        IngestedFilePruneService ingestedFilePruneService = Mockito.mock(IngestedFilePruneService.class);

        SourceCodeFileIngestionProcessor ingestionProcessor = new SourceCodeFileIngestionProcessor(
                new IngestionStorageServices(
                        hybridVectorService,
                        chunkProcessingService,
                        contentHasher,
                        localStoreService,
                        fileMarkerStore,
                        Mockito.mock(QdrantCollectionRouter.class)),
                progressTracker,
                ingestedFilePruneService);

        Path repositoryRoot = temporaryDirectory.resolve("repository");
        Path sourceDirectory = repositoryRoot.resolve("src");
        Files.createDirectories(sourceDirectory);
        Path sourceFilePath = sourceDirectory.resolve("Main.java");
        Files.writeString(sourceFilePath, "package demo; class Main {}", StandardCharsets.UTF_8);

        GitHubRepoMetadata repositoryMetadata = new GitHubRepoMetadata(
                repositoryRoot.toString(),
                GitHubRepositoryIdentity.of("openai", "java-chat"),
                TARGET_COLLECTION_NAME,
                "main",
                "abcdef123456",
                "MIT",
                "Example repository");

        String sourceUrl = "https://github.com/openai/java-chat/blob/main/src/Main.java";
        long fileSizeBytes = Files.size(sourceFilePath);
        long lastModifiedMillis = Files.getLastModifiedTime(sourceFilePath).toMillis();
        FileIngestionRecord previousFileRecord = new FileIngestionRecord(
                fileSizeBytes,
                lastModifiedMillis,
                "same-fingerprint",
                "",
                TARGET_COLLECTION_NAME,
                List.of("existing-hash"));

        when(contentHasher.sha256(sourceFilePath)).thenReturn("same-fingerprint");
        when(fileMarkerStore.readFileIngestionRecord(sourceUrl)).thenReturn(Optional.of(previousFileRecord));
        when(hybridVectorService.countPointsForUrl(TARGET_COLLECTION_NAME, sourceUrl))
                .thenReturn(0L);
        when(progressTracker.formatPercent()).thenReturn("100%");

        Document indexedDocument = new Document("point-1", "package demo; class Main {}", new HashMap<>());
        indexedDocument.getMetadata().put("hash", "existing-hash");
        ChunkProcessingService.ChunkProcessingOutcome chunkProcessingOutcome =
                new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(indexedDocument), List.of("existing-hash"), 1, 0);
        when(chunkProcessingService.processAndStoreChunksForce(
                        anyString(), eq(sourceUrl), eq("Main.java"), anyString()))
                .thenReturn(chunkProcessingOutcome);

        SourceFileProcessingResult sourceFileProcessing =
                ingestionProcessor.process(repositoryRoot, sourceFilePath, repositoryMetadata, TARGET_COLLECTION_NAME);

        assertTrue(sourceFileProcessing.outcome().processed());
        verify(ingestedFilePruneService)
                .pruneCollectionFileStrict(TARGET_COLLECTION_NAME, sourceUrl, previousFileRecord);
        verify(chunkProcessingService)
                .processAndStoreChunksForce(anyString(), eq(sourceUrl), eq("Main.java"), anyString());
        verify(hybridVectorService).upsertToCollection(TARGET_COLLECTION_NAME, List.of(indexedDocument));
        verify(chunkProcessingService, never())
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void markerWithoutCollectionIdentityPersistsCanonicalIdentityBeforePruning(@TempDir Path temporaryDirectory)
            throws IOException {
        ChunkProcessingService chunkProcessingService = Mockito.mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        LocalStoreService localStoreService = Mockito.mock(LocalStoreService.class);
        FileIngestionMarkerStore fileMarkerStore = Mockito.mock(FileIngestionMarkerStore.class);
        ContentHasher contentHasher = Mockito.mock(ContentHasher.class);
        IngestedFilePruneService ingestedFilePruneService = Mockito.mock(IngestedFilePruneService.class);
        SourceCodeFileIngestionProcessor ingestionProcessor = new SourceCodeFileIngestionProcessor(
                new IngestionStorageServices(
                        hybridVectorService,
                        chunkProcessingService,
                        contentHasher,
                        localStoreService,
                        fileMarkerStore,
                        Mockito.mock(QdrantCollectionRouter.class)),
                Mockito.mock(ProgressTracker.class),
                ingestedFilePruneService);

        Path repositoryRoot = temporaryDirectory.resolve("repository");
        Path sourceFilePath = repositoryRoot.resolve("src").resolve("Main.java");
        Files.createDirectories(Objects.requireNonNull(sourceFilePath.getParent(), "sourceFilePath parent"));
        Files.writeString(sourceFilePath, "package demo; class Main {}", StandardCharsets.UTF_8);
        GitHubRepoMetadata repositoryMetadata = new GitHubRepoMetadata(
                repositoryRoot.toString(),
                GitHubRepositoryIdentity.of("openai", "java-chat"),
                TARGET_COLLECTION_NAME,
                "main",
                "abcdef123456",
                "MIT",
                "Example repository");
        String sourceUrl = "https://github.com/openai/java-chat/blob/main/src/Main.java";
        FileIngestionRecord unboundIngestionRecord = new FileIngestionRecord(
                Files.size(sourceFilePath),
                Files.getLastModifiedTime(sourceFilePath).toMillis(),
                "same-fingerprint",
                "",
                "",
                List.of("unbound-hash"));
        when(contentHasher.sha256(sourceFilePath)).thenReturn("same-fingerprint");
        when(fileMarkerStore.readFileIngestionRecord(sourceUrl)).thenReturn(Optional.of(unboundIngestionRecord));
        when(chunkProcessingService.processAndStoreChunksForce(
                        anyString(), eq(sourceUrl), eq("Main.java"), anyString()))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(List.of(), List.of(), 0, 0));

        ingestionProcessor.process(repositoryRoot, sourceFilePath, repositoryMetadata, TARGET_COLLECTION_NAME);

        FileIngestionRecord boundIngestionRecord =
                unboundIngestionRecord.bindCollectionIdentity(TARGET_COLLECTION_NAME);
        InOrder migrationOrder = inOrder(fileMarkerStore, ingestedFilePruneService);
        migrationOrder.verify(fileMarkerStore).markFileIngested(sourceUrl, boundIngestionRecord);
        migrationOrder
                .verify(ingestedFilePruneService)
                .pruneCollectionFileStrict(TARGET_COLLECTION_NAME, sourceUrl, boundIngestionRecord);
    }

    @Test
    void canonicalIdentityPersistenceFailureStopsBeforePruneOrStorage(@TempDir Path temporaryDirectory)
            throws IOException {
        ChunkProcessingService chunkProcessingService = Mockito.mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        LocalStoreService localStoreService = Mockito.mock(LocalStoreService.class);
        FileIngestionMarkerStore fileMarkerStore = Mockito.mock(FileIngestionMarkerStore.class);
        ContentHasher contentHasher = Mockito.mock(ContentHasher.class);
        IngestedFilePruneService ingestedFilePruneService = Mockito.mock(IngestedFilePruneService.class);
        SourceCodeFileIngestionProcessor ingestionProcessor = new SourceCodeFileIngestionProcessor(
                new IngestionStorageServices(
                        hybridVectorService,
                        chunkProcessingService,
                        contentHasher,
                        localStoreService,
                        fileMarkerStore,
                        Mockito.mock(QdrantCollectionRouter.class)),
                Mockito.mock(ProgressTracker.class),
                ingestedFilePruneService);

        Path repositoryRoot = temporaryDirectory.resolve("repository");
        Path sourceFilePath = repositoryRoot.resolve("src").resolve("Main.java");
        Files.createDirectories(Objects.requireNonNull(sourceFilePath.getParent(), "sourceFilePath parent"));
        Files.writeString(sourceFilePath, "package demo; class Main {}", StandardCharsets.UTF_8);
        GitHubRepoMetadata repositoryMetadata = new GitHubRepoMetadata(
                repositoryRoot.toString(),
                GitHubRepositoryIdentity.of("openai", "java-chat"),
                TARGET_COLLECTION_NAME,
                "main",
                "abcdef123456",
                "MIT",
                "Example repository");
        String sourceUrl = "https://github.com/openai/java-chat/blob/main/src/Main.java";
        FileIngestionRecord unboundIngestionRecord = new FileIngestionRecord(
                Files.size(sourceFilePath),
                Files.getLastModifiedTime(sourceFilePath).toMillis(),
                "same-fingerprint",
                "",
                "",
                List.of("unbound-hash"));
        when(contentHasher.sha256(sourceFilePath)).thenReturn("same-fingerprint");
        when(fileMarkerStore.readFileIngestionRecord(sourceUrl)).thenReturn(Optional.of(unboundIngestionRecord));
        doThrow(new IOException("marker write failed"))
                .when(fileMarkerStore)
                .markFileIngested(eq(sourceUrl), any(FileIngestionRecord.class));

        SourceFileProcessingResult sourceFileProcessing =
                ingestionProcessor.process(repositoryRoot, sourceFilePath, repositoryMetadata, TARGET_COLLECTION_NAME);

        assertFalse(sourceFileProcessing.outcome().processed());
        assertEquals(
                "marker-migration",
                sourceFileProcessing.outcome().failure().orElseThrow().phase());
        verify(ingestedFilePruneService, never())
                .pruneCollectionFileStrict(anyString(), eq(sourceUrl), any(FileIngestionRecord.class));
        verifyNoInteractions(chunkProcessingService, hybridVectorService, localStoreService);
    }

    @Test
    void unchangedFileWithSufficientPointCoverageSkipsProcessing(@TempDir Path temporaryDirectory) throws IOException {
        ChunkProcessingService chunkProcessingService = Mockito.mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        LocalStoreService localStoreService = Mockito.mock(LocalStoreService.class);
        FileIngestionMarkerStore fileMarkerStore = Mockito.mock(FileIngestionMarkerStore.class);
        ContentHasher contentHasher = Mockito.mock(ContentHasher.class);
        ProgressTracker progressTracker = Mockito.mock(ProgressTracker.class);
        IngestedFilePruneService ingestedFilePruneService = Mockito.mock(IngestedFilePruneService.class);

        SourceCodeFileIngestionProcessor ingestionProcessor = new SourceCodeFileIngestionProcessor(
                new IngestionStorageServices(
                        hybridVectorService,
                        chunkProcessingService,
                        contentHasher,
                        localStoreService,
                        fileMarkerStore,
                        Mockito.mock(QdrantCollectionRouter.class)),
                progressTracker,
                ingestedFilePruneService);

        Path repositoryRoot = temporaryDirectory.resolve("repository");
        Path sourceDirectory = repositoryRoot.resolve("src");
        Files.createDirectories(sourceDirectory);
        Path sourceFilePath = sourceDirectory.resolve("Main.java");
        Files.writeString(sourceFilePath, "package demo; class Main {}", StandardCharsets.UTF_8);

        GitHubRepoMetadata repositoryMetadata = new GitHubRepoMetadata(
                repositoryRoot.toString(),
                GitHubRepositoryIdentity.of("openai", "java-chat"),
                TARGET_COLLECTION_NAME,
                "main",
                "abcdef123456",
                "MIT",
                "Example repository");

        String sourceUrl = "https://github.com/openai/java-chat/blob/main/src/Main.java";
        long fileSizeBytes = Files.size(sourceFilePath);
        long lastModifiedMillis = Files.getLastModifiedTime(sourceFilePath).toMillis();

        FileIngestionRecord previousFileRecord = new FileIngestionRecord(
                fileSizeBytes, lastModifiedMillis, "same-fingerprint", "", TARGET_COLLECTION_NAME, List.of("h1", "h2"));

        when(contentHasher.sha256(sourceFilePath)).thenReturn("same-fingerprint");
        when(fileMarkerStore.readFileIngestionRecord(sourceUrl)).thenReturn(Optional.of(previousFileRecord));
        when(hybridVectorService.countPointsForUrl(TARGET_COLLECTION_NAME, sourceUrl))
                .thenReturn(2L);

        SourceFileProcessingResult sourceFileProcessing =
                ingestionProcessor.process(repositoryRoot, sourceFilePath, repositoryMetadata, TARGET_COLLECTION_NAME);

        assertFalse(sourceFileProcessing.outcome().processed());
        assertTrue(sourceFileProcessing.outcome().failure().isEmpty());
        assertEquals(sourceUrl, sourceFileProcessing.fileUrl());
        verify(ingestedFilePruneService, never())
                .pruneCollectionFileStrict(anyString(), anyString(), eq(previousFileRecord));
        verify(chunkProcessingService, never())
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
        verify(chunkProcessingService, never())
                .processAndStoreChunksForce(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void unchangedFileWithNullChunkHashesStillSkipsWhenPointsExist(@TempDir Path temporaryDirectory)
            throws IOException {
        ChunkProcessingService chunkProcessingService = Mockito.mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        LocalStoreService localStoreService = Mockito.mock(LocalStoreService.class);
        FileIngestionMarkerStore fileMarkerStore = Mockito.mock(FileIngestionMarkerStore.class);
        ContentHasher contentHasher = Mockito.mock(ContentHasher.class);
        ProgressTracker progressTracker = Mockito.mock(ProgressTracker.class);
        IngestedFilePruneService ingestedFilePruneService = Mockito.mock(IngestedFilePruneService.class);

        SourceCodeFileIngestionProcessor ingestionProcessor = new SourceCodeFileIngestionProcessor(
                new IngestionStorageServices(
                        hybridVectorService,
                        chunkProcessingService,
                        contentHasher,
                        localStoreService,
                        fileMarkerStore,
                        Mockito.mock(QdrantCollectionRouter.class)),
                progressTracker,
                ingestedFilePruneService);

        Path repositoryRoot = temporaryDirectory.resolve("repository");
        Path sourceDirectory = repositoryRoot.resolve("src");
        Files.createDirectories(sourceDirectory);
        Path sourceFilePath = sourceDirectory.resolve("Main.java");
        Files.writeString(sourceFilePath, "package demo; class Main {}", StandardCharsets.UTF_8);

        GitHubRepoMetadata repositoryMetadata = new GitHubRepoMetadata(
                repositoryRoot.toString(),
                GitHubRepositoryIdentity.of("openai", "java-chat"),
                TARGET_COLLECTION_NAME,
                "main",
                "abcdef123456",
                "MIT",
                "Example repository");

        String sourceUrl = "https://github.com/openai/java-chat/blob/main/src/Main.java";
        long fileSizeBytes = Files.size(sourceFilePath);
        long lastModifiedMillis = Files.getLastModifiedTime(sourceFilePath).toMillis();

        FileIngestionRecord previousFileRecord = new FileIngestionRecord(
                fileSizeBytes, lastModifiedMillis, "same-fingerprint", "", TARGET_COLLECTION_NAME, null);

        when(contentHasher.sha256(sourceFilePath)).thenReturn("same-fingerprint");
        when(fileMarkerStore.readFileIngestionRecord(sourceUrl)).thenReturn(Optional.of(previousFileRecord));
        when(hybridVectorService.countPointsForUrl(TARGET_COLLECTION_NAME, sourceUrl))
                .thenReturn(1L);

        SourceFileProcessingResult sourceFileProcessing =
                ingestionProcessor.process(repositoryRoot, sourceFilePath, repositoryMetadata, TARGET_COLLECTION_NAME);

        assertFalse(sourceFileProcessing.outcome().processed());
        assertTrue(sourceFileProcessing.outcome().failure().isEmpty());
        verify(chunkProcessingService, never())
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
        verify(chunkProcessingService, never())
                .processAndStoreChunksForce(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void processAlwaysIncludesFileUrlInResult(@TempDir Path temporaryDirectory) throws IOException {
        ChunkProcessingService chunkProcessingService = Mockito.mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        LocalStoreService localStoreService = Mockito.mock(LocalStoreService.class);
        FileIngestionMarkerStore fileMarkerStore = Mockito.mock(FileIngestionMarkerStore.class);
        ContentHasher contentHasher = Mockito.mock(ContentHasher.class);
        ProgressTracker progressTracker = Mockito.mock(ProgressTracker.class);
        IngestedFilePruneService ingestedFilePruneService = Mockito.mock(IngestedFilePruneService.class);

        SourceCodeFileIngestionProcessor ingestionProcessor = new SourceCodeFileIngestionProcessor(
                new IngestionStorageServices(
                        hybridVectorService,
                        chunkProcessingService,
                        contentHasher,
                        localStoreService,
                        fileMarkerStore,
                        Mockito.mock(QdrantCollectionRouter.class)),
                progressTracker,
                ingestedFilePruneService);

        Path repositoryRoot = temporaryDirectory.resolve("repository");
        Path sourceDirectory = repositoryRoot.resolve("src");
        Files.createDirectories(sourceDirectory);
        Path sourceFilePath = sourceDirectory.resolve("Empty.java");
        Files.writeString(sourceFilePath, "", StandardCharsets.UTF_8);

        GitHubRepoMetadata repositoryMetadata = new GitHubRepoMetadata(
                repositoryRoot.toString(),
                GitHubRepositoryIdentity.of("owner", "repo"),
                TARGET_COLLECTION_NAME,
                "main",
                "abc123",
                "",
                "");

        SourceFileProcessingResult sourceFileProcessing =
                ingestionProcessor.process(repositoryRoot, sourceFilePath, repositoryMetadata, TARGET_COLLECTION_NAME);

        assertFalse(sourceFileProcessing.outcome().processed());
        assertTrue(sourceFileProcessing.fileUrl().startsWith("https://github.com/owner/repo/blob/main/src/Empty.java"));
    }
}
