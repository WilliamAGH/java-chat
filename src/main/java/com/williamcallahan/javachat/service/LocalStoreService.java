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
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Persists document snapshots, parsed chunks, and chunk-hash markers on the local filesystem.
 */
@Service
public class LocalStoreService {
    private static final String SHA_256_ALGORITHM = "SHA-256";
    private static final int SHORT_SHA_BYTES = 6;
    private static final int HASH_PREFIX_LENGTH = 12;
    private static final int SAFE_NAME_MAX_LENGTH = 150;
    private static final int SAFE_NAME_PREFIX_LENGTH = 80;
    private static final int SAFE_NAME_SUFFIX_LENGTH = 40;
    private static final String HASH_MARKER_INGESTED_FLAG = "1";
    private static final String HASH_MARKER_TITLE_PREFIX = "titleB64=";
    private static final String HASH_MARKER_PACKAGE_PREFIX = "packageB64=";

    private final String snapshotDirConfig;
    private final String parsedDirConfig;
    private final String indexDirConfig;
    private Path snapshotDir;
    private Path parsedDir;
    private Path indexDir;
    private final ProgressTracker progressTracker;

    /**
     * Creates the local store using configured roots for snapshots, parsed content, and ingest markers.
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
     * Stores parsed chunk text for later local search and attribution.
     */
    public void saveChunkText(String url, int index, String text, String hash) throws IOException {
        String shortHash = hash.length() >= HASH_PREFIX_LENGTH ? hash.substring(0, HASH_PREFIX_LENGTH) : hash;
        Path chunkFilePath = parsedDir.resolve(safeName(url) + "_" + index + "_" + shortHash + ".txt");
        ensureParentDirectoryExists(chunkFilePath);
        Files.writeString(chunkFilePath, text, StandardCharsets.UTF_8);
        if (progressTracker != null) {
            progressTracker.markChunkParsed();
        }
    }

    /**
     * Returns true when an ingest marker exists for the given chunk hash.
     */
    public boolean isHashIngested(String hash) {
        return Files.exists(hashMarkerPath(hash));
    }

    /**
     * Returns true when the stored metadata for an ingested chunk hash differs from the current metadata.
     *
     * <p>Older markers may not contain metadata fields. In that case this method returns true so callers
     * can perform a one-time metadata refresh and backfill marker metadata.</p>
     *
     * @param hash chunk hash marker key
     * @param title current document title
     * @param packageName current package name
     * @return true when metadata has changed since the chunk was marked ingested
     */
    public boolean hasHashMetadataChanged(String hash, String title, String packageName) {
        Path markerPath = hashMarkerPath(hash);
        if (!Files.exists(markerPath)) {
            return false;
        }
        try {
            HashMarkerMetadata storedMetadata = readHashMarkerMetadata(markerPath);
            String normalizedTitle = normalizeHashMetadataText(title);
            String normalizedPackageName = normalizeHashMetadataText(packageName);
            return !normalizedTitle.equals(storedMetadata.title())
                    || !normalizedPackageName.equals(storedMetadata.packageName());
        } catch (IOException markerReadFailure) {
            throw new IllegalStateException(
                    "Failed to read hash ingestion marker for hash: " + hash, markerReadFailure);
        }
    }

    /**
     * Writes or updates an ingest marker for the given chunk hash and associated metadata.
     *
     * <p>When a marker already exists and metadata changed, this method updates the marker payload so
     * future dedup checks can detect metadata drift accurately.</p>
     *
     * @param hash chunk hash marker key
     * @param title document title associated with the ingested chunk
     * @param packageName package name associated with the ingested chunk
     * @throws IOException if marker write fails
     */
    public void markHashIngested(String hash, String title, String packageName) throws IOException {
        Path markerPath = hashMarkerPath(hash);
        String normalizedTitle = normalizeHashMetadataText(title);
        String normalizedPackageName = normalizeHashMetadataText(packageName);
        String markerPayload = buildHashMarkerPayload(normalizedTitle, normalizedPackageName);

        if (!Files.exists(markerPath)) {
            Files.writeString(markerPath, markerPayload, StandardCharsets.UTF_8);
            if (progressTracker != null) {
                progressTracker.markChunkIndexed();
            }
            return;
        }

        HashMarkerMetadata storedMetadata = readHashMarkerMetadata(markerPath);
        if (!normalizedTitle.equals(storedMetadata.title())
                || !normalizedPackageName.equals(storedMetadata.packageName())) {
            Files.writeString(markerPath, markerPayload, StandardCharsets.UTF_8);
        }
    }

