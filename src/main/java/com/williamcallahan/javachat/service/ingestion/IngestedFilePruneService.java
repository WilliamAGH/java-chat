package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.service.ContentHasher;
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

    /**
     * Creates a prune service for vector-store and local-marker cleanup.
     *
     * @param hybridVectorService hybrid vector storage service
     * @param localStoreService local marker and parsed-chunk store
     * @param contentHasher content hash helper
     */
    public IngestedFilePruneService(
            HybridVectorService hybridVectorService, LocalStoreService localStoreService, ContentHasher contentHasher) {
        this.hybridVectorService = Objects.requireNonNull(hybridVectorService, "hybridVectorService");
        this.localStoreService = Objects.requireNonNull(localStoreService, "localStoreService");
        this.contentHasher = Objects.requireNonNull(contentHasher, "contentHasher");
    }

    /**
     * Strictly prunes stale vectors and local markers for a file in the specified collection.
     *
     * @param collectionName target Qdrant collection name
     * @param sourceUrl authoritative URL key for file markers and vectors
     * @param previousFileRecord previous file marker record, or {@code null} if unavailable
     * @throws IOException when local marker or parsed-chunk cleanup fails
     */
    public void pruneCollectionFileStrict(
            String collectionName, String sourceUrl, LocalStoreService.FileIngestionRecord previousFileRecord)
            throws IOException {
        Objects.requireNonNull(collectionName, "collectionName");
        Objects.requireNonNull(sourceUrl, "sourceUrl");

        hybridVectorService.deleteByUrl(collectionName, sourceUrl);

        List<String> staleChunkHashes = resolveChunkHashesForPrune(sourceUrl, previousFileRecord);
        if (!staleChunkHashes.isEmpty()) {
            localStoreService.deleteChunkIngestionMarkers(staleChunkHashes);
        }
        localStoreService.deleteParsedChunksForUrl(sourceUrl);
        localStoreService.deleteFileIngestionRecord(sourceUrl);
    }

    private List<String> resolveChunkHashesForPrune(
            String sourceUrl, LocalStoreService.FileIngestionRecord previousFileRecord) throws IOException {
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
