package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.DocsSourceRegistry.JavaApiDocumentationSource;
import com.williamcallahan.javachat.service.ChunkProcessingService;
import com.williamcallahan.javachat.service.ContentHasher;
import com.williamcallahan.javachat.service.FileOperationsService;
import com.williamcallahan.javachat.service.HtmlContentExtractor;
import com.williamcallahan.javachat.service.HybridVectorService;
import com.williamcallahan.javachat.service.LocalStoreService;
import com.williamcallahan.javachat.service.PdfContentExtractor;
import com.williamcallahan.javachat.service.ProgressTracker;
import com.williamcallahan.javachat.service.QdrantCollectionRouter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

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
        String expectedJavadocUrl =
                javaApiDocumentationSource.remoteBaseUrl() + "java.base/java/lang/" + JAVA_API_CLASS_NAME + ".html";
        when(localStoreService.computeFileContentFingerprint(localJavadocFile)).thenReturn("javadoc-fingerprint");
        when(localStoreService.readFileIngestionRecord(expectedJavadocUrl)).thenReturn(Optional.empty());
        when(chunkProcessingService.processAndStoreChunks(
                        anyString(), eq(expectedJavadocUrl), eq(JAVA_API_CLASS_NAME), anyString()))
                .thenReturn(new ChunkProcessingService.ChunkProcessingOutcome(List.of(), List.of(), 0, 0));

        LocalDocsFileIngestionProcessor ingestionProcessor = new LocalDocsFileIngestionProcessor(
                new FileContentServices(
                        new HtmlContentExtractor(),
                        mock(PdfContentExtractor.class),
                        new FileOperationsService(),
                        mock(PdfTitleExtractor.class),
                        new HtmlContentGuard(),
                        mock(IngestionQuarantineService.class)),
                new IngestionStorageServices(
                        mock(HybridVectorService.class),
                        chunkProcessingService,
                        mock(ContentHasher.class),
                        localStoreService,
                        new QdrantCollectionRouter()),
                mock(ProgressTracker.class),
                new IngestionProvenanceDeriver(),
                new LocalIngestionFailureFactory(),
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
