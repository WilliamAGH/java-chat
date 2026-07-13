package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.SystemPromptConfig;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.support.PdfCitationEnhancer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;

/**
 * Verifies guided citations preserve PDF page anchors for independently retrieved book chunks.
 */
class GuidedLearningServiceCitationTest {
    private static final String FIRST_BOOK_CHUNK_ID = "first-book-chunk";
    private static final int FIRST_BOOK_CHUNK_INDEX = 0;
    private static final int FIRST_BOOK_CHUNK_PAGE = 1;
    private static final String LAST_BOOK_CHUNK_ID = "last-book-chunk";
    private static final String SAME_PAGE_BOOK_CHUNK_ID = "same-page-book-chunk";
    private static final int SAME_PAGE_BOOK_CHUNK_INDEX = 1;
    private static final String THINK_JAVA_PDF_URL =
            "https://example.test/pdfs/Think%20Java%20-%202nd%20Edition%20Book.pdf";
    private static final String THINK_JAVA_SOURCE_CHUNK_TEXT = "Think Java source chunk";
    private static final String THINK_JAVA_TITLE = "Think Java";
    private static final String METADATA_CHUNK_INDEX = "chunkIndex";
    private static final String METADATA_TITLE = "title";
    private static final String METADATA_URL = "url";
    private static final String PDF_PAGE_URL_FRAGMENT_PREFIX = "#page=";
    private static final String PDF_PAGE_ANCHOR_PREFIX = "page=";
    private static final String PARSED_CHUNK_SAFE_NAME = "think-java";
    private static final String PARSED_CHUNK_FILE_PREFIX = PARSED_CHUNK_SAFE_NAME + "_";
    private static final String PARSED_CHUNK_FILE_SUFFIX = ".txt";
    private static final int PARSED_CHUNK_FILE_COUNT = 10;
    private static final int TEST_PDF_PAGE_COUNT = 5;
    private static final String TEST_JDK_VERSION = "25";

    @Test
    void citationsForBookDocumentsRetainsDistinctPageAnchorsAndDeduplicatesSamePage(@TempDir Path parsedChunkDirectory)
            throws IOException {
        createParsedChunkFiles(parsedChunkDirectory);
        LocalStoreService localStoreService = mock(LocalStoreService.class);
        when(localStoreService.toSafeName(THINK_JAVA_PDF_URL)).thenReturn(PARSED_CHUNK_SAFE_NAME);
        when(localStoreService.getParsedDir()).thenReturn(parsedChunkDirectory);
        GuidedLearningService guidedLearningService = new GuidedLearningService(
                mock(GuidedTOCProvider.class),
                retrievalService(),
                mock(EnrichmentService.class),
                mock(ChatService.class),
                mock(SystemPromptConfig.class),
                new FixedPagePdfCitationEnhancer(localStoreService, TEST_PDF_PAGE_COUNT),
                new AppProperties(),
                TEST_JDK_VERSION);

        List<Citation> pageAnchoredCitations = guidedLearningService.citationsForBookDocuments(List.of(
                thinkJavaChunk(FIRST_BOOK_CHUNK_ID, FIRST_BOOK_CHUNK_INDEX),
                thinkJavaChunk(SAME_PAGE_BOOK_CHUNK_ID, SAME_PAGE_BOOK_CHUNK_INDEX),
                thinkJavaChunk(LAST_BOOK_CHUNK_ID, PARSED_CHUNK_FILE_COUNT - 1)));

        assertEquals(
                List.of(
                        THINK_JAVA_PDF_URL + PDF_PAGE_URL_FRAGMENT_PREFIX + FIRST_BOOK_CHUNK_PAGE,
                        THINK_JAVA_PDF_URL + PDF_PAGE_URL_FRAGMENT_PREFIX + TEST_PDF_PAGE_COUNT),
                pageAnchoredCitations.stream().map(Citation::getUrl).toList());
        assertEquals(
                List.of(PDF_PAGE_ANCHOR_PREFIX + FIRST_BOOK_CHUNK_PAGE, PDF_PAGE_ANCHOR_PREFIX + TEST_PDF_PAGE_COUNT),
                pageAnchoredCitations.stream().map(Citation::getAnchor).toList());
    }

    private static RetrievalService retrievalService() {
        return new RetrievalService(
                mock(HybridSearchService.class),
                new AppProperties(),
                mock(RerankerService.class),
                mock(DocumentFactory.class));
    }

    private static void createParsedChunkFiles(Path parsedChunkDirectory) throws IOException {
        for (int chunkIndex = 0; chunkIndex < PARSED_CHUNK_FILE_COUNT; chunkIndex++) {
            Files.createFile(
                    parsedChunkDirectory.resolve(PARSED_CHUNK_FILE_PREFIX + chunkIndex + PARSED_CHUNK_FILE_SUFFIX));
        }
    }

    private static Document thinkJavaChunk(String documentId, int chunkIndex) {
        return Document.builder()
                .id(documentId)
                .text(THINK_JAVA_SOURCE_CHUNK_TEXT)
                .metadata(METADATA_URL, THINK_JAVA_PDF_URL)
                .metadata(METADATA_TITLE, THINK_JAVA_TITLE)
                .metadata(METADATA_CHUNK_INDEX, chunkIndex)
                .build();
    }

    /** Supplies a deterministic page count while retaining the production anchor calculation. */
    private static final class FixedPagePdfCitationEnhancer extends PdfCitationEnhancer {
        private final int pdfPageCount;

        private FixedPagePdfCitationEnhancer(LocalStoreService localStoreService, int pdfPageCount) {
            super(localStoreService);
            this.pdfPageCount = pdfPageCount;
        }

        @Override
        public int getThinkJavaPdfPages() {
            return pdfPageCount;
        }
    }
}
