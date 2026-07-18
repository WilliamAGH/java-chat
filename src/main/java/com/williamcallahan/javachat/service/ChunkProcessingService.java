package com.williamcallahan.javachat.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
    private static final String JAVADOC_MEMBER_CHUNK_HASH_VERSION = "java-api-member-chunk-v1";
    private static final char JAVADOC_MEMBER_CHUNK_HASH_FIELD_SEPARATOR = '\u0000';

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
        return processAndStoreSegmentBatch(
                url, title, packageName, List.of(ChunkSegment.withoutJavadocAnchor(text)), false);
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
        return processAndStoreSegmentBatch(
                url, title, packageName, List.of(ChunkSegment.withoutJavadocAnchor(text)), true);
    }

    /**
     * Processes a Javadoc page's overview and member sections into page-wide indexed chunks.
     *
     * <p>Each section retains the page URL for stable hashing and stale-vector pruning. Member sections carry
     * their exact DOM identifier as separate metadata so retrieval can construct a precise citation fragment.</p>
     *
     * @param javaApiPage page and section contract produced by the Java API extractor
     * @return chunk processing outcome including dedup skip counts
     * @throws IOException when parsed chunk text cannot be persisted
     */
    public ChunkProcessingOutcome processAndStoreJavaApiPage(JavaApiPage javaApiPage) throws IOException {
        JavaApiPage requiredPage = Objects.requireNonNull(javaApiPage, "javaApiPage");
        return processAndStoreSegmentBatch(
                requiredPage.sourceUrl(),
                requiredPage.title(),
                requiredPage.packageName(),
                requiredPage.toChunkSegments(),
                false);
    }

    /**
     * Processes a Javadoc page while ignoring prior chunk markers after its vectors were pruned.
     *
     * @param javaApiPage page and section contract produced by the Java API extractor
     * @return chunk processing outcome containing every generated chunk
     * @throws IOException when parsed chunk text cannot be persisted
     */
    public ChunkProcessingOutcome processAndStoreJavaApiPageForce(JavaApiPage javaApiPage) throws IOException {
        JavaApiPage requiredPage = Objects.requireNonNull(javaApiPage, "javaApiPage");
        return processAndStoreSegmentBatch(
                requiredPage.sourceUrl(),
                requiredPage.title(),
                requiredPage.packageName(),
                requiredPage.toChunkSegments(),
                true);
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

    private ChunkProcessingOutcome processAndStoreSegmentBatch(
            String url, String title, String packageName, List<ChunkSegment> chunkSegments, boolean force)
            throws IOException {
        List<Document> documents = new ArrayList<>();
        List<String> allChunkHashes = new ArrayList<>();
        int skippedChunks = 0;
        int globalChunkIndex = 0;

        for (ChunkSegment chunkSegment : chunkSegments) {
            List<String> segmentChunks =
                    chunker.chunkByTokens(chunkSegment.text(), DEFAULT_CHUNK_SIZE, DEFAULT_CHUNK_OVERLAP);
            for (String chunkText : segmentChunks) {
                String contentHash = generateChunkContentHash(chunkSegment, url, globalChunkIndex, chunkText);
                allChunkHashes.add(contentHash);
                boolean alreadyIngested = !force && hashIngestionLookup.isHashIngested(contentHash);
                boolean metadataChanged =
                        alreadyIngested && hashIngestionLookup.hasMetadataChanged(contentHash, title, packageName);
                if (!force && alreadyIngested && !metadataChanged) {
                    skippedChunks++;
                    globalChunkIndex++;
                    continue;
                }

                DocumentFactory.ChunkDocumentMetadata chunkDocumentMetadata =
                        chunkSegment.toDocumentMetadata(url, title, globalChunkIndex, packageName, contentHash);
                documents.add(documentFactory.createDocument(chunkText, chunkDocumentMetadata));
                chunkTextStore.saveChunkText(url, globalChunkIndex, chunkText, contentHash);
                globalChunkIndex++;
            }
        }

        return new ChunkProcessingOutcome(
                List.copyOf(documents), List.copyOf(allChunkHashes), globalChunkIndex, skippedChunks);
    }

    private String generateChunkContentHash(
            ChunkSegment chunkSegment, String sourceUrl, int chunkIndex, String chunkText) {
        return chunkSegment
                .javadocAnchor()
                .map(exactJavadocAnchor -> hasher.sha256(
                        javadocMemberChunkHashInput(sourceUrl, chunkIndex, chunkText, exactJavadocAnchor)))
                .orElseGet(() -> hasher.generateChunkHash(sourceUrl, chunkIndex, chunkText));
    }

    private static String javadocMemberChunkHashInput(
            String sourceUrl, int chunkIndex, String chunkText, String exactJavadocAnchor) {
        return new StringBuilder(JAVADOC_MEMBER_CHUNK_HASH_VERSION)
                .append(JAVADOC_MEMBER_CHUNK_HASH_FIELD_SEPARATOR)
                .append(sourceUrl.length())
                .append(JAVADOC_MEMBER_CHUNK_HASH_FIELD_SEPARATOR)
                .append(sourceUrl)
                .append(JAVADOC_MEMBER_CHUNK_HASH_FIELD_SEPARATOR)
                .append(chunkIndex)
                .append(JAVADOC_MEMBER_CHUNK_HASH_FIELD_SEPARATOR)
                .append(exactJavadocAnchor.length())
                .append(JAVADOC_MEMBER_CHUNK_HASH_FIELD_SEPARATOR)
                .append(exactJavadocAnchor)
                .append(JAVADOC_MEMBER_CHUNK_HASH_FIELD_SEPARATOR)
                .append(chunkText.length())
                .append(JAVADOC_MEMBER_CHUNK_HASH_FIELD_SEPARATOR)
                .append(chunkText)
                .toString();
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
     * Describes a canonical Java API page and the independent sections that compose it.
     *
     * <p>The source URL must not include a fragment because local storage, deterministic hashes, and stale vector
     * deletion are all keyed by the page. Individual member identifiers belong to
     * {@link JavaApiPageSegment}.</p>
     *
     * @param sourceUrl fragmentless canonical Java API page URL
     * @param title human-readable page title
     * @param packageName Java package name represented by the page
     * @param segments overview and member sections in source order
     */
    public record JavaApiPage(String sourceUrl, String title, String packageName, List<JavaApiPageSegment> segments) {

        /** Validates the page identity and defensively copies its ordered sections. */
        public JavaApiPage {
            sourceUrl = requireFragmentlessSourceUrl(sourceUrl);
            title = Objects.requireNonNull(title, "title");
            packageName = Objects.requireNonNull(packageName, "packageName");
            segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        }

        private List<ChunkSegment> toChunkSegments() {
            return segments.stream().map(JavaApiPageSegment::toChunkSegment).toList();
        }
    }

    /**
     * Describes one independently chunked Javadoc page section.
     *
     * <p>Overview sections have no member anchor. Member sections require the exact DOM identifier from the
     * source page, preserving precise citations without fragmenting the page's storage identity.</p>
     *
     * @param text extracted section text
     * @param javadocAnchor exact optional DOM member identifier
     */
    public record JavaApiPageSegment(String text, Optional<String> javadocAnchor) {

        /** Validates extracted section text and its optional exact DOM identifier. */
        public JavaApiPageSegment {
            text = Objects.requireNonNull(text, "text");
            javadocAnchor = Objects.requireNonNull(javadocAnchor, "javadocAnchor");
            javadocAnchor.ifPresent(ChunkProcessingService::requireExactJavadocAnchor);
        }

        /**
         * Creates a page-overview section that cites the containing page.
         *
         * @param text extracted overview text
         * @return an unanchored page section
         */
        public static JavaApiPageSegment overview(String text) {
            return new JavaApiPageSegment(text, Optional.empty());
        }

        /**
         * Creates a member section that cites the exact DOM fragment on its containing page.
         *
         * @param text extracted member-section text
         * @param exactJavadocAnchor exact DOM member identifier without a leading hash
         * @return an anchored member section
         */
        public static JavaApiPageSegment member(String text, String exactJavadocAnchor) {
            return new JavaApiPageSegment(text, Optional.of(requireExactJavadocAnchor(exactJavadocAnchor)));
        }

        private ChunkSegment toChunkSegment() {
            return new ChunkSegment(text, javadocAnchor);
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

    private record ChunkSegment(String text, Optional<String> javadocAnchor) {
        private ChunkSegment {
            text = Objects.requireNonNull(text, "text");
            javadocAnchor = Objects.requireNonNull(javadocAnchor, "javadocAnchor");
        }

        private static ChunkSegment withoutJavadocAnchor(String text) {
            return new ChunkSegment(text, Optional.empty());
        }

        private DocumentFactory.ChunkDocumentMetadata toDocumentMetadata(
                String url, String title, int chunkIndex, String packageName, String contentHash) {
            return javadocAnchor
                    .map(anchor -> DocumentFactory.ChunkDocumentMetadata.withAnchor(
                            url, title, chunkIndex, packageName, contentHash, anchor))
                    .orElseGet(() -> DocumentFactory.ChunkDocumentMetadata.withoutAnchor(
                            url, title, chunkIndex, packageName, contentHash));
        }
    }

    private static String requireFragmentlessSourceUrl(String sourceUrl) {
        String requiredUrl = Objects.requireNonNull(sourceUrl, "sourceUrl");
        if (requiredUrl.isBlank() || !requiredUrl.equals(requiredUrl.trim()) || requiredUrl.indexOf('#') >= 0) {
            throw new IllegalArgumentException("sourceUrl must be an unpadded URL without a fragment");
        }
        return requiredUrl;
    }

    private static String requireExactJavadocAnchor(String exactJavadocAnchor) {
        String requiredAnchor = Objects.requireNonNull(exactJavadocAnchor, "exactJavadocAnchor");
        if (requiredAnchor.isBlank()
                || !requiredAnchor.equals(requiredAnchor.trim())
                || requiredAnchor.indexOf('#') >= 0) {
            throw new IllegalArgumentException("exactJavadocAnchor must be an unpadded DOM identifier without '#'");
        }
        return requiredAnchor;
    }
}
