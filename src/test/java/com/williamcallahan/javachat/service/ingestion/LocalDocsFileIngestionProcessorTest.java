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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
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
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.document.Document;

/** Verifies configured local Javadoc files use structured Java API extraction. */
class LocalDocsFileIngestionProcessorTest {
    private static final int DOCUMENT_COUNT_SPANNING_TWO_EMBEDDING_BATCHES =
            LocalDocsFileIngestionProcessor.MAX_EMBEDDING_BATCH_DOCUMENTS + 1;
    private static final int EXPECTED_EMBEDDING_BATCH_COUNT = 2;

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
    private static final long METADATA_ONLY_MODIFIED_TIME_OFFSET_MILLIS = 1_000L;

    @Test
    void shouldCoalesceThirtyThreeNewFilesIntoTwoHybridUpserts(@TempDir Path temporaryDirectory) throws IOException {
        DocumentationSource documentationSource =
                DocsSourceRegistry.documentationSources().getFirst();
        Path selectedDocumentationRoot =
                temporaryDirectory.resolve("corpus").resolve(documentationSource.relativeMirrorPath());
        Files.createDirectories(selectedDocumentationRoot);
        List<Path> documentationFiles = new ArrayList<>();
        for (int i = 0; i < DOCUMENT_COUNT_SPANNING_TWO_EMBEDDING_BATCHES; i++) {
            Path documentationFile = selectedDocumentationRoot.resolve("documentation-" + i + ".html");
            Files.writeString(documentationFile, javaApiHtml(), StandardCharsets.UTF_8);
            documentationFiles.add(documentationFile);
        }

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        when(ingestionFixture.hybridVectorService.resolveCollectionName(any())).thenReturn("documentation");
        when(ingestionFixture.chunkProcessingService.processAndStoreChunks(
                        anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String sourceUrl = invocation.getArgument(1, String.class);
                    Document indexedDocument = new Document(sourceUrl, "Documentation body", new HashMap<>());
                    return new ChunkProcessingService.ChunkProcessingOutcome(
                            List.of(indexedDocument), List.of(sourceUrl + "-hash"), 1, 0);
                });

        List<LocalDocsFileOutcome> outcomes =
                ingestionFixture.ingestionProcessor().processBatch(selectedDocumentationRoot, documentationFiles);

        assertEquals(DOCUMENT_COUNT_SPANNING_TWO_EMBEDDING_BATCHES, outcomes.size());
        assertTrue(outcomes.stream().allMatch(LocalDocsFileOutcome::processed));
        ArgumentCaptor<List<Document>> documentBatchCaptor = ArgumentCaptor.captor();
        verify(ingestionFixture.hybridVectorService, times(EXPECTED_EMBEDDING_BATCH_COUNT))
                .upsert(eq(QdrantCollectionKind.DOCS), documentBatchCaptor.capture());
        assertEquals(
                LocalDocsFileIngestionProcessor.MAX_EMBEDDING_BATCH_DOCUMENTS,
                documentBatchCaptor.getAllValues().getFirst().size());
        assertEquals(1, documentBatchCaptor.getAllValues().getLast().size());
        verify(ingestionFixture.hybridVectorService, never())
                .replaceUrlDocuments(any(QdrantCollectionKind.class), anyString(), any());
        verify(ingestionFixture.fileIngestionMarkerStore, times(DOCUMENT_COUNT_SPANNING_TWO_EMBEDDING_BATCHES))
                .markFileIngested(anyString(), any(FileIngestionRecord.class));
    }

