package com.williamcallahan.javachat.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * Service for processing text into chunks with consistent metadata and storage.
 * This consolidates chunking logic that was duplicated in DocsIngestionService.
 */
@Service
public class ChunkProcessingService {

    private static final int DEFAULT_CHUNK_SIZE = 900;
    private static final int DEFAULT_CHUNK_OVERLAP = 150;
    private static final int PDF_PAGE_CHUNK_OVERLAP = 0;

    private final Chunker chunker;
    private final ContentHasher hasher;
    private final DocumentFactory documentFactory;
    private final LocalStoreService localStore;
    private final PdfContentExtractor pdfExtractor;

    /**
     * Creates the chunking pipeline with storage and hashing dependencies.
     */
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
     * @return Chunk processing outcome including dedup skip counts
     * @throws IOException If file operations fail
     */
    ChunkProcessingOutcome processAndStoreChunks(String text, String url, String title, String packageName)
            throws IOException {

        // Chunk the text with standard parameters
        List<String> chunks = chunker.chunkByTokens(text, DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
        List<Document> documents = new ArrayList<>();
        int skipped = 0;

        for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
            String chunkText = chunks.get(chunkIndex);

            // Generate standardized hash
            String hash = hasher.generateChunkHash(url, chunkIndex, chunkText);

            // Skip if already processed (deduplication)
            if (localStore.isHashIngested(hash)) {
                skipped++;
                continue;
            }

            // Create document with standardized metadata
            Document document = documentFactory.createDocument(chunkText, url, title, chunkIndex, packageName, hash);

            documents.add(document);

            // Store chunk text but DON'T mark as ingested yet
            // Will be marked after successful vector store addition
            localStore.saveChunkText(url, chunkIndex, chunkText, hash);
        }

        return new ChunkProcessingOutcome(List.copyOf(documents), chunks.size(), skipped);
    }

    /**
     * Processes text into chunks and stores them, ignoring existing ingest markers.
     *
     * This is used when a source file has changed and the previous vectors have been deleted.
     * In that scenario we must re-create all chunk documents, even if local ingest markers
     * are present from a prior run.
     *
     * @param text The full text to chunk
     * @param url The source URL
     * @param title The document title
     * @param packageName The Java package name (if applicable)
     * @return chunk processing outcome, including total chunk count
     * @throws IOException If file operations fail
     */
    ChunkProcessingOutcome processAndStoreChunksForce(String text, String url, String title, String packageName)
            throws IOException {

        List<String> chunks = chunker.chunkByTokens(text, DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
        List<Document> documents = new ArrayList<>();

        for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
            String chunkText = chunks.get(chunkIndex);
            String hash = hasher.generateChunkHash(url, chunkIndex, chunkText);
            Document document = documentFactory.createDocument(chunkText, url, title, chunkIndex, packageName, hash);
            documents.add(document);
            localStore.saveChunkText(url, chunkIndex, chunkText, hash);
        }

        return new ChunkProcessingOutcome(List.copyOf(documents), chunks.size(), 0);
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
    public List<Document> processChunksOnly(String text, String url, String title, String packageName) {

        List<String> chunks = chunker.chunkByTokens(text, DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
        List<Document> documents = new ArrayList<>();

        for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
            String chunkText = chunks.get(chunkIndex);
            String hash = hasher.generateChunkHash(url, chunkIndex, chunkText);

            Document document = documentFactory.createDocument(chunkText, url, title, chunkIndex, packageName, hash);

            documents.add(document);
        }

        return documents;
    }

    /**
     * Process a PDF by splitting into per-page chunks with token-aware splitting
     * for long pages. Adds pageStart/pageEnd metadata to each resulting document.
     */
    ChunkProcessingOutcome processPdfAndStoreWithPages(
            java.nio.file.Path pdfPath, String url, String title, String packageName) throws java.io.IOException {

        List<String> pages = pdfExtractor.extractPageTexts(pdfPath);
        List<Document> pageDocuments = new ArrayList<>();
        int globalIndex = 0;
        int totalChunks = 0;
        int skipped = 0;

        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            String pageText = pages.get(pageIndex);
            if (pageText == null) pageText = "";

            // Split pageText by tokens if longer than window; no overlap for simplicity
            List<String> pageChunks = chunker.chunkByTokens(pageText, DEFAULT_CHUNK_SIZE, PDF_PAGE_CHUNK_OVERLAP);
            for (String chunkText : pageChunks) {
                totalChunks++;
                String hash = hasher.generateChunkHash(url, globalIndex, chunkText);
                if (!localStore.isHashIngested(hash)) {
                    Document doc = documentFactory.createDocumentWithPages(
                            chunkText, url, title, globalIndex, packageName, hash, pageIndex + 1, pageIndex + 1);
                    pageDocuments.add(doc);
                    localStore.saveChunkText(url, globalIndex, chunkText, hash);
                } else {
                    skipped++;
                }
                globalIndex++;
            }
        }
        return new ChunkProcessingOutcome(List.copyOf(pageDocuments), totalChunks, skipped);
    }

    /**
     * Processes a PDF into per-page chunks and stores them, ignoring existing ingest markers.
     *
     * This is used when a source PDF has changed and the previous vectors have been deleted.
     *
     * @param pdfPath The PDF path
     * @param url The source URL
     * @param title The document title
     * @param packageName The Java package name (if applicable)
     * @return chunk processing outcome, including total chunk count
     * @throws IOException if PDF extraction or file operations fail
     */
    ChunkProcessingOutcome processPdfAndStoreWithPagesForce(
            java.nio.file.Path pdfPath, String url, String title, String packageName) throws java.io.IOException {

        List<String> pages = pdfExtractor.extractPageTexts(pdfPath);
        List<Document> pageDocuments = new ArrayList<>();
        int globalIndex = 0;
        int totalChunks = 0;

        for (int pageIndex = 0; pageIndex < pages.size(); pageIndex++) {
            String pageText = pages.get(pageIndex);
            if (pageText == null) {
                pageText = "";
            }

            List<String> pageChunks = chunker.chunkByTokens(pageText, DEFAULT_CHUNK_SIZE, PDF_PAGE_CHUNK_OVERLAP);
            for (String chunkText : pageChunks) {
                totalChunks++;
                String hash = hasher.generateChunkHash(url, globalIndex, chunkText);
                Document doc = documentFactory.createDocumentWithPages(
                        chunkText, url, title, globalIndex, packageName, hash, pageIndex + 1, pageIndex + 1);
                pageDocuments.add(doc);
                localStore.saveChunkText(url, globalIndex, chunkText, hash);
                globalIndex++;
            }
        }
        return new ChunkProcessingOutcome(List.copyOf(pageDocuments), totalChunks, 0);
    }

    record ChunkProcessingOutcome(List<Document> documents, int totalChunks, int skippedChunks) {
        ChunkProcessingOutcome {
            documents = documents == null ? List.of() : List.copyOf(documents);
            if (totalChunks < 0) {
                throw new IllegalArgumentException("totalChunks must be non-negative");
            }
            if (skippedChunks < 0) {
                throw new IllegalArgumentException("skippedChunks must be non-negative");
            }
        }

        boolean generatedNoChunks() {
            return totalChunks == 0;
        }

        boolean skippedAllChunks() {
            return totalChunks > 0 && documents.isEmpty();
        }
    }
}
