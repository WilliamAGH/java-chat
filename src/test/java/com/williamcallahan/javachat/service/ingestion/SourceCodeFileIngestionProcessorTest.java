package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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
import com.williamcallahan.javachat.service.QdrantPayloadFieldSchema;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
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
    void missingMarkerWithExistingVectorsReplacesUrlDocuments(@TempDir Path temporaryDirectory) throws IOException {
        SourceIngestionScenario ingestionScenario = sourceIngestionScenario(temporaryDirectory);
        Document indexedDocument = new Document("point-1", "package demo; class Main {}", new HashMap<>());
        indexedDocument.getMetadata().put(QdrantPayloadFieldSchema.HASH_FIELD, "newhash");
        when(ingestionScenario
                        .chunkProcessingService()
                        .processAndStoreChunksForce(
                                anyString(), eq(ingestionScenario.sourceUrl()), eq("Main.java"), anyString()))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(indexedDocument), List.of("newhash"), 1, 0));

        SourceFileProcessingResult sourceFileProcessing = ingestionScenario
                .ingestionProcessor()
                .process(
                        ingestionScenario.repositoryContext(Set.of(ingestionScenario.sourceUrl())),
                        ingestionScenario.sourceFilePath());

        assertTrue(sourceFileProcessing.outcome().processed());
        verify(ingestionScenario.chunkProcessingService())
                .processAndStoreChunksForce(
                        anyString(), eq(ingestionScenario.sourceUrl()), eq("Main.java"), anyString());
        verify(ingestionScenario.hybridVectorService())
                .replaceUrlDocuments(TARGET_COLLECTION_NAME, ingestionScenario.sourceUrl(), List.of(indexedDocument));
        verify(ingestionScenario.hybridVectorService(), never())
                .upsertToCollection(eq(TARGET_COLLECTION_NAME), Mockito.anyList());
        verify(ingestionScenario.hybridVectorService(), never()).countPointsForUrl(anyString(), anyString());
    }

    @Test
    void missingMarkerWithoutExistingVectorsUsesNormalUpsert(@TempDir Path temporaryDirectory) throws IOException {
        SourceIngestionScenario ingestionScenario = sourceIngestionScenario(temporaryDirectory);
        Document indexedDocument = new Document("point-1", "package demo; class Main {}", new HashMap<>());
        indexedDocument.getMetadata().put(QdrantPayloadFieldSchema.HASH_FIELD, "newhash");
        when(ingestionScenario
                        .chunkProcessingService()
                        .processAndStoreChunks(
                                anyString(), eq(ingestionScenario.sourceUrl()), eq("Main.java"), anyString()))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(indexedDocument), List.of("newhash"), 1, 0));

        SourceFileProcessingResult sourceFileProcessing = ingestionScenario
                .ingestionProcessor()
                .process(ingestionScenario.repositoryContext(Set.of()), ingestionScenario.sourceFilePath());

        assertTrue(sourceFileProcessing.outcome().processed());
        verify(ingestionScenario.hybridVectorService())
                .upsertToCollection(TARGET_COLLECTION_NAME, List.of(indexedDocument));
        verify(ingestionScenario.hybridVectorService(), never())
                .replaceUrlDocuments(eq(TARGET_COLLECTION_NAME), eq(ingestionScenario.sourceUrl()), Mockito.anyList());
        verify(ingestionScenario.hybridVectorService(), never()).countPointsForUrl(anyString(), anyString());
    }

    @Test
    void changedFileReplacesBeforePruningLocalState(@TempDir Path temporaryDirectory) throws IOException {
        ChunkProcessingService chunkProcessingService = Mockito.mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        LocalStoreService localStoreService = Mockito.mock(LocalStoreService.class);
        FileIngestionMarkerStore fileIngestionMarkerStore = Mockito.mock(FileIngestionMarkerStore.class);
        ContentHasher contentHasher = Mockito.mock(ContentHasher.class);
        ProgressTracker progressTracker = Mockito.mock(ProgressTracker.class);
        IngestedFilePruneService ingestedFilePruneService = Mockito.mock(IngestedFilePruneService.class);

        SourceCodeFileIngestionProcessor ingestionProcessor = new SourceCodeFileIngestionProcessor(
                new IngestionStorageServices(
                        hybridVectorService,
                        chunkProcessingService,
                        contentHasher,
                        localStoreService,
                        fileIngestionMarkerStore,
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
                fileSizeBytes, lastModifiedMillis, "old-fingerprint", "", TARGET_COLLECTION_NAME, List.of("oldhash"));

        when(contentHasher.sha256(sourceFilePath)).thenReturn("new-fingerprint");
        when(fileIngestionMarkerStore.readFileIngestionRecord(sourceUrl)).thenReturn(Optional.of(previousFileRecord));
        when(progressTracker.formatPercent()).thenReturn("100%");

        Document indexedDocument = new Document("point-1", "package demo; class Main {}", new HashMap<>());
        indexedDocument.getMetadata().put(QdrantPayloadFieldSchema.HASH_FIELD, "newhash");

        ChunkProcessingService.ChunkProcessingOutcome chunkProcessingOutcome =
                new ChunkProcessingService.ChunkProcessingOutcome(List.of(indexedDocument), List.of("newhash"), 1, 0);
        when(chunkProcessingService.processAndStoreChunksForce(
                        anyString(), eq(sourceUrl), eq("Main.java"), anyString()))
                .thenReturn(chunkProcessingOutcome);

        SourceFileProcessingResult sourceFileProcessing =
                ingestionProcessor.process(repositoryContext(repositoryRoot, repositoryMetadata), sourceFilePath);

        assertTrue(sourceFileProcessing.outcome().processed());
        assertEquals(sourceUrl, sourceFileProcessing.fileUrl());
        InOrder replacementOrder = inOrder(hybridVectorService, ingestedFilePruneService, fileIngestionMarkerStore);
        replacementOrder
                .verify(hybridVectorService)
                .replaceUrlDocuments(TARGET_COLLECTION_NAME, sourceUrl, List.of(indexedDocument));
        replacementOrder
                .verify(ingestedFilePruneService)
                .pruneObsoleteLocalStateAfterReplacement(sourceUrl, previousFileRecord, List.of("newhash"));
        verify(chunkProcessingService)
                .processAndStoreChunksForce(anyString(), eq(sourceUrl), eq("Main.java"), anyString());
        verify(chunkProcessingService, never())
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
        ArgumentCaptor<FileIngestionRecord> ingestionRecordCaptor = ArgumentCaptor.forClass(FileIngestionRecord.class);
        verify(fileIngestionMarkerStore).markFileIngested(eq(sourceUrl), ingestionRecordCaptor.capture());
        assertEquals("new-fingerprint", ingestionRecordCaptor.getValue().ingestionFingerprint());
        assertTrue(ingestionRecordCaptor.getValue().extractionSemanticsVersion().isBlank());
        assertEquals(TARGET_COLLECTION_NAME, ingestionRecordCaptor.getValue().collectionName());
        assertEquals(List.of("newhash"), ingestionRecordCaptor.getValue().chunkHashes());
    }

    @Test
    void changedFileDoesNotAdvanceMarkersWhenLocalCleanupFails(@TempDir Path temporaryDirectory) throws IOException {
        ChunkProcessingService chunkProcessingService = Mockito.mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        LocalStoreService localStoreService = Mockito.mock(LocalStoreService.class);
        FileIngestionMarkerStore fileIngestionMarkerStore = Mockito.mock(FileIngestionMarkerStore.class);
        ContentHasher contentHasher = Mockito.mock(ContentHasher.class);
        IngestedFilePruneService ingestedFilePruneService = Mockito.mock(IngestedFilePruneService.class);
        SourceCodeFileIngestionProcessor ingestionProcessor = new SourceCodeFileIngestionProcessor(
                new IngestionStorageServices(
                        hybridVectorService,
                        chunkProcessingService,
                        contentHasher,
                        localStoreService,
                        fileIngestionMarkerStore,
                        Mockito.mock(QdrantCollectionRouter.class)),
                Mockito.mock(ProgressTracker.class),
                ingestedFilePruneService);

        Path repositoryRoot = temporaryDirectory.resolve("repository");
        Path sourceFilePath = repositoryRoot.resolve("src/Main.java");
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
        FileIngestionRecord previousFileRecord = new FileIngestionRecord(
                Files.size(sourceFilePath),
                Files.getLastModifiedTime(sourceFilePath).toMillis(),
                "old-fingerprint",
                "",
                TARGET_COLLECTION_NAME,
                List.of("oldhash"));
        Document indexedDocument = new Document("point-1", "package demo; class Main {}", new HashMap<>());
        indexedDocument.getMetadata().put(QdrantPayloadFieldSchema.HASH_FIELD, "newhash");

        when(contentHasher.sha256(sourceFilePath)).thenReturn("new-fingerprint");
        when(fileIngestionMarkerStore.readFileIngestionRecord(sourceUrl)).thenReturn(Optional.of(previousFileRecord));
        when(chunkProcessingService.processAndStoreChunksForce(
                        anyString(), eq(sourceUrl), eq("Main.java"), anyString()))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(indexedDocument), List.of("newhash"), 1, 0));
        Mockito.doThrow(new IOException("local cleanup failed"))
                .when(ingestedFilePruneService)
                .pruneObsoleteLocalStateAfterReplacement(sourceUrl, previousFileRecord, List.of("newhash"));

        SourceFileProcessingResult sourceFileProcessing =
                ingestionProcessor.process(repositoryContext(repositoryRoot, repositoryMetadata), sourceFilePath);

        assertFalse(sourceFileProcessing.outcome().processed());
        assertEquals(
                "prune-local",
                sourceFileProcessing.outcome().failure().orElseThrow().phase());
        verify(hybridVectorService).replaceUrlDocuments(TARGET_COLLECTION_NAME, sourceUrl, List.of(indexedDocument));
        verify(fileIngestionMarkerStore, never()).markFileIngested(anyString(), Mockito.any());
        verify(localStoreService, never()).markHashIngested(anyString(), anyString(), anyString());
    }

    @Test
    void unchangedFingerprintWithStaleMetadataAndDisjointPointIdsForcesReplacement(@TempDir Path temporaryDirectory)
            throws IOException {
        ChunkProcessingService chunkProcessingService = Mockito.mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        LocalStoreService localStoreService = Mockito.mock(LocalStoreService.class);
        FileIngestionMarkerStore fileIngestionMarkerStore = Mockito.mock(FileIngestionMarkerStore.class);
        ContentHasher contentHasher = Mockito.mock(ContentHasher.class);
        ProgressTracker progressTracker = Mockito.mock(ProgressTracker.class);
        IngestedFilePruneService ingestedFilePruneService = Mockito.mock(IngestedFilePruneService.class);

        SourceCodeFileIngestionProcessor ingestionProcessor = new SourceCodeFileIngestionProcessor(
                new IngestionStorageServices(
                        hybridVectorService,
                        chunkProcessingService,
                        contentHasher,
                        localStoreService,
                        fileIngestionMarkerStore,
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
                fileSizeBytes + 1,
                lastModifiedMillis + 1,
                "same-fingerprint",
                "",
                TARGET_COLLECTION_NAME,
                List.of("existing-hash"));

        when(contentHasher.sha256(sourceFilePath)).thenReturn("same-fingerprint");
        when(contentHasher.uuidFromHash("existing-hash")).thenReturn("expected-point-uuid");
        when(fileIngestionMarkerStore.readFileIngestionRecord(sourceUrl)).thenReturn(Optional.of(previousFileRecord));
        when(hybridVectorService.hasExactPointIdsForUrl(
                        TARGET_COLLECTION_NAME, sourceUrl, List.of("expected-point-uuid")))
                .thenReturn(false);
        when(progressTracker.formatPercent()).thenReturn("100%");

        Document indexedDocument = new Document("point-1", "package demo; class Main {}", new HashMap<>());
        indexedDocument.getMetadata().put(QdrantPayloadFieldSchema.HASH_FIELD, "existing-hash");
        ChunkProcessingService.ChunkProcessingOutcome chunkProcessingOutcome =
                new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(indexedDocument), List.of("existing-hash"), 1, 0);
        when(chunkProcessingService.processAndStoreChunksForce(
                        anyString(), eq(sourceUrl), eq("Main.java"), anyString()))
                .thenReturn(chunkProcessingOutcome);

        SourceFileProcessingResult sourceFileProcessing =
                ingestionProcessor.process(repositoryContext(repositoryRoot, repositoryMetadata), sourceFilePath);

        assertTrue(sourceFileProcessing.outcome().processed());
        verify(ingestedFilePruneService)
                .pruneObsoleteLocalStateAfterReplacement(sourceUrl, previousFileRecord, List.of("existing-hash"));
        verify(chunkProcessingService)
                .processAndStoreChunksForce(anyString(), eq(sourceUrl), eq("Main.java"), anyString());
        verify(hybridVectorService).replaceUrlDocuments(TARGET_COLLECTION_NAME, sourceUrl, List.of(indexedDocument));
        verify(chunkProcessingService, never())
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void markerWithoutCollectionIdentityFailsBeforeMutation(@TempDir Path temporaryDirectory) throws IOException {
        ChunkProcessingService chunkProcessingService = Mockito.mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        LocalStoreService localStoreService = Mockito.mock(LocalStoreService.class);
        FileIngestionMarkerStore fileIngestionMarkerStore = Mockito.mock(FileIngestionMarkerStore.class);
        ContentHasher contentHasher = Mockito.mock(ContentHasher.class);
        IngestedFilePruneService ingestedFilePruneService = Mockito.mock(IngestedFilePruneService.class);
        SourceCodeFileIngestionProcessor ingestionProcessor = new SourceCodeFileIngestionProcessor(
                new IngestionStorageServices(
                        hybridVectorService,
                        chunkProcessingService,
                        contentHasher,
                        localStoreService,
                        fileIngestionMarkerStore,
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
        when(fileIngestionMarkerStore.readFileIngestionRecord(sourceUrl))
                .thenReturn(Optional.of(unboundIngestionRecord));
        when(chunkProcessingService.processAndStoreChunksForce(
                        anyString(), eq(sourceUrl), eq("Main.java"), anyString()))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(List.of(), List.of(), 0, 0));

        SourceFileProcessingResult sourceFileProcessing =
                ingestionProcessor.process(repositoryContext(repositoryRoot, repositoryMetadata), sourceFilePath);

        assertEquals(
                "collection-generation",
                sourceFileProcessing.outcome().failure().orElseThrow().phase());
        verify(fileIngestionMarkerStore).readFileIngestionRecord(sourceUrl);
        verifyNoInteractions(chunkProcessingService, hybridVectorService, ingestedFilePruneService);
    }

    @Test
    void markerFromDifferentGenerationFailsBeforeMutation(@TempDir Path temporaryDirectory) throws IOException {
        ChunkProcessingService chunkProcessingService = Mockito.mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        LocalStoreService localStoreService = Mockito.mock(LocalStoreService.class);
        FileIngestionMarkerStore fileIngestionMarkerStore = Mockito.mock(FileIngestionMarkerStore.class);
        ContentHasher contentHasher = Mockito.mock(ContentHasher.class);
        IngestedFilePruneService ingestedFilePruneService = Mockito.mock(IngestedFilePruneService.class);
        SourceCodeFileIngestionProcessor ingestionProcessor = new SourceCodeFileIngestionProcessor(
                new IngestionStorageServices(
                        hybridVectorService,
                        chunkProcessingService,
                        contentHasher,
                        localStoreService,
                        fileIngestionMarkerStore,
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
                PRIOR_COLLECTION_NAME,
                "",
                List.of("unbound-hash"));
        when(contentHasher.sha256(sourceFilePath)).thenReturn("same-fingerprint");
        when(fileIngestionMarkerStore.readFileIngestionRecord(sourceUrl))
                .thenReturn(Optional.of(unboundIngestionRecord));
        SourceFileProcessingResult sourceFileProcessing =
                ingestionProcessor.process(repositoryContext(repositoryRoot, repositoryMetadata), sourceFilePath);

        assertFalse(sourceFileProcessing.outcome().processed());
        assertEquals(
                "collection-generation",
                sourceFileProcessing.outcome().failure().orElseThrow().phase());
        verify(fileIngestionMarkerStore).readFileIngestionRecord(sourceUrl);
        verifyNoMoreInteractions(localStoreService);
        verifyNoMoreInteractions(fileIngestionMarkerStore);
        verifyNoInteractions(chunkProcessingService, hybridVectorService, ingestedFilePruneService);
    }

    @Test
    void unchangedFingerprintWithStaleMetadataAndExactPointCoverageSkipsProcessing(@TempDir Path temporaryDirectory)
            throws IOException {
        ChunkProcessingService chunkProcessingService = Mockito.mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        LocalStoreService localStoreService = Mockito.mock(LocalStoreService.class);
        FileIngestionMarkerStore fileIngestionMarkerStore = Mockito.mock(FileIngestionMarkerStore.class);
        ContentHasher contentHasher = Mockito.mock(ContentHasher.class);
        ProgressTracker progressTracker = Mockito.mock(ProgressTracker.class);
        IngestedFilePruneService ingestedFilePruneService = Mockito.mock(IngestedFilePruneService.class);

        SourceCodeFileIngestionProcessor ingestionProcessor = new SourceCodeFileIngestionProcessor(
                new IngestionStorageServices(
                        hybridVectorService,
                        chunkProcessingService,
                        contentHasher,
                        localStoreService,
                        fileIngestionMarkerStore,
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
                fileSizeBytes + 1,
                lastModifiedMillis + 1,
                "same-fingerprint",
                "",
                TARGET_COLLECTION_NAME,
                List.of("h1", "h2"));

        when(contentHasher.sha256(sourceFilePath)).thenReturn("same-fingerprint");
        when(contentHasher.uuidFromHash("h1")).thenReturn("first-point-uuid");
        when(contentHasher.uuidFromHash("h2")).thenReturn("second-point-uuid");
        when(fileIngestionMarkerStore.readFileIngestionRecord(sourceUrl)).thenReturn(Optional.of(previousFileRecord));
        when(hybridVectorService.hasExactPointIdsForUrl(
                        TARGET_COLLECTION_NAME, sourceUrl, List.of("first-point-uuid", "second-point-uuid")))
                .thenReturn(true);

        SourceFileProcessingResult sourceFileProcessing =
                ingestionProcessor.process(repositoryContext(repositoryRoot, repositoryMetadata), sourceFilePath);

        assertFalse(sourceFileProcessing.outcome().processed());
        assertTrue(sourceFileProcessing.outcome().failure().isEmpty());
        assertEquals(sourceUrl, sourceFileProcessing.fileUrl());
        verify(hybridVectorService)
                .hasExactPointIdsForUrl(
                        TARGET_COLLECTION_NAME, sourceUrl, List.of("first-point-uuid", "second-point-uuid"));
        verify(hybridVectorService, never()).upsertToCollection(eq(TARGET_COLLECTION_NAME), Mockito.anyList());
        verify(hybridVectorService, never())
                .replaceUrlDocuments(eq(TARGET_COLLECTION_NAME), eq(sourceUrl), Mockito.anyList());
        verify(ingestedFilePruneService, never())
                .pruneCollectionFileStrict(anyString(), anyString(), eq(previousFileRecord));
        verify(chunkProcessingService, never())
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
        verify(chunkProcessingService, never())
                .processAndStoreChunksForce(anyString(), anyString(), anyString(), anyString());
        verify(fileIngestionMarkerStore, never()).markFileIngested(anyString(), Mockito.any());
    }

    @Test
    void unchangedFileWithNullChunkHashesForcesReplacement(@TempDir Path temporaryDirectory) throws IOException {
        ChunkProcessingService chunkProcessingService = Mockito.mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        LocalStoreService localStoreService = Mockito.mock(LocalStoreService.class);
        FileIngestionMarkerStore fileIngestionMarkerStore = Mockito.mock(FileIngestionMarkerStore.class);
        ContentHasher contentHasher = Mockito.mock(ContentHasher.class);
        ProgressTracker progressTracker = Mockito.mock(ProgressTracker.class);
        IngestedFilePruneService ingestedFilePruneService = Mockito.mock(IngestedFilePruneService.class);

        SourceCodeFileIngestionProcessor ingestionProcessor = new SourceCodeFileIngestionProcessor(
                new IngestionStorageServices(
                        hybridVectorService,
                        chunkProcessingService,
                        contentHasher,
                        localStoreService,
                        fileIngestionMarkerStore,
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
        when(fileIngestionMarkerStore.readFileIngestionRecord(sourceUrl)).thenReturn(Optional.of(previousFileRecord));
        when(progressTracker.formatPercent()).thenReturn("100%");
        Document replacementDocument =
                new Document("replacement-point", "package demo; class Main {}", new HashMap<>());
        replacementDocument.getMetadata().put(QdrantPayloadFieldSchema.HASH_FIELD, "replacement-hash");
        when(chunkProcessingService.processAndStoreChunksForce(
                        anyString(), eq(sourceUrl), eq("Main.java"), anyString()))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(replacementDocument), List.of("replacement-hash"), 1, 0));

        SourceFileProcessingResult sourceFileProcessing =
                ingestionProcessor.process(repositoryContext(repositoryRoot, repositoryMetadata), sourceFilePath);

        assertTrue(sourceFileProcessing.outcome().processed());
        verify(hybridVectorService, never()).hasExactPointIdsForUrl(anyString(), anyString(), Mockito.anyList());
        verify(hybridVectorService)
                .replaceUrlDocuments(TARGET_COLLECTION_NAME, sourceUrl, List.of(replacementDocument));
        verify(chunkProcessingService, never())
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
        verify(chunkProcessingService)
                .processAndStoreChunksForce(anyString(), eq(sourceUrl), eq("Main.java"), anyString());
    }

    @Test
    void processAlwaysIncludesFileUrlInResult(@TempDir Path temporaryDirectory) throws IOException {
        ChunkProcessingService chunkProcessingService = Mockito.mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        LocalStoreService localStoreService = Mockito.mock(LocalStoreService.class);
        FileIngestionMarkerStore fileIngestionMarkerStore = Mockito.mock(FileIngestionMarkerStore.class);
        ContentHasher contentHasher = Mockito.mock(ContentHasher.class);
        ProgressTracker progressTracker = Mockito.mock(ProgressTracker.class);
        IngestedFilePruneService ingestedFilePruneService = Mockito.mock(IngestedFilePruneService.class);

        SourceCodeFileIngestionProcessor ingestionProcessor = new SourceCodeFileIngestionProcessor(
                new IngestionStorageServices(
                        hybridVectorService,
                        chunkProcessingService,
                        contentHasher,
                        localStoreService,
                        fileIngestionMarkerStore,
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
                ingestionProcessor.process(repositoryContext(repositoryRoot, repositoryMetadata), sourceFilePath);

        assertFalse(sourceFileProcessing.outcome().processed());
        assertTrue(sourceFileProcessing.fileUrl().startsWith("https://github.com/owner/repo/blob/main/src/Empty.java"));
    }

    private static SourceCodeFileIngestionProcessor.RepositoryIngestionContext repositoryContext(
            Path repositoryRoot, GitHubRepoMetadata repositoryMetadata) {
        return new SourceCodeFileIngestionProcessor.RepositoryIngestionContext(
                repositoryRoot, repositoryMetadata, Set.of());
    }

    private static SourceIngestionScenario sourceIngestionScenario(Path temporaryDirectory) throws IOException {
        ChunkProcessingService chunkProcessingService = Mockito.mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        LocalStoreService localStoreService = Mockito.mock(LocalStoreService.class);
        FileIngestionMarkerStore fileIngestionMarkerStore = Mockito.mock(FileIngestionMarkerStore.class);
        ContentHasher contentHasher = Mockito.mock(ContentHasher.class);
        ProgressTracker progressTracker = Mockito.mock(ProgressTracker.class);
        IngestedFilePruneService ingestedFilePruneService = Mockito.mock(IngestedFilePruneService.class);
        SourceCodeFileIngestionProcessor ingestionProcessor = new SourceCodeFileIngestionProcessor(
                new IngestionStorageServices(
                        hybridVectorService,
                        chunkProcessingService,
                        contentHasher,
                        localStoreService,
                        fileIngestionMarkerStore,
                        Mockito.mock(QdrantCollectionRouter.class)),
                progressTracker,
                ingestedFilePruneService);
        Path repositoryRoot = temporaryDirectory.resolve("repository");
        Path sourceFilePath = repositoryRoot.resolve("src/Main.java");
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
        when(contentHasher.sha256(sourceFilePath)).thenReturn("new-fingerprint");
        when(fileIngestionMarkerStore.readFileIngestionRecord(sourceUrl)).thenReturn(Optional.empty());
        when(progressTracker.formatPercent()).thenReturn("100%");
        return new SourceIngestionScenario(
                ingestionProcessor,
                chunkProcessingService,
                hybridVectorService,
                localStoreService,
                fileIngestionMarkerStore,
                ingestedFilePruneService,
                repositoryRoot,
                sourceFilePath,
                repositoryMetadata,
                sourceUrl);
    }

    private record SourceIngestionScenario(
            SourceCodeFileIngestionProcessor ingestionProcessor,
            ChunkProcessingService chunkProcessingService,
            HybridVectorService hybridVectorService,
            LocalStoreService localStoreService,
            FileIngestionMarkerStore fileIngestionMarkerStore,
            IngestedFilePruneService ingestedFilePruneService,
            Path repositoryRoot,
            Path sourceFilePath,
            GitHubRepoMetadata repositoryMetadata,
            String sourceUrl) {
        private SourceCodeFileIngestionProcessor.RepositoryIngestionContext repositoryContext(
                Set<String> storedFileUrls) {
            return new SourceCodeFileIngestionProcessor.RepositoryIngestionContext(
                    repositoryRoot, repositoryMetadata, storedFileUrls);
        }
    }
}
