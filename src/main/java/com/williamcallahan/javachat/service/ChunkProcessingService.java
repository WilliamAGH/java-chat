package com.williamcallahan.javachat.service;

import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for processing text into chunks with consistent metadata and storage.
 * This consolidates chunking logic that was duplicated in DocsIngestionService.
 */
@Service
public class ChunkProcessingService {

    private final Chunker chunker;
    private final ContentHasher hasher;
    private final DocumentFactory documentFactory;
    private final LocalStoreService localStore;

    public ChunkProcessingService(
            Chunker chunker,
            ContentHasher hasher,
            DocumentFactory documentFactory,
            LocalStoreService localStore) {
        this.chunker = chunker;
        this.hasher = hasher;
        this.documentFactory = documentFactory;
        this.localStore = localStore;
    }

    /**
     * Processes text into chunks, creates documents, and stores them.
     * This eliminates the duplicated chunking logic from DocsIngestionService.
     *
     * @param text The full text to chunk
     * @param url The source URL
     * @param title The document title
     * @param packageName The Java package name (if applicable)
     * @return List of created documents
     * @throws IOException If file operations fail
     */
    public List<Document> processAndStoreChunks(
            String text,
            String url,
            String title,
            String packageName) throws IOException {

        // Chunk the text with standard parameters
        List<String> chunks = chunker.chunkByTokens(text, 900, 150);
        List<Document> documents = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = chunks.get(i);

            // Generate standardized hash
            String hash = hasher.generateChunkHash(url, i, chunkText);

            // Skip if already processed (deduplication)
            if (localStore.isHashIngested(hash)) {
                continue;
            }

            // Create document with standardized metadata
            Document document = documentFactory.createDocument(
                chunkText, url, title, i, packageName, hash);

            documents.add(document);

            // Store chunk text but DON'T mark as ingested yet
            // Will be marked after successful vector store addition
            localStore.saveChunkText(url, i, chunkText, hash);
        }

        return documents;
    }

    /**
     * Processes text into chunks without storing them (for retrieval operations).
     *
     * @param text The text to chunk
     * @param url The source URL
     * @param title The document title
     * @param packageName The Java package name
     * @return List of documents ready for vector storage
     */
    public List<Document> processChunksOnly(
            String text,
            String url,
            String title,
            String packageName) {

        List<String> chunks = chunker.chunkByTokens(text, 900, 150);
        List<Document> documents = new ArrayList<>();

        for (int i = 0; i < chunks.size(); i++) {
            String chunkText = chunks.get(i);
            String hash = hasher.generateChunkHash(url, i, chunkText);

            Document document = documentFactory.createDocument(
                chunkText, url, title, i, packageName, hash);

            documents.add(document);
        }

        return documents;
    }
}