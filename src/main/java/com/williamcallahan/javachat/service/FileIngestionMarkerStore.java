package com.williamcallahan.javachat.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Persists the canonical file-level ingestion record keyed by source URL.
 *
 * <p>This store owns the marker field inventory and exact serialized representation. Chunk-hash markers and
 * parsed content remain owned by {@link LocalStoreService} because their lifecycle differs from a file record.</p>
 */
@Service
public class FileIngestionMarkerStore {
    private static final String FILE_MARKER_PREFIX = "file_";
    private static final String FILE_MARKER_EXTENSION = ".marker";
    private static final String FILE_MARKER_SIZE_PREFIX = "size=";
    private static final String FILE_MARKER_LAST_MODIFIED_PREFIX = "mtime=";
    private static final String FILE_MARKER_HASH_PREFIX = "hash=";
    private static final String FILE_MARKER_INGESTION_FINGERPRINT_PREFIX = "fingerprint=";
    private static final String FILE_MARKER_EXTRACTION_SEMANTICS_VERSION_PREFIX = "extractorSemanticsVersion=";
    private static final String FILE_MARKER_COLLECTION_NAME_PREFIX = "collectionName=";
    private static final String FILE_MARKER_TEMPORARY_PREFIX = ".file-marker-";
    private static final String FILE_MARKER_TEMPORARY_SUFFIX = ".tmp";

    private final LocalStoreService localStoreService;

    /**
     * Creates the file marker store over the application's canonical local index directory.
     *
     * @param localStoreService owner of the configured index root and stable URL filename projection
     */
    public FileIngestionMarkerStore(LocalStoreService localStoreService) {
        this.localStoreService = Objects.requireNonNull(localStoreService, "localStoreService");
    }

