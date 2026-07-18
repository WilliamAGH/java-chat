package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.DocsSourceRegistry.DocumentationSource;
import com.williamcallahan.javachat.config.DocsSourceRegistry.JavaApiDocumentationSource;
import com.williamcallahan.javachat.domain.javaapi.JavadocMemberAnchor;
import com.williamcallahan.javachat.service.ChunkProcessingService;
import com.williamcallahan.javachat.service.ContentHasher;
import com.williamcallahan.javachat.service.FileIngestionMarkerStore;
import com.williamcallahan.javachat.service.FileIngestionMarkerStore.FileIngestionRecord;
import com.williamcallahan.javachat.service.FileOperationsService;
import com.williamcallahan.javachat.service.HtmlContentExtractor;
import com.williamcallahan.javachat.service.HybridVectorService;
import com.williamcallahan.javachat.service.LocalStoreService;
import com.williamcallahan.javachat.service.PdfContentExtractor;
import com.williamcallahan.javachat.service.ProgressTracker;
import com.williamcallahan.javachat.service.QdrantCollectionKind;
import com.williamcallahan.javachat.service.QdrantCollectionRouter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.document.Document;

/** Verifies configured local Javadoc files use structured Java API extraction. */
class LocalDocsFileIngestionProcessorTest {

    private static final String JAVA_API_CLASS_NAME = "StringBuilder";
    private static final String JAVA_API_METHOD_SIGNATURE = "append(String text)";
    private static final String JAVA_API_METHOD_ANCHOR = "append(java.lang.String)";
    private static final String JAVA_API_RELATIVE_PATH = "java.base/java/lang/" + JAVA_API_CLASS_NAME + ".html";
    private static final String JAVA_API_CLASS_USE_RELATIVE_PATH = "java.base/java/util/class-use/List.html";
    private static final String JAVA_API_CLASS_PLACEHOLDER = "__JAVA_API_CLASS__";
    private static final String JAVA_API_DESCRIPTION_PLACEHOLDER = "__JAVA_API_DESCRIPTION__";
    private static final String JAVA_API_METHOD_PLACEHOLDER = "__JAVA_API_METHOD__";
    private static final int JAVA_API_DESCRIPTION_REPEAT_COUNT = 200;
    private static final String JAVA_API_DESCRIPTION =
            "Detailed Java API documentation explains mutability, character sequences, and method contracts. "
                    .repeat(JAVA_API_DESCRIPTION_REPEAT_COUNT);

    @Test
    void shouldSendAnchoredJavadocSectionsToChunkingForConfiguredJavaApiFile(@TempDir Path temporaryDirectory)
            throws IOException {
        JavaApiDocumentationSource javaApiDocumentationSource =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst();
        Path localDocsRoot = temporaryDirectory.resolve("data").resolve("docs");
        Path localJavadocFile =
                writeJavaApiFile(localDocsRoot, javaApiDocumentationSource, JAVA_API_RELATIVE_PATH, javaApiHtml());

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        String expectedJavadocUrl = javaApiDocumentationSource.remoteBaseUrl() + JAVA_API_RELATIVE_PATH;
        when(ingestionFixture.fileIngestionMarkerStore.readFileIngestionRecord(expectedJavadocUrl))
                .thenReturn(Optional.empty());
        for (QdrantCollectionKind governedCollectionKind : QdrantCollectionKind.values()) {
            when(ingestionFixture.hybridVectorService.resolveCollectionName(governedCollectionKind))
                    .thenReturn(testCollectionName(governedCollectionKind));
        }
        when(ingestionFixture.chunkProcessingService.processAndStoreJavaApiPage(any()))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(List.of(), List.of(), 0, 0));

        LocalDocsFileIngestionProcessor ingestionProcessor = ingestionFixture.ingestionProcessor();

        LocalDocsFileOutcome processingOutcome = ingestionProcessor.process(localDocsRoot, localJavadocFile);

