package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.domain.ingestion.IngestionLocalFailure;
import com.williamcallahan.javachat.service.ChunkProcessingService;
import com.williamcallahan.javachat.service.DocumentFactory;
import com.williamcallahan.javachat.service.EmbeddingServiceUnavailableException;
import com.williamcallahan.javachat.service.FileIngestionMarkerStore;
import com.williamcallahan.javachat.service.FileIngestionMarkerStore.FileIngestionRecord;
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
import java.util.Arrays;
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
    static final String LOCAL_DOCS_EXTRACTION_SEMANTICS_VERSION = "utf8-document-extraction-provenance-v2";

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
        String url = mapLocalPathToUrl(file);
        if (fileName.endsWith(".pdf")) {
            Optional<String> publicPdfUrl = DocsSourceRegistry.mapBookLocalToPublic(file.toString());
            url = publicPdfUrl.orElse(url);
        }

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
        FileIngestionMarkerStore fileMarkerStore = storage.fileMarkers();
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
            priorIngestionRecord = fileMarkerStore.readFileIngestionRecord(url);
        } catch (RuntimeException markerReadException) {
            return LocalDocsFileOutcome.failedFile(
                    failureFactory.failure(file, "file-marker-read", markerReadException));
        }

        boolean unchangedByFingerprint = priorIngestionRecord
                .map(ingestionRecord -> ingestionRecord.fileSizeBytes() == fileSizeBytes
                        && ingestionRecord.lastModifiedMillis() == lastModifiedMillis
                        && provenanceAwareIngestionFingerprint.equals(ingestionRecord.ingestionFingerprint())
                        && LOCAL_DOCS_EXTRACTION_SEMANTICS_VERSION.equals(ingestionRecord.extractionSemanticsVersion())
                        && collectionName.equals(ingestionRecord.collectionName()))
                .orElse(false);

        if (unchangedByFingerprint) {
            try {
                long storedPointCount = storage.hybridVector().countPointsForUrl(collectionKind, url);
                int expectedChunkCount = priorIngestionRecord
                        .map(ingestionRecord -> ingestionRecord.chunkHashes().size())
                        .orElse(0);
                boolean sufficientPointCoverage = expectedChunkCount <= 0 || storedPointCount >= expectedChunkCount;
                if (storedPointCount > 0 && sufficientPointCoverage) {
                    INDEXING_LOG.debug("[INDEXING] Skipping unchanged file (already ingested)");
                    return LocalDocsFileOutcome.skippedFile();
                }
            } catch (RuntimeException consistencyException) {
                return LocalDocsFileOutcome.failedFile(
                        failureFactory.failure(file, "qdrant-consistency-check", consistencyException));
            }
            INDEXING_LOG.warn("[INDEXING] Marker exists but Qdrant has no points for URL; forcing reindex");
        }

        boolean requiresFullReindex = priorIngestionRecord.isPresent() && !unchangedByFingerprint;
        if (requiresFullReindex) {
            try {
                prunePreviouslyIngestedFileStrict(url, priorIngestionRecord.orElseThrow());
            } catch (IOException ioException) {
                return LocalDocsFileOutcome.failedFile(failureFactory.failure(file, "prune-local", ioException));
            } catch (RuntimeException runtimeException) {
                return LocalDocsFileOutcome.failedFile(failureFactory.failure(file, "prune-runtime", runtimeException));
            }
        }

        String title;
        String bodyText = "";
        String packageName;

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
                bodyText = JavaPackageExtractor.isJavaApiUrl(url)
                        ? htmlExtractor.extractJavaApiContent(parsedDocument)
                        : htmlExtractor.extractCleanContent(parsedDocument);
                packageName = JavaPackageExtractor.extractPackage(url, bodyText);
            } catch (IOException htmlReadException) {
                log.error(
                        "Failed to read HTML file (exception type: {})",
                        htmlReadException.getClass().getSimpleName());
                return LocalDocsFileOutcome.failedFile(failureFactory.failure(file, "html-read", htmlReadException));
            }

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

        ChunkProcessingService.ChunkProcessingOutcome chunkingOutcome;
        var chunkProcessor = storage.chunks();
        try {
            if (fileName.endsWith(".pdf")) {
                chunkingOutcome = requiresFullReindex
                        ? chunkProcessor.processPdfAndStoreWithPagesForce(file, url, title, packageName)
                        : chunkProcessor.processPdfAndStoreWithPages(file, url, title, packageName);
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

        List<Document> documents = chunkingOutcome.documents();
        if (!documents.isEmpty()) {
            applyProvenanceMetadata(documents, provenance);
            return processDocuments(
                    collectionKind,
                    collectionName,
                    file,
                    url,
                    fileSizeBytes,
                    lastModifiedMillis,
                    provenanceAwareIngestionFingerprint,
                    documents,
                    chunkingOutcome.allChunkHashes(),
                    fileStartMillis);
        }
        if (chunkingOutcome.skippedAllChunks()) {
            try {
                long storedPointCount = storage.hybridVector().countPointsForUrl(collectionKind, url);
                long expectedChunkCount = chunkingOutcome.allChunkHashes().size();
                if (storedPointCount < expectedChunkCount) {
                    INDEXING_LOG.warn(
                            "[INDEXING] Hash markers exist but Qdrant has insufficient points for URL; forcing reindex");
                    ChunkProcessingService.ChunkProcessingOutcome forcedChunkingOutcome =
                            forceChunking(file, fileName, bodyText, url, title, packageName);
                    List<Document> forcedDocuments = forcedChunkingOutcome.documents();
                    if (!forcedDocuments.isEmpty()) {
                        applyProvenanceMetadata(forcedDocuments, provenance);
                        return processDocuments(
                                collectionKind,
                                collectionName,
                                file,
                                url,
                                fileSizeBytes,
                                lastModifiedMillis,
                                provenanceAwareIngestionFingerprint,
                                forcedDocuments,
                                forcedChunkingOutcome.allChunkHashes(),
                                fileStartMillis);
                    }
                    if (forcedChunkingOutcome.generatedNoChunks()) {
                        return LocalDocsFileOutcome.failedFile(
                                new IngestionLocalFailure(file.toString(), "empty-document", "No content to chunk"));
                    }
                    return LocalDocsFileOutcome.failedFile(
                            new IngestionLocalFailure(file.toString(), "empty-document", "No chunks generated"));
                }
            } catch (IOException chunkingException) {
                return LocalDocsFileOutcome.failedFile(failureFactory.failure(file, "chunking", chunkingException));
            } catch (RuntimeException consistencyException) {
                return LocalDocsFileOutcome.failedFile(
                        failureFactory.failure(file, "qdrant-consistency-check", consistencyException));
            }
            INDEXING_LOG.debug("[INDEXING] Skipping file where all chunks were previously ingested");
            markFileIngested(
                    url,
                    new FileIngestionRecord(
                            fileSizeBytes,
                            lastModifiedMillis,
                            provenanceAwareIngestionFingerprint,
                            LOCAL_DOCS_EXTRACTION_SEMANTICS_VERSION,
                            collectionName,
                            chunkingOutcome.allChunkHashes()));
            return LocalDocsFileOutcome.skippedFile();
        }
        if (chunkingOutcome.generatedNoChunks()) {
            return LocalDocsFileOutcome.failedFile(
                    new IngestionLocalFailure(file.toString(), "empty-document", "No content to chunk"));
        }
        return LocalDocsFileOutcome.failedFile(
                new IngestionLocalFailure(file.toString(), "empty-document", "No chunks generated"));
    }

    private void prunePreviouslyIngestedFileStrict(String url, FileIngestionRecord priorIngestionRecord)
            throws IOException {
        if (priorIngestionRecord.hasCollectionIdentity()) {
            ingestedFilePruneService.pruneCollectionFileStrict(
                    priorIngestionRecord.collectionName(), url, priorIngestionRecord);
            return;
        }
        List<String> governedCollectionNames = Arrays.stream(QdrantCollectionKind.values())
                .map(storage.hybridVector()::resolveCollectionName)
                .toList();
        ingestedFilePruneService.pruneCollectionsFileStrict(governedCollectionNames, url, priorIngestionRecord);
    }

    private LocalDocsFileOutcome processDocuments(
            QdrantCollectionKind collectionKind,
            String collectionName,
            Path file,
            String url,
            long fileSizeBytes,
            long lastModifiedMillis,
            String ingestionFingerprint,
            List<Document> documents,
            List<String> allChunkHashes,
            long fileStartMillis) {
        INDEXING_LOG.info("[INDEXING] Processing file with {} chunks", documents.size());

        try {
            storeDocumentsWithRetry(collectionKind, documents);
        } catch (EmbeddingServiceUnavailableException embeddingException) {
            log.error(
                    "Embedding service unavailable during upsert (exception type: {})",
                    embeddingException.getClass().getSimpleName());
            return LocalDocsFileOutcome.failedFile(
                    failureFactory.failure(file, "embedding-unavailable", embeddingException));
        }

        long totalDuration = System.currentTimeMillis() - fileStartMillis;
        String formattedPercent = progressTracker.formatPercent();
        INDEXING_LOG.info(
                "[INDEXING] Completed processing {}/{} chunks to Qdrant in {}ms (end-to-end) ({})",
                documents.size(),
                documents.size(),
                totalDuration,
                formattedPercent);

        markDocumentsIngested(documents);
        markFileIngested(
                url,
                new FileIngestionRecord(
                        fileSizeBytes,
                        lastModifiedMillis,
                        ingestionFingerprint,
                        LOCAL_DOCS_EXTRACTION_SEMANTICS_VERSION,
                        collectionName,
                        allChunkHashes));

        return LocalDocsFileOutcome.processedFile();
    }

    private ChunkProcessingService.ChunkProcessingOutcome forceChunking(
            Path file, String fileName, String bodyText, String url, String title, String packageName)
            throws IOException {
        var chunkProcessor = storage.chunks();
        if (fileName.endsWith(".pdf")) {
            return chunkProcessor.processPdfAndStoreWithPagesForce(file, url, title, packageName);
        }
        return chunkProcessor.processAndStoreChunksForce(bodyText, url, title, packageName);
    }

    private void storeDocumentsWithRetry(QdrantCollectionKind collectionKind, List<Document> documents) {
        var hybridVector = storage.hybridVector();
        long startTime = System.currentTimeMillis();
        hybridVector.upsert(collectionKind, documents);
        long duration = System.currentTimeMillis() - startTime;
        String formattedPercent = progressTracker.formatPercent();
        INDEXING_LOG.info(
                "[INDEXING] Added {} hybrid vectors to {} in {}ms ({})",
                documents.size(),
                collectionKind,
                duration,
                formattedPercent);
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

    private String mapLocalPathToUrl(final Path file) {
        final String absolutePath = file.toAbsolutePath().toString().replace('\\', '/');
        return DocsSourceRegistry.resolveLocalPath(absolutePath).orElse(FILE_URL_PREFIX + absolutePath);
    }

    private String provenanceAwareIngestionFingerprint(String fileContentFingerprint, IngestionProvenance provenance) {
        Objects.requireNonNull(fileContentFingerprint, "fileContentFingerprint");
        Objects.requireNonNull(provenance, "provenance");
        return storage.hasher().sha256(provenance.fingerprintInput(fileContentFingerprint));
    }
}
