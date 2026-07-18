package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.domain.javaapi.JavadocMemberAnchor;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies chunk processing deduplication behavior for hash and metadata updates.
 */
class ChunkProcessingServiceTest {

    private static final String SOURCE_URL = "https://example.test/reference";
    private static final String UPDATED_TITLE = "Updated Reference Title";
    private static final String PACKAGE_NAME = "com.example.reference";
    private static final String CHUNK_TEXT = "Chunk text used for deterministic hash checks.";
    private static final String JAVA_API_PAGE_URL =
            "https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/util/stream/Stream.html";
    private static final String JAVA_API_PAGE_TITLE = "Interface Stream<T>";
    private static final String JAVA_API_PACKAGE_NAME = "java.util.stream";
    private static final String OVERVIEW_TEXT = "Overview section for the Stream interface.";
    private static final String OVERVIEW_CHUNK_TEXT = "Overview chunk for Stream.";
    private static final String MEMBER_TEXT = "Member section for Stream.map.";
    private static final String MEMBER_FIRST_CHUNK_TEXT = "First Stream.map chunk.";
    private static final String MEMBER_SECOND_CHUNK_TEXT = "Second Stream.map chunk.";
    private static final JavadocMemberAnchor STREAM_MAP_ANCHOR =
            new JavadocMemberAnchor("map(java.util.function.Function)");
    private static final JavadocMemberAnchor STREAM_MAP_TO_DOUBLE_ANCHOR =
            new JavadocMemberAnchor("mapToDouble(java.util.function.ToDoubleFunction)");

    private Chunker chunker;
    private ContentHasher contentHasher;
    private LocalStoreService localStoreService;
    private ChunkProcessingService chunkProcessingService;

    @BeforeEach
    void setUp() {
        chunker = mock(Chunker.class);
        contentHasher = new ContentHasher();
        DocumentFactory documentFactory = new DocumentFactory(contentHasher);
        localStoreService = mock(LocalStoreService.class);
        PdfContentExtractor pdfContentExtractor = mock(PdfContentExtractor.class);
        chunkProcessingService = new ChunkProcessingService(
                chunker, contentHasher, documentFactory, localStoreService, pdfContentExtractor);
    }

    @Test
    void processAndStoreChunks_reingestsWhenMetadataChangedForExistingHash() throws IOException {
        when(chunker.chunkByTokens(anyString(), anyInt(), anyInt())).thenReturn(List.of(CHUNK_TEXT));

        String chunkHash = contentHasher.generateChunkHash(SOURCE_URL, 0, CHUNK_TEXT);
        when(localStoreService.isHashIngested(chunkHash)).thenReturn(true);
        when(localStoreService.hasHashMetadataChanged(chunkHash, UPDATED_TITLE, PACKAGE_NAME))
                .thenReturn(true);

        ChunkProcessingService.ChunkProcessingOutcome processingOutcome =
                chunkProcessingService.processAndStoreChunks("ignored", SOURCE_URL, UPDATED_TITLE, PACKAGE_NAME);

        assertEquals(1, processingOutcome.documents().size());
        assertEquals(0, processingOutcome.skippedChunks());
        assertFalse(processingOutcome
                .documents()
                .getFirst()
                .getMetadata()
                .containsKey(QdrantPayloadFieldSchema.ANCHOR_FIELD));
        verify(localStoreService).saveChunkText(SOURCE_URL, 0, CHUNK_TEXT, chunkHash);
    }

    @Test
    void processAndStoreChunks_skipsWhenHashIngestedAndMetadataUnchanged() throws IOException {
        when(chunker.chunkByTokens(anyString(), anyInt(), anyInt())).thenReturn(List.of(CHUNK_TEXT));

        String chunkHash = contentHasher.generateChunkHash(SOURCE_URL, 0, CHUNK_TEXT);
        when(localStoreService.isHashIngested(chunkHash)).thenReturn(true);
        when(localStoreService.hasHashMetadataChanged(chunkHash, UPDATED_TITLE, PACKAGE_NAME))
                .thenReturn(false);

        ChunkProcessingService.ChunkProcessingOutcome processingOutcome =
                chunkProcessingService.processAndStoreChunks("ignored", SOURCE_URL, UPDATED_TITLE, PACKAGE_NAME);

        assertTrue(processingOutcome.documents().isEmpty());
        assertEquals(1, processingOutcome.skippedChunks());
        verify(localStoreService, never()).saveChunkText(SOURCE_URL, 0, CHUNK_TEXT, chunkHash);
    }

