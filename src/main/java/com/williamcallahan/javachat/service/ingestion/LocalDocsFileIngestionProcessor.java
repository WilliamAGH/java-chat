package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.domain.ingestion.IngestionLocalFailure;
import com.williamcallahan.javachat.service.ChunkProcessingService;
import com.williamcallahan.javachat.service.DocumentFactory;
import com.williamcallahan.javachat.service.EmbeddingServiceUnavailableException;
import com.williamcallahan.javachat.service.FileIngestionMarkerStore.FileIngestionRecord;
import com.williamcallahan.javachat.service.JavaApiPageExtraction;
import com.williamcallahan.javachat.service.LocalStoreService;
import com.williamcallahan.javachat.service.ProgressTracker;
import com.williamcallahan.javachat.service.QdrantCollectionKind;
import com.williamcallahan.javachat.service.QdrantPayloadFieldSchema;
import com.williamcallahan.javachat.service.ingestion.HtmlContentGuard.GuardDecision;
import com.williamcallahan.javachat.service.ingestion.HtmlContentGuard.GuardInput;
import com.williamcallahan.javachat.service.ingestion.IngestionProvenanceDeriver.IngestionProvenance;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * Processes a single local HTML/PDF file into chunks and stores them in Qdrant.
 *
 * <p>This component owns the per-file ingestion lifecycle: provenance derivation, content extraction,
 * chunking, hybrid vector upsert, and local ingestion marker updates.</p>
 */
@Service
public class LocalDocsFileIngestionProcessor {
    private static final Logger log = LoggerFactory.getLogger(LocalDocsFileIngestionProcessor.class);
    private static final Logger INDEXING_LOG = LoggerFactory.getLogger("INDEXING");

    private static final String FILE_URL_PREFIX = "file://";
    static final String LOCAL_DOCS_EXTRACTION_SEMANTICS_VERSION = "utf8-document-extraction-provenance-v4";

    private final FileContentServices fileContentServices;
    private final IngestionStorageServices storage;
    private final ProgressTracker progressTracker;
    private final IngestionProvenanceDeriver provenanceDeriver;
    private final LocalIngestionFailureFactory failureFactory;
    private final IngestedFilePruneService ingestedFilePruneService;

    /**
     * Wires grouped ingestion dependencies.
     *
     * @param fileContentServices file content extraction and validation services
     * @param storage chunking, hashing, vector storage, and local marker services
     * @param progressTracker ingestion progress tracker
     * @param provenanceDeriver derives deterministic provenance tokens for routing and citations
     * @param failureFactory builds typed failures with diagnostics
     * @param ingestedFilePruneService shared stale-file prune service
     */
    public LocalDocsFileIngestionProcessor(
            FileContentServices fileContentServices,
            IngestionStorageServices storage,
            ProgressTracker progressTracker,
            IngestionProvenanceDeriver provenanceDeriver,
            LocalIngestionFailureFactory failureFactory,
            IngestedFilePruneService ingestedFilePruneService) {
        this.fileContentServices = Objects.requireNonNull(fileContentServices, "fileContentServices");
        this.storage = Objects.requireNonNull(storage, "storage");
        this.progressTracker = Objects.requireNonNull(progressTracker, "progressTracker");
        this.provenanceDeriver = Objects.requireNonNull(provenanceDeriver, "provenanceDeriver");
        this.failureFactory = Objects.requireNonNull(failureFactory, "failureFactory");
        this.ingestedFilePruneService = Objects.requireNonNull(ingestedFilePruneService, "ingestedFilePruneService");
    }

