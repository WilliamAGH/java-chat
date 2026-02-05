package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidParameterException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service for caching embeddings locally to reduce API calls and enable batch processing.
 * Saves embeddings to compressed files in data/embeddings-cache for later upload to Qdrant.
 */
@Service
public class EmbeddingCacheService {
    private static final Logger CACHE_LOG = LoggerFactory.getLogger("EMBEDDING_CACHE");
    private static final String CACHE_FILE_NAME = "embeddings_cache.gz";
    private static final String CORRUPT_CACHE_PREFIX = "embeddings_cache.corrupt.";
    private static final DateTimeFormatter CACHE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final ObjectMapper cacheMapper;
    private final Path cacheDir;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    /** In-memory cache for fast lookup of computed embeddings */
    private final Map<String, EmbeddingCacheEntry> memoryCache = new ConcurrentHashMap<>();

    private final EmbeddingCacheFileImporter cacheFileImporter;
    private final AtomicInteger cacheHits = new AtomicInteger(0);
    private final AtomicInteger cacheMisses = new AtomicInteger(0);
    private final AtomicInteger embeddingsSinceLastSave = new AtomicInteger(0);
    private static final int AUTO_SAVE_THRESHOLD = 50; // Save every 50 embeddings
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private final Object cacheFileLock = new Object();

    /**
     * Wraps cache persistence failures as a runtime exception suitable for Spring initialization paths.
     */
    private static final class EmbeddingCacheOperationException extends IllegalStateException {
        private EmbeddingCacheOperationException(String message, Exception cause) {
            super(message, cause);
        }
    }