    @Test
    void processAndStoreJavaApiPage_storesExactMemberAnchorWithGlobalChunkIndexes() throws IOException {
        when(chunker.chunkByTokens(eq(OVERVIEW_TEXT), anyInt(), anyInt())).thenReturn(List.of(OVERVIEW_CHUNK_TEXT));
        when(chunker.chunkByTokens(eq(MEMBER_TEXT), anyInt(), anyInt()))
                .thenReturn(List.of(MEMBER_FIRST_CHUNK_TEXT, MEMBER_SECOND_CHUNK_TEXT));

        ChunkProcessingService.JavaApiPage javaApiPage = new ChunkProcessingService.JavaApiPage(
                JAVA_API_PAGE_URL,
                JAVA_API_PAGE_TITLE,
                JAVA_API_PACKAGE_NAME,
                List.of(
                        ChunkProcessingService.JavaApiPageSegment.overview(OVERVIEW_TEXT),
                        ChunkProcessingService.JavaApiPageSegment.member(MEMBER_TEXT, STREAM_MAP_ANCHOR)));

        ChunkProcessingService.ChunkProcessingOutcome processingOutcome =
                chunkProcessingService.processAndStoreJavaApiPage(javaApiPage);

        assertEquals(3, processingOutcome.totalChunks());
        assertEquals(0, processingOutcome.skippedChunks());
        assertEquals(
                List.of(0, 1, 2),
                processingOutcome.documents().stream()
                        .map(document -> document.getMetadata().get(QdrantPayloadFieldSchema.CHUNK_INDEX_FIELD))
                        .toList());
        assertEquals(
                List.of(JAVA_API_PAGE_URL, JAVA_API_PAGE_URL, JAVA_API_PAGE_URL),
                processingOutcome.documents().stream()
                        .map(document -> document.getMetadata().get(QdrantPayloadFieldSchema.URL_FIELD))
                        .toList());
        assertFalse(processingOutcome
                .documents()
                .getFirst()
                .getMetadata()
                .containsKey(QdrantPayloadFieldSchema.ANCHOR_FIELD));
        assertEquals(
                List.of(STREAM_MAP_ANCHOR.domIdentifier(), STREAM_MAP_ANCHOR.domIdentifier()),
                processingOutcome.documents().subList(1, 3).stream()
                        .map(document -> document.getMetadata().get(QdrantPayloadFieldSchema.ANCHOR_FIELD))
                        .toList());
        assertEquals(
                contentHasher.generateChunkHash(JAVA_API_PAGE_URL, 0, OVERVIEW_CHUNK_TEXT),
                processingOutcome.allChunkHashes().getFirst());
        assertNotEquals(
                contentHasher.generateChunkHash(JAVA_API_PAGE_URL, 1, MEMBER_FIRST_CHUNK_TEXT),
                processingOutcome.allChunkHashes().get(1));
        assertNotEquals(
                contentHasher.generateChunkHash(JAVA_API_PAGE_URL, 2, MEMBER_SECOND_CHUNK_TEXT),
                processingOutcome.allChunkHashes().get(2));
        assertTrue(QdrantPayloadFieldSchema.STRING_METADATA_FIELDS.contains(QdrantPayloadFieldSchema.ANCHOR_FIELD));
        verify(localStoreService)
                .saveChunkText(
                        JAVA_API_PAGE_URL,
                        0,
                        OVERVIEW_CHUNK_TEXT,
                        contentHasher.generateChunkHash(JAVA_API_PAGE_URL, 0, OVERVIEW_CHUNK_TEXT));
        verify(localStoreService)
                .saveChunkText(
                        JAVA_API_PAGE_URL,
                        1,
                        MEMBER_FIRST_CHUNK_TEXT,
                        processingOutcome.allChunkHashes().get(1));
        verify(localStoreService)
                .saveChunkText(
                        JAVA_API_PAGE_URL,
                        2,
                        MEMBER_SECOND_CHUNK_TEXT,
                        processingOutcome.allChunkHashes().get(2));
    }

