package com.williamcallahan.javachat.cli;

import com.williamcallahan.javachat.service.DocsIngestionService;
import com.williamcallahan.javachat.service.ProgressTracker;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final List<DocumentationSet> DOCUMENTATION_SETS = List.of(
        new DocumentationSet("PDF Books", "books"),
        new DocumentationSet("Java 24 Complete API", "java/java24-complete"),
        new DocumentationSet("Java 25 Complete API", "java/java25-complete"),
        new DocumentationSet("Java 25 EA Complete API", "java/java25-ea-complete"),
        new DocumentationSet("Spring Boot Complete", "spring-boot-complete"),
        new DocumentationSet("Spring Framework Complete", "spring-framework-complete"),
        new DocumentationSet("Spring AI Complete", "spring-ai-complete"),
        new DocumentationSet("Java 24 Quick", "java24"),
        new DocumentationSet("Java 25 Quick", "java25"),
        new DocumentationSet("Spring Boot Quick", "spring-boot"),
        new DocumentationSet("Spring Framework Quick", "spring-framework"),
        new DocumentationSet("Spring AI Quick", "spring-ai")
    );

    @Autowired
    private DocsIngestionService ingestionService;

    @Autowired
    private ProgressTracker progressTracker;

    /**
     * Creates the CLI document processor.
     */
    public DocumentProcessor() {
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

        final IngestionTotals totals = DOCUMENTATION_SETS.stream()
            .map(docSet -> processDocumentationSet(config.docsDirectory(), docSet))
            .reduce(IngestionTotals.ZERO, this::accumulateOutcome, IngestionTotals::combine);

        logSummary(config, totals);

        if (totals.failedSets() > 0) {
            throw new DocumentProcessingException(
                "Document processing completed with " + totals.failedSets() + " failed documentation set(s)"
            );
        }
    }

    private IngestionTotals accumulateOutcome(final IngestionTotals totals, final ProcessingOutcome outcome) {
        return switch (outcome) {
            case ProcessingOutcome.Success success -> totals.addSuccess(success.processed(), success.duplicates());
            case ProcessingOutcome.Skipped ignored -> totals.addSkipped();
            case ProcessingOutcome.Failed ignored -> totals.addFailed();
        };
    }

    private ProcessingOutcome processDocumentationSet(final String docsDirectory, final DocumentationSet docSet) {
        final Path docsPath = Paths.get(docsDirectory).resolve(docSet.relativePath());

        if (!Files.exists(docsPath) || !Files.isDirectory(docsPath)) {
            LOGGER.debug("Skipping {} (directory not found: {})", docSet.displayName(), docsPath);
            return new ProcessingOutcome.Skipped(docSet.displayName(), "directory not found");
        }

        final long fileCount = countEligibleFiles(docsPath);
        if (fileCount <= 0) {
            LOGGER.debug("Skipping {} (no eligible files in {})", docSet.displayName(), docsPath);
            return new ProcessingOutcome.Skipped(docSet.displayName(), "no eligible files");
        }

        LOGGER.info("-----------------------------------------------");
        LOGGER.info("Processing: {}", docSet.displayName());
        LOGGER.info("Path: {}", docsPath);
        LOGGER.info("Files to process: {}", fileCount);

        final long startMillis = System.currentTimeMillis();
        try {
            final int processed = ingestionService.ingestLocalDirectory(docsPath.toString(), Integer.MAX_VALUE);
            final long elapsedMillis = System.currentTimeMillis() - startMillis;
            logProcessingStats(processed, elapsedMillis);

            final long duplicates = fileCount - processed;
            if (duplicates > 0) {
                LOGGER.info("  Skipped {} duplicate files (already in Qdrant)", duplicates);
            }
            return new ProcessingOutcome.Success(processed, duplicates);

        } catch (IOException ioException) {
            LOGGER.error("Failed to process {}: {}", docSet.displayName(), ioException.getMessage());
            LOGGER.debug("Stack trace:", ioException);
            return new ProcessingOutcome.Failed(docSet.displayName(), ioException);
        }
    }

    private long countEligibleFiles(final Path docsPath) {
        try (Stream<Path> paths = Files.walk(docsPath)) {
            return paths
                .filter(path -> !Files.isDirectory(path))
                .filter(DocumentProcessor::isEligibleFile)
                .count();
        } catch (IOException ioException) {
            throw new UncheckedIOException(
                "Failed to enumerate files in " + docsPath + " - check directory permissions",
                ioException
            );
        }
    }

    private static boolean isEligibleFile(final Path filePath) {
        final String fileName = filePath.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(".html") || fileName.endsWith(".htm") || fileName.endsWith(".pdf");
    }

    private void logStartBanner(final EnvironmentConfig config) {
        LOGGER.info("===============================================");
        LOGGER.info("Starting Document Processing with Deduplication");
        LOGGER.info("===============================================");
        LOGGER.info("Documentation directory: {}", config.docsDirectory());
        LOGGER.info("Qdrant Collection: {}", config.qdrantCollection());
        LOGGER.info("Deduplication: ENABLED (using content hashes)");
        LOGGER.info("");
    }

    private void logProcessingStats(final int processed, final long elapsedMillis) {
        final double elapsedSeconds = Math.max(elapsedMillis, 1) / 1000.0;
        final double rate = processed / elapsedSeconds;
        LOGGER.info("Processed {} files in {:.2f}s ({:.1f} files/sec) ({})",
            processed, elapsedSeconds, rate, progressTracker.formatPercent());
    }

    private void logSummary(final EnvironmentConfig config, final IngestionTotals totals) {
        LOGGER.info("");
        LOGGER.info("===============================================");
        LOGGER.info("DOCUMENT PROCESSING COMPLETE");
        LOGGER.info("===============================================");
        LOGGER.info("Total new documents processed: {}", totals.processed());
        LOGGER.info("Total duplicates skipped: {}", totals.duplicates());
        LOGGER.info("Documentation sets skipped: {}", totals.skippedSets());
        if (totals.failedSets() > 0) {
            LOGGER.warn("Documentation sets FAILED: {}", totals.failedSets());
        }
        LOGGER.info("");
        LOGGER.info("Documents have been indexed in Qdrant with automatic deduplication.");
        LOGGER.info("Each document chunk is identified by a SHA-256 hash of its content.");
        LOGGER.info("");
        LOGGER.info("Next steps:");
        LOGGER.info("1. Verify in Qdrant Dashboard: http://{}:{}/dashboard",
            config.qdrantHost(), config.qdrantPort());
        LOGGER.info("2. Test retrieval: curl http://localhost:{}/api/search?query='Java streams'",
            config.appPort());
        LOGGER.info("3. Start chat: mvn spring-boot:run");
        LOGGER.info("===============================================");
    }

    /**
     * Environment configuration consolidated from system environment variables.
     */
    private record EnvironmentConfig(
        String docsDirectory,
        String qdrantCollection,
        String qdrantHost,
        String qdrantPort,
        String appPort
    ) {
        private static final String DOCS_DIR_DEFAULT = "data/docs";
        private static final String QDRANT_HOST_DEFAULT = "localhost";
        private static final String QDRANT_PORT_DEFAULT = "8086";
        private static final String APP_PORT_DEFAULT = "8085";

        static EnvironmentConfig fromEnvironment() {
            return new EnvironmentConfig(
                envOrDefault("DOCS_DIR", DOCS_DIR_DEFAULT),
                System.getenv("QDRANT_COLLECTION"),
                envOrDefault("QDRANT_HOST", QDRANT_HOST_DEFAULT),
                envOrDefault("QDRANT_PORT", QDRANT_PORT_DEFAULT),
                envOrDefault("PORT", APP_PORT_DEFAULT)
            );
        }

        private static String envOrDefault(final String key, final String defaultValue) {
            final String value = System.getenv(key);
            return value == null || value.isBlank() ? defaultValue : value;
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
                left.failedSets + right.failedSets
            );
        }
    }

    /**
     * Outcome of processing a single documentation set - distinguishes success, skip, and failure.
     */
    private sealed interface ProcessingOutcome {
        record Success(long processed, long duplicates) implements ProcessingOutcome {}
        record Skipped(String setName, String reason) implements ProcessingOutcome {}
        record Failed(String setName, IOException cause) implements ProcessingOutcome {}
    }

    /**
     * A documentation set to process, defined by display name and relative path.
     */
    private record DocumentationSet(String displayName, String relativePath) {
    }

    /**
     * Thrown when document processing completes but one or more documentation sets failed.
     */
    public static class DocumentProcessingException extends RuntimeException {
        public DocumentProcessingException(final String message) {
            super(message);
        }
    }
}