    @Test
    void shouldKeepDistinctIngestionStateForJavaPagesThatShareOneCitation(@TempDir Path temporaryDirectory)
            throws IOException {
        DocumentationSource javaSourceDocumentation = DocsSourceRegistry.documentationSources().stream()
                .filter(documentationSource -> documentationSource.citationPathStyle()
                        == DocsSourceRegistry.DocumentationCitationPathStyle.JAVA_SOURCE)
                .findFirst()
                .orElseThrow();
        Path localDocsRoot = temporaryDirectory.resolve("data/docs");
        Path sourceMirrorRoot = localDocsRoot.resolve(javaSourceDocumentation.relativeMirrorPath());
        Path outerTypePage = sourceMirrorRoot.resolve("com/fasterxml/jackson/databind/ObjectMapper.html");
        Path nestedTypePage =
                sourceMirrorRoot.resolve("com/fasterxml/jackson/databind/ObjectMapper.DefaultTyping.html");
        Files.createDirectories(Objects.requireNonNull(outerTypePage.getParent(), "outerTypePage parent"));
        Files.writeString(outerTypePage, javaApiHtml(), StandardCharsets.UTF_8);
        Files.writeString(nestedTypePage, javaApiHtml(), StandardCharsets.UTF_8);

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        Map<String, FileIngestionRecord> ingestionRecords = new HashMap<>();
        String legacyCitationUrl = DocsSourceRegistry.resolveMirroredPath(sourceMirrorRoot, outerTypePage)
                .orElseThrow();
        when(ingestionFixture.fileIngestionMarkerStore.readFileIngestionRecord(anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(ingestionRecords.get(invocation.getArgument(0))));
        doAnswer(invocation -> {
                    ingestionRecords.put(invocation.getArgument(0), invocation.getArgument(1));
                    return null;
                })
                .when(ingestionFixture.fileIngestionMarkerStore)
                .markFileIngested(anyString(), any(FileIngestionRecord.class));
        when(ingestionFixture.hybridVectorService.resolveCollectionName(any())).thenReturn("documentation");
        when(ingestionFixture.chunkProcessingService.processAndStoreChunks(
                        anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String sourceUrl = invocation.getArgument(1, String.class);
                    Document indexedDocument = new Document(sourceUrl, "Documentation body", new HashMap<>());
                    return new ChunkProcessingService.ChunkProcessingOutcome(
                            List.of(indexedDocument), List.of(sourceUrl + "-hash"), 1, 0);
                });

        List<LocalDocsFileOutcome> outcomes = ingestionFixture
                .ingestionProcessor()
                .processBatch(sourceMirrorRoot, List.of(outerTypePage, nestedTypePage));

        assertEquals(2, outcomes.size());
        assertTrue(outcomes.stream().allMatch(LocalDocsFileOutcome::processed));
        ArgumentCaptor<String> markerIdentityCaptor = ArgumentCaptor.forClass(String.class);
        verify(ingestionFixture.fileIngestionMarkerStore, times(2))
                .markFileIngested(markerIdentityCaptor.capture(), any(FileIngestionRecord.class));
        List<String> markerIdentities = markerIdentityCaptor.getAllValues();
        assertNotEquals(markerIdentities.getFirst(), markerIdentities.getLast());
        assertEquals(
                DocsSourceRegistry.normalizeDocUrl(markerIdentities.getFirst()),
                DocsSourceRegistry.normalizeDocUrl(markerIdentities.getLast()));
        assertEquals(legacyCitationUrl, markerIdentities.getFirst());
        verify(ingestionFixture.ingestedFilePruneService, never())
                .pruneCollectionFileStrict(anyString(), anyString(), any());

        clearInvocations(
                ingestionFixture.chunkProcessingService,
                ingestionFixture.hybridVectorService,
                ingestionFixture.fileIngestionMarkerStore,
                ingestionFixture.ingestedFilePruneService);
        when(ingestionFixture.hybridVectorService.hasExactPointIdsForUrl(
                        any(QdrantCollectionKind.class), anyString(), any()))
                .thenReturn(true);

        List<LocalDocsFileOutcome> repeatedOutcomes = ingestionFixture
                .ingestionProcessor()
                .processBatch(sourceMirrorRoot, List.of(outerTypePage, nestedTypePage));

        assertEquals(2, repeatedOutcomes.size());
        assertTrue(repeatedOutcomes.stream()
                .allMatch(outcome -> !outcome.processed() && outcome.failure().isEmpty()));
        verify(ingestionFixture.chunkProcessingService, never())
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
        verify(ingestionFixture.hybridVectorService, never()).upsert(any(QdrantCollectionKind.class), any());
        verify(ingestionFixture.hybridVectorService, never())
                .replaceUrlDocuments(any(QdrantCollectionKind.class), anyString(), any());
        verify(ingestionFixture.ingestedFilePruneService, never())
                .pruneCollectionFileStrict(anyString(), anyString(), any());
        verify(ingestionFixture.fileIngestionMarkerStore, never()).markFileIngested(anyString(), any());
    }

    @Test
    void shouldWriteNoMarkersWhenCombinedEmbeddingFails(@TempDir Path temporaryDirectory) throws IOException {
        DocumentationSource documentationSource =
                DocsSourceRegistry.documentationSources().getFirst();
        Path selectedDocumentationRoot =
                temporaryDirectory.resolve("corpus").resolve(documentationSource.relativeMirrorPath());
        Files.createDirectories(selectedDocumentationRoot);
        Path firstDocumentationFile = selectedDocumentationRoot.resolve("first.html");
        Path secondDocumentationFile = selectedDocumentationRoot.resolve("second.html");
        Files.writeString(firstDocumentationFile, javaApiHtml(), StandardCharsets.UTF_8);
        Files.writeString(secondDocumentationFile, javaApiHtml(), StandardCharsets.UTF_8);

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        when(ingestionFixture.hybridVectorService.resolveCollectionName(any())).thenReturn("documentation");
        when(ingestionFixture.chunkProcessingService.processAndStoreChunks(
                        anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String sourceUrl = invocation.getArgument(1, String.class);
                    Document indexedDocument = new Document(sourceUrl, "Documentation body", new HashMap<>());
                    return new ChunkProcessingService.ChunkProcessingOutcome(
                            List.of(indexedDocument), List.of(sourceUrl + "-hash"), 1, 0);
                });
        doThrow(new com.williamcallahan.javachat.service.EmbeddingServiceUnavailableException("gateway unavailable"))
                .when(ingestionFixture.hybridVectorService)
                .upsert(eq(QdrantCollectionKind.DOCS), any());

        List<LocalDocsFileOutcome> outcomes = ingestionFixture
                .ingestionProcessor()
                .processBatch(selectedDocumentationRoot, List.of(firstDocumentationFile, secondDocumentationFile));

        assertEquals(2, outcomes.size());
        assertTrue(outcomes.stream()
                .map(LocalDocsFileOutcome::failure)
                .map(Optional::orElseThrow)
                .allMatch(failure -> failure.phase().equals("embedding-unavailable")));
        verify(ingestionFixture.fileIngestionMarkerStore, never())
                .markFileIngested(anyString(), any(FileIngestionRecord.class));
        verify(ingestionFixture.localStoreService, never()).markHashIngested(anyString(), anyString(), anyString());
    }

    @Test
    void shouldNotRunLaterExcludedPageTransitionWhenEarlierEmbeddingFails(@TempDir Path temporaryDirectory)
            throws IOException {
        DocumentationSource documentationSource =
                DocsSourceRegistry.documentationSources().getFirst();
        JavaApiDocumentationSource javaApiDocumentationSource =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst();
        Path localDocsRoot = temporaryDirectory.resolve("corpus");
        Path documentationFile =
                localDocsRoot.resolve(documentationSource.relativeMirrorPath()).resolve("index.html");
        Files.createDirectories(Objects.requireNonNull(documentationFile.getParent(), "documentationFile parent"));
        Files.writeString(documentationFile, javaApiHtml(), StandardCharsets.UTF_8);
        Path classUseFile = writeJavaApiFile(
                localDocsRoot, javaApiDocumentationSource, JAVA_API_CLASS_USE_RELATIVE_PATH, javaApiHtml());
        String expectedClassUseUrl = javaApiDocumentationSource.remoteBaseUrl() + JAVA_API_CLASS_USE_RELATIVE_PATH;

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        when(ingestionFixture.fileIngestionMarkerStore.readFileIngestionRecord(anyString()))
                .thenReturn(Optional.empty());
        when(ingestionFixture.hybridVectorService.resolveCollectionName(any())).thenReturn("documentation");
        when(ingestionFixture.hybridVectorService.countPointsForUrl(any(QdrantCollectionKind.class), anyString()))
                .thenAnswer(
                        invocation -> expectedClassUseUrl.equals(invocation.getArgument(1, String.class)) ? 1L : 0L);
        when(ingestionFixture.chunkProcessingService.processAndStoreChunks(
                        anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String sourceUrl = invocation.getArgument(1, String.class);
                    Document indexedDocument = new Document(sourceUrl, "Documentation body", new HashMap<>());
                    return new ChunkProcessingService.ChunkProcessingOutcome(
                            List.of(indexedDocument), List.of(sourceUrl + "-hash"), 1, 0);
                });
        doThrow(new com.williamcallahan.javachat.service.EmbeddingServiceUnavailableException("gateway unavailable"))
                .when(ingestionFixture.hybridVectorService)
                .upsert(eq(QdrantCollectionKind.DOCS), any());

        List<LocalDocsFileOutcome> outcomes = ingestionFixture
                .ingestionProcessor()
                .processBatch(localDocsRoot, List.of(documentationFile, classUseFile));

        assertEquals(2, outcomes.size());
        assertTrue(outcomes.stream()
                .map(LocalDocsFileOutcome::failure)
                .map(Optional::orElseThrow)
                .allMatch(failure -> failure.phase().equals("embedding-unavailable")));
        verify(ingestionFixture.hybridVectorService, never()).deleteByUrl(any(QdrantCollectionKind.class), anyString());
        verify(ingestionFixture.ingestedFilePruneService, never())
                .pruneObsoleteLocalStateAfterReplacement(anyString(), any(), any());
        verify(ingestionFixture.fileIngestionMarkerStore, never())
                .markFileIngested(anyString(), any(FileIngestionRecord.class));
    }

    @Test
    void shouldNotQuarantineLaterRejectedPageWhenEarlierEmbeddingFails(@TempDir Path temporaryDirectory)
            throws IOException {
        DocumentationSource documentationSource =
                DocsSourceRegistry.documentationSources().getFirst();
        Path selectedDocumentationRoot =
                temporaryDirectory.resolve("corpus").resolve(documentationSource.relativeMirrorPath());
        Files.createDirectories(selectedDocumentationRoot);
        Path acceptedFile = selectedDocumentationRoot.resolve("accepted.html");
        Path rejectedFile = selectedDocumentationRoot.resolve("rejected.html");
        Files.writeString(acceptedFile, javaApiHtml(), StandardCharsets.UTF_8);
        Files.writeString(
                rejectedFile,
                "<html><head><title>404 Not Found</title></head><body><h1>404 Not Found</h1></body></html>",
                StandardCharsets.UTF_8);

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        when(ingestionFixture.fileIngestionMarkerStore.readFileIngestionRecord(anyString()))
                .thenReturn(Optional.empty());
        when(ingestionFixture.hybridVectorService.resolveCollectionName(any())).thenReturn("documentation");
        when(ingestionFixture.chunkProcessingService.processAndStoreChunks(
                        anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String sourceUrl = invocation.getArgument(1, String.class);
                    Document indexedDocument = new Document(sourceUrl, "Documentation body", new HashMap<>());
                    return new ChunkProcessingService.ChunkProcessingOutcome(
                            List.of(indexedDocument), List.of(sourceUrl + "-hash"), 1, 0);
                });
        doThrow(new com.williamcallahan.javachat.service.EmbeddingServiceUnavailableException("gateway unavailable"))
                .when(ingestionFixture.hybridVectorService)
                .upsert(eq(QdrantCollectionKind.DOCS), any());

        List<LocalDocsFileOutcome> outcomes = ingestionFixture
                .ingestionProcessor()
                .processBatch(selectedDocumentationRoot, List.of(acceptedFile, rejectedFile));

        assertEquals(1, outcomes.size());
        assertEquals(
                "embedding-unavailable",
                outcomes.getFirst().failure().orElseThrow().phase());
        verify(ingestionFixture.quarantineService, never()).quarantine(any(Path.class));
    }

    @Test
    void shouldStopBeforeLaterFileWhenQuarantineWriteFails(@TempDir Path temporaryDirectory) throws IOException {
        DocumentationSource documentationSource =
                DocsSourceRegistry.documentationSources().getFirst();
        Path selectedDocumentationRoot =
                temporaryDirectory.resolve("corpus").resolve(documentationSource.relativeMirrorPath());
        Files.createDirectories(selectedDocumentationRoot);
        Path rejectedFile = selectedDocumentationRoot.resolve("rejected.html");
        Path laterFile = selectedDocumentationRoot.resolve("later.html");
        Files.writeString(
                rejectedFile,
                "<html><head><title>404 Not Found</title></head><body><h1>404 Not Found</h1></body></html>",
                StandardCharsets.UTF_8);
        Files.writeString(laterFile, javaApiHtml(), StandardCharsets.UTF_8);

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        when(ingestionFixture.hybridVectorService.resolveCollectionName(any())).thenReturn("documentation");
        doThrow(new IOException("quarantine storage unavailable"))
                .when(ingestionFixture.quarantineService)
                .quarantine(rejectedFile);

        List<LocalDocsFileOutcome> outcomes = ingestionFixture
                .ingestionProcessor()
                .processBatch(selectedDocumentationRoot, List.of(rejectedFile, laterFile));

        assertEquals(1, outcomes.size());
        assertEquals(
                "quarantine-write", outcomes.getFirst().failure().orElseThrow().phase());
        verify(ingestionFixture.chunkProcessingService, never())
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
        verify(ingestionFixture.fileIngestionMarkerStore, never())
                .markFileIngested(anyString(), any(FileIngestionRecord.class));
    }

    @Test
    void shouldStopBeforeLaterFileWhenContentIsRejected(@TempDir Path temporaryDirectory) throws IOException {
        DocumentationSource documentationSource =
                DocsSourceRegistry.documentationSources().getFirst();
        Path selectedDocumentationRoot =
                temporaryDirectory.resolve("corpus").resolve(documentationSource.relativeMirrorPath());
        Files.createDirectories(selectedDocumentationRoot);
        Path rejectedFile = selectedDocumentationRoot.resolve("rejected.html");
        Path laterFile = selectedDocumentationRoot.resolve("later.html");
        Files.writeString(
                rejectedFile,
                "<html><head><title>404 Not Found</title></head><body><h1>404 Not Found</h1></body></html>",
                StandardCharsets.UTF_8);
        Files.writeString(laterFile, javaApiHtml(), StandardCharsets.UTF_8);

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        when(ingestionFixture.hybridVectorService.resolveCollectionName(any())).thenReturn("documentation");
        when(ingestionFixture.quarantineService.quarantine(rejectedFile))
                .thenReturn(new IngestionQuarantineService.QuarantineResult(
                        rejectedFile, temporaryDirectory.resolve("quarantine/rejected.html")));

        List<LocalDocsFileOutcome> outcomes = ingestionFixture
                .ingestionProcessor()
                .processBatch(selectedDocumentationRoot, List.of(rejectedFile, laterFile));

        assertEquals(1, outcomes.size());
        assertEquals(
                "content-guard", outcomes.getFirst().failure().orElseThrow().phase());
        verify(ingestionFixture.quarantineService).quarantine(rejectedFile);
        verify(ingestionFixture.chunkProcessingService, never())
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldStopBeforeLaterFileWhenChunkStorageFails(@TempDir Path temporaryDirectory) throws IOException {
        DocumentationSource documentationSource =
                DocsSourceRegistry.documentationSources().getFirst();
        Path selectedDocumentationRoot =
                temporaryDirectory.resolve("corpus").resolve(documentationSource.relativeMirrorPath());
        Files.createDirectories(selectedDocumentationRoot);
        Path failedFile = selectedDocumentationRoot.resolve("failed.html");
        Path laterFile = selectedDocumentationRoot.resolve("later.html");
        Files.writeString(failedFile, javaApiHtml(), StandardCharsets.UTF_8);
        Files.writeString(laterFile, javaApiHtml(), StandardCharsets.UTF_8);

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        when(ingestionFixture.hybridVectorService.resolveCollectionName(any())).thenReturn("documentation");
        doThrow(new IOException("chunk text storage unavailable"))
                .when(ingestionFixture.chunkProcessingService)
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());

        List<LocalDocsFileOutcome> outcomes = ingestionFixture
                .ingestionProcessor()
                .processBatch(selectedDocumentationRoot, List.of(failedFile, laterFile));

        assertEquals(1, outcomes.size());
        assertEquals("chunking", outcomes.getFirst().failure().orElseThrow().phase());
        verify(ingestionFixture.chunkProcessingService)
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
        verify(ingestionFixture.hybridVectorService, never()).upsert(any(QdrantCollectionKind.class), any());
        verify(ingestionFixture.fileIngestionMarkerStore, never())
                .markFileIngested(anyString(), any(FileIngestionRecord.class));
    }

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
                .pruneObsoleteLocalStateAfterReplacement(
                        expectedJavadocUrl, priorIngestionRecord, List.of("current-javadoc-hash"));
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
                .pruneObsoleteLocalStateAfterReplacement(anyString(), any(), any());
        verify(ingestionFixture.localStoreService, never()).deleteChunkIngestionMarkers(any());
        verify(ingestionFixture.localStoreService, never()).markHashIngested(anyString(), anyString(), anyString());
        verify(ingestionFixture.fileIngestionMarkerStore, never()).markFileIngested(anyString(), any());
        verify(ingestionFixture.fileIngestionMarkerStore, never()).deleteFileIngestionRecord(anyString());
        verify(ingestionFixture.hybridVectorService, never()).deleteByUrl(anyString(), anyString());
    }