    @Test
    void processAndStoreJavaApiPageForce_recreatesAnchoredChunksAfterPruning() throws IOException {
        when(chunker.chunkByTokens(eq(MEMBER_TEXT), anyInt(), anyInt())).thenReturn(List.of(MEMBER_FIRST_CHUNK_TEXT));

        ChunkProcessingService.ChunkProcessingOutcome processingOutcome =
                chunkProcessingService.processAndStoreJavaApiPageForce(new ChunkProcessingService.JavaApiPage(
                        JAVA_API_PAGE_URL,
                        JAVA_API_PAGE_TITLE,
                        JAVA_API_PACKAGE_NAME,
                        List.of(ChunkProcessingService.JavaApiPageSegment.member(MEMBER_TEXT, STREAM_MAP_ANCHOR))));

        assertEquals(1, processingOutcome.documents().size());
        assertEquals(
                STREAM_MAP_ANCHOR.domIdentifier(),
                processingOutcome.documents().getFirst().getMetadata().get(QdrantPayloadFieldSchema.ANCHOR_FIELD));
        assertEquals(0, processingOutcome.skippedChunks());
        verify(localStoreService, never()).isHashIngested(anyString());
        verify(localStoreService, never()).hasHashMetadataChanged(anyString(), anyString(), anyString());
        verify(localStoreService)
                .saveChunkText(
                        JAVA_API_PAGE_URL,
                        0,
                        MEMBER_FIRST_CHUNK_TEXT,
                        processingOutcome.allChunkHashes().getFirst());
    }

    @Test
    void processAndStoreJavaApiPage_reindexesIdenticalMemberTextWhenExactAnchorChanges() throws IOException {
        when(chunker.chunkByTokens(eq(MEMBER_TEXT), anyInt(), anyInt())).thenReturn(List.of(MEMBER_FIRST_CHUNK_TEXT));
        ChunkProcessingService.JavaApiPage originalPage = new ChunkProcessingService.JavaApiPage(
                JAVA_API_PAGE_URL,
                JAVA_API_PAGE_TITLE,
                JAVA_API_PACKAGE_NAME,
                List.of(ChunkProcessingService.JavaApiPageSegment.member(MEMBER_TEXT, STREAM_MAP_ANCHOR)));

        ChunkProcessingService.ChunkProcessingOutcome originalProcessingOutcome =
                chunkProcessingService.processAndStoreJavaApiPage(originalPage);
        String originalMemberHash = originalProcessingOutcome.allChunkHashes().getFirst();
        when(localStoreService.isHashIngested(originalMemberHash)).thenReturn(true);
        when(localStoreService.hasHashMetadataChanged(originalMemberHash, JAVA_API_PAGE_TITLE, JAVA_API_PACKAGE_NAME))
                .thenReturn(false);

        ChunkProcessingService.ChunkProcessingOutcome unchangedProcessingOutcome =
                chunkProcessingService.processAndStoreJavaApiPage(originalPage);
        ChunkProcessingService.ChunkProcessingOutcome changedAnchorProcessingOutcome =
                chunkProcessingService.processAndStoreJavaApiPage(new ChunkProcessingService.JavaApiPage(
                        JAVA_API_PAGE_URL,
                        JAVA_API_PAGE_TITLE,
                        JAVA_API_PACKAGE_NAME,
                        List.of(ChunkProcessingService.JavaApiPageSegment.member(
                                MEMBER_TEXT, STREAM_MAP_TO_DOUBLE_ANCHOR))));

        assertTrue(unchangedProcessingOutcome.skippedAllChunks());
        assertEquals(1, changedAnchorProcessingOutcome.documents().size());
        assertEquals(0, changedAnchorProcessingOutcome.skippedChunks());
        assertNotEquals(
                originalMemberHash,
                changedAnchorProcessingOutcome.allChunkHashes().getFirst());
        assertEquals(
                STREAM_MAP_TO_DOUBLE_ANCHOR.domIdentifier(),
                changedAnchorProcessingOutcome
                        .documents()
                        .getFirst()
                        .getMetadata()
                        .get(QdrantPayloadFieldSchema.ANCHOR_FIELD));
    }

    @Test
    void javaApiPage_rejectsFragmentedPageUrl() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ChunkProcessingService.JavaApiPage(
                        JAVA_API_PAGE_URL + "#" + STREAM_MAP_ANCHOR.domIdentifier(),
                        JAVA_API_PAGE_TITLE,
                        JAVA_API_PACKAGE_NAME,
                        List.of(ChunkProcessingService.JavaApiPageSegment.overview(OVERVIEW_TEXT))));
    }
}
