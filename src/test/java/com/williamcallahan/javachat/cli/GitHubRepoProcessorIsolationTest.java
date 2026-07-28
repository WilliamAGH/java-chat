package com.williamcallahan.javachat.cli;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.domain.ingestion.GitHubRepoMetadata;
import com.williamcallahan.javachat.domain.ingestion.GitHubRepositoryIdentity;
import com.williamcallahan.javachat.domain.ingestion.IngestionLocalFailure;
import com.williamcallahan.javachat.domain.ingestion.SourceFileProcessingResult;
import com.williamcallahan.javachat.service.HybridVectorService;
import com.williamcallahan.javachat.service.ProgressTracker;
import com.williamcallahan.javachat.service.ingestion.GitHubRepositoryIdentityResolver;
import com.williamcallahan.javachat.service.ingestion.IngestedFilePruneService;
import com.williamcallahan.javachat.service.ingestion.LocalDocsFileOutcome;
import com.williamcallahan.javachat.service.ingestion.SourceCodeFileIngestionProcessor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies that direct GitHub CLI execution cannot cross embedding-generation boundaries. */
class GitHubRepoProcessorIsolationTest {
    private static final String COLLECTION_NAME = "github-qwen3-embedding-4b-2560-openai-java-chat-0123456789abcdef";
    private static final int ELIGIBLE_FILE_COUNT = 50;

    @Test
    void acceptsSharedGenerationCollection() {
        assertDoesNotThrow(() -> GitHubRepoProcessor.requireGenerationGitHubCollection(COLLECTION_NAME));
    }

    @Test
    void rejectsEnvironmentScopedCollection() {
        assertThrows(
                GitHubRepoProcessor.GitHubRepoProcessingException.class,
                () -> GitHubRepoProcessor.requireGenerationGitHubCollection(
                        "github-prod-qwen3-embedding-4b-2560-openai-java-chat-0123456789abcdef"));
    }

    @Test
    void rejectsCollectionFromAnotherEmbeddingGeneration() {
        assertThrows(
                GitHubRepoProcessor.GitHubRepoProcessingException.class,
                () -> GitHubRepoProcessor.requireGenerationGitHubCollection(
                        "github-qwen3-embedding-8b-4096-openai-java-chat-0123456789abcdef"));
    }

    @Test
    void snapshotsStoredUrlsOnceForLargeRepositoryWalk(@TempDir Path repositoryRoot) throws IOException {
        for (int fileIndex = 0; fileIndex < ELIGIBLE_FILE_COUNT; fileIndex++) {
            Files.writeString(repositoryRoot.resolve("Source" + fileIndex + ".java"), "class Source {}");
        }
        ProcessorFixture processorFixture = processorFixture();
        when(processorFixture.hybridVectorService().scrollAllUrlsInCollection(COLLECTION_NAME))
                .thenReturn(Set.of());
        when(processorFixture
                        .fileProcessor()
                        .process(
                                any(SourceCodeFileIngestionProcessor.RepositoryIngestionContext.class),
                                any(Path.class)))
                .thenReturn(new SourceFileProcessingResult(
                        LocalDocsFileOutcome.skippedFile(),
                        "https://github.com/openai/java-chat/blob/main/Source.java"));

        processorFixture.processor().processRepository(repositoryMetadata(repositoryRoot));

        verify(processorFixture.hybridVectorService()).scrollAllUrlsInCollection(COLLECTION_NAME);
        verifyNoMoreInteractions(processorFixture.hybridVectorService());
        verify(processorFixture.fileProcessor(), times(ELIGIBLE_FILE_COUNT))
                .process(any(SourceCodeFileIngestionProcessor.RepositoryIngestionContext.class), any(Path.class));
        verifyNoInteractions(processorFixture.ingestedFilePruneService());
    }

    @Test
    void snapshotFailureStopsBeforeRepositoryTraversal(@TempDir Path repositoryRoot) throws IOException {
        Files.writeString(repositoryRoot.resolve("Source.java"), "class Source {}");
        ProcessorFixture processorFixture = processorFixture();
        when(processorFixture.hybridVectorService().scrollAllUrlsInCollection(COLLECTION_NAME))
                .thenThrow(new IllegalStateException("qdrant unavailable"));

        assertThrows(
                IllegalStateException.class,
                () -> processorFixture.processor().processRepository(repositoryMetadata(repositoryRoot)));

        verifyNoInteractions(
                processorFixture.fileProcessor(),
                processorFixture.ingestedFilePruneService(),
                processorFixture.progressTracker());
    }

    @Test
    void fileFailurePreventsOrphanPruningAndMetadataRefresh(@TempDir Path repositoryRoot) throws IOException {
        Path sourceFilePath = repositoryRoot.resolve("Source.java");
        Files.writeString(sourceFilePath, "class Source {}");
        ProcessorFixture processorFixture = processorFixture();
        when(processorFixture.hybridVectorService().scrollAllUrlsInCollection(COLLECTION_NAME))
                .thenReturn(Set.of("https://github.com/openai/java-chat/blob/main/Deleted.java"));
        when(processorFixture
                        .fileProcessor()
                        .process(
                                any(SourceCodeFileIngestionProcessor.RepositoryIngestionContext.class),
                                any(Path.class)))
                .thenReturn(new SourceFileProcessingResult(
                        LocalDocsFileOutcome.failedFile(
                                new IngestionLocalFailure(sourceFilePath.toString(), "chunking", "malformed source")),
                        "https://github.com/openai/java-chat/blob/main/Source.java"));

        assertThrows(
                GitHubRepoProcessor.GitHubRepoProcessingException.class,
                () -> processorFixture.processor().processRepository(repositoryMetadata(repositoryRoot)));

        verify(processorFixture.hybridVectorService()).scrollAllUrlsInCollection(COLLECTION_NAME);
        verifyNoMoreInteractions(processorFixture.hybridVectorService());
        verifyNoInteractions(processorFixture.ingestedFilePruneService());
    }

    private static GitHubRepoMetadata repositoryMetadata(Path repositoryRoot) {
        return new GitHubRepoMetadata(
                repositoryRoot.toString(),
                GitHubRepositoryIdentity.of("openai", "java-chat"),
                COLLECTION_NAME,
                "",
                "",
                "",
                "");
    }

    private static ProcessorFixture processorFixture() {
        SourceCodeFileIngestionProcessor fileProcessor = mock(SourceCodeFileIngestionProcessor.class);
        HybridVectorService hybridVectorService = mock(HybridVectorService.class);
        IngestedFilePruneService ingestedFilePruneService = mock(IngestedFilePruneService.class);
        ProgressTracker progressTracker = mock(ProgressTracker.class);
        GitHubRepoProcessor processor = new GitHubRepoProcessor(
                fileProcessor,
                hybridVectorService,
                ingestedFilePruneService,
                progressTracker,
                mock(GitHubRepositoryIdentityResolver.class));
        return new ProcessorFixture(
                processor, fileProcessor, hybridVectorService, ingestedFilePruneService, progressTracker);
    }

    private record ProcessorFixture(
            GitHubRepoProcessor processor,
            SourceCodeFileIngestionProcessor fileProcessor,
            HybridVectorService hybridVectorService,
            IngestedFilePruneService ingestedFilePruneService,
            ProgressTracker progressTracker) {}
}
