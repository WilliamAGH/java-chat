package com.williamcallahan.javachat.service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Persists document snapshots, parsed chunks, and ingestion markers on the local filesystem.
 */
@Service
public class LocalStoreService {
    private static final Logger log = LoggerFactory.getLogger(LocalStoreService.class);
    private static final String SHA_256_ALGORITHM = "SHA-256";
    private static final int SHORT_SHA_BYTES = 6;
    private static final int HASH_PREFIX_LENGTH = 12;
    private static final int SAFE_NAME_MAX_LENGTH = 150;
    private static final int SAFE_NAME_PREFIX_LENGTH = 80;
    private static final int SAFE_NAME_SUFFIX_LENGTH = 40;
    private static final String FILE_MARKER_PREFIX = "file_";
    private static final String FILE_MARKER_EXTENSION = ".marker";
    private static final String FILE_MARKER_HASH_PREFIX = "hash=";

    private final String snapshotDirConfig;
    private final String parsedDirConfig;
    private final String indexDirConfig;
    private Path snapshotDir;
    private Path parsedDir;
    private Path indexDir;
    private final ProgressTracker progressTracker;

    /**
     * Creates the local store using configured directory roots for snapshots, parsed content, and ingest markers.
     */
    public LocalStoreService(
            @Value("${app.docs.snapshot-dir}") String snapshotDir,
            @Value("${app.docs.parsed-dir}") String parsedDir,
            @Value("${app.docs.index-dir}") String indexDir,
            ProgressTracker progressTracker) {
        this.snapshotDirConfig = snapshotDir;
        this.parsedDirConfig = parsedDir;
        this.indexDirConfig = indexDir;
        this.progressTracker = progressTracker;
    }

    /**
     * Ensures the backing directories exist before any ingestion work begins.
     */
    @PostConstruct
    void createStoreDirectories() {
        try {
            this.snapshotDir = Path.of(snapshotDirConfig);
            this.parsedDir = Path.of(parsedDirConfig);
            this.indexDir = Path.of(indexDirConfig);
            Files.createDirectories(this.snapshotDir);
            Files.createDirectories(this.parsedDir);
            Files.createDirectories(this.indexDir);
            log.info("Local store directories ready");
        } catch (InvalidPathException | IOException exception) {
            throw new IllegalStateException("Failed to create local store directories", exception);
        }
    }

    /**
     * Stores a raw HTML snapshot for a document URL.
     */
    public void saveHtml(String url, String html) throws IOException {
        Path snapshotFilePath = snapshotDir.resolve(safeName(url) + ".html");
        ensureParentDirectoryExists(snapshotFilePath);
        Files.writeString(snapshotFilePath, html, StandardCharsets.UTF_8);
    }

    /**
     * Stores a parsed chunk payload for later local search and attribution.
     */
    public void saveChunkText(String url, int index, String text, String hash) throws IOException {
        String shortHash = hash.length() >= HASH_PREFIX_LENGTH ? hash.substring(0, HASH_PREFIX_LENGTH) : hash;
        Path chunkFilePath = parsedDir.resolve(safeName(url) + "_" + index + "_" + shortHash + ".txt");
        ensureParentDirectoryExists(chunkFilePath);
        Files.writeString(chunkFilePath, text, StandardCharsets.UTF_8);
        // Update progress after chunk text is saved
        if (progressTracker != null) {
            progressTracker.markChunkParsed();
        }
    }

    /**
     * Returns true when an ingest marker exists for the given chunk hash.
     */
    public boolean isHashIngested(String hash) {
        Path marker = indexDir.resolve(hash);
        return Files.exists(marker);
    }

    /**
     * Writes an ingest marker for the given chunk hash when not already present.
     */
    public void markHashIngested(String hash) throws IOException {
        Path marker = indexDir.resolve(hash);
        if (!Files.exists(marker)) {
            Files.writeString(marker, "1", StandardCharsets.UTF_8);
            // Update progress after successful ingest
            if (progressTracker != null) {
                progressTracker.markChunkIndexed();
            }
        }
    }

