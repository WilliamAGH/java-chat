package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.DocsSourceRegistry.DocumentationSource;
import com.williamcallahan.javachat.config.DocsSourceRegistry.JavaApiDocumentationSource;
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
import org.springframework.ai.document.Document;

/** Verifies manifest-mapped local Javadoc files use structured Java API extraction. */
class LocalDocsFileIngestionProcessorTest {

    private static final String JAVA_API_CLASS_NAME = "StringBuilder";
    private static final String JAVA_API_METHOD_SIGNATURE = "append(String text)";
    private static final String JAVA_API_CLASS_PLACEHOLDER = "__JAVA_API_CLASS__";
    private static final String JAVA_API_DESCRIPTION_PLACEHOLDER = "__JAVA_API_DESCRIPTION__";
    private static final String JAVA_API_METHOD_PLACEHOLDER = "__JAVA_API_METHOD__";
    private static final int JAVA_API_DESCRIPTION_REPEAT_COUNT = 200;
    private static final String JAVA_API_DESCRIPTION =
            "Detailed Java API documentation explains mutability, character sequences, and method contracts. "
                    .repeat(JAVA_API_DESCRIPTION_REPEAT_COUNT);

    @Test
    void shouldSendStructuredJavadocTextToChunkingForManifestMappedJavaApiFile(@TempDir Path temporaryDirectory)
            throws IOException {
        JavaApiDocumentationSource javaApiDocumentationSource =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst();
        Path localDocsRoot = temporaryDirectory.resolve("data").resolve("docs");
        Path localJavadocFile = localDocsRoot
                .resolve(javaApiDocumentationSource.relativeMirrorPath())
                .resolve("api")
                .resolve("java.base")
                .resolve("java")
                .resolve("lang")
                .resolve(JAVA_API_CLASS_NAME + ".html");
        Files.createDirectories(Objects.requireNonNull(localJavadocFile.getParent(), "localJavadocFile parent"));
        Files.writeString(localJavadocFile, javaApiHtml(), StandardCharsets.UTF_8);

        ChunkProcessingService chunkProcessingService = mock(ChunkProcessingService.class);
        LocalStoreService localStoreService = mock(LocalStoreService.class);
        FileIngestionMarkerStore fileMarkerStore = mock(FileIngestionMarkerStore.class);
        HybridVectorService hybridVectorService = mock(HybridVectorService.class);
        String expectedJavadocUrl =
                javaApiDocumentationSource.remoteBaseUrl() + "java.base/java/lang/" + JAVA_API_CLASS_NAME + ".html";
        when(fileMarkerStore.readFileIngestionRecord(expectedJavadocUrl)).thenReturn(Optional.empty());
        when(hybridVectorService.resolveCollectionName(any())).thenReturn("java-api-docs");
        when(chunkProcessingService.processAndStoreChunks(
                        anyString(), eq(expectedJavadocUrl), eq(JAVA_API_CLASS_NAME), anyString()))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(List.of(), List.of(), 0, 0));

        LocalDocsFileIngestionProcessor ingestionProcessor = createIngestionProcessor(
                chunkProcessingService,
                localStoreService,
                fileMarkerStore,
                hybridVectorService,
                mock(IngestedFilePruneService.class));

        LocalDocsFileOutcome processingOutcome = ingestionProcessor.process(localDocsRoot, localJavadocFile);

