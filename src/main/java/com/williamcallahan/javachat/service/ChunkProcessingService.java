package com.williamcallahan.javachat.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
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
    private final HashIngestionLookup hashIngestionLookup;
    private final ChunkTextStore chunkTextStore;
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
        LocalStoreService requiredLocalStore = Objects.requireNonNull(localStore, "localStore");
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.documentFactory = Objects.requireNonNull(documentFactory, "documentFactory");
        this.hashIngestionLookup = new HashIngestionLookup() {
            @Override
            public boolean isHashIngested(String hash) {
                return requiredLocalStore.isHashIngested(hash);
            }

            @Override
            public boolean hasMetadataChanged(String hash, String title, String packageName) {
                return requiredLocalStore.hasHashMetadataChanged(hash, title, packageName);
            }
        };
        this.chunkTextStore = requiredLocalStore::saveChunkText;
        this.pdfExtractor = Objects.requireNonNull(pdfExtractor, "pdfExtractor");
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
    public ChunkProcessingOutcome processAndStoreChunks(String text, String url, String title, String packageName)
            throws IOException {

        // Chunk the text with standard parameters
        List<String> chunks = chunker.chunkByTokens(text, DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
        List<Document> documents = new ArrayList<>();
        List<String> allChunkHashes = new ArrayList<>(Math.max(0, chunks.size()));
        int skipped = 0;

        for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
            String chunkText = chunks.get(chunkIndex);

            // Generate standardized hash
            String hash = hasher.generateChunkHash(url, chunkIndex, chunkText);
            allChunkHashes.add(hash);

            // Skip if already processed (deduplication)
            boolean hashAlreadyIngested = hashIngestionLookup.isHashIngested(hash);
            if (hashAlreadyIngested && !hashIngestionLookup.hasMetadataChanged(hash, title, packageName)) {
                skipped++;
                continue;
            }

            // Create document with standardized metadata
            Document document = documentFactory.createDocument(chunkText, url, title, chunkIndex, packageName, hash);

            documents.add(document);

            // Store chunk text but DON'T mark as ingested yet
            // Will be marked after successful vector store addition
            chunkTextStore.saveChunkText(url, chunkIndex, chunkText, hash);
        }

        return new ChunkProcessingOutcome(List.copyOf(documents), List.copyOf(allChunkHashes), chunks.size(), skipped);
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
    public ChunkProcessingOutcome processAndStoreChunksForce(String text, String url, String title, String packageName)
            throws IOException {

        List<String> chunks = chunker.chunkByTokens(text, DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
        List<Document> documents = new ArrayList<>();
        List<String> allChunkHashes = new ArrayList<>(Math.max(0, chunks.size()));

        for (int chunkIndex = 0; chunkIndex < chunks.size(); chunkIndex++) {
            String chunkText = chunks.get(chunkIndex);
            String hash = hasher.generateChunkHash(url, chunkIndex, chunkText);
            allChunkHashes.add(hash);
            Document document = documentFactory.createDocument(chunkText, url, title, chunkIndex, packageName, hash);
            documents.add(document);
            chunkTextStore.saveChunkText(url, chunkIndex, chunkText, hash);
        }

        return new ChunkProcessingOutcome(List.copyOf(documents), List.copyOf(allChunkHashes), chunks.size(), 0);
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
    public ChunkProcessingOutcome processPdfAndStoreWithPages(
            java.nio.file.Path pdfPath, String url, String title, String packageName) throws java.io.IOException {

        List<String> pages = pdfExtractor.extractPageTexts(pdfPath);
        List<Document> pageDocuments = new ArrayList<>();
        List<String> allChunkHashes = new ArrayList<>();
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
                allChunkHashes.add(hash);
                boolean hashAlreadyIngested = hashIngestionLookup.isHashIngested(hash);
                if (!hashAlreadyIngested || hashIngestionLookup.hasMetadataChanged(hash, title, packageName)) {
                    Document pageDocument = documentFactory.createDocumentWithPages(
                            chunkText, url, title, globalIndex, packageName, hash, pageIndex + 1, pageIndex + 1);
                    pageDocuments.add(pageDocument);
                    chunkTextStore.saveChunkText(url, globalIndex, chunkText, hash);
                } else {
                    skipped++;
                }
                globalIndex++;
            }
        }
        return new ChunkProcessingOutcome(
                List.copyOf(pageDocuments), List.copyOf(allChunkHashes), totalChunks, skipped);
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
    public ChunkProcessingOutcome processPdfAndStoreWithPagesForce(
            java.nio.file.Path pdfPath, String url, String title, String packageName) throws java.io.IOException {

        List<String> pages = pdfExtractor.extractPageTexts(pdfPath);
        List<Document> pageDocuments = new ArrayList<>();
        List<String> allChunkHashes = new ArrayList<>();
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
                allChunkHashes.add(hash);
                Document doc = documentFactory.createDocumentWithPages(
                        chunkText, url, title, globalIndex, packageName, hash, pageIndex + 1, pageIndex + 1);
                pageDocuments.add(doc);
                chunkTextStore.saveChunkText(url, globalIndex, chunkText, hash);
                globalIndex++;
            }
        }
        return new ChunkProcessingOutcome(List.copyOf(pageDocuments), List.copyOf(allChunkHashes), totalChunks, 0);
    }

    public record ChunkProcessingOutcome(
            List<Document> documents, List<String> allChunkHashes, int totalChunks, int skippedChunks) {
        public ChunkProcessingOutcome {
            documents = documents == null ? List.of() : List.copyOf(documents);
            allChunkHashes = allChunkHashes == null ? List.of() : List.copyOf(allChunkHashes);
            if (totalChunks < 0) {
                throw new IllegalArgumentException("totalChunks must be non-negative");
            }
            if (skippedChunks < 0) {
                throw new IllegalArgumentException("skippedChunks must be non-negative");
            }
            if (skippedChunks > totalChunks) {
                throw new IllegalArgumentException("skippedChunks must not exceed totalChunks");
            }
            if (allChunkHashes.size() != totalChunks) {
                throw new IllegalArgumentException("allChunkHashes must have one entry per chunk");
            }
        }

        /**
         * Returns true when no chunks were generated for the source content.
         */
        public boolean generatedNoChunks() {
            return totalChunks == 0;
        }

        /**
         * Returns true when every generated chunk was skipped as already ingested.
         */
        public boolean skippedAllChunks() {
            return totalChunks > 0 && skippedChunks == totalChunks;
        }
    }

    /**
     * Reads whether a chunk hash has already been indexed.
     */
    private interface HashIngestionLookup {
        /**
         * Returns true when the chunk hash already has an ingest marker.
         */
        boolean isHashIngested(String hash);

        /**
         * Returns true when stored metadata for an ingested hash differs from current metadata.
         */
        boolean hasMetadataChanged(String hash, String title, String packageName);
    }

    /**
     * Persists parsed chunk text for later incremental ingestion and audit workflows.
     */
    @FunctionalInterface
    private interface ChunkTextStore {
        /**
         * Saves parsed chunk text with deterministic chunk metadata.
         */
        void saveChunkText(String url, int index, String text, String hash) throws IOException;
    }
}
