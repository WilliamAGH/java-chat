package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.service.ContentHasher;
import com.williamcallahan.javachat.service.FileIngestionMarkerStore;
import com.williamcallahan.javachat.service.FileIngestionMarkerStore.FileIngestionRecord;
import com.williamcallahan.javachat.service.HybridVectorService;
import com.williamcallahan.javachat.service.LocalStoreService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Removes stale vectors and local ingestion markers for a previously ingested source file.
 *
 * <p>This service centralizes strict prune behavior used by ingestion processors when source
 * content changes between runs.</p>
 */
@Service
public class IngestedFilePruneService {
    private static final int TRUNCATED_CHUNK_HASH_PREFIX_LENGTH = 12;
    private static final int SHA_256_HEX_LENGTH = 64;
    private static final String PARSED_CHUNK_FILE_EXTENSION = ".txt";

    private final HybridVectorService hybridVectorService;
    private final LocalStoreService localStoreService;
    private final FileIngestionMarkerStore fileIngestionMarkerStore;
    private final ContentHasher contentHasher;

    /**
     * Creates a prune service for vector-store and local-marker cleanup.
     *
     * @param hybridVectorService hybrid vector storage service
     * @param localStoreService local marker and parsed-chunk store
     * @param fileIngestionMarkerStore file marker storage
     * @param contentHasher content hash helper
     */
    public IngestedFilePruneService(
            HybridVectorService hybridVectorService,
            LocalStoreService localStoreService,
            FileIngestionMarkerStore fileIngestionMarkerStore,
            ContentHasher contentHasher) {
        this.hybridVectorService = Objects.requireNonNull(hybridVectorService, "hybridVectorService");
        this.localStoreService = Objects.requireNonNull(localStoreService, "localStoreService");
        this.fileIngestionMarkerStore = Objects.requireNonNull(fileIngestionMarkerStore, "fileIngestionMarkerStore");
        this.contentHasher = Objects.requireNonNull(contentHasher, "contentHasher");
    }

    /**
     * Strictly prunes stale vectors and local markers for a file in the specified collection.
     *
     * @param collectionName target Qdrant collection name
     * @param sourceUrl authoritative URL key for file markers and vectors
     * @param previousFileRecord previous file marker record, or {@code null} if unavailable
     * @throws IOException when local marker or parsed-chunk cleanup fails
     * @throws IllegalArgumentException when the collection name is blank
     */
    public void pruneCollectionFileStrict(
            String collectionName, String sourceUrl, FileIngestionRecord previousFileRecord) throws IOException {
        Objects.requireNonNull(collectionName, "collectionName");
        pruneFileStrict(List.of(collectionName), sourceUrl, previousFileRecord);
    }

    /**
     * Strictly prunes a file from every specified collection before deleting its local ingestion state.
     *
     * <p>Deferring local cleanup until every vector deletion succeeds preserves the marker needed to retry
     * a partially completed prior-format marker prune.</p>
     *
     * @param collectionNames target Qdrant collection names
     * @param sourceUrl authoritative URL key for file markers and vectors
     * @param previousFileRecord previous file marker record, or {@code null} if unavailable
     * @throws IOException when local marker or parsed-chunk cleanup fails
     * @throws IllegalArgumentException when no collection names are provided or any name is blank
     */
    public void pruneCollectionsFileStrict(
            List<String> collectionNames, String sourceUrl, FileIngestionRecord previousFileRecord) throws IOException {
        Objects.requireNonNull(collectionNames, "collectionNames");
        if (collectionNames.isEmpty()) {
            throw new IllegalArgumentException("At least one collection name is required for file pruning");
        }
        pruneFileStrict(List.copyOf(collectionNames), sourceUrl, previousFileRecord);
    }

