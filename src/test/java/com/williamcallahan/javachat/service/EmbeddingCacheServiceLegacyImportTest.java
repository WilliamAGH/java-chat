package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Verifies legacy embedding cache imports remain compatible with new metadata handling.
 */
final class EmbeddingCacheServiceLegacyImportTest {
    @Test
    void importsLegacyJavaSerializedCacheAndPreservesAdditionalMetadata(@TempDir Path tempDir) throws IOException {
        EmbeddingModel embeddingModel = Mockito.mock(EmbeddingModel.class);
        HybridVectorService hybridVectorService = Mockito.mock(HybridVectorService.class);
        QdrantCollectionRouter collectionRouter = Mockito.mock(QdrantCollectionRouter.class);
        ObjectMapper objectMapper = new ObjectMapper();

        EmbeddingCacheService cacheService = new EmbeddingCacheService(
                tempDir.toString(), embeddingModel, hybridVectorService, collectionRouter, objectMapper);

        Path legacyCachePath = tempDir.resolve("legacy_cache.gz");
        writeLegacyCache(legacyCachePath);

        cacheService.importCache("legacy_cache.gz");

        EmbeddingCacheService.CacheStats cacheStats = cacheService.getCacheStats();
        assertEquals(1L, cacheStats.totalCached());

        cacheService.exportCache("exported_cache.gz");

        JsonNode exported = readGzipJson(tempDir.resolve("exported_cache.gz"), objectMapper);
        assertNotNull(exported);
        assertEquals(1, exported.size());

        JsonNode entry = exported.get(0);
        assertEquals("legacy-id", entry.path("id").asText());
        assertEquals(true, entry.path("uploaded").asBoolean());

        JsonNode metadata = entry.path("metadata");
        assertEquals("https://example.com/docs", metadata.path("url").asText());

        JsonNode additionalMetadata = metadata.path("additionalMetadata");
        assertEquals("legacyValue", additionalMetadata.path("legacyField").asText());
    }

    private static void writeLegacyCache(Path legacyCachePath) throws IOException {
        EmbeddingCacheService.CachedEmbedding legacyEntry = new EmbeddingCacheService.CachedEmbedding();
        legacyEntry.id = "legacy-id";
        legacyEntry.content = "Legacy content";
        legacyEntry.embedding = new float[] {0.1f, 0.2f, 0.3f};
        legacyEntry.createdAt = LocalDateTime.of(2024, 1, 1, 0, 0);
        legacyEntry.uploaded = true;

        HashMap<String, String> legacyMetadata = new HashMap<>();
        legacyMetadata.put("url", "https://example.com/docs");
        legacyMetadata.put("legacyField", "legacyValue");
        legacyEntry.metadata = legacyMetadata;

        List<EmbeddingCacheService.CachedEmbedding> entries = new ArrayList<>();
        entries.add(legacyEntry);

        try (OutputStream fileOutputStream = Files.newOutputStream(legacyCachePath);
                GZIPOutputStream gzipOutputStream = new GZIPOutputStream(fileOutputStream);
                ObjectOutputStream objectOutputStream = new ObjectOutputStream(gzipOutputStream)) {
            objectOutputStream.writeObject(entries);
        }
    }

    private static JsonNode readGzipJson(Path gzipPath, ObjectMapper objectMapper) throws IOException {
        try (var fileInputStream = Files.newInputStream(gzipPath);
                var gzipInputStream = new java.util.zip.GZIPInputStream(fileInputStream)) {
            return objectMapper.readTree(gzipInputStream);
        }
    }
}
