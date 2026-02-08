package com.williamcallahan.javachat.cli;

import static io.qdrant.client.ConditionFactory.matchKeyword;

import com.williamcallahan.javachat.domain.ingestion.GitHubRepoMetadata;
import com.williamcallahan.javachat.domain.ingestion.GitHubRepositoryIdentity;
import com.williamcallahan.javachat.domain.ingestion.SourceFileLanguage;
import com.williamcallahan.javachat.domain.ingestion.SourceFileProcessingResult;
import com.williamcallahan.javachat.service.HybridVectorService;
import com.williamcallahan.javachat.service.ProgressTracker;
import com.williamcallahan.javachat.service.ingestion.GitHubRepositoryIdentityResolver;
import com.williamcallahan.javachat.service.ingestion.IngestedFilePruneService;
import com.williamcallahan.javachat.service.ingestion.LocalDocsFileOutcome;
import com.williamcallahan.javachat.service.ingestion.SourceCodeFileIngestionProcessor;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.JsonWithInt.Value;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Profile;

/**
 * CLI application for batch ingestion of a GitHub repository clone into a dedicated
 * Qdrant collection. Walks the repository tree, filters eligible source and documentation
 * files, and delegates per-file processing to {@link SourceCodeFileIngestionProcessor}.
 */
@SpringBootApplication
@Profile(GitHubRepoProcessor.PROFILE_CLI_GITHUB)
@ComponentScan(basePackages = GitHubRepoProcessor.BASE_PACKAGE)
public class GitHubRepoProcessor {