    /**
     * Removes obsolete local chunk state after a complete same-collection replacement was stored.
     *
     * <p>Collection generations own separate state roots. This operation therefore never deletes vectors or file
     * markers; the caller replaces the current marker only after local chunk cleanup succeeds.</p>
     *
     * @param sourceUrl authoritative URL key for local state and vectors
     * @param previousFileRecord previous file marker record, or {@code null} when no marker existed
     * @param replacementChunkHashes complete hash inventory for the stored replacement
     * @throws IOException when obsolete parsed chunks or hash markers cannot be deleted
     */
    public void pruneObsoleteLocalStateAfterReplacement(
            String sourceUrl, FileIngestionRecord previousFileRecord, List<String> replacementChunkHashes)
            throws IOException {
        Objects.requireNonNull(sourceUrl, "sourceUrl");
        Set<String> replacementHashSet = validatedChunkHashSet(replacementChunkHashes, "replacementChunkHashes");

        List<ParsedChunkReference> parsedChunkReferences = readParsedChunkReferences(sourceUrl);
        Set<String> obsoleteChunkHashes = new LinkedHashSet<>();
        if (previousFileRecord != null) {
            obsoleteChunkHashes.addAll(previousFileRecord.chunkHashes());
        }
        parsedChunkReferences.stream()
                .map(ParsedChunkReference::canonicalChunkHash)
                .filter(parsedChunkHash -> !parsedChunkHash.isBlank())
                .filter(parsedChunkHash -> !replacementHashSet.contains(parsedChunkHash))
                .forEach(obsoleteChunkHashes::add);
        obsoleteChunkHashes.removeAll(replacementHashSet);
        if (!obsoleteChunkHashes.isEmpty()) {
            localStoreService.deleteChunkIngestionMarkers(List.copyOf(obsoleteChunkHashes));
        }
        deleteObsoleteParsedChunks(parsedChunkReferences, replacementHashSet);
    }

    private void pruneFileStrict(List<String> collectionNames, String sourceUrl, FileIngestionRecord previousFileRecord)
            throws IOException {
        Objects.requireNonNull(sourceUrl, "sourceUrl");

        for (String collectionName : collectionNames) {
            if (collectionName.isBlank()) {
                throw new IllegalArgumentException("Collection names must not be blank for file pruning");
            }
        }
        for (String collectionName : collectionNames) {
            hybridVectorService.deleteByUrl(collectionName, sourceUrl);
        }

        List<String> staleChunkHashes = resolveChunkHashesForPrune(sourceUrl, previousFileRecord);
        if (!staleChunkHashes.isEmpty()) {
            localStoreService.deleteChunkIngestionMarkers(staleChunkHashes);
        }
        localStoreService.deleteParsedChunksForUrl(sourceUrl);
        fileIngestionMarkerStore.deleteFileIngestionRecord(sourceUrl);
    }

    private List<String> resolveChunkHashesForPrune(String sourceUrl, FileIngestionRecord previousFileRecord)
            throws IOException {
        if (previousFileRecord != null) {
            List<String> previousHashes = previousFileRecord.chunkHashes();
            if (previousHashes != null && !previousHashes.isEmpty()) {
                return previousHashes;
            }
        }
        return reconstructChunkHashesFromParsedChunks(sourceUrl);
    }

    private List<String> reconstructChunkHashesFromParsedChunks(String sourceUrl) throws IOException {
        Set<String> canonicalHashSet = new LinkedHashSet<>();
        readParsedChunkReferences(sourceUrl).stream()
                .map(ParsedChunkReference::canonicalChunkHash)
                .filter(canonicalChunkHash -> !canonicalChunkHash.isBlank())
                .forEach(canonicalHashSet::add);
        return List.copyOf(canonicalHashSet);
    }

    private List<ParsedChunkReference> readParsedChunkReferences(String sourceUrl) throws IOException {
        if (sourceUrl == null || sourceUrl.isBlank()) {
            return List.of();
        }

        Path parsedChunkDirectory = localStoreService.getParsedDir();
        if (parsedChunkDirectory == null || !Files.isDirectory(parsedChunkDirectory)) {
            return List.of();
        }

        String safeSourceName = localStoreService.toSafeName(sourceUrl);
        String parsedChunkPrefix = safeSourceName + "_";
        List<ParsedChunkReference> parsedChunkReferences = new ArrayList<>();

        try (var parsedChunkDirectoryStream = Files.newDirectoryStream(parsedChunkDirectory, parsedChunkPath -> {
            Path parsedChunkFileNamePath = parsedChunkPath.getFileName();
            if (parsedChunkFileNamePath == null) {
                return false;
            }
            String parsedChunkFileName = parsedChunkFileNamePath.toString();
            return parsedChunkFileName.startsWith(parsedChunkPrefix)
                    && parsedChunkFileName.endsWith(PARSED_CHUNK_FILE_EXTENSION);
        })) {
            for (Path parsedChunkPath : parsedChunkDirectoryStream) {
                parsedChunkReferences.add(parseParsedChunkReference(parsedChunkPath, sourceUrl, parsedChunkPrefix));
            }
        }
        return List.copyOf(parsedChunkReferences);
    }

