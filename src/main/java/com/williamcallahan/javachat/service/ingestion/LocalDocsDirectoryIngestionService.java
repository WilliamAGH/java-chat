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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Ingests local HTML/PDF documentation files under {@code data/docs/**} into Qdrant (or cache).
 */
@Service
public class LocalDocsDirectoryIngestionService {
    private static final String DEFAULT_DOCS_ROOT = "data/docs";

    private final LocalDocsFileIngestionProcessor fileProcessor;

    /**
     * Creates a directory ingestor backed by a per-file ingestion processor.
     *
     * @param fileProcessor file processor for extraction, chunking, and persistence
     */
    public LocalDocsDirectoryIngestionService(LocalDocsFileIngestionProcessor fileProcessor) {
        this.fileProcessor = Objects.requireNonNull(fileProcessor, "fileProcessor");
    }

    /**
     * Ingests HTML/PDF files from a local directory mirror (for example, {@code data/docs/**}).
     *
     * @param rootDir root directory to scan (must be under {@code data/docs})
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
        Path baseDir = Path.of(DEFAULT_DOCS_ROOT).toAbsolutePath().normalize();
        Path rootRoot = root.getRoot();
        Path absoluteBaseDir =
                rootRoot == null ? baseDir : rootRoot.resolve(DEFAULT_DOCS_ROOT).normalize();
        if (!root.startsWith(baseDir) && !root.startsWith(absoluteBaseDir)) {
            throw new IllegalArgumentException("Local docs directory must be under " + absoluteBaseDir);
        }
        if (!Files.exists(root)) {
            throw new IllegalArgumentException("Local docs directory does not exist: " + rootDir);
        }

        AtomicInteger processedCount = new AtomicInteger(0);
        List<IngestionLocalFailure> failures = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(root)) {
            Iterator<Path> pathIterator = paths.filter(pathCandidate -> !Files.isDirectory(pathCandidate))
                    .filter(this::isIngestableFile)
                    .iterator();
            while (pathIterator.hasNext() && processedCount.get() < maxFiles) {
                Path file = pathIterator.next();
                LocalDocsFileOutcome outcome = fileProcessor.process(root, file);
                if (outcome.processed()) {
                    processedCount.incrementAndGet();
                }
                outcome.failure().ifPresent(failures::add);
            }
        }

        return IngestionLocalOutcome.success(processedCount.get(), rootDir, failures);
    }

    private boolean isIngestableFile(Path path) {
        Path fileNamePath = path.getFileName();
        if (fileNamePath == null) {
            return false;
        }
        if (shouldSkipVersionedSpringReference(path)) {
            return false;
        }
        String name = fileNamePath.toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".pdf");
    }

    private boolean shouldSkipVersionedSpringReference(Path path) {
        if (path == null) {
            return false;
        }
        String normalized = path.toAbsolutePath().normalize().toString().replace('\\', '/');
        return shouldSkipSpringFrameworkReference(normalized) || shouldSkipSpringAiReference(normalized);
    }

    private boolean shouldSkipSpringFrameworkReference(String normalizedPath) {
        return containsDisallowedVersionedSpringReference(normalizedPath, "spring-framework", version -> true);
    }

    private boolean shouldSkipSpringAiReference(String normalizedPath) {
        return containsDisallowedVersionedSpringReference(
                normalizedPath, "spring-ai", version -> !version.startsWith("2."));
    }

    private boolean containsDisallowedVersionedSpringReference(
            String normalizedPath, String springMarker, java.util.function.Predicate<String> versionDisallowed) {
        return extractReferenceSubdirectory(normalizedPath, springMarker)
                .filter(this::isVersionedOrSnapshot)
                .filter(subdir -> isSnapshot(subdir) || versionDisallowed.test(subdir))
                .isPresent();
    }

    private Optional<String> extractReferenceSubdirectory(String normalizedPath, String springMarker) {
        if (normalizedPath == null || normalizedPath.isBlank() || springMarker == null || springMarker.isBlank()) {
            return Optional.empty();
        }
        String marker = "/" + springMarker;
        int springIndex = normalizedPath.indexOf(marker);
        if (springIndex < 0) {
            return Optional.empty();
        }
        int referenceIndex = normalizedPath.indexOf("/reference/", springIndex);
        if (referenceIndex < 0) {
            return Optional.empty();
        }
        int versionStart = referenceIndex + "/reference/".length();
        if (versionStart >= normalizedPath.length()) {
            return Optional.empty();
        }
        int versionEnd = normalizedPath.indexOf('/', versionStart);
        if (versionEnd < 0) {
            versionEnd = normalizedPath.length();
        }
        String subdirectory = normalizedPath.substring(versionStart, versionEnd);
        return subdirectory.isBlank() ? Optional.empty() : Optional.of(subdirectory);
    }

    private boolean isVersionedOrSnapshot(String subdirectory) {
        return isSnapshot(subdirectory) || startsWithDigit(subdirectory);
    }

    private boolean isSnapshot(String subdirectory) {
        return subdirectory.contains("SNAPSHOT");
    }

    private boolean startsWithDigit(String subdirectory) {
        return !subdirectory.isEmpty() && Character.isDigit(subdirectory.charAt(0));
    }
}
