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

/** Persists file-level ingestion records independently from chunk and snapshot storage. */
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

    /** Creates a marker store over the configured local index directory. */
    public FileIngestionMarkerStore(LocalStoreService localStoreService) {
        this.localStoreService = Objects.requireNonNull(localStoreService, "localStoreService");
    }

    /** Records a file-level ingestion marker keyed by its authoritative URL. */
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

    /** Loads the file ingestion marker for an authoritative URL. */
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

    /** Deletes the file-level ingestion marker for an authoritative URL. */
    public void deleteFileIngestionRecord(String url) throws IOException {
        if (url == null || url.isBlank()) {
            return;
        }
        Files.deleteIfExists(fileMarkerPath(url));
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

    /** Defines the persisted file-level ingestion fields. */
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

        /** Binds a legacy marker to the collection that owns its vectors. */
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