    /**
     * Records a canonical file-level ingestion marker keyed by URL.
     *
     * @param url authoritative URL used for chunk hashing and citations
     * @param fileIngestionRecord canonical marker contents
     * @throws IOException if marker creation or writing fails
     */
    public void markFileIngested(String url, FileIngestionRecord fileIngestionRecord) throws IOException {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL is required for file ingestion marker");
        }
        FileIngestionRecord canonicalFileIngestionRecord =
                Objects.requireNonNull(fileIngestionRecord, "fileIngestionRecord");
        if (!canonicalFileIngestionRecord.hasCollectionIdentity()) {
            throw new IllegalArgumentException("Collection name is required for new file ingestion markers");
        }
        Path markerPath = fileMarkerPath(url);
        writeFileMarkerAtomically(markerPath, buildFileMarkerPayload(canonicalFileIngestionRecord));
    }

    private void writeFileMarkerAtomically(Path markerPath, String markerPayload) throws IOException {
        Path markerDirectory = markerPath.getParent();
        if (markerDirectory == null) {
            throw new IOException("File marker path has no parent directory: " + markerPath);
        }
        Files.createDirectories(markerDirectory);
        if (markerPath.getFileName() == null) {
            throw new IOException("File marker path has no filename: " + markerPath);
        }
        Path temporaryMarker =
                Files.createTempFile(markerDirectory, FILE_MARKER_TEMPORARY_PREFIX, FILE_MARKER_TEMPORARY_SUFFIX);
        try {
            Files.writeString(temporaryMarker, markerPayload, StandardCharsets.UTF_8);
            Files.move(
                    temporaryMarker, markerPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporaryMarker);
        }
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
        } catch (IOException markerReadFailure) {
            throw new IllegalStateException("Failed to read file ingestion marker for URL: " + url, markerReadFailure);
        }
    }

    /**
     * Deletes the file-level ingestion marker for a URL when present.
     *
     * @param url authoritative URL used for chunk hashing and citations
     * @throws IOException if deletion fails
     */
    public void deleteFileIngestionRecord(String url) throws IOException {
        if (url == null || url.isBlank()) {
            return;
        }
        Files.deleteIfExists(fileMarkerPath(url));
    }

    private Path fileMarkerPath(String url) {
        String markerFileName = FILE_MARKER_PREFIX + localStoreService.toSafeName(url) + FILE_MARKER_EXTENSION;
        return localStoreService.indexDirectory().resolve(markerFileName);
    }

    private FileIngestionRecord readFileMarker(Path markerPath) throws IOException {
        String markerPayload = Files.readString(markerPath, StandardCharsets.UTF_8);
        long fileSizeBytes = -1;
        long lastModifiedMillis = -1;
        String ingestionFingerprint = "";
        String extractionSemanticsVersion = "";
        String collectionName = "";
        List<String> chunkHashes = new ArrayList<>();
        for (String markerLine : markerPayload.split("\n")) {
            String trimmedMarkerLine = markerLine == null ? "" : markerLine.trim();
            if (trimmedMarkerLine.startsWith(FILE_MARKER_SIZE_PREFIX)) {
                fileSizeBytes = parseLongSafely(trimmedMarkerLine.substring(FILE_MARKER_SIZE_PREFIX.length()));
            } else if (trimmedMarkerLine.startsWith(FILE_MARKER_LAST_MODIFIED_PREFIX)) {
                lastModifiedMillis =
                        parseLongSafely(trimmedMarkerLine.substring(FILE_MARKER_LAST_MODIFIED_PREFIX.length()));
            } else if (trimmedMarkerLine.startsWith(FILE_MARKER_INGESTION_FINGERPRINT_PREFIX)) {
                ingestionFingerprint = trimmedMarkerLine
                        .substring(FILE_MARKER_INGESTION_FINGERPRINT_PREFIX.length())
                        .trim();
            } else if (trimmedMarkerLine.startsWith(FILE_MARKER_EXTRACTION_SEMANTICS_VERSION_PREFIX)) {
                extractionSemanticsVersion = trimmedMarkerLine
                        .substring(FILE_MARKER_EXTRACTION_SEMANTICS_VERSION_PREFIX.length())
                        .trim();
            } else if (trimmedMarkerLine.startsWith(FILE_MARKER_COLLECTION_NAME_PREFIX)) {
                collectionName = trimmedMarkerLine
                        .substring(FILE_MARKER_COLLECTION_NAME_PREFIX.length())
                        .trim();
            } else if (trimmedMarkerLine.startsWith(FILE_MARKER_HASH_PREFIX)) {
                String chunkHash = trimmedMarkerLine
                        .substring(FILE_MARKER_HASH_PREFIX.length())
                        .trim();
                if (!chunkHash.isBlank()) {
                    chunkHashes.add(chunkHash);
                }
            }
        }
        if (fileSizeBytes < 0 || lastModifiedMillis < 0) {
            throw new IOException("Invalid file ingestion marker format: " + markerPath);
        }
        return new FileIngestionRecord(
                fileSizeBytes,
                lastModifiedMillis,
                ingestionFingerprint,
                extractionSemanticsVersion,
                collectionName,
                List.copyOf(chunkHashes));
    }

    private long parseLongSafely(String markerNumber) throws IOException {
        try {
            return Long.parseLong(markerNumber);
        } catch (NumberFormatException numberFormatException) {
            throw new IOException("Invalid marker value: " + markerNumber, numberFormatException);
        }
    }

    private String buildFileMarkerPayload(FileIngestionRecord fileIngestionRecord) {
        StringBuilder markerPayload = new StringBuilder();
        markerPayload
                .append(FILE_MARKER_SIZE_PREFIX)
                .append(fileIngestionRecord.fileSizeBytes())
                .append('\n');
        markerPayload
                .append(FILE_MARKER_LAST_MODIFIED_PREFIX)
                .append(fileIngestionRecord.lastModifiedMillis())
                .append('\n');
        markerPayload
                .append(FILE_MARKER_INGESTION_FINGERPRINT_PREFIX)
                .append(fileIngestionRecord.ingestionFingerprint())
                .append('\n');
        markerPayload
                .append(FILE_MARKER_EXTRACTION_SEMANTICS_VERSION_PREFIX)
                .append(fileIngestionRecord.extractionSemanticsVersion())
                .append('\n');
        markerPayload
                .append(FILE_MARKER_COLLECTION_NAME_PREFIX)
                .append(fileIngestionRecord.collectionName())
                .append('\n');
        for (String chunkHash : fileIngestionRecord.chunkHashes()) {
            if (chunkHash != null && !chunkHash.isBlank()) {
                markerPayload.append(FILE_MARKER_HASH_PREFIX).append(chunkHash).append('\n');
            }
        }
        return markerPayload.toString();
    }

    /**
     * Defines every field persisted by a file-level ingestion marker.
     *
     * @param fileSizeBytes file size in bytes at ingestion time
     * @param lastModifiedMillis file last modified timestamp in millis at ingestion time
     * @param ingestionFingerprint fingerprint of every input whose change requires reingestion
     * @param extractionSemanticsVersion version of the extraction semantics used to create chunks
     * @param collectionName exact Qdrant collection that received the file's vectors
     * @param chunkHashes chunk hashes ingested for the file
     */
    public record FileIngestionRecord(
            long fileSizeBytes,
            long lastModifiedMillis,
            String ingestionFingerprint,
            String extractionSemanticsVersion,
            String collectionName,
            List<String> chunkHashes) {
        public FileIngestionRecord {
            ingestionFingerprint = ingestionFingerprint == null ? "" : ingestionFingerprint;
            extractionSemanticsVersion = extractionSemanticsVersion == null ? "" : extractionSemanticsVersion;
            collectionName = collectionName == null ? "" : collectionName;
            chunkHashes = chunkHashes == null ? List.of() : List.copyOf(chunkHashes);
        }

        /** Returns whether the marker identifies the collection that received its vectors. */
        public boolean hasCollectionIdentity() {
            return !collectionName.isBlank();
        }

        /**
         * Binds a marker without collection identity before any destructive migration step.
         *
         * @param canonicalCollectionName authoritative collection that received the file vectors
         * @return this record when already bound, otherwise an equivalent record with collection identity
         * @throws IllegalArgumentException when the canonical collection is blank or conflicts with this marker
         */
        public FileIngestionRecord bindCollectionIdentity(String canonicalCollectionName) {
            Objects.requireNonNull(canonicalCollectionName, "canonicalCollectionName");
            if (canonicalCollectionName.isBlank()) {
                throw new IllegalArgumentException("canonicalCollectionName must not be blank");
            }
            if (hasCollectionIdentity()) {
                if (!collectionName.equals(canonicalCollectionName)) {
                    throw new IllegalArgumentException(
                            "Marker collection identity conflicts with canonical collection: " + collectionName);
                }
                return this;
            }
            return new FileIngestionRecord(
                    fileSizeBytes,
                    lastModifiedMillis,
                    ingestionFingerprint,
                    extractionSemanticsVersion,
                    canonicalCollectionName,
                    chunkHashes);
        }
    }
}