    /**
     * Returns true when the given URL has been fully ingested and the local file fingerprint
     * (size + last modified time) matches the marker stored under {@code app.docs.index-dir}.
     *
     * This is a fast incremental check that avoids re-parsing unchanged files on re-runs.
     *
     * @param url authoritative URL used for chunk hashing and citations
     * @param fileSizeBytes current file size in bytes
     * @param lastModifiedMillis current last modified timestamp in millis since epoch
     * @return true when a matching marker exists, false otherwise
     */
    public boolean isFileIngestedAndUnchanged(String url, long fileSizeBytes, long lastModifiedMillis) {
        if (url == null || url.isBlank()) {
            return false;
        }
        return readFileIngestionRecord(url)
                .map(record -> record.fileSizeBytes() == fileSizeBytes && record.lastModifiedMillis() == lastModifiedMillis)
                .orElse(false);
    }

    /**
     * Records a file-level ingestion marker keyed by URL.
     *
     * @param url authoritative URL used for chunk hashing and citations
     * @param fileSizeBytes file size in bytes
     * @param lastModifiedMillis last modified timestamp in millis since epoch
     * @throws IOException if marker write fails
     */
    public void markFileIngested(String url, long fileSizeBytes, long lastModifiedMillis) throws IOException {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL is required for file ingestion marker");
        }
        markFileIngested(url, fileSizeBytes, lastModifiedMillis, List.of());
    }

    private Path fileMarkerPath(String url) {
        return indexDir.resolve(FILE_MARKER_PREFIX + safeName(url) + FILE_MARKER_EXTENSION);
    }

    /**
     * Records a file-level ingestion marker keyed by URL, including the chunk hashes created for the file.
     *
     * Persisting chunk hashes enables incremental re-runs to delete obsolete vectors when a file changes,
     * preventing stale embeddings from accumulating in the vector store.
     *
     * @param url authoritative URL used for chunk hashing and citations
     * @param fileSizeBytes file size in bytes
     * @param lastModifiedMillis last modified timestamp in millis since epoch
     * @param chunkHashes chunk hashes for the file content (may be empty)
     * @throws IOException if marker write fails
     */
    public void markFileIngested(String url, long fileSizeBytes, long lastModifiedMillis, List<String> chunkHashes)
            throws IOException {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL is required for file ingestion marker");
        }
        Path markerPath = fileMarkerPath(url);
        ensureParentDirectoryExists(markerPath);
        String payload = buildFileMarkerPayload(fileSizeBytes, lastModifiedMillis, chunkHashes);
        Files.writeString(markerPath, payload, StandardCharsets.UTF_8);
    }

    /**
     * Loads the file ingestion marker record for a URL.
     *
     * @param url authoritative URL used for chunk hashing and citations
     * @return ingestion record when a marker exists and is readable
     */
    public Optional<FileIngestionRecord> readFileIngestionRecord(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        Path markerPath = fileMarkerPath(url);
        if (!Files.exists(markerPath)) {
            return Optional.empty();
        }
        try {
            return Optional.of(readFileMarker(markerPath));
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    /**
     * Deletes the file-level ingestion marker for a URL when present.
     *
     * @param url authoritative URL used for chunk hashing and citations
     * @throws IOException if delete fails
     */
    public void deleteFileIngestionRecord(String url) throws IOException {
        if (url == null || url.isBlank()) {
            return;
        }
        Files.deleteIfExists(fileMarkerPath(url));
    }

    /**
     * Deletes per-chunk ingest markers for the provided hashes.
     *
     * @param hashes chunk hashes to unmark
     * @throws IOException if any delete fails
     */
    public void deleteChunkIngestionMarkers(List<String> hashes) throws IOException {
        if (hashes == null || hashes.isEmpty()) {
            return;
        }
        IOException firstFailure = null;
        for (String hash : hashes) {
            if (hash == null || hash.isBlank()) {
                continue;
            }
            try {
                Files.deleteIfExists(indexDir.resolve(hash));
            } catch (IOException exception) {
                if (firstFailure == null) {
                    firstFailure = exception;
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    /**
     * Deletes locally parsed chunk text files for the provided URL.
     * Chunk filenames are prefixed with {@code safeName(url) + "_"}.
     *
     * @param url authoritative URL used for chunk hashing and citations
     * @throws IOException if any delete fails
     */
    public void deleteParsedChunksForUrl(String url) throws IOException {
        if (url == null || url.isBlank()) {
            return;
        }
        String prefix = safeName(url) + "_";
        if (!Files.isDirectory(parsedDir)) {
            return;
        }
        IOException firstFailure = null;
        try (var stream = Files.newDirectoryStream(parsedDir, path -> {
            Path fileNamePath = path.getFileName();
            if (fileNamePath == null) {
                return false;
            }
            String fileName = fileNamePath.toString();
            return fileName.startsWith(prefix) && fileName.endsWith(".txt");
        })) {
            for (Path candidate : stream) {
                try {
                    Files.deleteIfExists(candidate);
                } catch (IOException exception) {
                    if (firstFailure == null) {
                        firstFailure = exception;
                    }
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private FileIngestionRecord readFileMarker(Path markerPath) throws IOException {
        String raw = Files.readString(markerPath, StandardCharsets.UTF_8);
        long size = -1;
        long mtime = -1;
        List<String> hashes = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.startsWith("size=")) {
                size = parseLongSafely(trimmed.substring("size=".length()));
            } else if (trimmed.startsWith("mtime=")) {
                mtime = parseLongSafely(trimmed.substring("mtime=".length()));
            } else if (trimmed.startsWith(FILE_MARKER_HASH_PREFIX)) {
                String hash = trimmed.substring(FILE_MARKER_HASH_PREFIX.length()).trim();
                if (!hash.isBlank()) {
                    hashes.add(hash);
                }
            }
        }
        return new FileIngestionRecord(size, mtime, List.copyOf(hashes));
    }

    private long parseLongSafely(String value) throws IOException {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException nfe) {
            throw new IOException("Invalid marker value: " + value, nfe);
        }
    }

    private String buildFileMarkerPayload(long fileSizeBytes, long lastModifiedMillis, List<String> chunkHashes) {
        StringBuilder payload = new StringBuilder();
        payload.append("size=").append(fileSizeBytes).append('\n');
        payload.append("mtime=").append(lastModifiedMillis).append('\n');
        if (chunkHashes != null) {
            for (String hash : chunkHashes) {
                if (hash == null || hash.isBlank()) {
                    continue;
                }
                payload.append(FILE_MARKER_HASH_PREFIX).append(hash).append('\n');
            }
        }
        return payload.toString();
    }

    /**
     * File-level ingestion marker contents.
     *
     * @param fileSizeBytes file size in bytes at ingestion time
     * @param lastModifiedMillis file last modified timestamp in millis at ingestion time
     * @param chunkHashes chunk hashes ingested for the file
     */
    public record FileIngestionRecord(long fileSizeBytes, long lastModifiedMillis, List<String> chunkHashes) {
        public FileIngestionRecord {
            chunkHashes = chunkHashes == null ? List.of() : List.copyOf(chunkHashes);
        }
    }

    private String safeName(String url) {
        String sanitized = url.replaceAll("[^a-zA-Z0-9._-]", "_");
        // Ensure filename stays within safe limits for most filesystems
        if (sanitized.length() <= SAFE_NAME_MAX_LENGTH) return sanitized;
        String prefix = sanitized.substring(0, SAFE_NAME_PREFIX_LENGTH);
        String suffix = sanitized.substring(sanitized.length() - SAFE_NAME_SUFFIX_LENGTH);
        String hash = shortSha256(url);
        return prefix + "_" + hash + "_" + suffix;
    }

    /**
     * Produces a filesystem-safe name for URLs used in snapshot and chunk file naming.
     */
    public String toSafeName(String url) {
        return safeName(url);
    }

    /**
     * Returns the root directory for parsed chunk files.
     */
    public Path getParsedDir() {
        return parsedDir;
    }

    /**
     * Returns the root directory for ingest marker files.
     */
    public Path getIndexDir() {
        return indexDir;
    }

    /**
     * Ensures the parent directory exists for a given file path, creating it if necessary.
     *
     * @param filePath the file path whose parent directory should exist
     * @throws IOException if the parent is null (root path) or directory creation fails
     */
    private void ensureParentDirectoryExists(Path filePath) throws IOException {
        if (filePath == null) {
            throw new IOException("File path is required");
        }
        Path parentDir = filePath.getParent();
        if (parentDir == null) {
            throw new IOException("File path has no parent directory: " + filePath);
        }
        Files.createDirectories(parentDir);
    }

    private String shortSha256(String input) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance(SHA_256_ALGORITHM);
            byte[] digest = messageDigest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, SHORT_SHA_BYTES);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 MessageDigest is not available", exception);
        }
    }
}