    /**
     * Validates filename and resolves to a safe path within cacheDir.
     * Prevents path traversal attacks by ensuring the resolved path stays within bounds.
     *
     * @param filename the filename to validate
     * @return the validated, normalized path
     * @throws InvalidParameterException if filename would escape cacheDir
     */
    private Path validateAndResolvePath(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new InvalidParameterException("Filename cannot be null or blank");
        }
        // Reject obvious path traversal attempts and path separators
        if (filename.contains("..")
                || filename.startsWith("/")
                || filename.contains(":")
                || filename.contains("/")
                || filename.contains("\\")) {
            throw new InvalidParameterException("Invalid filename: path traversal not allowed");
        }
        Path resolved = cacheDir.resolve(filename).normalize();
        // Verify resolved path is still within cacheDir
        if (!resolved.startsWith(cacheDir.normalize())) {
            throw new InvalidParameterException("Invalid filename: resolved path escapes cache directory");
        }
        return resolved;
    }

    /**
     * Creates an embedding cache service rooted at the configured cache directory.
     */
    public EmbeddingCacheService(
            @Value("${app.embeddings.cache-dir:./data/embeddings-cache}") String cacheDir,
            EmbeddingModel embeddingModel,
            VectorStore vectorStore,
            ObjectMapper objectMapper) {
        this.cacheDir = Path.of(cacheDir);
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
        this.cacheMapper = objectMapper
                .copy()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.cacheFileImporter = new EmbeddingCacheFileImporter(this.cacheMapper);
    }

    /**
     * Initializes the cache directory and schedules periodic persistence for incremental updates.
     */
    @PostConstruct
    public void initializeCache() {
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException exception) {
            throw new EmbeddingCacheOperationException("Failed to create cache directory", exception);
        }

        loadExistingCache();

        // Register shutdown hook to save cache on exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                scheduler.shutdown();
                CACHE_LOG.info("Saving cache before shutdown...");
                saveIncrementalCache();
                CACHE_LOG.info("Cache saved successfully. Total embeddings cached: {}", memoryCache.size());
            } catch (RuntimeException exception) {
                CACHE_LOG.error(
                        "Failed to save cache on shutdown (exception type: {})",
                        exception.getClass().getSimpleName());
            }
        }));

        // Start periodic save timer (every 2 minutes)
        scheduler.scheduleAtFixedRate(
                () -> {
                    try {
                        if (embeddingsSinceLastSave.get() > 0) {
                            CACHE_LOG.info(
                                    "Periodic save: {} new embeddings since last save", embeddingsSinceLastSave.get());
                            saveIncrementalCache();
                            embeddingsSinceLastSave.set(0);
                        }
                    } catch (RuntimeException exception) {
                        CACHE_LOG.error(
                                "Periodic save failed (exception type: {})",
                                exception.getClass().getSimpleName());
                    }
                },
                2,
                2,
                TimeUnit.MINUTES);
    }

    /**
     * Gets embeddings from cache or computes them if not cached
     * @param documents List of documents to get embeddings for
     * @return List of embedding vectors
     */
    public List<float[]> getOrComputeEmbeddings(List<Document> documents) {
        List<float[]> embeddings = new ArrayList<>();
        List<Document> toCompute = new ArrayList<>();
        Map<Integer, Integer> indexMapping = new HashMap<>();

        for (int documentIndex = 0; documentIndex < documents.size(); documentIndex++) {
            Document sourceDocument = documents.get(documentIndex);
            String cacheKey = generateCacheKey(sourceDocument);
            EmbeddingCacheEntry cachedEmbedding = memoryCache.get(cacheKey);

            if (cachedEmbedding != null) {
                embeddings.add(cachedEmbedding.getEmbedding());
                cacheHits.incrementAndGet();
                CACHE_LOG.debug("Cache HIT for document");
            } else {
                toCompute.add(sourceDocument);
                indexMapping.put(toCompute.size() - 1, documentIndex);
                embeddings.add(null);
                cacheMisses.incrementAndGet();
            }
        }

        if (!toCompute.isEmpty()) {
            CACHE_LOG.info(
                    "Computing {} new embeddings (cache hit rate: {}%)",
                    toCompute.size(), String.format("%.1f", getCacheHitRate() * 100));

            List<String> texts = toCompute.stream().map(Document::getText).collect(Collectors.toList());

            EmbeddingResponse response = embeddingModel.embedForResponse(texts);
            List<float[]> computedEmbeddings = response.getResults().stream()
                    .map(embeddingResult -> embeddingResult.getOutput())
                    .collect(Collectors.toList());

            for (int computedIndex = 0; computedIndex < computedEmbeddings.size(); computedIndex++) {
                Document sourceDocument = toCompute.get(computedIndex);
                float[] embeddingVector = computedEmbeddings.get(computedIndex);
                int originalIndex = indexMapping.get(computedIndex);
                embeddings.set(originalIndex, embeddingVector);

                EmbeddingCacheEntry cachedEmbedding = new EmbeddingCacheEntry(
                        UUID.randomUUID().toString(),
                        sourceDocument.getText(),
                        embeddingVector,
                        EmbeddingCacheMetadata.fromDocument(sourceDocument));

                String cacheKey = generateCacheKey(sourceDocument);
                memoryCache.put(cacheKey, cachedEmbedding);

                // Auto-save every N embeddings
                if (embeddingsSinceLastSave.incrementAndGet() >= AUTO_SAVE_THRESHOLD) {
                    CACHE_LOG.info("Auto-saving cache after {} new embeddings...", AUTO_SAVE_THRESHOLD);
                    saveIncrementalCache();
                    embeddingsSinceLastSave.set(0);
                    CACHE_LOG.info("Auto-save completed. Total cached: {}", memoryCache.size());
                }
            }

            // Final save after batch completion
            if (embeddingsSinceLastSave.get() > 0) {
                saveIncrementalCache();
                embeddingsSinceLastSave.set(0);
            }
        }

        return embeddings;
    }

    /**
     * Exports cache to compressed file.
     * Validates filename to prevent path traversal attacks per OWASP guidelines.
     *
     * @param filename Name of export file (must not contain path separators)
     * @throws IOException if export fails
     * @throws InvalidParameterException if filename contains path traversal sequences
     */
    public void exportCache(String filename) throws IOException {
        Path exportPath = validateAndResolvePath(filename);
        CACHE_LOG.info("Exporting {} cached embeddings", memoryCache.size());

        synchronized (cacheFileLock) {
            try (OutputStream fos = Files.newOutputStream(exportPath);
                    GZIPOutputStream gzos = new GZIPOutputStream(fos)) {
                cacheMapper.writeValue(gzos, new ArrayList<>(memoryCache.values()));
                CACHE_LOG.info("Successfully exported cache");
            }
        }
    }

    /**
     * Imports embeddings from compressed file.
     * Validates filename to prevent path traversal and applies deserialization filter
     * to prevent arbitrary code execution per OWASP guidelines.
     *
     * @param filename Name of file to import (must not contain path separators)
     * @throws IOException if import fails
     * @throws InvalidParameterException if filename contains path traversal sequences
     */
    public final void importCache(String filename) throws IOException {
        Path importPath = validateAndResolvePath(filename);
        importCacheFromPath(importPath);
    }

    /**
     * Uploads pending embeddings to Qdrant in batches
     * @param batchSize Number of embeddings per batch
     * @return Number of successfully uploaded embeddings
     */
    public int uploadPendingToVectorStore(int batchSize) {
        List<EmbeddingCacheEntry> pendingEmbeddings = memoryCache.values().stream()
                .filter(cachedEmbedding -> !cachedEmbedding.isUploaded())
                .collect(Collectors.toList());

        if (pendingEmbeddings.isEmpty()) {
            CACHE_LOG.info("No pending embeddings to upload");
            return 0;
        }

        CACHE_LOG.info(
                "Uploading {} pending embeddings to vector store in batches of {}",
                pendingEmbeddings.size(),
                batchSize);

        int uploadedCount = 0;
        for (int batchStartIndex = 0; batchStartIndex < pendingEmbeddings.size(); batchStartIndex += batchSize) {
            int batchEndIndex = Math.min(batchStartIndex + batchSize, pendingEmbeddings.size());
            List<EmbeddingCacheEntry> batch = pendingEmbeddings.subList(batchStartIndex, batchEndIndex);

            List<Document> documents =
                    batch.stream().map(EmbeddingCacheEntry::toDocument).collect(Collectors.toList());

            try {
                vectorStore.add(documents);

                for (EmbeddingCacheEntry cachedEmbedding : batch) {
                    cachedEmbedding.setUploaded(true);
                }

                uploadedCount += batch.size();
                CACHE_LOG.info(
                        "Uploaded batch {}/{} ({} embeddings)",
                        (batchStartIndex / batchSize) + 1,
                        (pendingEmbeddings.size() + batchSize - 1) / batchSize,
                        batch.size());

            } catch (Exception exception) {
                CACHE_LOG.error(
                        "Failed to upload batch (exception type: {})",
                        exception.getClass().getSimpleName());
                throw new EmbeddingCacheOperationException(
                        "Failed to upload embedding batch starting at index " + batchStartIndex, exception);
            }
        }

        saveIncrementalCache();
        CACHE_LOG.info("Successfully uploaded {} embeddings to vector store", uploadedCount);
        return uploadedCount;
    }

    /**
     * Saves timestamped snapshot of current cache
     */
    public void saveSnapshot() throws IOException {
        String timestamp = LocalDateTime.now().format(CACHE_TIMESTAMP_FORMAT);
        String filename = String.format("embeddings_snapshot_%s.gz", timestamp);
        exportCache(filename);
    }

    /**
     * Returns cache statistics including hit rate and pending uploads.
     *
     * @return typed cache statistics
     */
    public CacheStats getCacheStats() {
        return new CacheStats(
                memoryCache.size(),
                memoryCache.values().stream()
                        .filter(EmbeddingCacheEntry::isUploaded)
                        .count(),
                memoryCache.values().stream()
                        .filter(cachedEmbedding -> !cachedEmbedding.isUploaded())
                        .count(),
                cacheHits.get(),
                cacheMisses.get(),
                getCacheHitRate(),
                cacheDir.toString());
    }

    /**
     * Embedding cache statistics.
     *
     * @param totalCached total number of cached embeddings
     * @param uploaded number of embeddings uploaded to vector store
     * @param pending number of embeddings waiting to be uploaded
     * @param cacheHits number of cache hits
     * @param cacheMisses number of cache misses
     * @param hitRate cache hit rate (0.0 to 1.0)
     * @param cacheDirectory path to cache directory
     */
    public record CacheStats(
            long totalCached,
            long uploaded,
            long pending,
            long cacheHits,
            long cacheMisses,
            double hitRate,
            String cacheDirectory) {}

    private void loadExistingCache() {
        Path latestCache = cacheDir.resolve(CACHE_FILE_NAME);
        if (Files.exists(latestCache)) {
            try {
                importCacheFromPath(latestCache);
                CACHE_LOG.info("Loaded existing cache with {} embeddings", memoryCache.size());
            } catch (Exception exception) {
                CACHE_LOG.error(
                        "Could not load existing cache (exception type: {})",
                        exception.getClass().getSimpleName());
                quarantineCorruptCache(latestCache, exception);
            }
        }
    }

    private void saveIncrementalCache() {
        try {
            exportCache(CACHE_FILE_NAME);
        } catch (IOException exception) {
            CACHE_LOG.error(
                    "Failed to save incremental cache (exception type: {})",
                    exception.getClass().getSimpleName());
            throw new EmbeddingCacheOperationException("Failed to save incremental cache", exception);
        }
    }

    private String generateCacheKey(Document doc) {
        if (doc == null) {
            throw new IllegalArgumentException("Document is required");
        }
        return generateCacheKey(doc.getText(), EmbeddingCacheMetadata.fromDocument(doc));
    }

    private String generateCacheKey(String content, EmbeddingCacheMetadata metadata) {
        StringBuilder key = new StringBuilder();
        String safeContent = content == null ? "" : content;
        key.append(safeContent.hashCode());
        if (metadata != null && !metadata.equals(EmbeddingCacheMetadata.empty())) {
            key.append("_").append(metadata.hashCode());
        }
        return key.toString();
    }

    private void importCacheFromPath(Path importPath) throws IOException {
        CACHE_LOG.info("Importing cached embeddings");

        synchronized (cacheFileLock) {
            try (InputStream fileInputStream = Files.newInputStream(importPath);
                    GZIPInputStream gzipInputStream = new GZIPInputStream(fileInputStream);
                    BufferedInputStream bufferedInputStream = new BufferedInputStream(gzipInputStream)) {

                List<EmbeddingCacheEntry> importedEmbeddings = cacheFileImporter.read(bufferedInputStream);

                if (importedEmbeddings == null) {
                    throw new IOException("Unexpected cache format; expected a list of embeddings");
                }

                for (EmbeddingCacheEntry cachedEmbedding : importedEmbeddings) {
                    String cacheKey = generateCacheKey(cachedEmbedding.getContent(), cachedEmbedding.getMetadata());
                    memoryCache.put(cacheKey, cachedEmbedding);
                }

                CACHE_LOG.info("Successfully imported {} embeddings", importedEmbeddings.size());
            }
        }
    }

    private void quarantineCorruptCache(Path cachePath, Exception exception) {
        if (cachePath == null || !Files.exists(cachePath)) {
            return;
        }
        String timestamp = LocalDateTime.now().format(CACHE_TIMESTAMP_FORMAT);
        String quarantineName = CORRUPT_CACHE_PREFIX + timestamp + ".gz";
        Path quarantinePath = cacheDir.resolve(quarantineName);
        try {
            Files.move(cachePath, quarantinePath);
            CACHE_LOG.warn(
                    "Moved invalid cache file to {} (reason: {})",
                    quarantinePath.getFileName(),
                    exception.getClass().getSimpleName());
        } catch (IOException moveException) {
            CACHE_LOG.error(
                    "Failed to move invalid cache file (exception type: {})",
                    moveException.getClass().getSimpleName());
        }
    }

    /**
     * Legacy Java-serialized cache entry used only to import historical caches created before the JSON format.
     */
    static final class CachedEmbedding implements Serializable {
        @Serial
        private static final long serialVersionUID = -4408107863391743604L;

        boolean uploaded;
        String content;
        LocalDateTime createdAt;
        float[] embedding;
        String id;
        Map<?, ?> metadata;

        CachedEmbedding() {}
    }

    private double getCacheHitRate() {
        int total = cacheHits.get() + cacheMisses.get();
        return total == 0 ? 0.0 : (double) cacheHits.get() / total;
    }
}
