package com.williamcallahan.javachat.cli;

import com.williamcallahan.javachat.domain.ingestion.IngestionLocalOutcome;
import com.williamcallahan.javachat.service.DocsIngestionService;
import com.williamcallahan.javachat.service.ProgressTracker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Profile;

/**
 * CLI application for batch ingestion of documentation into the vector store.
 * Processes HTML and PDF files from configured documentation directories with
 * content-hash-based deduplication.
 */
@SpringBootApplication
@Profile(DocumentProcessor.PROFILE_CLI)
@ComponentScan(basePackages = DocumentProcessor.BASE_PACKAGE)
public class DocumentProcessor {

    static final String PROFILE_CLI = "cli";
    static final String BASE_PACKAGE = "com.williamcallahan.javachat";

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentProcessor.class);

    private static final String LOG_BANNER_LINE = "===============================================";
    private static final String LOG_SECTION_LINE = "-----------------------------------------------";
    private static final String LOG_BLANK_LINE = "";
    private static final String LOG_START_TITLE = "Starting Document Processing with Deduplication";
    private static final String LOG_COMPLETE_TITLE = "DOCUMENT PROCESSING COMPLETE";
    private static final String LOG_DEDUP_ENABLED = "Deduplication: ENABLED (using content hashes)";
    private static final String LOG_NEXT_STEPS = "Next steps:";
    private static final String LOG_DOCS_INDEXED =
            "Documents have been indexed in Qdrant with automatic deduplication.";
    private static final String LOG_HASH_DESCRIPTION =
            "Each document chunk is identified by a SHA-256 hash of its content.";
    private static final String LOG_PROCESSING_SET = "Processing documentation set";
    private static final String LOG_FILES_TO_PROCESS = "Files to process: {}";
    private static final String LOG_DUPLICATES_SKIPPED = "  Skipped {} duplicate files (already in Qdrant)";
    private static final String LOG_SKIP_PATH_ESCAPE = "Skipping documentation set (path escaped base directory)";
    private static final String LOG_SKIP_DIR_NOT_FOUND = "Skipping documentation set (directory not found)";
    private static final String LOG_SKIP_NO_ELIGIBLE = "Skipping documentation set (no eligible files)";
    private static final String LOG_PROCESSING_FAILED = "Failed to process documentation set (exceptionType={})";
    private static final String LOG_STACK_TRACE = "Stack trace:";
    private static final String LOG_PROCESSED_STATS = "Processed {} files in {}s ({} files/sec) ({})";
    private static final String LOG_TOTAL_PROCESSED = "Total new documents processed: {}";
    private static final String LOG_TOTAL_DUPLICATES = "Total duplicates skipped: {}";
    private static final String LOG_TOTAL_SKIPPED = "Documentation sets skipped: {}";
    private static final String LOG_TOTAL_FAILED = "Documentation sets FAILED: {}";
    private static final String LOG_NEXT_STEP_QDRANT = "1. Verify in Qdrant Dashboard";
    private static final String LOG_NEXT_STEP_RETRIEVAL = "2. Test retrieval";
    private static final String LOG_NEXT_STEP_CHAT = "3. Start chat: ./gradlew bootRun";
    private static final String LOG_DOCSET_FILTER = "Doc set filter active";
    private static final String LOG_DOCSET_SELECTED = "Doc sets selected: {} set(s)";

    private static final String SKIP_REASON_INVALID_PATH = "invalid path";
    private static final String SKIP_REASON_DIR_MISSING = "directory not found";
    private static final String SKIP_REASON_NO_ELIGIBLE_FILES = "no eligible files";

    private static final String EXT_HTML = ".html";
    private static final String EXT_HTM = ".htm";
    private static final String EXT_PDF = ".pdf";

    private static final String FORMAT_SECONDS = "%.2f";
    private static final String FORMAT_RATE = "%.1f";
    private static final double MILLIS_PER_SECOND = 1_000.0;

    private static final String PROCESSING_FAILED_TEMPLATE =
            "Document processing completed with %d failed documentation set(s)";
    private static final String FILE_ENUMERATION_FAILURE_TEMPLATE =
            "Failed to enumerate files in %s - check directory permissions";

    private final DocsIngestionService ingestionService;
    private final ProgressTracker progressTracker;

    /**
     * Creates the CLI document processor with required dependencies.
     *
     * @param ingestionService service for ingesting documentation into the vector store
     * @param progressTracker tracker for monitoring ingestion progress
     */
    public DocumentProcessor(final DocsIngestionService ingestionService, final ProgressTracker progressTracker) {
        this.ingestionService = ingestionService;
        this.progressTracker = progressTracker;
    }

    /**
     * CLI entry point for document ingestion.
     *
     * @param args command-line arguments
     */
    public static void main(final String[] args) {
        SpringApplication.run(DocumentProcessor.class, args);
    }

    /**
     * Configures the CLI processing flow.
     *
     * @return the command line runner that performs ingestion
     */
    @Bean
    public CommandLineRunner processDocuments() {
        return this::runDocumentProcessing;
    }

    private void runDocumentProcessing(final String... ignoredArgs) {
        final EnvironmentConfig config = EnvironmentConfig.fromEnvironment();
        logStartBanner(config);

        final Path basePath = Path.of(config.docsDirectory()).toAbsolutePath().normalize();
        final List<DocumentationSet> selectedSets = selectDocumentationSets(config);
        final IngestionTotals totals = selectedSets.stream()
                .map(docSet -> processDocumentationSet(basePath, docSet))
                .reduce(IngestionTotals.ZERO, this::accumulateOutcome, IngestionTotals::combine);

        logSummary(config, totals);

        if (totals.failedSets() > 0) {
            throw new DocumentProcessingException(
                    String.format(Locale.ROOT, PROCESSING_FAILED_TEMPLATE, totals.failedSets()));
        }
    }

    private IngestionTotals accumulateOutcome(final IngestionTotals totals, final ProcessingOutcome outcome) {
        return switch (outcome) {
            case ProcessingOutcome.Success success -> totals.addSuccess(success.processed(), success.duplicates());
            case ProcessingOutcome.Skipped _ -> totals.addSkipped();
            case ProcessingOutcome.Failed _ -> totals.addFailed();
        };
    }

    private ProcessingOutcome processDocumentationSet(final Path basePath, final DocumentationSet docSet) {
        final Path docsPath = basePath.resolve(docSet.relativePath()).normalize();

        if (!docsPath.startsWith(basePath)) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(LOG_SKIP_PATH_ESCAPE);
            }
            return new ProcessingOutcome.Skipped(docSet.displayName(), SKIP_REASON_INVALID_PATH);
        }

        if (!Files.exists(docsPath) || !Files.isDirectory(docsPath)) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(LOG_SKIP_DIR_NOT_FOUND);
            }
            return new ProcessingOutcome.Skipped(docSet.displayName(), SKIP_REASON_DIR_MISSING);
        }

        final long fileCount = countEligibleFiles(docsPath);
        if (fileCount <= 0) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(LOG_SKIP_NO_ELIGIBLE);
            }
            return new ProcessingOutcome.Skipped(docSet.displayName(), SKIP_REASON_NO_ELIGIBLE_FILES);
        }

        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(LOG_SECTION_LINE);
            LOGGER.info(LOG_PROCESSING_SET);
            LOGGER.info(LOG_FILES_TO_PROCESS, fileCount);
        }

        final long startMillis = System.currentTimeMillis();
        try {
            final IngestionLocalOutcome outcome =
                    ingestionService.ingestLocalDirectory(docsPath.toString(), Integer.MAX_VALUE);
            final int processed = outcome.processed();
            final long elapsedMillis = System.currentTimeMillis() - startMillis;
            logProcessingStats(processed, elapsedMillis);

            final int failureCount = outcome.failures().size();
            final long duplicates = fileCount - processed - failureCount;
            if (duplicates > 0) {
                if (LOGGER.isInfoEnabled()) {
                    LOGGER.info(LOG_DUPLICATES_SKIPPED, duplicates);
                }
            }
            if (failureCount > 0 && LOGGER.isWarnEnabled()) {
                LOGGER.warn("Ingestion completed with {} file failures", failureCount);
            }
            return new ProcessingOutcome.Success(processed, duplicates);

        } catch (IOException ioException) {
            if (LOGGER.isErrorEnabled()) {
                LOGGER.error(LOG_PROCESSING_FAILED, ioException.getClass().getSimpleName());
            }
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug(LOG_STACK_TRACE, ioException);
            }
            return new ProcessingOutcome.Failed(docSet.displayName());
        }
    }

    private long countEligibleFiles(final Path docsPath) {
        try (Stream<Path> paths = Files.walk(docsPath)) {
            return paths.filter(path -> !Files.isDirectory(path))
                    .filter(DocumentProcessor::isEligibleFile)
                    .count();
        } catch (IOException ioException) {
            throw new UncheckedIOException(
                    String.format(Locale.ROOT, FILE_ENUMERATION_FAILURE_TEMPLATE, docsPath), ioException);
        }
    }

    private static boolean isEligibleFile(final Path filePath) {
        final Path fileNamePath = filePath.getFileName();
        if (fileNamePath == null) {
            return false;
        }
        final String fileName = fileNamePath.toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(EXT_HTML) || fileName.endsWith(EXT_HTM) || fileName.endsWith(EXT_PDF);
    }

    private void logStartBanner(final EnvironmentConfig config) {
        if (!LOGGER.isInfoEnabled()) {
            return;
        }
        LOGGER.info(LOG_BANNER_LINE);
        LOGGER.info(LOG_START_TITLE);
        LOGGER.info(LOG_BANNER_LINE);
        LOGGER.info(LOG_DEDUP_ENABLED);
        LOGGER.info(LOG_BLANK_LINE);
    }

    private List<DocumentationSet> selectDocumentationSets(final EnvironmentConfig config) {
        final String filter = config.docSetFilter();
        if (filter == null || filter.isBlank()) {
            if (config.includeQuickSets()) {
                return DocumentationSetCatalog.allSets();
            }
            return DocumentationSetCatalog.baseSets();
        }
        final Set<String> tokens = parseDocSetFilter(filter);
        if (tokens.isEmpty() || tokens.contains("all")) {
            return DocumentationSetCatalog.allSets();
        }
        final List<DocumentationSet> selectedSets = new ArrayList<>();
        for (DocumentationSet docSet : DocumentationSetCatalog.allSets()) {
            if (docSet.matchesAny(tokens)) {
                selectedSets.add(docSet);
            }
        }
        if (selectedSets.isEmpty()) {
            throw new DocumentProcessingException(String.format(
                    Locale.ROOT,
                    "DOCS_SETS matched no documentation sets. Available doc sets: %s",
                    formatDocSetSummary(DocumentationSetCatalog.allSets())));
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(LOG_DOCSET_FILTER);
            LOGGER.info(LOG_DOCSET_SELECTED, selectedSets.size());
        }
        return selectedSets;
    }

    private static Set<String> parseDocSetFilter(final String filter) {
        final Set<String> tokens = new LinkedHashSet<>();
        if (filter == null || filter.isBlank()) {
            return tokens;
        }
        for (String token : filter.split(",")) {
            final String normalized = normalizeToken(token);
            if (!normalized.isBlank()) {
                tokens.add(normalized);
            }
        }
        return tokens;
    }

    private static String formatDocSetSummary(final List<DocumentationSet> docSets) {
        return docSets.stream()
                .map(docSet -> docSet.docSetId() + " (" + docSet.displayName() + ")")
                .collect(Collectors.joining(", "));
    }

    private static String normalizeToken(final String token) {
        if (token == null) {
            return "";
        }
        return token.trim().toLowerCase(Locale.ROOT);
    }

    private void logProcessingStats(final int processed, final long elapsedMillis) {
        if (!LOGGER.isInfoEnabled()) {
            return;
        }
        final double elapsedSeconds = Math.max(elapsedMillis, 1) / MILLIS_PER_SECOND;
        final double rate = processed / elapsedSeconds;
        LOGGER.info(
                LOG_PROCESSED_STATS,
                processed,
                String.format(Locale.ROOT, FORMAT_SECONDS, elapsedSeconds),
                String.format(Locale.ROOT, FORMAT_RATE, rate),
                progressTracker.formatPercent());
    }

    private void logSummary(final EnvironmentConfig config, final IngestionTotals totals) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(LOG_BLANK_LINE);
            LOGGER.info(LOG_BANNER_LINE);
            LOGGER.info(LOG_COMPLETE_TITLE);
            LOGGER.info(LOG_BANNER_LINE);
            LOGGER.info(LOG_TOTAL_PROCESSED, totals.processed());
            LOGGER.info(LOG_TOTAL_DUPLICATES, totals.duplicates());
            LOGGER.info(LOG_TOTAL_SKIPPED, totals.skippedSets());
        }
        if (totals.failedSets() > 0 && LOGGER.isWarnEnabled()) {
            LOGGER.warn(LOG_TOTAL_FAILED, totals.failedSets());
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(LOG_BLANK_LINE);
            LOGGER.info(LOG_DOCS_INDEXED);
            LOGGER.info(LOG_HASH_DESCRIPTION);
            LOGGER.info(LOG_BLANK_LINE);
            LOGGER.info(LOG_NEXT_STEPS);
            LOGGER.info(LOG_NEXT_STEP_QDRANT);
            LOGGER.info(LOG_NEXT_STEP_RETRIEVAL);
            LOGGER.info(LOG_NEXT_STEP_CHAT);
            LOGGER.info(LOG_BANNER_LINE);
        }
    }

    /**
     * Environment configuration consolidated from system environment variables.
     */
    private record EnvironmentConfig(
            String docsDirectory,
            String qdrantCollection,
            String qdrantHost,
            String qdrantPort,
            String appPort,
            String docSetFilter,
            boolean includeQuickSets) {
        private static final String DOCS_DIR_DEFAULT = "data/docs";
        private static final String QDRANT_COLLECTION_DEFAULT = "(not set)";
        private static final String QDRANT_HOST_DEFAULT = "localhost";
        private static final String QDRANT_PORT_DEFAULT = "8086";
        private static final String APP_PORT_DEFAULT = "8085";
        private static final String ENV_DOCS_DIR = "DOCS_DIR";
        private static final String ENV_QDRANT_COLLECTION = "QDRANT_COLLECTION";
        private static final String ENV_QDRANT_HOST = "QDRANT_HOST";
        private static final String ENV_QDRANT_PORT = "QDRANT_PORT";
        private static final String ENV_APP_PORT = "PORT";
        private static final String ENV_DOCS_SETS = "DOCS_SETS";
        private static final String ENV_DOCS_INCLUDE_QUICK = "DOCS_INCLUDE_QUICK";

        static EnvironmentConfig fromEnvironment() {
            return new EnvironmentConfig(
                    envOrDefault(ENV_DOCS_DIR, DOCS_DIR_DEFAULT),
                    envOrDefault(ENV_QDRANT_COLLECTION, QDRANT_COLLECTION_DEFAULT),
                    envOrDefault(ENV_QDRANT_HOST, QDRANT_HOST_DEFAULT),
                    envOrDefault(ENV_QDRANT_PORT, QDRANT_PORT_DEFAULT),
                    envOrDefault(ENV_APP_PORT, APP_PORT_DEFAULT),
                    envOrDefault(ENV_DOCS_SETS, ""),
                    envBooleanOrDefault(ENV_DOCS_INCLUDE_QUICK, false));
        }

        private static String envOrDefault(final String key, final String fallbackText) {
            final String envSetting = System.getenv(key);
            return envSetting == null || envSetting.isBlank() ? fallbackText : envSetting;
        }

        private static boolean envBooleanOrDefault(final String key, final boolean fallbackValue) {
            final String envSetting = System.getenv(key);
            if (envSetting == null || envSetting.isBlank()) {
                return fallbackValue;
            }
            final String normalized = envSetting.trim().toLowerCase(Locale.ROOT);
            return "true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized);
        }
    }

    /**
     * Accumulated totals across all documentation sets, tracking successes and failures separately.
     */
    private record IngestionTotals(long processed, long duplicates, int skippedSets, int failedSets) {
        static final IngestionTotals ZERO = new IngestionTotals(0, 0, 0, 0);

        IngestionTotals addSuccess(final long newProcessed, final long newDuplicates) {
            return new IngestionTotals(processed + newProcessed, duplicates + newDuplicates, skippedSets, failedSets);
        }

        IngestionTotals addSkipped() {
            return new IngestionTotals(processed, duplicates, skippedSets + 1, failedSets);
        }

        IngestionTotals addFailed() {
            return new IngestionTotals(processed, duplicates, skippedSets, failedSets + 1);
        }

        static IngestionTotals combine(final IngestionTotals left, final IngestionTotals right) {
            return new IngestionTotals(
                    left.processed + right.processed,
                    left.duplicates + right.duplicates,
                    left.skippedSets + right.skippedSets,
                    left.failedSets + right.failedSets);
        }
    }

    /**
     * Outcome of processing a single documentation set - distinguishes success, skip, and failure.
     */
    private sealed interface ProcessingOutcome {
        record Success(long processed, long duplicates) implements ProcessingOutcome {}

        record Skipped(String setName, String reason) implements ProcessingOutcome {}

        record Failed(String setName) implements ProcessingOutcome {}
    }

    /**
     * Thrown when document processing completes but one or more documentation sets failed.
     */
    public static class DocumentProcessingException extends RuntimeException {
        /**
         * Creates a processing exception with a descriptive message.
         *
         * @param message explanation of the processing failure
         */
        public DocumentProcessingException(final String message) {
            super(message);
        }
    }
}