        assertFalse(processingOutcome.processed());
        assertTrue(processingOutcome.failure().isPresent());
        ArgumentCaptor<ChunkProcessingService.JavaApiPage> javaApiPageCaptor =
                ArgumentCaptor.forClass(ChunkProcessingService.JavaApiPage.class);
        verify(ingestionFixture.chunkProcessingService).processAndStoreJavaApiPage(javaApiPageCaptor.capture());
        ChunkProcessingService.JavaApiPage extractedJavaApiPage = javaApiPageCaptor.getValue();
        assertEquals(expectedJavadocUrl, extractedJavaApiPage.sourceUrl());
        assertEquals(JAVA_API_CLASS_NAME, extractedJavaApiPage.title());
        assertEquals(2, extractedJavaApiPage.segments().size());
        ChunkProcessingService.JavaApiPageSegment memberSegment =
                extractedJavaApiPage.segments().get(1);
        assertEquals(
                Optional.of(JAVA_API_METHOD_ANCHOR),
                memberSegment.javadocMemberAnchor().map(JavadocMemberAnchor::domIdentifier));
        assertTrue(memberSegment.text().contains(JAVA_API_METHOD_SIGNATURE));
        verify(ingestionFixture.chunkProcessingService, never())
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldForceCompleteReplacementWhenOnlyPartOfChunkSetWasSkipped(@TempDir Path temporaryDirectory)
            throws IOException {
        JavaApiDocumentationSource javaApiDocumentationSource =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst();
        Path localDocsRoot = temporaryDirectory.resolve("data").resolve("docs");
        Path localJavadocFile =
                writeJavaApiFile(localDocsRoot, javaApiDocumentationSource, JAVA_API_RELATIVE_PATH, javaApiHtml());
        String expectedJavadocUrl = javaApiDocumentationSource.remoteBaseUrl() + JAVA_API_RELATIVE_PATH;
        Document incrementalDocument = new Document("incremental-point", "Incremental body", new HashMap<>());
        Document completeFirstDocument = new Document("complete-first-point", "Complete first body", new HashMap<>());
        Document completeSecondDocument =
                new Document("complete-second-point", "Complete second body", new HashMap<>());
        List<String> completeChunkHashes = List.of("retained-chunk-hash", "new-chunk-hash");
        ChunkProcessingService.ChunkProcessingOutcome partialChunkingOutcome =
                new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(incrementalDocument), completeChunkHashes, 2, 1);
        ChunkProcessingService.ChunkProcessingOutcome completeChunkingOutcome =
                new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(completeFirstDocument, completeSecondDocument), completeChunkHashes, 2, 0);

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        when(ingestionFixture.fileIngestionMarkerStore.readFileIngestionRecord(expectedJavadocUrl))
                .thenReturn(Optional.empty());
        when(ingestionFixture.hybridVectorService.resolveCollectionName(any())).thenReturn("java-api-docs");
        when(ingestionFixture.chunkProcessingService.processAndStoreJavaApiPage(any()))
                .thenReturn(partialChunkingOutcome);
        when(ingestionFixture.chunkProcessingService.processAndStoreJavaApiPageForce(any()))
                .thenReturn(completeChunkingOutcome);

        LocalDocsFileOutcome processingOutcome =
                ingestionFixture.ingestionProcessor().process(localDocsRoot, localJavadocFile);

        assertTrue(processingOutcome.processed());
        verify(ingestionFixture.hybridVectorService)
                .replaceUrlDocuments(
                        any(QdrantCollectionKind.class),
                        eq(expectedJavadocUrl),
                        eq(List.of(completeFirstDocument, completeSecondDocument)));
        verify(ingestionFixture.hybridVectorService, never()).upsert(any(QdrantCollectionKind.class), any());
        verify(ingestionFixture.chunkProcessingService).processAndStoreJavaApiPageForce(any());
        ArgumentCaptor<FileIngestionRecord> completedMarkerCaptor = ArgumentCaptor.forClass(FileIngestionRecord.class);
        verify(ingestionFixture.fileIngestionMarkerStore)
                .markFileIngested(eq(expectedJavadocUrl), completedMarkerCaptor.capture());
        assertEquals(completeChunkHashes, completedMarkerCaptor.getValue().chunkHashes());
    }

    @Test
    void shouldReplaceChangedJavadocBeforePruningObsoleteStateWhenChunkCountShrinks(@TempDir Path temporaryDirectory)
            throws IOException {
        JavaApiDocumentationSource javaApiDocumentationSource =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst();
        Path localDocsRoot = temporaryDirectory.resolve("data").resolve("docs");
        Path localJavadocFile =
                writeJavaApiFile(localDocsRoot, javaApiDocumentationSource, JAVA_API_RELATIVE_PATH, javaApiHtml());

        String expectedJavadocUrl = javaApiDocumentationSource.remoteBaseUrl() + JAVA_API_RELATIVE_PATH;
        long fileSizeBytes = Files.size(localJavadocFile);
        long lastModifiedMillis = Files.getLastModifiedTime(localJavadocFile).toMillis();
        FileIngestionRecord priorIngestionRecord = new FileIngestionRecord(
                fileSizeBytes,
                lastModifiedMillis,
                "javadoc-fingerprint",
                "",
                "java-api-docs",
                List.of("retained-javadoc-hash", "obsolete-javadoc-hash"));

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        Document indexedDocument = new Document("javadoc-point", "Javadoc body", new HashMap<>());
        ChunkProcessingService.ChunkProcessingOutcome forcedChunkingOutcome =
                new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(indexedDocument), List.of("current-javadoc-hash"), 1, 0);

        when(ingestionFixture.fileIngestionMarkerStore.readFileIngestionRecord(expectedJavadocUrl))
                .thenReturn(Optional.of(priorIngestionRecord));
        when(ingestionFixture.hybridVectorService.resolveCollectionName(any())).thenReturn("java-api-docs");
        when(ingestionFixture.chunkProcessingService.processAndStoreJavaApiPageForce(any()))
                .thenReturn(forcedChunkingOutcome);

        LocalDocsFileIngestionProcessor ingestionProcessor = ingestionFixture.ingestionProcessor();

        LocalDocsFileOutcome processingOutcome = ingestionProcessor.process(localDocsRoot, localJavadocFile);

        assertTrue(processingOutcome.processed());
        InOrder replacementOrder = inOrder(
                ingestionFixture.hybridVectorService,
                ingestionFixture.ingestedFilePruneService,
                ingestionFixture.fileIngestionMarkerStore);
        replacementOrder
                .verify(ingestionFixture.hybridVectorService)
                .replaceUrlDocuments(
                        any(QdrantCollectionKind.class), eq(expectedJavadocUrl), eq(List.of(indexedDocument)));
        replacementOrder
                .verify(ingestionFixture.ingestedFilePruneService)
                .pruneObsoleteStateAfterReplacement(
                        List.of(), expectedJavadocUrl, priorIngestionRecord, List.of("current-javadoc-hash"));
        ArgumentCaptor<ChunkProcessingService.JavaApiPage> javaApiPageCaptor =
                ArgumentCaptor.forClass(ChunkProcessingService.JavaApiPage.class);
        verify(ingestionFixture.chunkProcessingService).processAndStoreJavaApiPageForce(javaApiPageCaptor.capture());
        assertEquals(
                Optional.of(JAVA_API_METHOD_ANCHOR),
                javaApiPageCaptor
                        .getValue()
                        .segments()
                        .get(1)
                        .javadocMemberAnchor()
                        .map(JavadocMemberAnchor::domIdentifier));
        verify(ingestionFixture.chunkProcessingService, never())
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
        ArgumentCaptor<FileIngestionRecord> updatedMarkerCaptor = ArgumentCaptor.forClass(FileIngestionRecord.class);
        replacementOrder
                .verify(ingestionFixture.fileIngestionMarkerStore)
                .markFileIngested(eq(expectedJavadocUrl), updatedMarkerCaptor.capture());
        assertEquals(
                LocalDocsFileIngestionProcessor.LOCAL_DOCS_EXTRACTION_SEMANTICS_VERSION,
                updatedMarkerCaptor.getValue().extractionSemanticsVersion());
        assertEquals("java-api-docs", updatedMarkerCaptor.getValue().collectionName());
    }

    @Test
    void shouldPreservePriorVectorsAndMarkersWhenReplacementUpsertFails(@TempDir Path temporaryDirectory)
            throws IOException {
        JavaApiDocumentationSource javaApiDocumentationSource =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst();
        Path localDocsRoot = temporaryDirectory.resolve("data").resolve("docs");
        Path localJavadocFile =
                writeJavaApiFile(localDocsRoot, javaApiDocumentationSource, JAVA_API_RELATIVE_PATH, javaApiHtml());
        String expectedJavadocUrl = javaApiDocumentationSource.remoteBaseUrl() + JAVA_API_RELATIVE_PATH;
        FileIngestionRecord priorIngestionRecord = new FileIngestionRecord(
                Files.size(localJavadocFile),
                Files.getLastModifiedTime(localJavadocFile).toMillis(),
                "prior-javadoc-fingerprint",
                "utf8-document-extraction-provenance-v2",
                "java-api-docs",
                List.of("prior-first-hash", "prior-second-hash"));

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        Document replacementDocument = new Document("replacement-point", "Replacement Javadoc body", new HashMap<>());
        when(ingestionFixture.fileIngestionMarkerStore.readFileIngestionRecord(expectedJavadocUrl))
                .thenReturn(Optional.of(priorIngestionRecord));
        when(ingestionFixture.hybridVectorService.resolveCollectionName(any())).thenReturn("java-api-docs");
        when(ingestionFixture.chunkProcessingService.processAndStoreJavaApiPageForce(any()))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(replacementDocument), List.of("replacement-hash"), 1, 0));
        doThrow(new com.williamcallahan.javachat.service.EmbeddingServiceUnavailableException(
                        "embedding provider unavailable"))
                .when(ingestionFixture.hybridVectorService)
                .replaceUrlDocuments(
                        any(QdrantCollectionKind.class), eq(expectedJavadocUrl), eq(List.of(replacementDocument)));

        LocalDocsFileIngestionProcessor ingestionProcessor = ingestionFixture.ingestionProcessor();

        LocalDocsFileOutcome processingOutcome = ingestionProcessor.process(localDocsRoot, localJavadocFile);

        assertFalse(processingOutcome.processed());
        assertTrue(processingOutcome.failure().isPresent());
        verify(ingestionFixture.ingestedFilePruneService, never())
                .pruneObsoleteStateAfterReplacement(any(), anyString(), any(), any());
        verify(ingestionFixture.localStoreService, never()).deleteChunkIngestionMarkers(any());
        verify(ingestionFixture.localStoreService, never()).markHashIngested(anyString(), anyString(), anyString());
        verify(ingestionFixture.fileIngestionMarkerStore, never()).markFileIngested(anyString(), any());
        verify(ingestionFixture.fileIngestionMarkerStore, never()).deleteFileIngestionRecord(anyString());
        verify(ingestionFixture.hybridVectorService, never()).deleteByUrl(anyString(), anyString());
    }

    @Test
    void shouldReplaceUnmarkedJavadocVectorsBeforePruningSupersededCollections(@TempDir Path temporaryDirectory)
            throws IOException {
        JavaApiDocumentationSource javaApiDocumentationSource =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst();
        Path localDocsRoot = temporaryDirectory.resolve("data").resolve("docs");
        Path localJavadocFile =
                writeJavaApiFile(localDocsRoot, javaApiDocumentationSource, JAVA_API_RELATIVE_PATH, javaApiHtml());
        String expectedJavadocUrl = javaApiDocumentationSource.remoteBaseUrl() + JAVA_API_RELATIVE_PATH;

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        Document indexedDocument = new Document("javadoc-point", "Javadoc body", new HashMap<>());
        ChunkProcessingService.ChunkProcessingOutcome forcedChunkingOutcome =
                new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(indexedDocument), List.of("current-javadoc-hash"), 1, 0);
        when(ingestionFixture.fileIngestionMarkerStore.readFileIngestionRecord(expectedJavadocUrl))
                .thenReturn(Optional.empty());
        for (QdrantCollectionKind governedCollectionKind : QdrantCollectionKind.values()) {
            when(ingestionFixture.hybridVectorService.resolveCollectionName(governedCollectionKind))
                    .thenReturn(testCollectionName(governedCollectionKind));
        }
        when(ingestionFixture.hybridVectorService.countPointsForUrl(anyString(), eq(expectedJavadocUrl)))
                .thenReturn(1L);
        when(ingestionFixture.chunkProcessingService.processAndStoreJavaApiPageForce(any()))
                .thenReturn(forcedChunkingOutcome);

        LocalDocsFileIngestionProcessor ingestionProcessor = ingestionFixture.ingestionProcessor();

        LocalDocsFileOutcome processingOutcome = ingestionProcessor.process(localDocsRoot, localJavadocFile);

        assertTrue(processingOutcome.processed());
        IngestionProvenanceDeriver.IngestionProvenance ingestionProvenance =
                new IngestionProvenanceDeriver().derive(localDocsRoot, localJavadocFile, expectedJavadocUrl);
        QdrantCollectionKind routedCollectionKind = new QdrantCollectionRouter()
                .route(
                        ingestionProvenance.docSet(),
                        ingestionProvenance.docPath(),
                        ingestionProvenance.docType(),
                        expectedJavadocUrl);
        String routedCollectionName = testCollectionName(routedCollectionKind);
        List<String> supersededCollectionNames = Arrays.stream(QdrantCollectionKind.values())
                .map(LocalDocsFileIngestionProcessorTest::testCollectionName)
                .filter(collectionName -> !collectionName.equals(routedCollectionName))
                .toList();
        verify(ingestionFixture.hybridVectorService)
                .replaceUrlDocuments(routedCollectionKind, expectedJavadocUrl, List.of(indexedDocument));
        verify(ingestionFixture.ingestedFilePruneService)
                .pruneObsoleteStateAfterReplacement(
                        eq(supersededCollectionNames),
                        eq(expectedJavadocUrl),
                        isNull(),
                        eq(List.of("current-javadoc-hash")));
        for (String governedCollectionName : Arrays.stream(QdrantCollectionKind.values())
                .map(LocalDocsFileIngestionProcessorTest::testCollectionName)
                .toList()) {
            verify(ingestionFixture.hybridVectorService).countPointsForUrl(governedCollectionName, expectedJavadocUrl);
        }
        verify(ingestionFixture.chunkProcessingService).processAndStoreJavaApiPageForce(any());
        verify(ingestionFixture.chunkProcessingService, never()).processAndStoreJavaApiPage(any());
    }

    @Test
    void shouldReplaceRevertedJavadocWhenEqualSizedStoredCorpusHasDifferentPointIds(@TempDir Path temporaryDirectory)
            throws IOException {
        JavaApiDocumentationSource javaApiDocumentationSource =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst();
        Path localDocsRoot = temporaryDirectory.resolve("data").resolve("docs");
        Path localJavadocFile =
                writeJavaApiFile(localDocsRoot, javaApiDocumentationSource, JAVA_API_RELATIVE_PATH, javaApiHtml());
        String expectedJavadocUrl = javaApiDocumentationSource.remoteBaseUrl() + JAVA_API_RELATIVE_PATH;

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        Document indexedDocument = new Document("javadoc-point", "Javadoc body", new HashMap<>());
        ChunkProcessingService.ChunkProcessingOutcome initialChunkingOutcome =
                new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(indexedDocument), List.of("initial-javadoc-hash"), 1, 0);
        ChunkProcessingService.ChunkProcessingOutcome forcedChunkingOutcome =
                new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(indexedDocument), List.of("forced-javadoc-hash"), 1, 0);
        when(ingestionFixture.fileIngestionMarkerStore.readFileIngestionRecord(expectedJavadocUrl))
                .thenReturn(Optional.empty());
        when(ingestionFixture.hybridVectorService.resolveCollectionName(any())).thenReturn("java-api-docs");
        when(ingestionFixture.hybridVectorService.countPointsForUrl(anyString(), eq(expectedJavadocUrl)))
                .thenReturn(0L);
        when(ingestionFixture.chunkProcessingService.processAndStoreJavaApiPage(any()))
                .thenReturn(initialChunkingOutcome);
        when(ingestionFixture.chunkProcessingService.processAndStoreJavaApiPageForce(any()))
                .thenReturn(forcedChunkingOutcome);

        LocalDocsFileIngestionProcessor ingestionProcessor = ingestionFixture.ingestionProcessor();

        assertTrue(ingestionProcessor.process(localDocsRoot, localJavadocFile).processed());
        ArgumentCaptor<FileIngestionRecord> initialMarkerCaptor = ArgumentCaptor.forClass(FileIngestionRecord.class);
        verify(ingestionFixture.fileIngestionMarkerStore)
                .markFileIngested(eq(expectedJavadocUrl), initialMarkerCaptor.capture());
        FileIngestionRecord initialIngestionRecord = initialMarkerCaptor.getValue();
        when(ingestionFixture.fileIngestionMarkerStore.readFileIngestionRecord(expectedJavadocUrl))
                .thenReturn(Optional.of(initialIngestionRecord));
        List<String> revertedPointUuids = List.of(new ContentHasher().uuidFromHash("initial-javadoc-hash"));
        when(ingestionFixture.hybridVectorService.hasExactPointIdsForUrl(
                        any(QdrantCollectionKind.class), eq(expectedJavadocUrl), eq(revertedPointUuids)))
                .thenReturn(false);

        LocalDocsFileOutcome reindexOutcome = ingestionProcessor.process(localDocsRoot, localJavadocFile);

        assertTrue(reindexOutcome.processed());
        verify(ingestionFixture.hybridVectorService)
                .replaceUrlDocuments(
                        any(QdrantCollectionKind.class), eq(expectedJavadocUrl), eq(List.of(indexedDocument)));
        verify(ingestionFixture.ingestedFilePruneService)
                .pruneObsoleteStateAfterReplacement(
                        List.of(), expectedJavadocUrl, initialIngestionRecord, List.of("forced-javadoc-hash"));
        verify(ingestionFixture.hybridVectorService)
                .hasExactPointIdsForUrl(
                        any(QdrantCollectionKind.class), eq(expectedJavadocUrl), eq(revertedPointUuids));
        verify(ingestionFixture.chunkProcessingService).processAndStoreJavaApiPageForce(any());
    }

    @Test
    void shouldPruneAndMarkClassUsePageAsExcludedWithoutRetryingOrQuarantining(@TempDir Path temporaryDirectory)
            throws IOException {
        JavaApiDocumentationSource javaApiDocumentationSource =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst();
        Path localDocsRoot = temporaryDirectory.resolve("data").resolve("docs");
        Path classUseFile = writeJavaApiFile(
                localDocsRoot, javaApiDocumentationSource, JAVA_API_CLASS_USE_RELATIVE_PATH, classUseJavaApiHtml());
        String expectedClassUseUrl = javaApiDocumentationSource.remoteBaseUrl() + JAVA_API_CLASS_USE_RELATIVE_PATH;
        FileIngestionRecord staleIngestionRecord = new FileIngestionRecord(
                Files.size(classUseFile),
                Files.getLastModifiedTime(classUseFile).toMillis(),
                "legacy-class-use-fingerprint",
                "utf8-document-extraction-provenance-v2",
                "java-api-docs",
                List.of("legacy-class-use-hash"));

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        when(ingestionFixture.fileIngestionMarkerStore.readFileIngestionRecord(expectedClassUseUrl))
                .thenReturn(Optional.of(staleIngestionRecord));
        when(ingestionFixture.hybridVectorService.resolveCollectionName(any())).thenReturn("java-api-docs");

        LocalDocsFileIngestionProcessor ingestionProcessor = ingestionFixture.ingestionProcessor();

        LocalDocsFileOutcome firstOutcome = ingestionProcessor.process(localDocsRoot, classUseFile);

        assertFalse(firstOutcome.processed());
        assertTrue(firstOutcome.failure().isEmpty());
        verify(ingestionFixture.hybridVectorService)
                .deleteByUrl(any(QdrantCollectionKind.class), eq(expectedClassUseUrl));
        verify(ingestionFixture.ingestedFilePruneService)
                .pruneObsoleteStateAfterReplacement(List.of(), expectedClassUseUrl, staleIngestionRecord, List.of());
        ArgumentCaptor<FileIngestionRecord> excludedMarkerCaptor = ArgumentCaptor.forClass(FileIngestionRecord.class);
        verify(ingestionFixture.fileIngestionMarkerStore)
                .markFileIngested(eq(expectedClassUseUrl), excludedMarkerCaptor.capture());
        FileIngestionRecord excludedIngestionRecord = excludedMarkerCaptor.getValue();
        assertEquals(
                LocalDocsFileIngestionProcessor.LOCAL_DOCS_EXTRACTION_SEMANTICS_VERSION,
                excludedIngestionRecord.extractionSemanticsVersion());
        assertTrue(excludedIngestionRecord.chunkHashes().isEmpty());
        verify(ingestionFixture.chunkProcessingService, never()).processAndStoreJavaApiPage(any());
        verify(ingestionFixture.chunkProcessingService, never()).processAndStoreJavaApiPageForce(any());
        verify(ingestionFixture.quarantineService, never()).quarantine(any());

        when(ingestionFixture.fileIngestionMarkerStore.readFileIngestionRecord(expectedClassUseUrl))
                .thenReturn(Optional.of(excludedIngestionRecord));
        when(ingestionFixture.hybridVectorService.hasExactPointIdsForUrl(
                        any(QdrantCollectionKind.class), eq(expectedClassUseUrl), eq(List.of())))
                .thenReturn(true);

        LocalDocsFileOutcome repeatedOutcome = ingestionProcessor.process(localDocsRoot, classUseFile);

        assertFalse(repeatedOutcome.processed());
        assertTrue(repeatedOutcome.failure().isEmpty());
        verify(ingestionFixture.ingestedFilePruneService, times(1))
                .pruneObsoleteStateAfterReplacement(List.of(), expectedClassUseUrl, staleIngestionRecord, List.of());
        verify(ingestionFixture.fileIngestionMarkerStore, times(1)).markFileIngested(eq(expectedClassUseUrl), any());
        verify(ingestionFixture.quarantineService, never()).quarantine(any());
    }

    @Test
    void shouldReturnMarkerTransitionFailureWhenExcludedClassUseMarkerWriteFails(@TempDir Path temporaryDirectory)
            throws IOException {
        JavaApiDocumentationSource javaApiDocumentationSource =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst();
        Path localDocsRoot = temporaryDirectory.resolve("data").resolve("docs");
        Path classUseFile = writeJavaApiFile(
                localDocsRoot, javaApiDocumentationSource, JAVA_API_CLASS_USE_RELATIVE_PATH, classUseJavaApiHtml());
        String expectedClassUseUrl = javaApiDocumentationSource.remoteBaseUrl() + JAVA_API_CLASS_USE_RELATIVE_PATH;

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        when(ingestionFixture.fileIngestionMarkerStore.readFileIngestionRecord(expectedClassUseUrl))
                .thenReturn(Optional.empty());
        when(ingestionFixture.hybridVectorService.resolveCollectionName(any())).thenReturn("java-api-docs");
        doThrow(new IOException("marker write failed"))
                .when(ingestionFixture.fileIngestionMarkerStore)
                .markFileIngested(eq(expectedClassUseUrl), any(FileIngestionRecord.class));

        LocalDocsFileIngestionProcessor ingestionProcessor = ingestionFixture.ingestionProcessor();

        LocalDocsFileOutcome markerTransitionOutcome =
                assertDoesNotThrow(() -> ingestionProcessor.process(localDocsRoot, classUseFile));

        assertFalse(markerTransitionOutcome.processed());
        assertEquals(
                "marker-transition",
                markerTransitionOutcome.failure().orElseThrow().phase());
    }

    @Test
    void shouldReturnMarkerTransitionFailureWhenSkippedFileMarkerWriteFails(@TempDir Path temporaryDirectory)
            throws IOException {
        JavaApiDocumentationSource javaApiDocumentationSource =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst();
        Path localDocsRoot = temporaryDirectory.resolve("data").resolve("docs");
        Path localJavadocFile =
                writeJavaApiFile(localDocsRoot, javaApiDocumentationSource, JAVA_API_RELATIVE_PATH, javaApiHtml());
        String expectedJavadocUrl = javaApiDocumentationSource.remoteBaseUrl() + JAVA_API_RELATIVE_PATH;
        List<String> existingChunkHashes = List.of("existing-javadoc-hash");

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        when(ingestionFixture.fileIngestionMarkerStore.readFileIngestionRecord(expectedJavadocUrl))
                .thenReturn(Optional.empty());
        when(ingestionFixture.hybridVectorService.resolveCollectionName(any())).thenReturn("java-api-docs");
        when(ingestionFixture.chunkProcessingService.processAndStoreJavaApiPage(any()))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(List.of(), existingChunkHashes, 1, 1));
        when(ingestionFixture.hybridVectorService.hasExactPointIdsForUrl(
                        any(QdrantCollectionKind.class), eq(expectedJavadocUrl), any()))
                .thenReturn(true);
        doThrow(new IOException("marker write failed"))
                .when(ingestionFixture.fileIngestionMarkerStore)
                .markFileIngested(eq(expectedJavadocUrl), any(FileIngestionRecord.class));

        LocalDocsFileOutcome markerTransitionOutcome = assertDoesNotThrow(
                () -> ingestionFixture.ingestionProcessor().process(localDocsRoot, localJavadocFile));

        assertFalse(markerTransitionOutcome.processed());
        assertEquals(
                "marker-transition",
                markerTransitionOutcome.failure().orElseThrow().phase());
        verify(ingestionFixture.chunkProcessingService, never()).processAndStoreJavaApiPageForce(any());
    }

    @Test
    void shouldReprocessMarkerWhoseFingerprintOmitsCanonicalProvenance(@TempDir Path temporaryDirectory)
            throws IOException {
        DocumentationSource documentationSource =
                DocsSourceRegistry.documentationSources().getFirst();
        Path localDocsRoot = temporaryDirectory.resolve("data").resolve("docs");
        Path documentationFile =
                localDocsRoot.resolve(documentationSource.relativeMirrorPath()).resolve("index.html");
        Files.createDirectories(Objects.requireNonNull(documentationFile.getParent(), "documentationFile parent"));
        Files.writeString(documentationFile, javaApiHtml(), StandardCharsets.UTF_8);

        String expectedDocumentationUrl = documentationSource.citationBaseUrl() + "index.html";
        String contentOnlyIngestionFingerprint = "documentation-fingerprint";
        FileIngestionRecord contentOnlyMarker = new FileIngestionRecord(
                Files.size(documentationFile),
                Files.getLastModifiedTime(documentationFile).toMillis(),
                contentOnlyIngestionFingerprint,
                LocalDocsFileIngestionProcessor.LOCAL_DOCS_EXTRACTION_SEMANTICS_VERSION,
                "prior-documentation",
                List.of("old-documentation-hash"));

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        Document indexedDocument = new Document("documentation-point", "Documentation body", new HashMap<>());
        ChunkProcessingService.ChunkProcessingOutcome forcedChunkingOutcome =
                new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(indexedDocument), List.of("current-documentation-hash"), 1, 0);

        when(ingestionFixture.fileIngestionMarkerStore.readFileIngestionRecord(expectedDocumentationUrl))
                .thenReturn(Optional.of(contentOnlyMarker));
        when(ingestionFixture.hybridVectorService.resolveCollectionName(any())).thenReturn("documentation");
        when(ingestionFixture.chunkProcessingService.processAndStoreChunksForce(
                        anyString(), eq(expectedDocumentationUrl), anyString(), anyString()))
                .thenReturn(forcedChunkingOutcome);

        LocalDocsFileIngestionProcessor ingestionProcessor = ingestionFixture.ingestionProcessor();

        LocalDocsFileOutcome processingOutcome = ingestionProcessor.process(localDocsRoot, documentationFile);

        assertTrue(processingOutcome.processed());
        verify(ingestionFixture.hybridVectorService)
                .replaceUrlDocuments(
                        any(QdrantCollectionKind.class), eq(expectedDocumentationUrl), eq(List.of(indexedDocument)));
        verify(ingestionFixture.ingestedFilePruneService)
                .pruneObsoleteStateAfterReplacement(
                        List.of("prior-documentation"),
                        expectedDocumentationUrl,
                        contentOnlyMarker,
                        List.of("current-documentation-hash"));
        verify(ingestionFixture.chunkProcessingService)
                .processAndStoreChunksForce(anyString(), eq(expectedDocumentationUrl), anyString(), anyString());
        ArgumentCaptor<FileIngestionRecord> updatedMarkerCaptor = ArgumentCaptor.forClass(FileIngestionRecord.class);
        verify(ingestionFixture.fileIngestionMarkerStore)
                .markFileIngested(eq(expectedDocumentationUrl), updatedMarkerCaptor.capture());
        assertNotEquals(
                contentOnlyIngestionFingerprint, updatedMarkerCaptor.getValue().ingestionFingerprint());
        assertEquals(
                contentOnlyMarker.extractionSemanticsVersion(),
                updatedMarkerCaptor.getValue().extractionSemanticsVersion());
        assertEquals("documentation", updatedMarkerCaptor.getValue().collectionName());
    }

    @Test
    void shouldPruneEveryGovernedCollectionForLegacyMarkerWithoutCollectionIdentity(@TempDir Path temporaryDirectory)
            throws IOException {
        DocumentationSource documentationSource =
                DocsSourceRegistry.documentationSources().getFirst();
        Path localDocsRoot = temporaryDirectory.resolve("data").resolve("docs");
        Path documentationFile =
                localDocsRoot.resolve(documentationSource.relativeMirrorPath()).resolve("index.html");
        Files.createDirectories(Objects.requireNonNull(documentationFile.getParent(), "documentationFile parent"));
        Files.writeString(documentationFile, javaApiHtml(), StandardCharsets.UTF_8);

        String expectedDocumentationUrl = documentationSource.citationBaseUrl() + "index.html";
        FileIngestionRecord legacyIngestionRecord = new FileIngestionRecord(
                Files.size(documentationFile),
                Files.getLastModifiedTime(documentationFile).toMillis(),
                "legacy-fingerprint",
                LocalDocsFileIngestionProcessor.LOCAL_DOCS_EXTRACTION_SEMANTICS_VERSION,
                "",
                List.of("legacy-documentation-hash"));

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        for (QdrantCollectionKind governedCollectionKind : QdrantCollectionKind.values()) {
            when(ingestionFixture.hybridVectorService.resolveCollectionName(governedCollectionKind))
                    .thenReturn(testCollectionName(governedCollectionKind));
        }
        Document indexedDocument = new Document("documentation-point", "Documentation body", new HashMap<>());
        ChunkProcessingService.ChunkProcessingOutcome forcedChunkingOutcome =
                new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(indexedDocument), List.of("current-documentation-hash"), 1, 0);
        when(ingestionFixture.fileIngestionMarkerStore.readFileIngestionRecord(expectedDocumentationUrl))
                .thenReturn(Optional.of(legacyIngestionRecord));
        when(ingestionFixture.chunkProcessingService.processAndStoreChunksForce(
                        anyString(), eq(expectedDocumentationUrl), anyString(), anyString()))
                .thenReturn(forcedChunkingOutcome);

        LocalDocsFileIngestionProcessor ingestionProcessor = ingestionFixture.ingestionProcessor();

        LocalDocsFileOutcome processingOutcome = ingestionProcessor.process(localDocsRoot, documentationFile);

        assertTrue(processingOutcome.processed());
        IngestionProvenanceDeriver.IngestionProvenance ingestionProvenance =
                new IngestionProvenanceDeriver().derive(localDocsRoot, documentationFile, expectedDocumentationUrl);
        QdrantCollectionKind routedCollectionKind = new QdrantCollectionRouter()
                .route(
                        ingestionProvenance.docSet(),
                        ingestionProvenance.docPath(),
                        ingestionProvenance.docType(),
                        expectedDocumentationUrl);
        String routedCollectionName = testCollectionName(routedCollectionKind);
        List<String> supersededCollectionNames = Arrays.stream(QdrantCollectionKind.values())
                .map(LocalDocsFileIngestionProcessorTest::testCollectionName)
                .filter(collectionName -> !collectionName.equals(routedCollectionName))
                .toList();
        verify(ingestionFixture.hybridVectorService)
                .replaceUrlDocuments(routedCollectionKind, expectedDocumentationUrl, List.of(indexedDocument));
        verify(ingestionFixture.ingestedFilePruneService)
                .pruneObsoleteStateAfterReplacement(
                        supersededCollectionNames,
                        expectedDocumentationUrl,
                        legacyIngestionRecord,
                        List.of("current-documentation-hash"));
        ArgumentCaptor<FileIngestionRecord> updatedMarkerCaptor = ArgumentCaptor.forClass(FileIngestionRecord.class);
        verify(ingestionFixture.fileIngestionMarkerStore)
                .markFileIngested(eq(expectedDocumentationUrl), updatedMarkerCaptor.capture());
        assertEquals(
                testCollectionName(routedCollectionKind),
                updatedMarkerCaptor.getValue().collectionName());
    }

    /** Owns the collaborator graph shared by local documentation ingestion scenarios. */
    private static final class LocalDocsIngestionFixture {
        private final ChunkProcessingService chunkProcessingService = mock(ChunkProcessingService.class);
        private final LocalStoreService localStoreService = mock(LocalStoreService.class);
        private final FileIngestionMarkerStore fileIngestionMarkerStore = mock(FileIngestionMarkerStore.class);
        private final HybridVectorService hybridVectorService = mock(HybridVectorService.class);
        private final IngestedFilePruneService ingestedFilePruneService = mock(IngestedFilePruneService.class);
        private final IngestionQuarantineService quarantineService = mock(IngestionQuarantineService.class);

        private LocalDocsFileIngestionProcessor ingestionProcessor() {
            return new LocalDocsFileIngestionProcessor(
                    new FileContentServices(
                            new HtmlContentExtractor(),
                            mock(PdfContentExtractor.class),
                            new FileOperationsService(),
                            mock(PdfTitleExtractor.class),
                            new HtmlContentGuard(),
                            quarantineService),
                    new IngestionStorageServices(
                            hybridVectorService,
                            chunkProcessingService,
                            new ContentHasher(),
                            localStoreService,
                            fileIngestionMarkerStore,
                            new QdrantCollectionRouter()),
                    mock(ProgressTracker.class),
                    new IngestionProvenanceDeriver(),
                    new LocalIngestionFailureFactory(),
                    ingestedFilePruneService);
        }
    }

    private static String testCollectionName(QdrantCollectionKind collectionKind) {
        return collectionKind.name().toLowerCase(Locale.ROOT) + "-collection";
    }

    private static Path writeJavaApiFile(
            Path localDocsRoot,
            JavaApiDocumentationSource javaApiDocumentationSource,
            String apiRelativePath,
            String html)
            throws IOException {
        Path localJavadocFile = localDocsRoot
                .resolve(javaApiDocumentationSource.relativeMirrorPath())
                .resolve("api")
                .resolve(apiRelativePath);
        Files.createDirectories(Objects.requireNonNull(localJavadocFile.getParent(), "localJavadocFile parent"));
        Files.writeString(localJavadocFile, html, StandardCharsets.UTF_8);
        return localJavadocFile;
    }

    private static String javaApiHtml() {
        return """
            <html>
              <head><title>__JAVA_API_CLASS__</title></head>
              <body class="class-declaration-page">
                <main>
                  <div class="header"><h1 class="title">Class __JAVA_API_CLASS__</h1></div>
                  <section class="class-description" id="class-description">
                    <div class="type-signature">public final class __JAVA_API_CLASS__</div>
                    <div class="block">__JAVA_API_DESCRIPTION__</div>
                  </section>
                  <section class="detail" id="append(java.lang.String)">
                    <h3>append</h3>
                    <div class="member-signature">public StringBuilder __JAVA_API_METHOD__</div>
                    <div class="block">Appends the supplied text.</div>
                  </section>
                </main>
              </body>
            </html>
            """.replace(JAVA_API_CLASS_PLACEHOLDER, JAVA_API_CLASS_NAME)
                .replace(JAVA_API_DESCRIPTION_PLACEHOLDER, JAVA_API_DESCRIPTION)
                .replace(JAVA_API_METHOD_PLACEHOLDER, JAVA_API_METHOD_SIGNATURE);
    }

    private static String classUseJavaApiHtml() {
        return """
            <html>
              <head><title>Uses of Class List</title></head>
              <body class="class-use-page">
                <main><section class="detail" id="java.util">irrelevant usage</section></main>
              </body>
            </html>
            """;
    }
}