    /**
     * Processes a single local file and returns a typed outcome.
     *
     * @param root root ingestion directory
     * @param file file to process
     * @return processing outcome indicating processed/failed/skipped
     */
    public LocalDocsFileOutcome process(Path root, Path file) {
        long fileStartMillis = System.currentTimeMillis();
        Path fileNamePath = file.getFileName();
        if (fileNamePath == null) {
            return LocalDocsFileOutcome.failedFile(
                    new IngestionLocalFailure(file.toString(), "filename", "Missing filename"));
        }

        String fileName = fileNamePath.toString().toLowerCase(Locale.ROOT);
        String url = mapLocalPathToUrl(root, file);

        final long fileSizeBytes;
        final long lastModifiedMillis;
        final String fileContentFingerprint;
        try {
            fileSizeBytes = Files.size(file);
            lastModifiedMillis = Files.getLastModifiedTime(file).toMillis();
            fileContentFingerprint = storage.hasher().sha256(file);
        } catch (IOException attributeException) {
            return LocalDocsFileOutcome.failedFile(failureFactory.failure(file, "file-attributes", attributeException));
        }

        IngestionProvenance provenance = provenanceDeriver.derive(root, file, url);
        String provenanceAwareIngestionFingerprint =
                provenanceAwareIngestionFingerprint(fileContentFingerprint, provenance);
        var router = storage.router();
        QdrantCollectionKind collectionKind =
                router.route(provenance.docSet(), provenance.docPath(), provenance.docType(), url);
        String collectionName = storage.hybridVector().resolveCollectionName(collectionKind);
        INDEXING_LOG.info(
                "[INDEXING] Routed → {} (docSet={}, docType={})",
                collectionKind,
                provenance.docSet(),
                provenance.docType());

        final Optional<FileIngestionRecord> priorIngestionRecord;
        try {
            priorIngestionRecord = storage.fileMarkers().readFileIngestionRecord(url);
        } catch (RuntimeException markerReadException) {
            return LocalDocsFileOutcome.failedFile(
                    failureFactory.failure(file, "file-marker-read", markerReadException));
        }

        MarkerContext markerContext = new MarkerContext(
                file,
                url,
                fileSizeBytes,
                lastModifiedMillis,
                provenanceAwareIngestionFingerprint,
                collectionKind,
                collectionName,
                priorIngestionRecord);
        Optional<LocalDocsFileOutcome> collectionGenerationFailure = validateCollectionGeneration(markerContext);
        if (collectionGenerationFailure.isPresent()) {
            return collectionGenerationFailure.orElseThrow();
        }
        ReindexDecision markerDecision = inspectExistingMarker(markerContext);
        if (markerDecision.terminalOutcome().isPresent()) {
            return markerDecision.terminalOutcome().orElseThrow();
        }

        boolean requiresFullReindex = markerDecision.requiresFullReindex();

        String title;
        String bodyText = "";
        String packageName;
        boolean isJavaApiPage = JavaPackageExtractor.isJavaApiUrl(url);
        boolean excludedJavaApiPage = false;
        List<ChunkProcessingService.JavaApiPageSegment> javaApiPageSegments = List.of();

        if (fileName.endsWith(".pdf")) {
            try {
                var pdfExtractor = fileContentServices.pdfExtractor();
                var titleExtractor = fileContentServices.titleExtractor();
                String metadata = pdfExtractor.getPdfMetadata(file);
                title = titleExtractor.extractTitle(metadata, fileNamePath.toString());
                packageName = "";
            } catch (IOException pdfExtractionException) {
                log.error(
                        "Failed to extract PDF content (exception type: {})",
                        pdfExtractionException.getClass().getSimpleName());
                return LocalDocsFileOutcome.failedFile(
                        failureFactory.failure(file, "pdf-extraction", pdfExtractionException));
            }
        } else {
            org.jsoup.nodes.Document parsedDocument;
            try {
                var fileOps = fileContentServices.fileOps();
                var htmlExtractor = fileContentServices.htmlExtractor();
                String html = fileOps.readTextFile(file);
                parsedDocument = Jsoup.parse(html);
                title = Optional.ofNullable(parsedDocument.title()).orElse("");
                if (isJavaApiPage) {
                    JavaApiPageExtraction javaApiPageExtraction = htmlExtractor.extractJavaApiPage(parsedDocument);
                    excludedJavaApiPage = javaApiPageExtraction.excluded();
                    bodyText = javaApiPageExtraction.combinedText();
                    if (!excludedJavaApiPage) {
                        javaApiPageSegments = segmentsForJavaApiPage(javaApiPageExtraction);
                    }
                } else {
                    bodyText = htmlExtractor.extractCleanContent(parsedDocument);
                }
                packageName = JavaPackageExtractor.extractPackage(url, bodyText);
            } catch (IOException htmlReadException) {
                log.error(
                        "Failed to read HTML file (exception type: {})",
                        htmlReadException.getClass().getSimpleName());
                return LocalDocsFileOutcome.failedFile(failureFactory.failure(file, "html-read", htmlReadException));
            }

            if (!excludedJavaApiPage) {
                var contentGuard = fileContentServices.contentGuard();
                GuardDecision guardDecision = contentGuard.evaluate(new GuardInput(bodyText, parsedDocument));
                if (!guardDecision.acceptable()) {
                    try {
                        var quarantineService = fileContentServices.quarantine();
                        IngestionQuarantineService.QuarantineResult quarantineCopy = quarantineService.quarantine(file);
                        INDEXING_LOG.warn("[INDEXING] Content guard rejected file and copied it to quarantine");
                        return LocalDocsFileOutcome.failedFile(new IngestionLocalFailure(
                                file.toString(),
                                "content-guard",
                                "quarantine copy " + quarantineCopy.quarantined() + ": "
                                        + guardDecision.rejectionReason()));
                    } catch (IOException quarantineException) {
                        log.warn(
                                "Failed to quarantine invalid content (exception type: {})",
                                quarantineException.getClass().getSimpleName());
                        return LocalDocsFileOutcome.failedFile(
                                failureFactory.failure(file, "content-guard", quarantineException));
                    }
                }
            }
        }

        if (priorIngestionRecord.isEmpty()) {
            ReindexDecision unmarkedVectorDecision = inspectUnmarkedVectors(file, url, collectionKind);
            if (unmarkedVectorDecision.terminalOutcome().isPresent()) {
                return unmarkedVectorDecision.terminalOutcome().orElseThrow();
            }
            requiresFullReindex = unmarkedVectorDecision.requiresFullReindex();
        }

        if (excludedJavaApiPage) {
            try {
                if (requiresFullReindex) {
                    storage.hybridVector().deleteByUrl(collectionKind, url);
                    ingestedFilePruneService.pruneObsoleteLocalStateAfterReplacement(
                            url, priorIngestionRecord.orElse(null), List.of());
                }
            } catch (IOException pruneException) {
                return LocalDocsFileOutcome.failedFile(failureFactory.failure(file, "prune-local", pruneException));
            } catch (RuntimeException pruneException) {
                return LocalDocsFileOutcome.failedFile(failureFactory.failure(file, "prune-runtime", pruneException));
            }
            try {
                markFileIngested(
                        url,
                        new FileIngestionRecord(
                                fileSizeBytes,
                                lastModifiedMillis,
                                provenanceAwareIngestionFingerprint,
                                LOCAL_DOCS_EXTRACTION_SEMANTICS_VERSION,
                                collectionName,
                                List.of()));
            } catch (RuntimeException markerTransitionException) {
                return LocalDocsFileOutcome.failedFile(
                        failureFactory.failure(file, "marker-transition", markerTransitionException));
            }
            INDEXING_LOG.info("[INDEXING] Excluded Java API class-use page");
            return LocalDocsFileOutcome.skippedFile();
        }

        ChunkProcessingService.ChunkProcessingOutcome chunkingOutcome;
        var chunkProcessor = storage.chunks();
        try {
            if (fileName.endsWith(".pdf")) {
                chunkingOutcome = requiresFullReindex
                        ? chunkProcessor.processPdfAndStoreWithPagesForce(file, url, title, packageName)
                        : chunkProcessor.processPdfAndStoreWithPages(file, url, title, packageName);
            } else if (isJavaApiPage) {
                ChunkProcessingService.JavaApiPage extractedJavaApiPage =
                        new ChunkProcessingService.JavaApiPage(url, title, packageName, javaApiPageSegments);
                chunkingOutcome = requiresFullReindex
                        ? chunkProcessor.processAndStoreJavaApiPageForce(extractedJavaApiPage)
                        : chunkProcessor.processAndStoreJavaApiPage(extractedJavaApiPage);
            } else {
                chunkingOutcome = requiresFullReindex
                        ? chunkProcessor.processAndStoreChunksForce(bodyText, url, title, packageName)
                        : chunkProcessor.processAndStoreChunks(bodyText, url, title, packageName);
            }
        } catch (IOException chunkingException) {
            log.error(
                    "Chunking failed (exception type: {})",
                    chunkingException.getClass().getSimpleName());
            return LocalDocsFileOutcome.failedFile(failureFactory.failure(file, "chunking", chunkingException));
        }

        boolean skippedEveryChunk = chunkingOutcome.skippedAllChunks();
        boolean skippedPartOfChunkSet = chunkingOutcome.skippedChunks() > 0 && !skippedEveryChunk;
        if (skippedEveryChunk) {
            final boolean hasExactPointIds;
            try {
                hasExactPointIds = storage.hybridVector()
                        .hasExactPointIdsForUrl(
                                collectionKind, url, expectedPointUuids(chunkingOutcome.allChunkHashes()));
            } catch (RuntimeException consistencyException) {
                return LocalDocsFileOutcome.failedFile(
                        failureFactory.failure(file, "qdrant-consistency-check", consistencyException));
            }
            if (hasExactPointIds) {
                try {
                    markFileIngested(
                            url,
                            new FileIngestionRecord(
                                    fileSizeBytes,
                                    lastModifiedMillis,
                                    provenanceAwareIngestionFingerprint,
                                    LOCAL_DOCS_EXTRACTION_SEMANTICS_VERSION,
                                    collectionName,
                                    chunkingOutcome.allChunkHashes()));
                } catch (RuntimeException markerTransitionException) {
                    return LocalDocsFileOutcome.failedFile(
                            failureFactory.failure(file, "marker-transition", markerTransitionException));
                }
                INDEXING_LOG.debug("[INDEXING] Skipping file where all chunks were previously ingested");
                return LocalDocsFileOutcome.skippedFile();
            }
            INDEXING_LOG.warn("[INDEXING] Hash markers exist but Qdrant point identities differ; forcing reindex");
        }

        if (skippedPartOfChunkSet || skippedEveryChunk) {
            if (skippedPartOfChunkSet) {
                INDEXING_LOG.warn("[INDEXING] Partial chunk markers detected; forcing complete replacement");
            }
            final ChunkProcessingService.ChunkProcessingOutcome completeChunkingOutcome;
            try {
                if (fileName.endsWith(".pdf")) {
                    completeChunkingOutcome =
                            chunkProcessor.processPdfAndStoreWithPagesForce(file, url, title, packageName);
                } else if (isJavaApiPage) {
                    completeChunkingOutcome = chunkProcessor.processAndStoreJavaApiPageForce(
                            new ChunkProcessingService.JavaApiPage(url, title, packageName, javaApiPageSegments));
                } else {
                    completeChunkingOutcome =
                            chunkProcessor.processAndStoreChunksForce(bodyText, url, title, packageName);
                }
            } catch (IOException | RuntimeException completeChunkingException) {
                return LocalDocsFileOutcome.failedFile(
                        failureFactory.failure(file, "chunking", completeChunkingException));
            }
            List<Document> completeDocuments = completeChunkingOutcome.documents();
            if (!completeDocuments.isEmpty()) {
                applyProvenanceMetadata(completeDocuments, provenance);
                return processDocuments(new DocumentProcessingRequest(
                        markerContext,
                        true,
                        completeDocuments,
                        completeChunkingOutcome.allChunkHashes(),
                        fileStartMillis));
            }
            if (completeChunkingOutcome.generatedNoChunks()) {
                return LocalDocsFileOutcome.failedFile(
                        new IngestionLocalFailure(file.toString(), "empty-document", "No content to chunk"));
            }
            return LocalDocsFileOutcome.failedFile(
                    new IngestionLocalFailure(file.toString(), "empty-document", "No chunks generated"));
        }

        List<Document> documents = chunkingOutcome.documents();
        if (!documents.isEmpty()) {
            applyProvenanceMetadata(documents, provenance);
            return processDocuments(new DocumentProcessingRequest(
                    markerContext, requiresFullReindex, documents, chunkingOutcome.allChunkHashes(), fileStartMillis));
        }
        if (chunkingOutcome.generatedNoChunks()) {
            return LocalDocsFileOutcome.failedFile(
                    new IngestionLocalFailure(file.toString(), "empty-document", "No content to chunk"));
        }
        return LocalDocsFileOutcome.failedFile(
                new IngestionLocalFailure(file.toString(), "empty-document", "No chunks generated"));
    }

