package com.williamcallahan.javachat.service;

import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

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

        Map<String, ?> metadata = Map.of(
            "url", url,
            "title", title,
            "chunkIndex", chunkIndex,
            "package", packageName,
            "hash", hash
        );

        // Create and configure the document
        var document = createDocumentWithOptionalId(text, hash);
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
        Map<String, ?> metadata = Map.of(
            "url", url,
            "title", "Local Doc"
        );

        var document = new org.springframework.ai.document.Document(text);
        document.getMetadata().putAll(metadata);

        return document;
    }

    /**
     * Creates a Spring AI Document with new text content while preserving existing metadata.
     * Used for operations like truncation where the text changes but metadata should be retained.
     *
     * @param newText The new text content (required)
     * @param existingMetadata The metadata to preserve, copied not mutated (required, use Map.of() if empty)
     * @param additionalMetadata Entries to merge, can override existing (required, use Map.of() if empty)
     * @return A properly configured Spring AI Document with preserved metadata
     * @throws NullPointerException if any parameter is null
     */
    public org.springframework.ai.document.Document createWithPreservedMetadata(
            String newText,
            Map<String, ?> existingMetadata,
            Map<String, ?> additionalMetadata) {

        Objects.requireNonNull(newText, "newText must not be null");
        Objects.requireNonNull(existingMetadata, "existingMetadata must not be null; use Map.of() for empty");
        Objects.requireNonNull(additionalMetadata, "additionalMetadata must not be null; use Map.of() for empty");

        var document = new org.springframework.ai.document.Document(newText);
        document.getMetadata().putAll(existingMetadata);
        document.getMetadata().putAll(additionalMetadata);

        return document;
    }

    /**
     * Creates a Spring AI Document with page range metadata for PDFs.
     */
    public org.springframework.ai.document.Document createDocumentWithPages(
            String text,
            String url,
            String title,
            int chunkIndex,
            String packageName,
            String hash,
            int pageStart,
            int pageEnd) {
        var doc = createDocument(text, url, title, chunkIndex, packageName, hash);
        doc.getMetadata().put("pageStart", pageStart);
        doc.getMetadata().put("pageEnd", pageEnd);
        return doc;
    }

    private org.springframework.ai.document.Document createDocumentWithOptionalId(String text, String hash) {
        if (hash == null || hash.isBlank()) {
            return new org.springframework.ai.document.Document(text);
        }
        return new org.springframework.ai.document.Document(hash, text, new HashMap<>());
    }
}
