package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.domain.ingestion.IngestionLocalFailure;
import com.williamcallahan.javachat.domain.ingestion.IngestionLocalOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Ingests local HTML/PDF documentation files from a selected readable mirror into Qdrant.
 */
@Service
public final class LocalDocsDirectoryIngestionService {
    private final LocalDocsFileIngestionProcessor fileProcessor;
    private final Path configuredDocumentationRoot;

    /**
     * Creates a directory ingestor backed by a per-file ingestion processor.
     *
     * @param fileProcessor file processor for extraction, chunking, and persistence
     * @param documentationRoot configured boundary for every caller-selected source directory
     */
    public LocalDocsDirectoryIngestionService(
            LocalDocsFileIngestionProcessor fileProcessor, @Value("${DOCS_DIR:data/docs}") String documentationRoot) {
        this.fileProcessor = Objects.requireNonNull(fileProcessor, "fileProcessor");
        if (documentationRoot == null || documentationRoot.isBlank()) {
            throw new IllegalArgumentException("Configured documentation root is required");
        }
        this.configuredDocumentationRoot =
                Path.of(documentationRoot).toAbsolutePath().normalize();
    }

    /**
     * Ingests HTML/PDF files from a local directory mirror (for example, {@code data/docs/**}).
     *
     * @param rootDir root directory to scan
     * @param maxFiles maximum number of files to process
     * @return ingestion outcome including per-file failures
     * @throws IOException if directory walking fails
     */
    public IngestionLocalOutcome ingestLocalDirectory(String rootDir, int maxFiles) throws IOException {
        if (rootDir == null || rootDir.isBlank()) {
            throw new IllegalArgumentException("Local docs directory is required");
        }
        if (maxFiles <= 0) {
            throw new IllegalArgumentException("maxFiles must be positive");
        }

        Path root = Path.of(rootDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(root) || !Files.isReadable(root)) {
            throw new IllegalArgumentException("Local docs directory does not exist: " + rootDir);
        }
        Path realDocumentationRoot = configuredDocumentationRoot.toRealPath();
        Path realSelectedRoot = root.toRealPath();
        if (!realSelectedRoot.startsWith(realDocumentationRoot)) {
            throw new IllegalArgumentException("Local docs directory must remain beneath the configured DOCS_DIR");
        }

        AtomicInteger processedCount = new AtomicInteger(0);
        List<IngestionLocalFailure> failures = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(realSelectedRoot)) {
            Iterator<Path> pathIterator = paths.filter(pathCandidate -> !Files.isDirectory(pathCandidate))
                    .filter(this::isIngestableFile)
                    .iterator();
            while (pathIterator.hasNext() && processedCount.get() < maxFiles) {
                Path file = pathIterator.next();
                LocalDocsFileOutcome outcome = fileProcessor.process(realSelectedRoot, file);
                if (outcome.processed()) {
                    processedCount.incrementAndGet();
                }
                outcome.failure().ifPresent(failures::add);
                if (outcome.failure().isPresent()) {
                    break;
                }
            }
        }

        return IngestionLocalOutcome.success(processedCount.get(), rootDir, failures);
    }

    private boolean isIngestableFile(Path path) {
        Path fileNamePath = path.getFileName();
        if (fileNamePath == null) {
            return false;
        }
        String name = fileNamePath.toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".pdf");
    }
}
