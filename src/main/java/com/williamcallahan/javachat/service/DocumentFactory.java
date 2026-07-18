package com.williamcallahan.javachat.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Factory service for creating standardized Spring AI Document objects
 * with consistent metadata structure across the application.
 *
 * This eliminates code duplication in document creation patterns found in
 * DocsIngestionService and RetrievalService.
 */
@Service
public class DocumentFactory {
    private final ContentHasher hasher;

    /**
     * Creates the document factory with hash utilities for stable identifiers.
     *
     * @param hasher content hash helper
     */
    public DocumentFactory(ContentHasher hasher) {
        this.hasher = Objects.requireNonNull(hasher, "hasher");
    }

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
            String text, String url, String title, int chunkIndex, String packageName, String hash) {
        return createDocument(text, ChunkDocumentMetadata.withoutAnchor(url, title, chunkIndex, packageName, hash));
    }

    /**
     * Creates a Spring AI Document using the canonical metadata contract for one indexed chunk.
     *
     * <p>The structured contract keeps a Javadoc member anchor separate from its fragmentless page URL so
     * file pruning, chunk hashing, and citation rendering can each use the identity they require.</p>
     *
     * @param text chunk text that will be embedded
     * @param chunkDocumentMetadata canonical metadata for the indexed chunk
     * @return a document with the supplied metadata
     */
    public org.springframework.ai.document.Document createDocument(
            String text, ChunkDocumentMetadata chunkDocumentMetadata) {
        Objects.requireNonNull(text, "text");
        ChunkDocumentMetadata metadata = Objects.requireNonNull(chunkDocumentMetadata, "chunkDocumentMetadata");

        var document = createDocumentWithOptionalId(text, metadata.contentHash());
        document.getMetadata().put(QdrantPayloadFieldSchema.URL_FIELD, metadata.sourceUrl());
        document.getMetadata().put(QdrantPayloadFieldSchema.TITLE_FIELD, metadata.title());
        document.getMetadata().put(QdrantPayloadFieldSchema.CHUNK_INDEX_FIELD, metadata.chunkIndex());
        document.getMetadata().put(QdrantPayloadFieldSchema.PACKAGE_FIELD, metadata.packageName());
        document.getMetadata().put(QdrantPayloadFieldSchema.HASH_FIELD, metadata.contentHash());
        metadata.javadocAnchor()
                .ifPresent(anchor -> document.getMetadata().put(QdrantPayloadFieldSchema.ANCHOR_FIELD, anchor));
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
        Map<String, ?> metadata =
                Map.of(QdrantPayloadFieldSchema.URL_FIELD, url, QdrantPayloadFieldSchema.TITLE_FIELD, "Local Doc");

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
            String newText, Map<String, ?> existingMetadata, Map<String, ?> additionalMetadata) {

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
        doc.getMetadata().put(QdrantPayloadFieldSchema.PAGE_START_FIELD, pageStart);
        doc.getMetadata().put(QdrantPayloadFieldSchema.PAGE_END_FIELD, pageEnd);
        return doc;
    }

    /**
     * Extracts a metadata value as a string, returning empty string when absent.
     *
     * @param document the document to read metadata from
     * @param metadataKey the metadata key to look up
     * @return the metadata value as a string, or empty string when the key is absent or null
     */
    public static String metadataText(org.springframework.ai.document.Document document, String metadataKey) {
        Object metadataRaw = document.getMetadata().get(metadataKey);
        if (metadataRaw == null) {
            return "";
        }
        return metadataRaw.toString();
    }

    private org.springframework.ai.document.Document createDocumentWithOptionalId(String text, String hash) {
        if (hash == null || hash.isBlank()) {
            return new org.springframework.ai.document.Document(text);
        }
        String documentId = hasher.uuidFromHash(hash);
        return new org.springframework.ai.document.Document(documentId, text, new HashMap<>());
    }

    /**
     * Defines the complete metadata contract for one indexed document chunk.
     *
     * <p>The source URL remains fragmentless. A Javadoc member identifier, when present, is stored in
     * {@link QdrantPayloadFieldSchema#ANCHOR_FIELD} so citation construction can append it without changing
     * local storage or vector-pruning identities.</p>
     *
     * @param sourceUrl fragmentless source page URL
     * @param title human-readable page title
     * @param chunkIndex zero-based page-wide chunk position
     * @param packageName Java package name when applicable
     * @param contentHash deterministic hash of URL, chunk index, and text
     * @param javadocAnchor exact optional Javadoc DOM member identifier
     */
    public record ChunkDocumentMetadata(
            String sourceUrl,
            String title,
            int chunkIndex,
            String packageName,
            String contentHash,
            Optional<String> javadocAnchor) {

        /**
         * Validates the metadata values before they can enter the document/Qdrant round-trip.
         */
        public ChunkDocumentMetadata {
            sourceUrl = requireText(sourceUrl, "sourceUrl");
            title = Objects.requireNonNull(title, "title");
            if (chunkIndex < 0) {
                throw new IllegalArgumentException("chunkIndex must be non-negative");
            }
            packageName = Objects.requireNonNull(packageName, "packageName");
            contentHash = requireText(contentHash, "contentHash");
            javadocAnchor = Objects.requireNonNull(javadocAnchor, "javadocAnchor");
            javadocAnchor.ifPresent(ChunkDocumentMetadata::requireExactJavadocAnchor);
        }

        /**
         * Defines a document chunk that cites its containing page rather than a member fragment.
         *
         * @param sourceUrl fragmentless source page URL
         * @param title human-readable page title
         * @param chunkIndex zero-based page-wide chunk position
         * @param packageName Java package name when applicable
         * @param contentHash deterministic hash of URL, chunk index, and text
         * @return metadata with no Javadoc member anchor
         */
        public static ChunkDocumentMetadata withoutAnchor(
                String sourceUrl, String title, int chunkIndex, String packageName, String contentHash) {
            return new ChunkDocumentMetadata(sourceUrl, title, chunkIndex, packageName, contentHash, Optional.empty());
        }

        /**
         * Defines a document chunk that cites an exact member fragment on its source page.
         *
         * @param sourceUrl fragmentless source page URL
         * @param title human-readable page title
         * @param chunkIndex zero-based page-wide chunk position
         * @param packageName Java package name when applicable
         * @param contentHash deterministic hash of URL, chunk index, and text
         * @param exactJavadocAnchor exact DOM member identifier without a leading hash
         * @return metadata with the exact Javadoc member anchor
         */
        public static ChunkDocumentMetadata withAnchor(
                String sourceUrl,
                String title,
                int chunkIndex,
                String packageName,
                String contentHash,
                String exactJavadocAnchor) {
            return new ChunkDocumentMetadata(
                    sourceUrl,
                    title,
                    chunkIndex,
                    packageName,
                    contentHash,
                    Optional.of(requireExactJavadocAnchor(exactJavadocAnchor)));
        }

        private static String requireText(String text, String fieldName) {
            String requiredText = Objects.requireNonNull(text, fieldName);
            if (requiredText.isBlank()) {
                throw new IllegalArgumentException(fieldName + " must not be blank");
            }
            return requiredText;
        }

        private static String requireExactJavadocAnchor(String exactJavadocAnchor) {
            String requiredAnchor = requireText(exactJavadocAnchor, "exactJavadocAnchor");
            if (!requiredAnchor.equals(requiredAnchor.trim()) || requiredAnchor.indexOf('#') >= 0) {
                throw new IllegalArgumentException("exactJavadocAnchor must be an unpadded DOM identifier without '#'");
            }
            return requiredAnchor;
        }
    }
}