    private Path hashMarkerPath(String hash) {
        return indexDir.resolve(hash);
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
        List<Path> chunkMarkerPaths = new ArrayList<>();
        for (String hash : hashes) {
            if (hash != null && !hash.isBlank()) {
                chunkMarkerPaths.add(indexDir.resolve(hash));
            }
        }
        deleteIngestionPathsStrict(chunkMarkerPaths);
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
        try (var parsedChunkPaths = Files.newDirectoryStream(parsedDir, parsedChunkPath -> {
            Path fileNamePath = parsedChunkPath.getFileName();
            if (fileNamePath == null) {
                return false;
            }
            String fileName = fileNamePath.toString();
            return fileName.startsWith(prefix) && fileName.endsWith(".txt");
        })) {
            deleteIngestionPathsStrict(parsedChunkPaths);
        }
    }

    private void deleteIngestionPathsStrict(Iterable<Path> ingestionPaths) throws IOException {
        IOException firstDeleteFailure = null;
        for (Path ingestionPath : ingestionPaths) {
            try {
                Files.deleteIfExists(ingestionPath);
            } catch (IOException deleteFailure) {
                if (firstDeleteFailure == null) {
                    firstDeleteFailure = deleteFailure;
                }
            }
        }
        if (firstDeleteFailure != null) {
            throw firstDeleteFailure;
        }
    }

    private String buildHashMarkerPayload(String title, String packageName) {
        String encodedTitle = Base64.getEncoder().encodeToString(title.getBytes(StandardCharsets.UTF_8));
        String encodedPackageName = Base64.getEncoder().encodeToString(packageName.getBytes(StandardCharsets.UTF_8));
        StringBuilder markerPayload = new StringBuilder();
        markerPayload.append(HASH_MARKER_INGESTED_FLAG).append('\n');
        markerPayload.append(HASH_MARKER_TITLE_PREFIX).append(encodedTitle).append('\n');
        markerPayload
                .append(HASH_MARKER_PACKAGE_PREFIX)
                .append(encodedPackageName)
                .append('\n');
        return markerPayload.toString();
    }

    private HashMarkerMetadata readHashMarkerMetadata(Path markerPath) throws IOException {
        String markerPayload = Files.readString(markerPath, StandardCharsets.UTF_8);
        String resolvedTitle = "";
        String resolvedPackageName = "";
        for (String markerLine : markerPayload.split("\n")) {
            String trimmedMarkerLine = markerLine == null ? "" : markerLine.trim();
            if (trimmedMarkerLine.startsWith(HASH_MARKER_TITLE_PREFIX)) {
                String encodedTitle = trimmedMarkerLine
                        .substring(HASH_MARKER_TITLE_PREFIX.length())
                        .trim();
                resolvedTitle = decodeHashMetadataField(encodedTitle);
            } else if (trimmedMarkerLine.startsWith(HASH_MARKER_PACKAGE_PREFIX)) {
                String encodedPackageName = trimmedMarkerLine
                        .substring(HASH_MARKER_PACKAGE_PREFIX.length())
                        .trim();
                resolvedPackageName = decodeHashMetadataField(encodedPackageName);
            }
        }
        return new HashMarkerMetadata(resolvedTitle, resolvedPackageName);
    }

    private String decodeHashMetadataField(String encodedMetadataField) throws IOException {
        if (encodedMetadataField == null || encodedMetadataField.isBlank()) {
            return "";
        }
        try {
            byte[] decodedBytes = Base64.getDecoder().decode(encodedMetadataField);
            return new String(decodedBytes, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalidBase64Exception) {
            throw new IOException("Invalid hash marker metadata encoding", invalidBase64Exception);
        }
    }

    private String normalizeHashMetadataText(String metadataText) {
        return metadataText == null ? "" : metadataText.trim();
    }

    private record HashMarkerMetadata(String title, String packageName) {}

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

    Path indexDirectory() {
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
        MessageDigest messageDigest = newSha256Digest();
        byte[] digest = messageDigest.digest(input.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest, 0, SHORT_SHA_BYTES);
    }

    private MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance(SHA_256_ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 MessageDigest is not available", exception);
        }
    }
}
