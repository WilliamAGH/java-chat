package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.domain.ingestion.GitHubRepoMetadata;
import com.williamcallahan.javachat.domain.ingestion.GitHubRepositoryIdentity;
import com.williamcallahan.javachat.domain.ingestion.SourceFileProcessingResult;
import com.williamcallahan.javachat.service.ChunkProcessingService;
import com.williamcallahan.javachat.service.HybridVectorService;
import com.williamcallahan.javachat.service.LocalStoreService;
import com.williamcallahan.javachat.service.ProgressTracker;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.ai.document.Document;

/**
 * Verifies incremental GitHub source ingestion behavior for changed and unchanged files.
 */
class SourceCodeFileIngestionProcessorTest {
    private static final String TARGET_COLLECTION_NAME = "target-collection";

    @Test
    void changedFilePrunesAndForcesReindex(@TempDir Path tempDirectory) throws IOException {
        ChunkProcessingService chunkProcessingService = Mockito.mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        LocalStoreService localStoreService = Mockito.mock(LocalStoreService.class);
        ProgressTracker progressTracker = Mockito.mock(ProgressTracker.class);
        IngestedFilePruneService ingestedFilePruneService = Mockito.mock(IngestedFilePruneService.class);

        SourceCodeFileIngestionProcessor ingestionProcessor = new SourceCodeFileIngestionProcessor(
                chunkProcessingService,
                hybridVectorService,
                localStoreService,
                progressTracker,
                ingestedFilePruneService);

        Path repositoryRoot = tempDirectory.resolve("repository");
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
        LocalStoreService.FileIngestionRecord previousFileRecord = new LocalStoreService.FileIngestionRecord(
                fileSizeBytes, lastModifiedMillis, "old-fingerprint", List.of("oldhash"));

        when(localStoreService.computeFileContentFingerprint(sourceFilePath)).thenReturn("new-fingerprint");
        when(localStoreService.readFileIngestionRecord(sourceUrl)).thenReturn(Optional.of(previousFileRecord));
        when(progressTracker.formatPercent()).thenReturn("100%");

        Document indexedDocument = new Document("point-1", "package demo; class Main {}", new HashMap<>());
        indexedDocument.getMetadata().put("hash", "newhash");

        ChunkProcessingService.ChunkProcessingOutcome chunkProcessingOutcome =
                new ChunkProcessingService.ChunkProcessingOutcome(List.of(indexedDocument), List.of("newhash"), 1, 0);
        when(chunkProcessingService.processAndStoreChunksForce(
                        anyString(), eq(sourceUrl), eq("Main.java"), anyString()))
                .thenReturn(chunkProcessingOutcome);

        doNothing()
                .when(hybridVectorService)
                .upsertToCollection(eq(TARGET_COLLECTION_NAME), eq(List.of(indexedDocument)));
        doNothing().when(localStoreService).markHashIngested("newhash");
        doNothing()
                .when(localStoreService)
                .markFileIngested(eq(sourceUrl), anyLong(), anyLong(), eq("new-fingerprint"), eq(List.of("newhash")));

        SourceFileProcessingResult processingResult =
                ingestionProcessor.process(repositoryRoot, sourceFilePath, repositoryMetadata, TARGET_COLLECTION_NAME);

        assertTrue(processingResult.outcome().processed());
        assertEquals(sourceUrl, processingResult.fileUrl());
        verify(ingestedFilePruneService)
                .pruneCollectionFileStrict(TARGET_COLLECTION_NAME, sourceUrl, previousFileRecord);
        verify(chunkProcessingService)
                .processAndStoreChunksForce(anyString(), eq(sourceUrl), eq("Main.java"), anyString());
        verify(chunkProcessingService, never())
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void unchangedFileWithMissingPointCoverageForcesReindex(@TempDir Path tempDirectory) throws IOException {
        ChunkProcessingService chunkProcessingService = Mockito.mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        LocalStoreService localStoreService = Mockito.mock(LocalStoreService.class);
        ProgressTracker progressTracker = Mockito.mock(ProgressTracker.class);
        IngestedFilePruneService ingestedFilePruneService = Mockito.mock(IngestedFilePruneService.class);

        SourceCodeFileIngestionProcessor ingestionProcessor = new SourceCodeFileIngestionProcessor(
                chunkProcessingService,
                hybridVectorService,
                localStoreService,
                progressTracker,
                ingestedFilePruneService);

        Path repositoryRoot = tempDirectory.resolve("repository");
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
        LocalStoreService.FileIngestionRecord previousFileRecord = new LocalStoreService.FileIngestionRecord(
                fileSizeBytes, lastModifiedMillis, "same-fingerprint", List.of("existing-hash"));

        when(localStoreService.computeFileContentFingerprint(sourceFilePath)).thenReturn("same-fingerprint");
        when(localStoreService.readFileIngestionRecord(sourceUrl)).thenReturn(Optional.of(previousFileRecord));
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

        SourceFileProcessingResult processingResult =
                ingestionProcessor.process(repositoryRoot, sourceFilePath, repositoryMetadata, TARGET_COLLECTION_NAME);

        assertTrue(processingResult.outcome().processed());
        verify(ingestedFilePruneService)
                .pruneCollectionFileStrict(TARGET_COLLECTION_NAME, sourceUrl, previousFileRecord);
        verify(chunkProcessingService)
                .processAndStoreChunksForce(anyString(), eq(sourceUrl), eq("Main.java"), anyString());
        verify(hybridVectorService).upsertToCollection(TARGET_COLLECTION_NAME, List.of(indexedDocument));
        verify(chunkProcessingService, never())
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void unchangedFileWithSufficientPointCoverageSkipsProcessing(@TempDir Path tempDirectory) throws IOException {
        ChunkProcessingService chunkProcessingService = Mockito.mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        LocalStoreService localStoreService = Mockito.mock(LocalStoreService.class);
        ProgressTracker progressTracker = Mockito.mock(ProgressTracker.class);
        IngestedFilePruneService ingestedFilePruneService = Mockito.mock(IngestedFilePruneService.class);

        SourceCodeFileIngestionProcessor ingestionProcessor = new SourceCodeFileIngestionProcessor(
                chunkProcessingService,
                hybridVectorService,
                localStoreService,
                progressTracker,
                ingestedFilePruneService);

        Path repositoryRoot = tempDirectory.resolve("repository");
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

        LocalStoreService.FileIngestionRecord previousFileRecord = new LocalStoreService.FileIngestionRecord(
                fileSizeBytes, lastModifiedMillis, "same-fingerprint", List.of("h1", "h2"));

        when(localStoreService.computeFileContentFingerprint(sourceFilePath)).thenReturn("same-fingerprint");
        when(localStoreService.readFileIngestionRecord(sourceUrl)).thenReturn(Optional.of(previousFileRecord));
        when(hybridVectorService.countPointsForUrl(TARGET_COLLECTION_NAME, sourceUrl))
                .thenReturn(2L);

        SourceFileProcessingResult processingResult =
                ingestionProcessor.process(repositoryRoot, sourceFilePath, repositoryMetadata, TARGET_COLLECTION_NAME);

        assertFalse(processingResult.outcome().processed());
        assertTrue(processingResult.outcome().failure().isEmpty());
        assertEquals(sourceUrl, processingResult.fileUrl());
        verify(ingestedFilePruneService, never())
                .pruneCollectionFileStrict(anyString(), anyString(), eq(previousFileRecord));
        verify(chunkProcessingService, never())
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
        verify(chunkProcessingService, never())
                .processAndStoreChunksForce(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void unchangedFileWithNullChunkHashesStillSkipsWhenPointsExist(@TempDir Path tempDirectory) throws IOException {
        ChunkProcessingService chunkProcessingService = Mockito.mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        LocalStoreService localStoreService = Mockito.mock(LocalStoreService.class);
        ProgressTracker progressTracker = Mockito.mock(ProgressTracker.class);
        IngestedFilePruneService ingestedFilePruneService = Mockito.mock(IngestedFilePruneService.class);

        SourceCodeFileIngestionProcessor ingestionProcessor = new SourceCodeFileIngestionProcessor(
                chunkProcessingService,
                hybridVectorService,
                localStoreService,
                progressTracker,
                ingestedFilePruneService);

        Path repositoryRoot = tempDirectory.resolve("repository");
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

        LocalStoreService.FileIngestionRecord previousFileRecord =
                new LocalStoreService.FileIngestionRecord(fileSizeBytes, lastModifiedMillis, "same-fingerprint", null);

        when(localStoreService.computeFileContentFingerprint(sourceFilePath)).thenReturn("same-fingerprint");
        when(localStoreService.readFileIngestionRecord(sourceUrl)).thenReturn(Optional.of(previousFileRecord));
        when(hybridVectorService.countPointsForUrl(TARGET_COLLECTION_NAME, sourceUrl))
                .thenReturn(1L);

        SourceFileProcessingResult processingResult =
                ingestionProcessor.process(repositoryRoot, sourceFilePath, repositoryMetadata, TARGET_COLLECTION_NAME);

        assertFalse(processingResult.outcome().processed());
        assertTrue(processingResult.outcome().failure().isEmpty());
        verify(chunkProcessingService, never())
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
        verify(chunkProcessingService, never())
                .processAndStoreChunksForce(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void processAlwaysIncludesFileUrlInResult(@TempDir Path tempDirectory) throws IOException {
        ChunkProcessingService chunkProcessingService = Mockito.mock(ChunkProcessingService.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        LocalStoreService localStoreService = Mockito.mock(LocalStoreService.class);
        ProgressTracker progressTracker = Mockito.mock(ProgressTracker.class);
        IngestedFilePruneService ingestedFilePruneService = Mockito.mock(IngestedFilePruneService.class);

        SourceCodeFileIngestionProcessor ingestionProcessor = new SourceCodeFileIngestionProcessor(
                chunkProcessingService,
                hybridVectorService,
                localStoreService,
                progressTracker,
                ingestedFilePruneService);

        Path repositoryRoot = tempDirectory.resolve("repository");
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

        SourceFileProcessingResult processingResult =
                ingestionProcessor.process(repositoryRoot, sourceFilePath, repositoryMetadata, TARGET_COLLECTION_NAME);

        assertFalse(processingResult.outcome().processed());
        assertTrue(processingResult.fileUrl().startsWith("https://github.com/owner/repo/blob/main/src/Empty.java"));
    }
}
