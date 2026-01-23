package com.williamcallahan.javachat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Service for caching embeddings locally to reduce API calls and enable batch processing.
 * Saves embeddings to compressed files in data/embeddings-cache for later upload to Qdrant.
 */
@Service
public class EmbeddingCacheService {
    private static final Logger CACHE_LOG = LoggerFactory.getLogger("EMBEDDING_CACHE");
    
    private final Path cacheDir;
    private final EmbeddingModel embeddingModel;
    private final VectorStore vectorStore;
    /** In-memory cache for fast lookup of computed embeddings */
    private final Map<String, CachedEmbedding> memoryCache = new ConcurrentHashMap<>();
    private final AtomicInteger cacheHits = new AtomicInteger(0);
    private final AtomicInteger cacheMisses = new AtomicInteger(0);
    private final AtomicInteger embeddingsSinceLastSave = new AtomicInteger(0);
    private static final int AUTO_SAVE_THRESHOLD = 50; // Save every 50 embeddings
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    
    public EmbeddingCacheService(
            @Value("${app.embeddings.cache-dir:./data/embeddings-cache}") String cacheDir,
            EmbeddingModel embeddingModel,
            VectorStore vectorStore) throws IOException {
        this.cacheDir = Paths.get(cacheDir);
        this.embeddingModel = embeddingModel;
        this.vectorStore = vectorStore;
        Files.createDirectories(this.cacheDir);
        loadExistingCache();
        
        // Register shutdown hook to save cache on exit
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                scheduler.shutdown();
                CACHE_LOG.info("Saving cache before shutdown...");
                saveIncrementalCache();
                CACHE_LOG.info("Cache saved successfully. Total embeddings cached: {}", memoryCache.size());
            } catch (Exception e) {
                CACHE_LOG.error("Failed to save cache on shutdown: {}", e.getMessage());
            }
        }));
        
        // Start periodic save timer (every 2 minutes)
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (embeddingsSinceLastSave.get() > 0) {
                    CACHE_LOG.info("Periodic save: {} new embeddings since last save", embeddingsSinceLastSave.get());
                    saveIncrementalCache();
                    embeddingsSinceLastSave.set(0);
                }
            } catch (Exception e) {
                CACHE_LOG.error("Periodic save failed: {}", e.getMessage());
            }
        }, 2, 2, TimeUnit.MINUTES);
    }
    
    /**
     * Represents a cached embedding with its content and metadata
     */
    public static class CachedEmbedding implements Serializable {
        public String id;
        public String content;
        public float[] embedding;
        public Map<String, Object> metadata;
        public LocalDateTime createdAt;
        public boolean uploaded;
        
        public CachedEmbedding() {}
        
        public CachedEmbedding(String id, String content, float[] embedding, Map<String, Object> metadata) {
            this.id = id;
            this.content = content;
            this.embedding = embedding;
            this.metadata = metadata != null ? metadata : new HashMap<>();
            this.createdAt = LocalDateTime.now();
            this.uploaded = false;
        }
        
        /** Converts to Spring AI Document */
        public Document toDocument() {
            return new Document(content, metadata);
        }
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
        
        for (int i = 0; i < documents.size(); i++) {
            Document doc = documents.get(i);
            String cacheKey = generateCacheKey(doc);
            CachedEmbedding cached = memoryCache.get(cacheKey);
            
            if (cached != null) {
                embeddings.add(cached.embedding);
                cacheHits.incrementAndGet();
                CACHE_LOG.debug("Cache HIT for document: {}", doc.getId());
            } else {
                toCompute.add(doc);
                indexMapping.put(toCompute.size() - 1, i);
                embeddings.add(null);
                cacheMisses.incrementAndGet();
            }
        }
        
        if (!toCompute.isEmpty()) {
            CACHE_LOG.info("Computing {} new embeddings (cache hit rate: {:.1f}%)", 
                toCompute.size(), getCacheHitRate() * 100);
            
            List<String> texts = toCompute.stream()
                .map(Document::getText)
                .collect(Collectors.toList());
            
            EmbeddingResponse response = embeddingModel.embedForResponse(texts);
            List<float[]> computedEmbeddings = response.getResults().stream()
                .map(r -> r.getOutput())
                .collect(Collectors.toList());
            
            for (int i = 0; i < computedEmbeddings.size(); i++) {
                Document doc = toCompute.get(i);
                float[] embedding = computedEmbeddings.get(i);
                int originalIndex = indexMapping.get(i);
                embeddings.set(originalIndex, embedding);
                
                CachedEmbedding cached = new CachedEmbedding(
                    UUID.randomUUID().toString(),
                    doc.getText(),
                    embedding,
                    doc.getMetadata()
                );
                
                String cacheKey = generateCacheKey(doc);
                memoryCache.put(cacheKey, cached);
                
                // Auto-save every N embeddings
                if (embeddingsSinceLastSave.incrementAndGet() >= AUTO_SAVE_THRESHOLD) {
                    CACHE_LOG.info("Auto-saving cache after {} new embeddings...", AUTO_SAVE_THRESHOLD);
                    try {
                        saveIncrementalCache();
                        embeddingsSinceLastSave.set(0);
                        CACHE_LOG.info("Auto-save completed. Total cached: {}", memoryCache.size());
                    } catch (Exception e) {
                        CACHE_LOG.error("Auto-save failed: {}", e.getMessage());
                    }
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
     * Exports cache to compressed file
     * @param filename Name of export file
     */
    public void exportCache(String filename) throws IOException {
        Path exportPath = cacheDir.resolve(filename);
        CACHE_LOG.info("Exporting {} cached embeddings to: {}", memoryCache.size(), exportPath);
        
        try (OutputStream fos = Files.newOutputStream(exportPath);
             GZIPOutputStream gzos = new GZIPOutputStream(fos);
             ObjectOutputStream oos = new ObjectOutputStream(gzos)) {
            
            oos.writeObject(new ArrayList<>(memoryCache.values()));
            CACHE_LOG.info("Successfully exported cache to: {}", exportPath);
        }
    }
    
    /**
     * Imports embeddings from compressed file
     * @param filename Name of file to import
     */
    public final void importCache(String filename) throws IOException, ClassNotFoundException {
        Path importPath = cacheDir.resolve(filename);
        CACHE_LOG.info("Importing cached embeddings from: {}", importPath);
        
        try (InputStream fis = Files.newInputStream(importPath);
             GZIPInputStream gzis = new GZIPInputStream(fis);
             ObjectInputStream ois = new ObjectInputStream(gzis)) {
            
            @SuppressWarnings("unchecked")
            List<CachedEmbedding> imported = (List<CachedEmbedding>) ois.readObject();
            
            for (CachedEmbedding embedding : imported) {
                String cacheKey = generateCacheKey(embedding.content, embedding.metadata);
                memoryCache.put(cacheKey, embedding);
            }
            
            CACHE_LOG.info("Successfully imported {} embeddings", imported.size());
        }
    }
    
    /**
     * Uploads pending embeddings to Qdrant in batches
     * @param batchSize Number of embeddings per batch
     * @return Number of successfully uploaded embeddings
     */
    public int uploadPendingToVectorStore(int batchSize) {
        List<CachedEmbedding> pending = memoryCache.values().stream()
            .filter(e -> !e.uploaded)
            .collect(Collectors.toList());
        
        if (pending.isEmpty()) {
            CACHE_LOG.info("No pending embeddings to upload");
            return 0;
        }
        
        CACHE_LOG.info("Uploading {} pending embeddings to vector store in batches of {}", 
            pending.size(), batchSize);
        
        int uploaded = 0;
        for (int i = 0; i < pending.size(); i += batchSize) {
            List<CachedEmbedding> batch = pending.subList(i, 
                Math.min(i + batchSize, pending.size()));
            
            List<Document> documents = batch.stream()
                .map(CachedEmbedding::toDocument)
                .collect(Collectors.toList());
            
            try {
                vectorStore.add(documents);
                
                for (CachedEmbedding e : batch) {
                    e.uploaded = true;
                }
                
                uploaded += batch.size();
                CACHE_LOG.info("Uploaded batch {}/{} ({} embeddings)", 
                    (i / batchSize) + 1, 
                    (pending.size() + batchSize - 1) / batchSize,
                    batch.size());
                
            } catch (Exception e) {
                CACHE_LOG.error("Failed to upload batch starting at index {}: {}", i, e.getMessage());
            }
        }
        
        saveIncrementalCache();
        CACHE_LOG.info("Successfully uploaded {} embeddings to vector store", uploaded);
        return uploaded;
    }
    
    /**
     * Saves timestamped snapshot of current cache
     */
    public void saveSnapshot() throws IOException {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String filename = String.format("embeddings_snapshot_%s.gz", timestamp);
        exportCache(filename);
    }
    
    /**
     * Returns cache statistics including hit rate and pending uploads
     */
    public Map<String, Object> getCacheStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCached", memoryCache.size());
        stats.put("uploaded", memoryCache.values().stream().filter(e -> e.uploaded).count());
        stats.put("pending", memoryCache.values().stream().filter(e -> !e.uploaded).count());
        stats.put("cacheHits", cacheHits.get());
        stats.put("cacheMisses", cacheMisses.get());
        stats.put("hitRate", getCacheHitRate());
        stats.put("cacheDirectory", cacheDir.toString());
        return stats;
    }
    
    private void loadExistingCache() {
        Path latestCache = cacheDir.resolve("embeddings_cache.gz");
        if (Files.exists(latestCache)) {
            try {
                importCache("embeddings_cache.gz");
                CACHE_LOG.info("Loaded existing cache with {} embeddings", memoryCache.size());
            } catch (Exception e) {
                CACHE_LOG.warn("Could not load existing cache: {}", e.getMessage());
            }
        }
    }
    
    private void saveIncrementalCache() {
        try {
            exportCache("embeddings_cache.gz");
        } catch (IOException e) {
            CACHE_LOG.error("Failed to save incremental cache: {}", e.getMessage());
        }
    }
    
    private String generateCacheKey(Document doc) {
        return generateCacheKey(doc.getText(), doc.getMetadata());
    }
    
    private String generateCacheKey(String content, Map<String, Object> metadata) {
        StringBuilder key = new StringBuilder();
        key.append(content.hashCode());
        if (metadata != null && !metadata.isEmpty()) {
            key.append("_").append(metadata.hashCode());
        }
        return key.toString();
    }
    
    private double getCacheHitRate() {
        int total = cacheHits.get() + cacheMisses.get();
        return total == 0 ? 0.0 : (double) cacheHits.get() / total;
    }
}