        assertFalse(processingOutcome.processed());
        assertTrue(processingOutcome.failure().isPresent());
        ArgumentCaptor<String> extractedJavadocTextCaptor = ArgumentCaptor.forClass(String.class);
        verify(chunkProcessingService)
                .processAndStoreChunks(
                        extractedJavadocTextCaptor.capture(),
                        eq(expectedJavadocUrl),
                        eq(JAVA_API_CLASS_NAME),
                        anyString());
        assertTrue(extractedJavadocTextCaptor.getValue().contains("Method Summary:"));
        assertTrue(extractedJavadocTextCaptor.getValue().contains(JAVA_API_METHOD_SIGNATURE));
    }

    @Test
    void shouldReprocessOldJavadocMarkerWithoutExtractionSemanticsVersion(@TempDir Path temporaryDirectory)
            throws IOException {
        JavaApiDocumentationSource javaApiDocumentationSource =
                DocsSourceRegistry.javaApiDocumentationSources().getFirst();
        Path localDocsRoot = temporaryDirectory.resolve("data").resolve("docs");
        Path localJavadocFile = localDocsRoot
                .resolve(javaApiDocumentationSource.relativeMirrorPath())
                .resolve("api")
                .resolve("java.base")
                .resolve("java")
                .resolve("lang")
                .resolve(JAVA_API_CLASS_NAME + ".html");
        Files.createDirectories(Objects.requireNonNull(localJavadocFile.getParent(), "localJavadocFile parent"));
        Files.writeString(localJavadocFile, javaApiHtml(), StandardCharsets.UTF_8);

        String expectedJavadocUrl =
                javaApiDocumentationSource.remoteBaseUrl() + "java.base/java/lang/" + JAVA_API_CLASS_NAME + ".html";
        long fileSizeBytes = Files.size(localJavadocFile);
        long lastModifiedMillis = Files.getLastModifiedTime(localJavadocFile).toMillis();
        FileIngestionRecord priorIngestionRecord = new FileIngestionRecord(
                fileSizeBytes,
                lastModifiedMillis,
                "javadoc-fingerprint",
                "",
                "java-api-docs",
                List.of("old-javadoc-hash"));

        ChunkProcessingService chunkProcessingService = mock(ChunkProcessingService.class);
        LocalStoreService localStoreService = mock(LocalStoreService.class);
        FileIngestionMarkerStore fileMarkerStore = mock(FileIngestionMarkerStore.class);
        HybridVectorService hybridVectorService = mock(HybridVectorService.class);
        IngestedFilePruneService ingestedFilePruneService = mock(IngestedFilePruneService.class);
        Document indexedDocument = new Document("javadoc-point", "Javadoc body", new HashMap<>());
        ChunkProcessingService.ChunkProcessingOutcome forcedChunkingOutcome =
                new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(indexedDocument), List.of("current-javadoc-hash"), 1, 0);

        when(fileMarkerStore.readFileIngestionRecord(expectedJavadocUrl)).thenReturn(Optional.of(priorIngestionRecord));
        when(hybridVectorService.resolveCollectionName(any())).thenReturn("java-api-docs");
        when(chunkProcessingService.processAndStoreChunksForce(
                        anyString(), eq(expectedJavadocUrl), eq(JAVA_API_CLASS_NAME), anyString()))
                .thenReturn(forcedChunkingOutcome);

        LocalDocsFileIngestionProcessor ingestionProcessor = createIngestionProcessor(
                chunkProcessingService,
                localStoreService,
                fileMarkerStore,
                hybridVectorService,
                ingestedFilePruneService);

        LocalDocsFileOutcome processingOutcome = ingestionProcessor.process(localDocsRoot, localJavadocFile);

        assertTrue(processingOutcome.processed());
        verify(ingestedFilePruneService)
                .pruneCollectionFileStrict("java-api-docs", expectedJavadocUrl, priorIngestionRecord);
        verify(chunkProcessingService)
                .processAndStoreChunksForce(anyString(), eq(expectedJavadocUrl), eq(JAVA_API_CLASS_NAME), anyString());
        verify(chunkProcessingService, never())
                .processAndStoreChunks(anyString(), anyString(), anyString(), anyString());
        ArgumentCaptor<FileIngestionRecord> updatedMarkerCaptor = ArgumentCaptor.forClass(FileIngestionRecord.class);
        verify(fileMarkerStore).markFileIngested(eq(expectedJavadocUrl), updatedMarkerCaptor.capture());
        assertFalse(updatedMarkerCaptor.getValue().extractionSemanticsVersion().isBlank());
        assertEquals("java-api-docs", updatedMarkerCaptor.getValue().collectionName());
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

        ChunkProcessingService chunkProcessingService = mock(ChunkProcessingService.class);
        LocalStoreService localStoreService = mock(LocalStoreService.class);
        FileIngestionMarkerStore fileMarkerStore = mock(FileIngestionMarkerStore.class);
        HybridVectorService hybridVectorService = mock(HybridVectorService.class);
        IngestedFilePruneService ingestedFilePruneService = mock(IngestedFilePruneService.class);
        Document indexedDocument = new Document("documentation-point", "Documentation body", new HashMap<>());
        ChunkProcessingService.ChunkProcessingOutcome forcedChunkingOutcome =
                new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(indexedDocument), List.of("current-documentation-hash"), 1, 0);

        when(fileMarkerStore.readFileIngestionRecord(expectedDocumentationUrl))
                .thenReturn(Optional.of(contentOnlyMarker));
        when(hybridVectorService.resolveCollectionName(any())).thenReturn("documentation");
        when(chunkProcessingService.processAndStoreChunksForce(
                        anyString(), eq(expectedDocumentationUrl), anyString(), anyString()))
                .thenReturn(forcedChunkingOutcome);

        LocalDocsFileIngestionProcessor ingestionProcessor = createIngestionProcessor(
                chunkProcessingService,
                localStoreService,
                fileMarkerStore,
                hybridVectorService,
                ingestedFilePruneService);

        LocalDocsFileOutcome processingOutcome = ingestionProcessor.process(localDocsRoot, documentationFile);

        assertTrue(processingOutcome.processed());
        verify(ingestedFilePruneService)
                .pruneCollectionFileStrict("prior-documentation", expectedDocumentationUrl, contentOnlyMarker);
        verify(chunkProcessingService)
                .processAndStoreChunksForce(anyString(), eq(expectedDocumentationUrl), anyString(), anyString());
        ArgumentCaptor<FileIngestionRecord> updatedMarkerCaptor = ArgumentCaptor.forClass(FileIngestionRecord.class);
        verify(fileMarkerStore).markFileIngested(eq(expectedDocumentationUrl), updatedMarkerCaptor.capture());
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

        ChunkProcessingService chunkProcessingService = mock(ChunkProcessingService.class);
        LocalStoreService localStoreService = mock(LocalStoreService.class);
        FileIngestionMarkerStore fileMarkerStore = mock(FileIngestionMarkerStore.class);
        HybridVectorService hybridVectorService = mock(HybridVectorService.class);
        IngestedFilePruneService ingestedFilePruneService = mock(IngestedFilePruneService.class);
        for (QdrantCollectionKind governedCollectionKind : QdrantCollectionKind.values()) {
            when(hybridVectorService.resolveCollectionName(governedCollectionKind))
                    .thenReturn(testCollectionName(governedCollectionKind));
        }
        Document indexedDocument = new Document("documentation-point", "Documentation body", new HashMap<>());
        ChunkProcessingService.ChunkProcessingOutcome forcedChunkingOutcome =
                new ChunkProcessingService.ChunkProcessingOutcome(
                        List.of(indexedDocument), List.of("current-documentation-hash"), 1, 0);
        when(fileMarkerStore.readFileIngestionRecord(expectedDocumentationUrl))
                .thenReturn(Optional.of(legacyIngestionRecord));
        when(chunkProcessingService.processAndStoreChunksForce(
                        anyString(), eq(expectedDocumentationUrl), anyString(), anyString()))
                .thenReturn(forcedChunkingOutcome);

        LocalDocsFileIngestionProcessor ingestionProcessor = createIngestionProcessor(
                chunkProcessingService,
                localStoreService,
                fileMarkerStore,
                hybridVectorService,
                ingestedFilePruneService);

        LocalDocsFileOutcome processingOutcome = ingestionProcessor.process(localDocsRoot, documentationFile);

        assertTrue(processingOutcome.processed());
        List<String> governedCollectionNames = Arrays.stream(QdrantCollectionKind.values())
                .map(LocalDocsFileIngestionProcessorTest::testCollectionName)
                .toList();
        verify(ingestedFilePruneService)
                .pruneCollectionsFileStrict(governedCollectionNames, expectedDocumentationUrl, legacyIngestionRecord);
        ArgumentCaptor<FileIngestionRecord> updatedMarkerCaptor = ArgumentCaptor.forClass(FileIngestionRecord.class);
        verify(fileMarkerStore).markFileIngested(eq(expectedDocumentationUrl), updatedMarkerCaptor.capture());
        IngestionProvenanceDeriver.IngestionProvenance ingestionProvenance =
                new IngestionProvenanceDeriver().derive(localDocsRoot, documentationFile, expectedDocumentationUrl);
        QdrantCollectionKind routedCollectionKind = new QdrantCollectionRouter()
                .route(
                        ingestionProvenance.docSet(),
                        ingestionProvenance.docPath(),
                        ingestionProvenance.docType(),
                        expectedDocumentationUrl);
        assertEquals(
                testCollectionName(routedCollectionKind),
                updatedMarkerCaptor.getValue().collectionName());
    }

    private static LocalDocsFileIngestionProcessor createIngestionProcessor(
            ChunkProcessingService chunkProcessingService,
            LocalStoreService localStoreService,
            FileIngestionMarkerStore fileMarkerStore,
            HybridVectorService hybridVectorService,
            IngestedFilePruneService ingestedFilePruneService) {
        return new LocalDocsFileIngestionProcessor(
                new FileContentServices(
                        new HtmlContentExtractor(),
                        mock(PdfContentExtractor.class),
                        new FileOperationsService(),
                        mock(PdfTitleExtractor.class),
                        new HtmlContentGuard(),
                        mock(IngestionQuarantineService.class)),
                new IngestionStorageServices(
                        hybridVectorService,
                        chunkProcessingService,
                        new ContentHasher(),
                        localStoreService,
                        fileMarkerStore,
                        new QdrantCollectionRouter()),
                mock(ProgressTracker.class),
                new IngestionProvenanceDeriver(),
                new LocalIngestionFailureFactory(),
                ingestedFilePruneService);
    }

    private static String testCollectionName(QdrantCollectionKind collectionKind) {
        return collectionKind.name().toLowerCase(Locale.ROOT) + "-collection";
    }

    private static String javaApiHtml() {
        return """
                <html>
                  <head><title>__JAVA_API_CLASS__</title></head>
                  <body>
                    <div class="header"><h1>__JAVA_API_CLASS__</h1></div>
                    <div class="subTitle">java.lang</div>
                    <div class="description"><div class="block">__JAVA_API_DESCRIPTION__</div></div>
                    <div class="summary"><table class="memberSummary">
                      <tr><td>__JAVA_API_METHOD__</td><td>Appends the supplied text.</td></tr>
                    </table></div>
                  </body>
                </html>
                """.replace(JAVA_API_CLASS_PLACEHOLDER, JAVA_API_CLASS_NAME)
                .replace(JAVA_API_DESCRIPTION_PLACEHOLDER, JAVA_API_DESCRIPTION)
                .replace(JAVA_API_METHOD_PLACEHOLDER, JAVA_API_METHOD_SIGNATURE);
    }
}