    static final String PROFILE_CLI_GITHUB = "cli-github";
    static final String BASE_PACKAGE = "com.williamcallahan.javachat";

    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubRepoProcessor.class);

    private static final String LOG_BANNER_LINE = "===============================================";
    private static final String LOG_COMPLETE_TITLE = "DOCUMENT PROCESSING COMPLETE";

    /** Directories excluded from repository traversal. */
    private static final Set<String> EXCLUDED_DIRECTORIES = Set.of(
            ".git",
            "build",
            "target",
            "node_modules",
            ".gradle",
            ".idea",
            ".vscode",
            "__pycache__",
            "vendor",
            "dist",
            "out",
            ".next",
            ".cache",
            ".npm",
            ".yarn",
            "bin",
            "obj");

    private static final String ENV_GITHUB_REPO_PATH = "GITHUB_REPO_PATH";
    private static final String ENV_GITHUB_REPO_NAME = "GITHUB_REPO_NAME";
    private static final String ENV_GITHUB_COLLECTION_NAME = "GITHUB_COLLECTION_NAME";
    private static final String ENV_GITHUB_REPO_URL = "GITHUB_REPO_URL";
    private static final String ENV_GITHUB_REPO_OWNER = "GITHUB_REPO_OWNER";
    private static final String ENV_GITHUB_REPO_BRANCH = "GITHUB_REPO_BRANCH";
    private static final String ENV_GITHUB_REPO_COMMIT = "GITHUB_REPO_COMMIT";
    private static final String ENV_GITHUB_REPO_LICENSE = "GITHUB_REPO_LICENSE";
    private static final String ENV_GITHUB_REPO_DESCRIPTION = "GITHUB_REPO_DESCRIPTION";

    private final SourceCodeFileIngestionProcessor fileProcessor;
    private final HybridVectorService hybridVectorService;
    private final IngestedFilePruneService ingestedFilePruneService;
    private final ProgressTracker progressTracker;
    private final GitHubRepositoryIdentityResolver repositoryIdentityResolver;

    /**
     * Creates the CLI GitHub repository processor with required dependencies.
     *
     * @param fileProcessor per-file source code ingestion processor
     * @param hybridVectorService vector store service for scroll and payload operations
     * @param ingestedFilePruneService prune service for deleting orphaned file vectors
     * @param progressTracker tracker for monitoring ingestion progress
     * @param repositoryIdentityResolver canonical GitHub identity resolver
     */
    public GitHubRepoProcessor(
            SourceCodeFileIngestionProcessor fileProcessor,
            HybridVectorService hybridVectorService,
            IngestedFilePruneService ingestedFilePruneService,
            ProgressTracker progressTracker,
            GitHubRepositoryIdentityResolver repositoryIdentityResolver) {
        this.fileProcessor = Objects.requireNonNull(fileProcessor, "fileProcessor");
        this.hybridVectorService = Objects.requireNonNull(hybridVectorService, "hybridVectorService");
        this.ingestedFilePruneService = Objects.requireNonNull(ingestedFilePruneService, "ingestedFilePruneService");
        this.progressTracker = Objects.requireNonNull(progressTracker, "progressTracker");
        this.repositoryIdentityResolver =
                Objects.requireNonNull(repositoryIdentityResolver, "repositoryIdentityResolver");
    }

    /**
     * CLI entry point for GitHub repository ingestion.
     *
     * @param args command-line arguments (unused; configuration via environment variables)
     */
    public static void main(String[] args) {
        ConfigurableApplicationContext applicationContext = SpringApplication.run(GitHubRepoProcessor.class, args);
        int exitCode = SpringApplication.exit(applicationContext, () -> 0);
        System.exit(exitCode);
    }

    /**
     * Configures the CLI processing flow as a Spring Boot command line runner.
     *
     * @return the command line runner that performs repository ingestion
     */
    @Bean
    public CommandLineRunner processGitHubRepo() {
        return this::runRepoProcessing;
    }

    private void runRepoProcessing(String... ignoredArgs) {
        GitHubRepoMetadata repoMetadata = buildMetadataFromEnvironment();
        String collectionName = repoMetadata.collectionName();
        Path repoRoot = Path.of(repoMetadata.repoPath()).toAbsolutePath().normalize();

        logStartBanner(repoMetadata, repoRoot);

        IngestionWalkSummary ingestionWalkSummary = walkAndProcess(repoRoot, repoMetadata, collectionName);

        purgeDeletedFileOrphans(collectionName, ingestionWalkSummary.activeFileUrls());
        refreshCollectionMetadata(collectionName, repoMetadata);

        logSummary(ingestionWalkSummary.totals(), collectionName);

        if (ingestionWalkSummary.totals().failed() > 0) {
            throw new GitHubRepoProcessingException(String.format(
                    Locale.ROOT,
                    "GitHub repo processing completed with %d failed file(s)",
                    ingestionWalkSummary.totals().failed()));
        }
    }

    private IngestionWalkSummary walkAndProcess(Path repoRoot, GitHubRepoMetadata repoMetadata, String collectionName) {
        long processedCount = 0;
        long skippedCount = 0;
        long failedCount = 0;
        long eligibleFileCount = 0;
        Set<String> activeFileUrls = new LinkedHashSet<>();

        try (Stream<Path> fileStream = Files.walk(repoRoot)) {
            Stream<Path> eligibleFileStream = fileStream
                    .filter(path -> !Files.isDirectory(path))
                    .filter(path -> !isExcludedPath(repoRoot, path))
                    .filter(path -> {
                        Path fileNamePath = path.getFileName();
                        return fileNamePath != null && SourceFileLanguage.isIndexableFile(fileNamePath.toString());
                    });

            var eligibleFileIterator = eligibleFileStream.iterator();
            while (eligibleFileIterator.hasNext()) {
                eligibleFileCount++;
                Path sourceFile = eligibleFileIterator.next();
                SourceFileProcessingResult fileProcessingOutcome =
                        fileProcessor.process(repoRoot, sourceFile, repoMetadata, collectionName);
                activeFileUrls.add(fileProcessingOutcome.fileUrl());
                switch (fileProcessingOutcome.outcome()) {
                    case LocalDocsFileOutcome.Processed _ -> processedCount++;
                    case LocalDocsFileOutcome.Skipped _ -> skippedCount++;
                    case LocalDocsFileOutcome.Failed failed -> {
                        failedCount++;
                        failed.failure()
                                .ifPresent(failure ->
                                        LOGGER.warn("File failed (phase={}): {}", failure.phase(), failure.filePath()));
                    }
                }
            }
        } catch (IOException walkException) {
            throw new UncheckedIOException("Failed to walk repository directory: " + repoRoot, walkException);
        }
        LOGGER.info("Found {} eligible files to process", eligibleFileCount);

        return new IngestionWalkSummary(new IngestionTotals(processedCount, skippedCount, failedCount), activeFileUrls);
    }

    /**
     * Detects files removed from the repository since the last ingestion and purges
     * their orphaned vectors and local markers from Qdrant.
     *
     * @throws IllegalStateException if the collection scroll fails
     * @throws GitHubRepoProcessingException if any orphaned file purge operations fail
     */
    private void purgeDeletedFileOrphans(String collectionName, Set<String> activeFileUrls) {
        Set<String> qdrantStoredUrls = hybridVectorService.scrollAllUrlsInCollection(collectionName);

        Set<String> orphanedUrls = new LinkedHashSet<>(qdrantStoredUrls);
        orphanedUrls.removeAll(activeFileUrls);

        if (orphanedUrls.isEmpty()) {
            LOGGER.info("No orphaned file URLs detected in collection '{}'", collectionName);
            return;
        }

        LOGGER.info(
                "Detected {} orphaned file URL(s) in collection '{}'; purging", orphanedUrls.size(), collectionName);
        long purgeFailureCount = 0;
        for (String orphanedUrl : orphanedUrls) {
            try {
                ingestedFilePruneService.pruneCollectionFileStrict(collectionName, orphanedUrl, null);
                LOGGER.info("Purged orphaned file vectors: {}", orphanedUrl);
            } catch (IOException pruneException) {
                purgeFailureCount++;
                LOGGER.error(
                        "Failed to purge orphaned file '{}': {}",
                        orphanedUrl,
                        pruneException.getMessage(),
                        pruneException);
            }
        }
        if (purgeFailureCount > 0) {
            throw new GitHubRepoProcessingException(String.format(
                    Locale.ROOT,
                    "Failed to purge %d of %d orphaned file(s) in collection '%s'",
                    purgeFailureCount,
                    orphanedUrls.size(),
                    collectionName));
        }
    }

    /**
     * Refreshes commit hash and branch metadata on all points in the collection
     * without re-embedding, using Qdrant's partial payload merge.
     *
     * @throws IllegalStateException if the Qdrant payload update fails
     */
    private void refreshCollectionMetadata(String collectionName, GitHubRepoMetadata repoMetadata) {
        Map<String, Value> metadataUpdates = new LinkedHashMap<>();
        if (!repoMetadata.commitHash().isBlank()) {
            metadataUpdates.put("commitHash", ValueFactory.value(Objects.requireNonNull(repoMetadata.commitHash())));
        }
        if (!repoMetadata.repoBranch().isBlank()) {
            metadataUpdates.put("repoBranch", ValueFactory.value(Objects.requireNonNull(repoMetadata.repoBranch())));
        }
        if (metadataUpdates.isEmpty()) {
            return;
        }

        Filter repoFilter = Filter.newBuilder()
                .addMust(matchKeyword("repoName", Objects.requireNonNull(repoMetadata.repoName())))
                .build();

        hybridVectorService.updatePayloadByFilter(collectionName, metadataUpdates, repoFilter);
        LOGGER.info("Refreshed metadata ({}) on all points in '{}'", metadataUpdates.keySet(), collectionName);
    }

    private static boolean isExcludedPath(Path repoRoot, Path filePath) {
        Path relativePath = repoRoot.relativize(filePath);
        for (Path segment : relativePath) {
            if (EXCLUDED_DIRECTORIES.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    private GitHubRepoMetadata buildMetadataFromEnvironment() {
        String repoPath = requireEnv(ENV_GITHUB_REPO_PATH);
        String owner = envOrEmpty(ENV_GITHUB_REPO_OWNER);
        String repositoryName = envOrEmpty(ENV_GITHUB_REPO_NAME);
        String repositoryUrl = envOrEmpty(ENV_GITHUB_REPO_URL);
        GitHubRepositoryIdentity repositoryIdentity =
                repositoryIdentityResolver.resolve(owner, repositoryName, repositoryUrl);

        String collectionName = envOrEmpty(ENV_GITHUB_COLLECTION_NAME);
        if (collectionName.isBlank()) {
            collectionName = repositoryIdentity.canonicalCollectionName();
        }

        return new GitHubRepoMetadata(
                repoPath,
                repositoryIdentity,
                collectionName,
                envOrEmpty(ENV_GITHUB_REPO_BRANCH),
                envOrEmpty(ENV_GITHUB_REPO_COMMIT),
                envOrEmpty(ENV_GITHUB_REPO_LICENSE),
                envOrEmpty(ENV_GITHUB_REPO_DESCRIPTION));
    }

    private static String requireEnv(String key) {
        String environmentValue = System.getenv(key);
        if (environmentValue == null || environmentValue.isBlank()) {
            throw new GitHubRepoProcessingException("Required environment variable not set: " + key);
        }
        return environmentValue;
    }

    private static String envOrEmpty(String key) {
        String environmentValue = System.getenv(key);
        return environmentValue == null ? "" : environmentValue;
    }

    private void logStartBanner(GitHubRepoMetadata repoMetadata, Path repoRoot) {
        if (!LOGGER.isInfoEnabled()) {
            return;
        }
        LOGGER.info(LOG_BANNER_LINE);
        LOGGER.info("GitHub Repository Ingestion");
        LOGGER.info(LOG_BANNER_LINE);
        LOGGER.info("Repository: {}", repoMetadata.repoName());
        LOGGER.info("Path: {}", repoRoot);
        LOGGER.info("Collection: {}", repoMetadata.collectionName());
        if (!repoMetadata.repoUrl().isBlank()) {
            LOGGER.info("URL: {}", repoMetadata.repoUrl());
        }
        if (!repoMetadata.repoBranch().isBlank()) {
            LOGGER.info("Branch: {}", repoMetadata.repoBranch());
        }
        if (!repoMetadata.commitHash().isBlank()) {
            LOGGER.info(
                    "Commit: {}",
                    repoMetadata
                            .commitHash()
                            .substring(0, Math.min(12, repoMetadata.commitHash().length())));
        }
        LOGGER.info("");
    }

    private void logSummary(IngestionTotals totals, String collectionName) {
        if (!LOGGER.isInfoEnabled()) {
            return;
        }
        LOGGER.info("");
        LOGGER.info(LOG_BANNER_LINE);
        LOGGER.info(LOG_COMPLETE_TITLE);
        LOGGER.info(LOG_BANNER_LINE);
        LOGGER.info("Files processed: {}", totals.processed());
        LOGGER.info("Files skipped (duplicates/empty): {}", totals.skipped());
        if (totals.failed() > 0) {
            LOGGER.warn("Files FAILED: {}", totals.failed());
        }
        LOGGER.info("Target collection: {}", collectionName);
        LOGGER.info("Progress: {}", progressTracker.formatPercent());
        LOGGER.info(LOG_BANNER_LINE);
    }

    private record IngestionTotals(long processed, long skipped, long failed) {}

    /** Pairs ingestion counters with the set of active file URLs encountered during the walk. */
    private record IngestionWalkSummary(IngestionTotals totals, Set<String> activeFileUrls) {}

    /**
     * Thrown when GitHub repository processing encounters a fatal condition.
     */
    public static class GitHubRepoProcessingException extends RuntimeException {
        /**
         * Creates a processing exception with a descriptive message.
         *
         * @param message explanation of the processing failure
         */
        public GitHubRepoProcessingException(String message) {
            super(message);
        }
    }
}
