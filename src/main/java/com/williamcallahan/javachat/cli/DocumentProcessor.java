package com.williamcallahan.javachat.cli;

import com.williamcallahan.javachat.service.DocsIngestionService;
import com.williamcallahan.javachat.service.ProgressTracker;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
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

@SpringBootApplication
@Profile(DocumentProcessor.PROFILE_CLI)
@ComponentScan(basePackages = DocumentProcessor.BASE_PACKAGE)
public class DocumentProcessor {
    static final String PROFILE_CLI = "cli";
    static final String BASE_PACKAGE = "com.williamcallahan.javachat";

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentProcessor.class);
    private static final String DOCS_DIR_ENV = "DOCS_DIR";
    private static final String DEFAULT_DOCS_DIRECTORY = "data/docs";
    private static final String QDRANT_COLLECTION_ENV = "QDRANT_COLLECTION";
    private static final String QDRANT_HOST_ENV = "QDRANT_HOST";
    private static final String QDRANT_PORT_ENV = "QDRANT_PORT";
    private static final String DEFAULT_QDRANT_HOST = "localhost";
    private static final String DEFAULT_QDRANT_PORT = "8086";
    private static final String APP_PORT_ENV = "PORT";
    private static final String DEFAULT_APP_PORT = "8085";
    private static final String PRIMARY_SEPARATOR = "===============================================";
    private static final String SECTION_SEPARATOR = "-----------------------------------------------";
    private static final String LOG_START_TITLE = "Starting Document Processing with Deduplication";
    private static final String LOG_DOCS_DIR = "Documentation directory: {}";
    private static final String LOG_QDRANT_COLLECTION = "Qdrant Collection: {}";
    private static final String LOG_DEDUP_ENABLED = "Deduplication: ENABLED (using content hashes)";
    private static final String LOG_PROCESSING_LABEL = "Processing: {}";
    private static final String LOG_PATH_LABEL = "Path: {}";
    private static final String LOG_FILES_TO_PROCESS = "Files to process: {}";
    private static final String LOG_DUPLICATES = "  Skipped {} duplicate files (already in Qdrant)";
    private static final String LOG_ERROR_PROCESSING = "✗ Error processing {}: {}";
    private static final String LOG_STACK_TRACE = "Stack trace:";
    private static final String LOG_SKIP_NO_FILES = "Skipping {} (no HTML files found)";
    private static final String LOG_SKIP_DIR_NOT_FOUND = "Skipping {} (directory not found)";
    private static final String LOG_SUMMARY_TITLE = "DOCUMENT PROCESSING COMPLETE";
    private static final String LOG_TOTAL_PROCESSED = "Total new documents processed: {}";
    private static final String LOG_TOTAL_DUPLICATES = "Total duplicates skipped: {}";
    private static final String LOG_INDEXED_NOTICE = "Documents have been indexed in Qdrant with automatic deduplication.";
    private static final String LOG_HASH_NOTICE = "Each document chunk is identified by a SHA-256 hash of its content.";
    private static final String LOG_NEXT_STEPS = "Next steps:";
    private static final String LOG_START_CHAT = "3. Start chat: mvn spring-boot:run";
    private static final String LOG_EMPTY_LINE = "";
    private static final String LOG_PROCESSING_STATS_TEMPLATE =
        "✓ Processed %d files in %.2fs (%.1f files/sec) (%s)";
    private static final String LOG_DASHBOARD_TEMPLATE =
        "1. Verify in Qdrant Dashboard: http://%s:%s/dashboard";
    private static final String LOG_TEST_RETRIEVAL_TEMPLATE =
        "2. Test retrieval: curl http://localhost:%s/api/search?query='Java streams'";
    private static final String FILE_EXTENSION_HTML = ".html";
    private static final String FILE_EXTENSION_HTM = ".htm";
    private static final String FILE_EXTENSION_PDF = ".pdf";
    private static final double MILLIS_PER_SECOND = 1000.0;
    private static final int MAX_FILES_LIMIT = Integer.MAX_VALUE;

    private static final String DOCSET_BOOKS_LABEL = "PDF Books";
    private static final String DOCSET_BOOKS_PATH = "books";
    private static final String DOCSET_JAVA24_COMPLETE_LABEL = "Java 24 Complete API";
    private static final String DOCSET_JAVA24_COMPLETE_PATH = "java/java24-complete";
    private static final String DOCSET_JAVA25_COMPLETE_LABEL = "Java 25 Complete API";
    private static final String DOCSET_JAVA25_COMPLETE_PATH = "java/java25-complete";
    private static final String DOCSET_JAVA25_EA_COMPLETE_LABEL = "Java 25 EA Complete API";
    private static final String DOCSET_JAVA25_EA_COMPLETE_PATH = "java/java25-ea-complete";
    private static final String DOCSET_SPRING_BOOT_COMPLETE_LABEL = "Spring Boot Complete";
    private static final String DOCSET_SPRING_BOOT_COMPLETE_PATH = "spring-boot-complete";
    private static final String DOCSET_SPRING_FRAMEWORK_COMPLETE_LABEL = "Spring Framework Complete";
    private static final String DOCSET_SPRING_FRAMEWORK_COMPLETE_PATH = "spring-framework-complete";
    private static final String DOCSET_SPRING_AI_COMPLETE_LABEL = "Spring AI Complete";
    private static final String DOCSET_SPRING_AI_COMPLETE_PATH = "spring-ai-complete";
    private static final String DOCSET_JAVA24_QUICK_LABEL = "Java 24 Quick";
    private static final String DOCSET_JAVA24_QUICK_PATH = "java24";
    private static final String DOCSET_JAVA25_QUICK_LABEL = "Java 25 Quick";
    private static final String DOCSET_JAVA25_QUICK_PATH = "java25";
    private static final String DOCSET_SPRING_BOOT_QUICK_LABEL = "Spring Boot Quick";
    private static final String DOCSET_SPRING_BOOT_QUICK_PATH = "spring-boot";
    private static final String DOCSET_SPRING_FRAMEWORK_QUICK_LABEL = "Spring Framework Quick";
    private static final String DOCSET_SPRING_FRAMEWORK_QUICK_PATH = "spring-framework";
    private static final String DOCSET_SPRING_AI_QUICK_LABEL = "Spring AI Quick";
    private static final String DOCSET_SPRING_AI_QUICK_PATH = "spring-ai";

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
        final String docsDirectory = resolveDocsDirectory();
        logStartBanner(docsDirectory);

        final List<DocumentationSet> docSets = buildDocumentationSets();
        final AtomicLong totalProcessed = new AtomicLong(0);
        final AtomicLong totalDuplicates = new AtomicLong(0);

        for (DocumentationSet docSet : docSets) {
            processDocumentationSet(docsDirectory, docSet, totalProcessed, totalDuplicates);
        }

        logSummary(totalProcessed, totalDuplicates);
    }

    private String resolveDocsDirectory() {
        return resolveEnvOrDefault(DOCS_DIR_ENV, DEFAULT_DOCS_DIRECTORY);
    }

    private List<DocumentationSet> buildDocumentationSets() {
        return List.of(
            new DocumentationSet(DOCSET_BOOKS_LABEL, DOCSET_BOOKS_PATH),
            new DocumentationSet(DOCSET_JAVA24_COMPLETE_LABEL, DOCSET_JAVA24_COMPLETE_PATH),
            new DocumentationSet(DOCSET_JAVA25_COMPLETE_LABEL, DOCSET_JAVA25_COMPLETE_PATH),
            new DocumentationSet(DOCSET_JAVA25_EA_COMPLETE_LABEL, DOCSET_JAVA25_EA_COMPLETE_PATH),
            new DocumentationSet(DOCSET_SPRING_BOOT_COMPLETE_LABEL, DOCSET_SPRING_BOOT_COMPLETE_PATH),
            new DocumentationSet(DOCSET_SPRING_FRAMEWORK_COMPLETE_LABEL, DOCSET_SPRING_FRAMEWORK_COMPLETE_PATH),
            new DocumentationSet(DOCSET_SPRING_AI_COMPLETE_LABEL, DOCSET_SPRING_AI_COMPLETE_PATH),
            new DocumentationSet(DOCSET_JAVA24_QUICK_LABEL, DOCSET_JAVA24_QUICK_PATH),
            new DocumentationSet(DOCSET_JAVA25_QUICK_LABEL, DOCSET_JAVA25_QUICK_PATH),
            new DocumentationSet(DOCSET_SPRING_BOOT_QUICK_LABEL, DOCSET_SPRING_BOOT_QUICK_PATH),
            new DocumentationSet(DOCSET_SPRING_FRAMEWORK_QUICK_LABEL, DOCSET_SPRING_FRAMEWORK_QUICK_PATH),
            new DocumentationSet(DOCSET_SPRING_AI_QUICK_LABEL, DOCSET_SPRING_AI_QUICK_PATH)
        );
    }

    private void processDocumentationSet(
        final String docsDirectory,
        final DocumentationSet docSet,
        final AtomicLong totalProcessed,
        final AtomicLong totalDuplicates
    ) {
        final Path docsPath = Paths.get(docsDirectory).resolve(docSet.relativePath());
        if (!Files.exists(docsPath) || !Files.isDirectory(docsPath)) {
            logSkipDirectory(docSet.displayName());
            return;
        }

        final long fileCount;
        try {
            fileCount = countEligibleFiles(docsPath);
        } catch (IOException exception) {
            logProcessingError(docSet.displayName(), exception);
            return;
        }

        if (fileCount <= 0) {
            logSkipNoFiles(docSet.displayName());
            return;
        }

        logProcessingHeader(docSet.displayName(), docsPath, fileCount);

        final long startMillis = System.currentTimeMillis();
        try {
            final int processed = ingestionService.ingestLocalDirectory(docsPath.toString(), MAX_FILES_LIMIT);
            final long elapsedMillis = System.currentTimeMillis() - startMillis;
            logProcessingStats(processed, elapsedMillis);
            updateTotals(fileCount, processed, totalProcessed, totalDuplicates);
        } catch (IOException exception) {
            logProcessingError(docSet.displayName(), exception);
        }
    }

    private long countEligibleFiles(final Path docsPath) throws IOException {
        try (Stream<Path> paths = Files.walk(docsPath)) {
            return paths
                .filter(path -> !Files.isDirectory(path))
                .filter(this::isEligibleFile)
                .count();
        }
    }

    private boolean isEligibleFile(final Path filePath) {
        final String fileName = filePath.getFileName().toString().toLowerCase(Locale.ROOT);
        return fileName.endsWith(FILE_EXTENSION_HTML)
            || fileName.endsWith(FILE_EXTENSION_HTM)
            || fileName.endsWith(FILE_EXTENSION_PDF);
    }

    private void updateTotals(
        final long fileCount,
        final int processed,
        final AtomicLong totalProcessed,
        final AtomicLong totalDuplicates
    ) {
        totalProcessed.addAndGet(processed);
        final long duplicates = fileCount - processed;
        if (duplicates > 0) {
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info(LOG_DUPLICATES, duplicates);
            }
            totalDuplicates.addAndGet(duplicates);
        }
    }

    private void logStartBanner(final String docsDirectory) {
        if (!LOGGER.isInfoEnabled()) {
            return;
        }
        LOGGER.info(PRIMARY_SEPARATOR);
        LOGGER.info(LOG_START_TITLE);
        LOGGER.info(PRIMARY_SEPARATOR);
        LOGGER.info(LOG_DOCS_DIR, docsDirectory);
        LOGGER.info(LOG_QDRANT_COLLECTION, System.getenv(QDRANT_COLLECTION_ENV));
        LOGGER.info(LOG_DEDUP_ENABLED);
        LOGGER.info(LOG_EMPTY_LINE);
    }

    private void logProcessingHeader(final String displayName, final Path docsPath, final long fileCount) {
        if (!LOGGER.isInfoEnabled()) {
            return;
        }
        LOGGER.info(SECTION_SEPARATOR);
        LOGGER.info(LOG_PROCESSING_LABEL, displayName);
        LOGGER.info(LOG_PATH_LABEL, docsPath);
        LOGGER.info(LOG_FILES_TO_PROCESS, fileCount);
    }

    private void logProcessingStats(final int processed, final long elapsedMillis) {
        if (!LOGGER.isInfoEnabled()) {
            return;
        }
        final double elapsedSeconds = elapsedMillis / MILLIS_PER_SECOND;
        final double rate = processed > 0 && elapsedMillis > 0
            ? processed / elapsedSeconds
            : 0;
        final String message = String.format(
            Locale.ROOT,
            LOG_PROCESSING_STATS_TEMPLATE,
            processed,
            elapsedSeconds,
            rate,
            progressTracker.formatPercent()
        );
        LOGGER.info(message);
    }

    private void logProcessingError(final String displayName, final IOException exception) {
        if (LOGGER.isErrorEnabled()) {
            LOGGER.error(LOG_ERROR_PROCESSING, displayName, exception.getMessage());
        }
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(LOG_STACK_TRACE, exception);
        }
    }

    private void logSkipNoFiles(final String displayName) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(LOG_SKIP_NO_FILES, displayName);
        }
    }

    private void logSkipDirectory(final String displayName) {
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug(LOG_SKIP_DIR_NOT_FOUND, displayName);
        }
    }

    private void logSummary(final AtomicLong totalProcessed, final AtomicLong totalDuplicates) {
        if (!LOGGER.isInfoEnabled()) {
            return;
        }
        LOGGER.info(LOG_EMPTY_LINE);
        LOGGER.info(PRIMARY_SEPARATOR);
        LOGGER.info(LOG_SUMMARY_TITLE);
        LOGGER.info(PRIMARY_SEPARATOR);
        LOGGER.info(LOG_TOTAL_PROCESSED, totalProcessed.get());
        LOGGER.info(LOG_TOTAL_DUPLICATES, totalDuplicates.get());
        LOGGER.info(LOG_EMPTY_LINE);
        LOGGER.info(LOG_INDEXED_NOTICE);
        LOGGER.info(LOG_HASH_NOTICE);
        LOGGER.info(LOG_EMPTY_LINE);
        LOGGER.info(LOG_NEXT_STEPS);
        final String qdrantHost = resolveEnvOrDefault(QDRANT_HOST_ENV, DEFAULT_QDRANT_HOST);
        final String qdrantPort = resolveEnvOrDefault(QDRANT_PORT_ENV, DEFAULT_QDRANT_PORT);
        final String dashboardLine = String.format(Locale.ROOT, LOG_DASHBOARD_TEMPLATE, qdrantHost, qdrantPort);
        LOGGER.info(dashboardLine);
        final String appPort = resolveEnvOrDefault(APP_PORT_ENV, DEFAULT_APP_PORT);
        final String retrievalLine = String.format(Locale.ROOT, LOG_TEST_RETRIEVAL_TEMPLATE, appPort);
        LOGGER.info(retrievalLine);
        LOGGER.info(LOG_START_CHAT);
        LOGGER.info(PRIMARY_SEPARATOR);
    }

    private String resolveEnvOrDefault(final String envKey, final String defaultValue) {
        final String envValue = System.getenv(envKey);
        if (envValue == null || envValue.isBlank()) {
            return defaultValue;
        }
        return envValue;
    }

    private record DocumentationSet(String displayName, String relativePath) {
    }
}