    private ReindexDecision inspectExistingMarker(MarkerContext markerContext) {
        Optional<FileIngestionRecord> priorIngestionRecord = markerContext.priorIngestionRecord();
        boolean unchangedByFingerprint = priorIngestionRecord
                .map(ingestionRecord -> ingestionRecord.fileSizeBytes() == markerContext.fileSizeBytes()
                        && ingestionRecord.lastModifiedMillis() == markerContext.lastModifiedMillis()
                        && markerContext.ingestionFingerprint().equals(ingestionRecord.ingestionFingerprint())
                        && LOCAL_DOCS_EXTRACTION_SEMANTICS_VERSION.equals(ingestionRecord.extractionSemanticsVersion())
                        && markerContext.collectionName().equals(ingestionRecord.collectionName()))
                .orElse(false);
        if (!unchangedByFingerprint) {
            return ReindexDecision.continueWith(priorIngestionRecord.isPresent());
        }

        try {
            List<String> expectedChunkHashes =
                    priorIngestionRecord.orElseThrow().chunkHashes();
            boolean hasExactPointIds = storage.hybridVector()
                    .hasExactPointIdsForUrl(
                            markerContext.collectionKind(),
                            markerContext.url(),
                            expectedPointUuids(expectedChunkHashes));
            if (hasExactPointIds && expectedChunkHashes.isEmpty()) {
                INDEXING_LOG.debug("[INDEXING] Skipping unchanged excluded Java API page");
                return ReindexDecision.terminal(LocalDocsFileOutcome.skippedFile());
            }
            if (hasExactPointIds) {
                INDEXING_LOG.debug("[INDEXING] Skipping unchanged file (already ingested)");
                return ReindexDecision.terminal(LocalDocsFileOutcome.skippedFile());
            }
            INDEXING_LOG.warn("[INDEXING] Marker and Qdrant point identities differ; forcing safe replacement");
            return ReindexDecision.continueWith(true);
        } catch (RuntimeException consistencyException) {
            return ReindexDecision.terminal(LocalDocsFileOutcome.failedFile(
                    failureFactory.failure(markerContext.file(), "qdrant-consistency-check", consistencyException)));
        }
    }