    private ParsedChunkReference parseParsedChunkReference(
            Path parsedChunkPath, String sourceUrl, String parsedChunkPrefix) throws IOException {
        Path parsedChunkFileNamePath = parsedChunkPath.getFileName();
        if (parsedChunkFileNamePath == null) {
            return unverifiedParsedChunkReference(parsedChunkPath);
        }
        String parsedChunkFileName = parsedChunkFileNamePath.toString();
        String parsedChunkIdentitySuffix = parsedChunkFileName.substring(parsedChunkPrefix.length());
        int firstUnderscorePosition = parsedChunkIdentitySuffix.indexOf('_');
        if (firstUnderscorePosition <= 0) {
            return unverifiedParsedChunkReference(parsedChunkPath);
        }
        int storedHashStart = firstUnderscorePosition + 1;
        int storedHashEnd = parsedChunkIdentitySuffix.length() - PARSED_CHUNK_FILE_EXTENSION.length();
        if (storedHashStart >= storedHashEnd) {
            return unverifiedParsedChunkReference(parsedChunkPath);
        }
        String chunkIndexToken = parsedChunkIdentitySuffix.substring(0, firstUnderscorePosition);
        String storedChunkHash = parsedChunkIdentitySuffix.substring(storedHashStart, storedHashEnd);
        int chunkIndex;
        try {
            chunkIndex = Integer.parseInt(chunkIndexToken);
        } catch (NumberFormatException malformedChunkIndex) {
            return unverifiedParsedChunkReference(parsedChunkPath);
        }
        if (isFullCanonicalChunkHash(storedChunkHash)) {
            return new ParsedChunkReference(parsedChunkPath, storedChunkHash, "");
        }
        if (!isTruncatedChunkHashPrefix(storedChunkHash)) {
            return unverifiedParsedChunkReference(parsedChunkPath);
        }
        String chunkText = Files.readString(parsedChunkPath, StandardCharsets.UTF_8);
        String reconstructedChunkHash = contentHasher.generateChunkHash(sourceUrl, chunkIndex, chunkText);
        String canonicalChunkHash = reconstructedChunkHash.startsWith(storedChunkHash) ? reconstructedChunkHash : "";
        return new ParsedChunkReference(parsedChunkPath, canonicalChunkHash, storedChunkHash);
    }

    private static ParsedChunkReference unverifiedParsedChunkReference(Path parsedChunkPath) {
        return new ParsedChunkReference(parsedChunkPath, "", "");
    }

    private static Set<String> validatedChunkHashSet(List<String> chunkHashes, String parameterName) {
        Objects.requireNonNull(chunkHashes, parameterName);
        Set<String> validatedHashes = new LinkedHashSet<>();
        for (String chunkHash : chunkHashes) {
            if (chunkHash == null || chunkHash.isBlank()) {
                throw new IllegalArgumentException(parameterName + " must not contain blank hashes");
            }
            validatedHashes.add(chunkHash);
        }
        return Set.copyOf(validatedHashes);
    }

    private static void deleteObsoleteParsedChunks(
            List<ParsedChunkReference> parsedChunkReferences, Set<String> replacementHashSet) throws IOException {
        IOException firstDeleteFailure = null;
        for (ParsedChunkReference parsedChunkReference : parsedChunkReferences) {
            if (belongsToReplacement(parsedChunkReference, replacementHashSet)) {
                continue;
            }
            try {
                Files.deleteIfExists(parsedChunkReference.parsedChunkPath());
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

    private static boolean belongsToReplacement(
            ParsedChunkReference parsedChunkReference, Set<String> replacementHashSet) {
        String canonicalChunkHash = parsedChunkReference.canonicalChunkHash();
        if (!canonicalChunkHash.isBlank()) {
            return replacementHashSet.contains(canonicalChunkHash);
        }
        String storedHashPrefix = parsedChunkReference.storedHashPrefix();
        return !storedHashPrefix.isBlank()
                && replacementHashSet.stream()
                        .anyMatch(replacementHash -> replacementHash.startsWith(storedHashPrefix));
    }

    private static boolean isFullCanonicalChunkHash(String storedChunkHash) {
        return storedChunkHash.length() == SHA_256_HEX_LENGTH
                && storedChunkHash.chars().allMatch(IngestedFilePruneService::isLowercaseHexadecimalCharacter);
    }

    private static boolean isTruncatedChunkHashPrefix(String storedChunkHash) {
        return storedChunkHash.length() == TRUNCATED_CHUNK_HASH_PREFIX_LENGTH
                && storedChunkHash.chars().allMatch(IngestedFilePruneService::isLowercaseHexadecimalCharacter);
    }

    private static boolean isLowercaseHexadecimalCharacter(int characterCode) {
        return (characterCode >= '0' && characterCode <= '9') || (characterCode >= 'a' && characterCode <= 'f');
    }

    private record ParsedChunkReference(Path parsedChunkPath, String canonicalChunkHash, String storedHashPrefix) {}
}
