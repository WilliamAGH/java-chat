package com.williamcallahan.javachat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Persists document snapshots, parsed chunks, and ingestion markers on the local filesystem.
 */
@Service
public class LocalStoreService {
    private static final String SHA_256_ALGORITHM = "SHA-256";
    private static final int SHORT_SHA_BYTES = 6;
    private static final int HASH_PREFIX_LENGTH = 12;
    private static final int SAFE_NAME_MAX_LENGTH = 150;
    private static final int SAFE_NAME_PREFIX_LENGTH = 80;
    private static final int SAFE_NAME_SUFFIX_LENGTH = 40;

    private final Path snapshotDir;
    private final Path parsedDir;
    private final Path indexDir;
    private final ProgressTracker progressTracker;

    /**
     * Creates the local store using configured directory roots for snapshots, parsed content, and ingest markers.
     */
    public LocalStoreService(@Value("${app.docs.snapshot-dir}") String snapshotDir,
                             @Value("${app.docs.parsed-dir}") String parsedDir,
                             @Value("${app.docs.index-dir}") String indexDir,
                             ProgressTracker progressTracker) {
        this.snapshotDir = Path.of(snapshotDir);
        this.parsedDir = Path.of(parsedDir);
        this.indexDir = Path.of(indexDir);
        this.progressTracker = progressTracker;
    }

    @PostConstruct
    void createStoreDirectories() {
        try {
            Files.createDirectories(this.snapshotDir);
            Files.createDirectories(this.parsedDir);
            Files.createDirectories(this.indexDir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create local store directories", e);
        }
    }

    /**
     * Stores a raw HTML snapshot for a document URL.
     */
    public void saveHtml(String url, String html) throws IOException {
        Path snapshotFilePath = snapshotDir.resolve(safeName(url) + ".html");
        Files.createDirectories(snapshotFilePath.getParent());
        Files.writeString(snapshotFilePath, html, StandardCharsets.UTF_8);
    }

    /**
     * Stores a parsed chunk payload for later local search and attribution.
     */
    public void saveChunkText(String url, int index, String text, String hash) throws IOException {
        String shortHash = hash.substring(0, HASH_PREFIX_LENGTH);
        Path chunkFilePath = parsedDir.resolve(safeName(url) + "_" + index + "_" + shortHash + ".txt");
        Files.createDirectories(chunkFilePath.getParent());
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

    public Path getParsedDir() {
        return parsedDir;
    }

    public Path getIndexDir() {
        return indexDir;
    }

    private String shortSha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance(SHA_256_ALGORITHM);
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, SHORT_SHA_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 MessageDigest is not available", e);
        }
    }
}