    private ReindexDecision inspectUnmarkedVectors(Path file, String url, QdrantCollectionKind collectionKind) {
        try {
            long unmarkedPointCount = storage.hybridVector().countPointsForUrl(collectionKind, url);
            if (unmarkedPointCount == 0) {
                return ReindexDecision.continueWith(false);
            }
            INDEXING_LOG.warn(
                    "[INDEXING] Found {} Qdrant points without a file marker; forcing safe replacement",
                    unmarkedPointCount);
            return ReindexDecision.continueWith(true);
        } catch (RuntimeException consistencyException) {
            return ReindexDecision.terminal(LocalDocsFileOutcome.failedFile(
                    failureFactory.failure(file, "qdrant-consistency-check", consistencyException)));
        }
    }

    private List<String> expectedPointUuids(List<String> chunkHashes) {
        return chunkHashes.stream().map(storage.hasher()::uuidFromHash).toList();
    }

    private record MarkerContext(
            Path file,
            String url,
            long fileSizeBytes,
            long lastModifiedMillis,
            String ingestionFingerprint,
            QdrantCollectionKind collectionKind,
            String collectionName,
            Optional<FileIngestionRecord> priorIngestionRecord) {}

    private record ReindexDecision(boolean requiresFullReindex, Optional<LocalDocsFileOutcome> terminalOutcome) {

