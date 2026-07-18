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

    private final HybridVectorService hybridVectorService;
    private final LocalStoreService localStoreService;
    private final ContentHasher contentHasher;
    private final FileIngestionMarkerStore fileMarkerStore;

    /**
     * Creates a prune service for vector-store and local-marker cleanup.
     *
     * @param hybridVectorService hybrid vector storage service
     * @param localStoreService local marker and parsed-chunk store
     * @param contentHasher content hash helper
     * @param fileMarkerStore canonical file-level marker store
     */
    public IngestedFilePruneService(
            HybridVectorService hybridVectorService,
            LocalStoreService localStoreService,
            ContentHasher contentHasher,
            FileIngestionMarkerStore fileMarkerStore) {
        this.hybridVectorService = Objects.requireNonNull(hybridVectorService, "hybridVectorService");
        this.localStoreService = Objects.requireNonNull(localStoreService, "localStoreService");
        this.contentHasher = Objects.requireNonNull(contentHasher, "contentHasher");
        this.fileMarkerStore = Objects.requireNonNull(fileMarkerStore, "fileMarkerStore");
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
     * a partially completed legacy-record prune.</p>
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
        fileMarkerStore.deleteFileIngestionRecord(sourceUrl);
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
        if (sourceUrl == null || sourceUrl.isBlank()) {
            return List.of();
        }

        Path parsedChunkDirectory = localStoreService.getParsedDir();
        if (parsedChunkDirectory == null || !Files.isDirectory(parsedChunkDirectory)) {
            return List.of();
        }

        String safeSourceName = localStoreService.toSafeName(sourceUrl);
        String parsedChunkPrefix = safeSourceName + "_";
        Set<String> reconstructedHashSet = new LinkedHashSet<>();

        try (var parsedChunkDirectoryStream = Files.newDirectoryStream(parsedChunkDirectory, parsedChunkPath -> {
            Path parsedChunkFileNamePath = parsedChunkPath.getFileName();
            if (parsedChunkFileNamePath == null) {
                return false;
            }
            String parsedChunkFileName = parsedChunkFileNamePath.toString();
            return parsedChunkFileName.startsWith(parsedChunkPrefix) && parsedChunkFileName.endsWith(".txt");
        })) {
            for (Path parsedChunkPath : parsedChunkDirectoryStream) {
                Path parsedChunkFileNamePath = parsedChunkPath.getFileName();
                if (parsedChunkFileNamePath == null) {
                    continue;
                }
                String parsedChunkFileName = parsedChunkFileNamePath.toString();
                String remainder = parsedChunkFileName.substring(parsedChunkPrefix.length());
                int firstUnderscorePosition = remainder.indexOf('_');
                if (firstUnderscorePosition <= 0) {
                    continue;
                }
                String chunkIndexToken = remainder.substring(0, firstUnderscorePosition);
                int chunkIndex;
                try {
                    chunkIndex = Integer.parseInt(chunkIndexToken);
                } catch (NumberFormatException malformedChunkIndex) {
                    continue;
                }
                String chunkText = Files.readString(parsedChunkPath, StandardCharsets.UTF_8);
                String reconstructedHash = contentHasher.generateChunkHash(sourceUrl, chunkIndex, chunkText);
                if (!reconstructedHash.isBlank()) {
                    reconstructedHashSet.add(reconstructedHash);
                }
            }
        }
        return List.copyOf(reconstructedHashSet);
    }
}
