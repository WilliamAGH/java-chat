package com.williamcallahan.javachat.service;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Factory service for creating standardized Spring AI Document objects
 * with consistent metadata structure across the application.
 *
 * This eliminates code duplication in document creation patterns found in
 * DocsIngestionService and RetrievalService.
 */
@Service
public class DocumentFactory {

    /**
     * Creates a Spring AI Document with standardized metadata structure.
     *
     * @param text The document text content
     * @param url The source URL of the document
     * @param title The document title
     * @param chunkIndex The chunk index within the document
     * @param packageName The Java package name (if applicable)
     * @param hash The content hash for deduplication
     * @return A properly configured Spring AI Document
     */
    public org.springframework.ai.document.Document createDocument(
            String text,
            String url,
            String title,
            int chunkIndex,
            String packageName,
            String hash) {

        // Create standardized metadata map
        Map<String, Object> metadata = Map.of(
            "url", url,
            "title", title,
            "chunkIndex", chunkIndex,
            "package", packageName,
            "hash", hash
        );

        // Create and configure the document
        var document = new org.springframework.ai.document.Document(text);
        // Persist hash alongside metadata (used for audit and dedup checks)
        document.getMetadata().putAll(metadata);

        return document;
    }

    /**
     * Creates a Spring AI Document for local search results.
     *
     * @param text The document text content
     * @param url The source URL
     * @return A properly configured Spring AI Document
     */
    public org.springframework.ai.document.Document createLocalDocument(String text, String url) {
        Map<String, Object> metadata = Map.of(
            "url", url,
            "title", "Local Doc"
        );

        var document = new org.springframework.ai.document.Document(text);
        document.getMetadata().putAll(metadata);

        return document;
    }
}