        private ReindexDecision {
            terminalOutcome = Objects.requireNonNull(terminalOutcome, "terminalOutcome");
            if (requiresFullReindex && terminalOutcome.isPresent()) {
                throw new IllegalArgumentException("A terminal outcome cannot require a reindex");
            }
        }

        private static ReindexDecision continueWith(boolean requiresFullReindex) {
            return new ReindexDecision(requiresFullReindex, Optional.empty());
        }

        private static ReindexDecision terminal(LocalDocsFileOutcome terminalOutcome) {
            return new ReindexDecision(false, Optional.of(Objects.requireNonNull(terminalOutcome, "terminalOutcome")));
        }
    }

    private record DocumentProcessingRequest(
            MarkerContext markerContext,
            boolean requiresFullReindex,
            List<Document> documents,
            List<String> allChunkHashes,
            long fileStartMillis) {

        private DocumentProcessingRequest {
            markerContext = Objects.requireNonNull(markerContext, "markerContext");
            documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
            allChunkHashes = List.copyOf(Objects.requireNonNull(allChunkHashes, "allChunkHashes"));
        }
    }

    private LocalDocsFileOutcome processDocuments(DocumentProcessingRequest processingRequest) {
        MarkerContext markerContext = processingRequest.markerContext();
        List<Document> documents = processingRequest.documents();
        INDEXING_LOG.info("[INDEXING] Processing file with {} chunks", documents.size());

        try {
            storeDocuments(
                    markerContext.collectionKind(),
                    markerContext.url(),
                    documents,
                    processingRequest.requiresFullReindex());
        } catch (EmbeddingServiceUnavailableException embeddingException) {
            log.error(
                    "Embedding service unavailable during upsert (exception type: {})",
                    embeddingException.getClass().getSimpleName());
            return LocalDocsFileOutcome.failedFile(
                    failureFactory.failure(markerContext.file(), "embedding-unavailable", embeddingException));
        } catch (RuntimeException vectorStorageException) {
            return LocalDocsFileOutcome.failedFile(
                    failureFactory.failure(markerContext.file(), "qdrant-replacement", vectorStorageException));
        }

        if (processingRequest.requiresFullReindex()) {
            try {
                ingestedFilePruneService.pruneObsoleteLocalStateAfterReplacement(
                        markerContext.url(),
                        markerContext.priorIngestionRecord().orElse(null),
                        processingRequest.allChunkHashes());
            } catch (IOException localCleanupException) {
                return LocalDocsFileOutcome.failedFile(
                        failureFactory.failure(markerContext.file(), "prune-local", localCleanupException));
            } catch (RuntimeException replacementCleanupException) {
                return LocalDocsFileOutcome.failedFile(
                        failureFactory.failure(markerContext.file(), "prune-runtime", replacementCleanupException));
            }
        }

        long totalDuration = System.currentTimeMillis() - processingRequest.fileStartMillis();
        String formattedPercent = progressTracker.formatPercent();
        INDEXING_LOG.info(
                "[INDEXING] Completed processing {}/{} chunks to Qdrant in {}ms (end-to-end) ({})",
                documents.size(),
                documents.size(),
                totalDuration,
                formattedPercent);

        try {
            markDocumentsIngested(documents);
            markFileIngested(
                    markerContext.url(),
                    new FileIngestionRecord(
                            markerContext.fileSizeBytes(),
                            markerContext.lastModifiedMillis(),
                            markerContext.ingestionFingerprint(),
                            LOCAL_DOCS_EXTRACTION_SEMANTICS_VERSION,
                            markerContext.collectionName(),
                            processingRequest.allChunkHashes()));
        } catch (RuntimeException markerTransitionException) {
            return LocalDocsFileOutcome.failedFile(
                    failureFactory.failure(markerContext.file(), "marker-transition", markerTransitionException));
        }

        return LocalDocsFileOutcome.processedFile();
    }

