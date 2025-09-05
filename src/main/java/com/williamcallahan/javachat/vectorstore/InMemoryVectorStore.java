package com.williamcallahan.javachat.vectorstore;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * In-memory vector store implementation as a fallback when Qdrant is unavailable.
 * This provides basic semantic search capabilities using cosine similarity.
 */
public class InMemoryVectorStore implements VectorStore {
    
    private static final Logger logger = LoggerFactory.getLogger(InMemoryVectorStore.class);
    
    private final EmbeddingModel embeddingModel;
    private final ConcurrentHashMap<String, DocumentWithEmbedding> documents = new ConcurrentHashMap<>();
    
    public InMemoryVectorStore(EmbeddingModel embeddingModel) {
        this.embeddingModel = embeddingModel;
        logger.info("Using in-memory vector store (fallback mode - Qdrant unavailable)");
    }
    
    @Override
    public void add(List<Document> documents) {
        logger.debug("Adding {} documents to in-memory store", documents.size());
        
        // Generate embeddings for documents
        List<String> contents = documents.stream()
            .map(doc -> doc.getText())
            .collect(Collectors.toList());
        
        try {
            List<float[]> embeddings = embeddingModel.embed(contents);
            
            for (int i = 0; i < documents.size(); i++) {
                Document doc = documents.get(i);
                float[] embedding = embeddings.get(i);
                
                String id = doc.getId();
                if (id == null || id.isEmpty()) {
                    id = "doc_" + System.nanoTime() + "_" + i;
                }
                
                this.documents.put(id, new DocumentWithEmbedding(doc, embedding));
            }
            
            logger.info("Successfully added {} documents to in-memory store", documents.size());
            
        } catch (Exception e) {
            logger.error("Failed to generate embeddings for documents", e);
            // Store documents without embeddings as fallback
            for (Document doc : documents) {
                String id = doc.getId();
                if (id == null || id.isEmpty()) {
                    id = "doc_" + System.nanoTime();
                }
                this.documents.put(id, new DocumentWithEmbedding(doc, null));
            }
        }
    }
    
    @Override
    public void delete(List<String> idList) {
        logger.debug("Deleting {} documents from in-memory store", idList.size());
        
        for (String id : idList) {
            documents.remove(id);
        }
    }
    
    @Override
    public void delete(Filter.Expression filter) {
        logger.warn("Filter-based deletion not fully supported in InMemoryVectorStore");
        // Not implemented - this is a simple fallback store
    }
    
    @Override
    public List<Document> similaritySearch(SearchRequest request) {
        logger.debug("Performing similarity search with query: {}", request.getQuery());
        
        if (documents.isEmpty()) {
            logger.warn("No documents in in-memory store");
            return Collections.emptyList();
        }
        
        try {
            // Generate embedding for query
            float[] queryEmbedding = embeddingModel.embed(request.getQuery());
            
            // Calculate similarities and sort
            List<ScoredDocument> scoredDocs = new ArrayList<>();
            
            for (DocumentWithEmbedding docWithEmb : documents.values()) {
                if (docWithEmb.embedding != null) {
                    double similarity = cosineSimilarity(queryEmbedding, docWithEmb.embedding);
                    
                    // Apply similarity threshold if specified
                    double threshold = request.getSimilarityThreshold();
                    if (threshold <= 0 || similarity >= threshold) {
                        scoredDocs.add(new ScoredDocument(docWithEmb.document, similarity));
                    }
                }
            }
            
            // Sort by similarity (descending)
            scoredDocs.sort((a, b) -> Double.compare(b.similarity, a.similarity));
            
            // Limit results
            int topK = request.getTopK();
            List<Document> results = scoredDocs.stream()
                .limit(topK)
                .map(sd -> sd.document)
                .collect(Collectors.toList());
            
            logger.info("Found {} similar documents (requested top {})", results.size(), topK);
            return results;
            
        } catch (Exception e) {
            logger.error("Failed to perform similarity search", e);
            // Fallback to returning random documents
            return documents.values().stream()
                .limit(request.getTopK())
                .map(d -> d.document)
                .collect(Collectors.toList());
        }
    }
    
    /**
     * Calculate cosine similarity between two vectors.
     */
    private double cosineSimilarity(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vectors must have same dimension");
        }
        
        double dotProduct = 0.0;
        double normA = 0.0;
        double normB = 0.0;
        
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        
        if (normA == 0.0 || normB == 0.0) {
            return 0.0;
        }
        
        return dotProduct / (Math.sqrt(normA) * Math.sqrt(normB));
    }
    
    /**
     * Get the total number of documents in the store.
     */
    public int size() {
        return documents.size();
    }
    
    /**
     * Clear all documents from the store.
     */
    public void clear() {
        documents.clear();
        logger.info("Cleared all documents from in-memory store");
    }
    
    /**
     * Internal class to store document with its embedding.
     */
    private static class DocumentWithEmbedding {
        final Document document;
        final float[] embedding;
        
        DocumentWithEmbedding(Document document, float[] embedding) {
            this.document = document;
            this.embedding = embedding;
        }
    }
    
    /**
     * Internal class to hold document with similarity score.
     */
    private static class ScoredDocument {
        final Document document;
        final double similarity;
        
        ScoredDocument(Document document, double similarity) {
            this.document = document;
            this.similarity = similarity;
        }
    }
}