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
    private final PdfContentExtractor pdfExtractor;

    public ChunkProcessingService(
            Chunker chunker,
            ContentHasher hasher,
            DocumentFactory documentFactory,
            LocalStoreService localStore,
            PdfContentExtractor pdfExtractor) {
        this.chunker = chunker;
        this.hasher = hasher;
        this.documentFactory = documentFactory;
        this.localStore = localStore;
        this.pdfExtractor = pdfExtractor;
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

    /**
     * Process a PDF by splitting into per-page chunks with token-aware splitting
     * for long pages. Adds pageStart/pageEnd metadata to each resulting document.
     */
    public List<Document> processPdfAndStoreWithPages(
            java.nio.file.Path pdfPath,
            String url,
            String title,
            String packageName) throws java.io.IOException {

        List<String> pages = pdfExtractor.extractPageTexts(pdfPath);
        List<Document> out = new ArrayList<>();
        int globalIndex = 0;

        for (int i = 0; i < pages.size(); i++) {
            String pageText = pages.get(i);
            if (pageText == null) pageText = "";

            // Split pageText by tokens if longer than window; no overlap for simplicity
            List<String> sub = chunker.chunkByTokens(pageText, 900, 0);
            for (String chunkText : sub) {
                String hash = hasher.generateChunkHash(url, globalIndex, chunkText);
                if (!localStore.isHashIngested(hash)) {
                    Document doc = documentFactory.createDocumentWithPages(
                            chunkText,
                            url,
                            title,
                            globalIndex,
                            packageName,
                            hash,
                            i + 1,
                            i + 1);
                    out.add(doc);
                    localStore.saveChunkText(url, globalIndex, chunkText, hash);
                }
                globalIndex++;
            }
        }
        return out;
    }
}