    private void storeDocuments(
            QdrantCollectionKind collectionKind, String sourceUrl, List<Document> documents, boolean replaceExisting) {
        var hybridVector = storage.hybridVector();
        long startTime = System.currentTimeMillis();
        if (replaceExisting) {
            hybridVector.replaceUrlDocuments(collectionKind, sourceUrl, documents);
        } else {
            hybridVector.upsert(collectionKind, documents);
        }
        long duration = System.currentTimeMillis() - startTime;
        String formattedPercent = progressTracker.formatPercent();
        INDEXING_LOG.info(
                "[INDEXING] Added {} hybrid vectors to {} in {}ms ({})",
                documents.size(),
                collectionKind,
                duration,
                formattedPercent);
    }

    private Optional<LocalDocsFileOutcome> validateCollectionGeneration(MarkerContext markerContext) {
        return markerContext.priorIngestionRecord().flatMap(previousFileRecord -> {
            if (previousFileRecord.hasCollectionIdentity()
                    && markerContext.collectionName().equals(previousFileRecord.collectionName())) {
                return Optional.empty();
            }
            return Optional.of(LocalDocsFileOutcome.failedFile(new IngestionLocalFailure(
                    markerContext.file().toString(),
                    "collection-generation",
                    "The ingestion state belongs to a different or unknown collection generation")));
        });
    }