    @Test
    void shouldNotAdvanceMarkersWhenLocalCleanupFailsAfterReplacement(@TempDir Path temporaryDirectory)
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
                List.of("prior-hash"));

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        Document replacementDocument = new Document("replacement-point", "Replacement Javadoc body", new HashMap<>());
        when(ingestionFixture.fileIngestionMarkerStore.readFileIngestionRecord(expectedJavadocUrl))
                .thenReturn(Optional.of(priorIngestionRecord));
        when(ingestionFixture.hybridVectorService.resolveCollectionName(any())).thenReturn("java-api-docs");
        when(ingestionFixture.chunkProcessingService.processAndStoreJavaApiPageForce(any()))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(replacementDocument), List.of("replacement-hash"), 1, 0));
        doThrow(new IOException("local cleanup failed"))
                .when(ingestionFixture.ingestedFilePruneService)
                .pruneObsoleteLocalStateAfterReplacement(
                        expectedJavadocUrl, priorIngestionRecord, List.of("replacement-hash"));

        LocalDocsFileOutcome processingOutcome =
                ingestionFixture.ingestionProcessor().process(localDocsRoot, localJavadocFile);

        assertFalse(processingOutcome.processed());
        assertEquals("prune-local", processingOutcome.failure().orElseThrow().phase());
        verify(ingestionFixture.hybridVectorService)
                .replaceUrlDocuments(
                        any(QdrantCollectionKind.class), eq(expectedJavadocUrl), eq(List.of(replacementDocument)));
        verify(ingestionFixture.fileIngestionMarkerStore, never()).markFileIngested(anyString(), any());
        verify(ingestionFixture.localStoreService, never()).markHashIngested(anyString(), anyString(), anyString());
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
        when(ingestionFixture.hybridVectorService.countPointsForUrl(
                        any(QdrantCollectionKind.class), eq(expectedJavadocUrl)))
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
        verify(ingestionFixture.hybridVectorService)
                .replaceUrlDocuments(routedCollectionKind, expectedJavadocUrl, List.of(indexedDocument));
        verify(ingestionFixture.ingestedFilePruneService)
                .pruneObsoleteLocalStateAfterReplacement(
                        eq(expectedJavadocUrl), isNull(), eq(List.of("current-javadoc-hash")));
        verify(ingestionFixture.hybridVectorService).countPointsForUrl(routedCollectionKind, expectedJavadocUrl);
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
                .pruneObsoleteLocalStateAfterReplacement(
                        expectedJavadocUrl, initialIngestionRecord, List.of("forced-javadoc-hash"));
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
                .pruneObsoleteLocalStateAfterReplacement(expectedClassUseUrl, staleIngestionRecord, List.of());
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
                .pruneObsoleteLocalStateAfterReplacement(expectedClassUseUrl, staleIngestionRecord, List.of());
        verify(ingestionFixture.fileIngestionMarkerStore, times(1)).markFileIngested(eq(expectedClassUseUrl), any());
        verify(ingestionFixture.quarantineService, never()).quarantine(any());
    }

    @Test
    void shouldMarkGenericFramesetNavigationPageAsExcluded(@TempDir Path temporaryDirectory) throws IOException {
        DocumentationSource documentationSource =
                DocsSourceRegistry.documentationSources().getFirst();
        Path selectedDocumentationRoot =
                temporaryDirectory.resolve("corpus").resolve(documentationSource.relativeMirrorPath());
        Files.createDirectories(selectedDocumentationRoot);
        Path navigationFile = selectedDocumentationRoot.resolve("index.html");
        Files.writeString(navigationFile, """
                <html><head><title>Library API</title></head>
                  <frameset cols="20%,80%">
                    <frame src="overview-frame.html">
                    <frame src="overview-summary.html">
                    <noframes>Link to the non-frame overview.</noframes>
                  </frameset>
                </html>
                """, StandardCharsets.UTF_8);
        String expectedUrl = DocsSourceRegistry.normalizeDocUrl(
                "file:///data/docs/" + documentationSource.relativeMirrorPath() + "/index.html");
        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        when(ingestionFixture.hybridVectorService.resolveCollectionName(any())).thenReturn("documentation");

        LocalDocsFileOutcome outcome =
                ingestionFixture.ingestionProcessor().process(selectedDocumentationRoot, navigationFile);

        assertFalse(outcome.processed());
        assertTrue(outcome.failure().isEmpty());
        ArgumentCaptor<FileIngestionRecord> markerCaptor = ArgumentCaptor.forClass(FileIngestionRecord.class);
        verify(ingestionFixture.fileIngestionMarkerStore).markFileIngested(eq(expectedUrl), markerCaptor.capture());
        assertTrue(markerCaptor.getValue().chunkHashes().isEmpty());
        verify(ingestionFixture.chunkProcessingService, never())
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
        verify(ingestionFixture.quarantineService, never()).quarantine(any());
    }

    @Test
    void shouldMarkInteractiveApiReferenceShellAsExcludedAndContinueBatch(@TempDir Path temporaryDirectory)
            throws IOException {
        DocumentationSource documentationSource =
                DocsSourceRegistry.documentationSources().getFirst();
        Path selectedDocumentationRoot =
                temporaryDirectory.resolve("corpus").resolve(documentationSource.relativeMirrorPath());
        Files.createDirectories(selectedDocumentationRoot);
        Path interactiveReferenceFile = selectedDocumentationRoot.resolve("interactive-reference.html");
        Path laterDocumentationFile = selectedDocumentationRoot.resolve("later.html");
        Files.writeString(interactiveReferenceFile, """
                <html>
                  <head>
                    <title>Publisher API reference</title>
                    <link rel="alternate" type="text/markdown" href="reference.md">
                    <link rel="alternate" type="application/yaml" href="reference.yaml">
                  </head>
                  <body>
                    <main><article class="redoc-container"><redoc spec-url="reference.yaml"></redoc></article></main>
                    <script src="https://cdn.redoc.ly/redoc/latest/bundles/redoc.standalone.js"></script>
                  </body>
                </html>
                """, StandardCharsets.UTF_8);
        Files.writeString(laterDocumentationFile, javaApiHtml(), StandardCharsets.UTF_8);
        String expectedUrl = DocsSourceRegistry.normalizeDocUrl(
                "file:///data/docs/" + documentationSource.relativeMirrorPath() + "/interactive-reference.html");
        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        when(ingestionFixture.hybridVectorService.resolveCollectionName(any())).thenReturn("documentation");
        when(ingestionFixture.chunkProcessingService.processAndStoreChunks(
                        anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    String sourceUrl = invocation.getArgument(1, String.class);
                    Document indexedDocument = new Document(sourceUrl, "Documentation body", new HashMap<>());
                    return new ChunkProcessingService.ChunkProcessingOutcome(
                            List.of(indexedDocument), List.of(sourceUrl + "-hash"), 1, 0);
                });

        List<LocalDocsFileOutcome> outcomes = ingestionFixture
                .ingestionProcessor()
                .processBatch(selectedDocumentationRoot, List.of(interactiveReferenceFile, laterDocumentationFile));

        assertEquals(2, outcomes.size());
        assertFalse(outcomes.getFirst().processed());
        assertTrue(outcomes.getFirst().failure().isEmpty());
        assertTrue(outcomes.getLast().processed());
        assertTrue(outcomes.getLast().failure().isEmpty());
        ArgumentCaptor<FileIngestionRecord> markerCaptor = ArgumentCaptor.forClass(FileIngestionRecord.class);
        verify(ingestionFixture.fileIngestionMarkerStore).markFileIngested(eq(expectedUrl), markerCaptor.capture());
        assertTrue(markerCaptor.getValue().chunkHashes().isEmpty());
        verify(ingestionFixture.chunkProcessingService, times(1))
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
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
    void shouldSkipUnchangedFingerprintAfterMetadataOnlyChangeWithExactPointCoverage(@TempDir Path temporaryDirectory)
            throws IOException {
        DocumentationSource documentationSource =
                DocsSourceRegistry.documentationSources().getFirst();
        Path localDocsRoot = temporaryDirectory.resolve("data").resolve("docs");
        Path documentationFile =
                localDocsRoot.resolve(documentationSource.relativeMirrorPath()).resolve("index.html");
        Files.createDirectories(Objects.requireNonNull(documentationFile.getParent(), "documentationFile parent"));
        Files.writeString(documentationFile, javaApiHtml(), StandardCharsets.UTF_8);

        String expectedDocumentationUrl = documentationSource.citationBaseUrl() + "index.html";
        String originalFileText = Files.readString(documentationFile);
        long originalFileSizeBytes = Files.size(documentationFile);
        FileTime originalLastModifiedTime = Files.getLastModifiedTime(documentationFile);

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        Document indexedDocument = new Document("documentation-point", "Documentation body", new HashMap<>());
        List<String> initialChunkHashes = List.of("documentation-chunk-hash");
        when(ingestionFixture.fileIngestionMarkerStore.readFileIngestionRecord(expectedDocumentationUrl))
                .thenReturn(Optional.empty());
        when(ingestionFixture.hybridVectorService.resolveCollectionName(any())).thenReturn("documentation");
        when(ingestionFixture.chunkProcessingService.processAndStoreChunks(
                        anyString(), eq(expectedDocumentationUrl), anyString(), anyString()))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(indexedDocument), initialChunkHashes, 1, 0));

        LocalDocsFileIngestionProcessor ingestionProcessor = ingestionFixture.ingestionProcessor();
        LocalDocsFileOutcome initialOutcome = ingestionProcessor.process(localDocsRoot, documentationFile);

        assertTrue(initialOutcome.processed());
        ArgumentCaptor<FileIngestionRecord> initialMarkerCaptor = ArgumentCaptor.forClass(FileIngestionRecord.class);
        verify(ingestionFixture.fileIngestionMarkerStore)
                .markFileIngested(eq(expectedDocumentationUrl), initialMarkerCaptor.capture());
        FileIngestionRecord initialIngestionRecord = initialMarkerCaptor.getValue();
        assertEquals(originalFileSizeBytes, initialIngestionRecord.fileSizeBytes());
        assertEquals(originalLastModifiedTime.toMillis(), initialIngestionRecord.lastModifiedMillis());

        Files.setLastModifiedTime(
                documentationFile,
                FileTime.fromMillis(originalLastModifiedTime.toMillis() + METADATA_ONLY_MODIFIED_TIME_OFFSET_MILLIS));
        assertEquals(originalFileText, Files.readString(documentationFile));
        assertEquals(originalFileSizeBytes, Files.size(documentationFile));
        assertNotEquals(
                initialIngestionRecord.lastModifiedMillis(),
                Files.getLastModifiedTime(documentationFile).toMillis());
        List<String> expectedPointUuids = initialIngestionRecord.chunkHashes().stream()
                .map(new ContentHasher()::uuidFromHash)
                .toList();

        clearInvocations(
                ingestionFixture.chunkProcessingService,
                ingestionFixture.hybridVectorService,
                ingestionFixture.fileIngestionMarkerStore,
                ingestionFixture.ingestedFilePruneService);
        when(ingestionFixture.fileIngestionMarkerStore.readFileIngestionRecord(expectedDocumentationUrl))
                .thenReturn(Optional.of(initialIngestionRecord));
        when(ingestionFixture.hybridVectorService.hasExactPointIdsForUrl(
                        any(QdrantCollectionKind.class), eq(expectedDocumentationUrl), eq(expectedPointUuids)))
                .thenReturn(true);

        LocalDocsFileOutcome processingOutcome = ingestionProcessor.process(localDocsRoot, documentationFile);

        assertFalse(processingOutcome.processed());
        assertTrue(processingOutcome.failure().isEmpty());
        verify(ingestionFixture.hybridVectorService)
                .hasExactPointIdsForUrl(
                        any(QdrantCollectionKind.class), eq(expectedDocumentationUrl), eq(expectedPointUuids));
        verify(ingestionFixture.hybridVectorService, never()).upsert(any(QdrantCollectionKind.class), any());
        verify(ingestionFixture.hybridVectorService, never())
                .replaceUrlDocuments(any(QdrantCollectionKind.class), anyString(), any());
        verify(ingestionFixture.chunkProcessingService, never())
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
        verify(ingestionFixture.chunkProcessingService, never())
                .processAndStoreChunksForce(anyString(), anyString(), anyString(), anyString());
        verify(ingestionFixture.ingestedFilePruneService, never())
                .pruneObsoleteLocalStateAfterReplacement(anyString(), any(), any());
        verify(ingestionFixture.fileIngestionMarkerStore, never()).markFileIngested(anyString(), any());
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
                "documentation",
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
                .pruneObsoleteLocalStateAfterReplacement(
                        expectedDocumentationUrl, contentOnlyMarker, List.of("current-documentation-hash"));
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
    void shouldRejectLegacyMarkerWithoutCollectionIdentityBeforeMutation(@TempDir Path temporaryDirectory)
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

        assertFalse(processingOutcome.processed());
        assertEquals(
                "collection-generation",
                processingOutcome.failure().orElseThrow().phase());
        verify(ingestionFixture.ingestedFilePruneService, never())
                .pruneObsoleteLocalStateAfterReplacement(anyString(), any(), any());
        verify(ingestionFixture.fileIngestionMarkerStore, never()).markFileIngested(anyString(), any());
        verify(ingestionFixture.chunkProcessingService, never())
                .processAndStoreChunksForce(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void shouldRejectDifferentCollectionGenerationBeforeMutation(@TempDir Path temporaryDirectory) throws IOException {
        DocumentationSource documentationSource =
                DocsSourceRegistry.documentationSources().getFirst();
        Path selectedDocumentationRoot =
                temporaryDirectory.resolve("arbitrary-corpus").resolve(documentationSource.relativeMirrorPath());
        Path documentationFile = selectedDocumentationRoot.resolve("index.html");
        Files.createDirectories(Objects.requireNonNull(documentationFile.getParent(), "documentationFile parent"));
        Files.writeString(documentationFile, javaApiHtml(), StandardCharsets.UTF_8);

        String expectedDocumentationUrl = documentationSource.citationBaseUrl() + "index.html";
        FileIngestionRecord previousGenerationRecord = new FileIngestionRecord(
                Files.size(documentationFile),
                Files.getLastModifiedTime(documentationFile).toMillis(),
                "previous-generation-fingerprint",
                LocalDocsFileIngestionProcessor.LOCAL_DOCS_EXTRACTION_SEMANTICS_VERSION,
                "java-chat-local-previous-generation-docs",
                List.of("previous-generation-hash"));

        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        for (QdrantCollectionKind governedCollectionKind : QdrantCollectionKind.values()) {
            when(ingestionFixture.hybridVectorService.resolveCollectionName(governedCollectionKind))
                    .thenReturn(testCollectionName(governedCollectionKind));
        }
        when(ingestionFixture.fileIngestionMarkerStore.readFileIngestionRecord(expectedDocumentationUrl))
                .thenReturn(Optional.of(previousGenerationRecord));

        LocalDocsFileOutcome processingOutcome =
                ingestionFixture.ingestionProcessor().process(selectedDocumentationRoot, documentationFile);

        assertFalse(processingOutcome.processed());
        assertEquals(
                "collection-generation",
                processingOutcome.failure().orElseThrow().phase());
        verify(ingestionFixture.hybridVectorService, never())
                .replaceUrlDocuments(any(QdrantCollectionKind.class), anyString(), any());
        verify(ingestionFixture.hybridVectorService, never())
                .countPointsForUrl(any(QdrantCollectionKind.class), anyString());
        verify(ingestionFixture.ingestedFilePruneService, never())
                .pruneObsoleteLocalStateAfterReplacement(anyString(), any(), any());
        verify(ingestionFixture.fileIngestionMarkerStore, never()).markFileIngested(anyString(), any());
        verify(ingestionFixture.chunkProcessingService, never())
                .processAndStoreChunksForce(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void prunesRemovedDocumentationUrlsAfterCompleteInventory() throws IOException {
        LocalDocsIngestionFixture ingestionFixture = new LocalDocsIngestionFixture();
        String activeUrl = "https://docs.example.com/reference/active";
        String removedUrl = "https://docs.example.com/reference/removed";
        FileIngestionRecord removedRecord =
                new FileIngestionRecord(10, 20, "fingerprint", "extractor", "docs-collection", List.of("removed-hash"));
        for (QdrantCollectionKind collectionKind : QdrantCollectionKind.values()) {
            when(ingestionFixture.hybridVectorService.resolveCollectionName(collectionKind))
                    .thenReturn(testCollectionName(collectionKind));
            when(ingestionFixture.hybridVectorService.scrollAllUrlsInCollection(testCollectionName(collectionKind)))
                    .thenReturn(collectionKind == QdrantCollectionKind.DOCS ? Set.of(activeUrl, removedUrl) : Set.of());
        }
        when(ingestionFixture.fileIngestionMarkerStore.readFileIngestionRecord(removedUrl))
                .thenReturn(Optional.of(removedRecord));

        ingestionFixture
                .ingestionProcessor()
                .pruneRemovedSourceUrls("https://docs.example.com/reference/", Set.of(activeUrl));

        verify(ingestionFixture.ingestedFilePruneService)
                .pruneCollectionFileStrict("docs-collection", removedUrl, removedRecord);
        verify(ingestionFixture.ingestedFilePruneService, never())
                .pruneCollectionFileStrict(anyString(), eq(activeUrl), any());
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