    private void markDocumentsIngested(List<Document> documents) {
        LocalStoreService localStore = storage.localStore();
        for (Document indexedDocument : documents) {
            Object hashMetadata = indexedDocument.getMetadata().get(QdrantPayloadFieldSchema.HASH_FIELD);
            if (hashMetadata == null) {
                continue;
            }
            String title = DocumentFactory.metadataText(indexedDocument, QdrantPayloadFieldSchema.TITLE_FIELD);
            String packageName = DocumentFactory.metadataText(indexedDocument, QdrantPayloadFieldSchema.PACKAGE_FIELD);
            try {
                localStore.markHashIngested(hashMetadata.toString(), title, packageName);
            } catch (IOException markHashException) {
                throw new IllegalStateException("Failed to mark hash as ingested: " + hashMetadata, markHashException);
            }
        }
    }

    private void markFileIngested(String url, FileIngestionRecord fileIngestionRecord) {
        if (url == null || url.isBlank()) {
            return;
        }
        try {
            storage.fileMarkers().markFileIngested(url, fileIngestionRecord);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to mark file as ingested: " + url, exception);
        }
    }

    private static List<ChunkProcessingService.JavaApiPageSegment> segmentsForJavaApiPage(
            JavaApiPageExtraction javaApiPageExtraction) {
        List<ChunkProcessingService.JavaApiPageSegment> segments = new ArrayList<>();
        if (!javaApiPageExtraction.overviewText().isBlank()) {
            segments.add(ChunkProcessingService.JavaApiPageSegment.overview(javaApiPageExtraction.overviewText()));
        }
        for (var anchoredSection : javaApiPageExtraction.anchoredSections()) {
            segments.add(
                    ChunkProcessingService.JavaApiPageSegment.member(anchoredSection.text(), anchoredSection.anchor()));
        }
        return List.copyOf(segments);
    }

    private static void applyProvenanceMetadata(List<Document> documents, IngestionProvenance provenance) {
        Objects.requireNonNull(documents, "documents");
        Objects.requireNonNull(provenance, "provenance");
        for (Document indexedDocument : documents) {
            if (!provenance.docSet().isBlank()) {
                indexedDocument.getMetadata().put(QdrantPayloadFieldSchema.DOC_SET_FIELD, provenance.docSet());
            }
            if (!provenance.docPath().isBlank()) {
                indexedDocument.getMetadata().put(QdrantPayloadFieldSchema.DOC_PATH_FIELD, provenance.docPath());
            }
            if (!provenance.sourceName().isBlank()) {
                indexedDocument.getMetadata().put(QdrantPayloadFieldSchema.SOURCE_NAME_FIELD, provenance.sourceName());
            }
            if (!provenance.sourceKind().isBlank()) {
                indexedDocument.getMetadata().put(QdrantPayloadFieldSchema.SOURCE_KIND_FIELD, provenance.sourceKind());
            }
            if (!provenance.docVersion().isBlank()) {
                indexedDocument.getMetadata().put(QdrantPayloadFieldSchema.DOC_VERSION_FIELD, provenance.docVersion());
            }
            if (!provenance.docType().isBlank()) {
                indexedDocument.getMetadata().put(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, provenance.docType());
            }
        }
    }

    private String mapLocalPathToUrl(final Path root, final Path file) {
        final String absolutePath = file.toAbsolutePath().toString().replace('\\', '/');
        return DocsSourceRegistry.resolveMirroredPath(root, file)
                .or(() -> DocsSourceRegistry.resolveLocalPath(absolutePath))
                .orElse(FILE_URL_PREFIX + absolutePath);
    }

    private String provenanceAwareIngestionFingerprint(String fileContentFingerprint, IngestionProvenance provenance) {
        Objects.requireNonNull(fileContentFingerprint, "fileContentFingerprint");
        Objects.requireNonNull(provenance, "provenance");
        return storage.hasher().sha256(provenance.fingerprintInput(fileContentFingerprint));
    }
}
