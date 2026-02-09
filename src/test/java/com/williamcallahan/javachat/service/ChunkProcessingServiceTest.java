package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        when(localStoreService.hasHashMetadataChanged(chunkHash, UPDATED_TITLE, PACKAGE_NAME)).thenReturn(true);

        ChunkProcessingService.ChunkProcessingOutcome processingOutcome =
                chunkProcessingService.processAndStoreChunks("ignored", SOURCE_URL, UPDATED_TITLE, PACKAGE_NAME);

        assertEquals(1, processingOutcome.documents().size());
        assertEquals(0, processingOutcome.skippedChunks());
        verify(localStoreService).saveChunkText(SOURCE_URL, 0, CHUNK_TEXT, chunkHash);
    }

    @Test
    void processAndStoreChunks_skipsWhenHashIngestedAndMetadataUnchanged() throws IOException {
        when(chunker.chunkByTokens(anyString(), anyInt(), anyInt())).thenReturn(List.of(CHUNK_TEXT));

        String chunkHash = contentHasher.generateChunkHash(SOURCE_URL, 0, CHUNK_TEXT);
        when(localStoreService.isHashIngested(chunkHash)).thenReturn(true);
        when(localStoreService.hasHashMetadataChanged(chunkHash, UPDATED_TITLE, PACKAGE_NAME)).thenReturn(false);

        ChunkProcessingService.ChunkProcessingOutcome processingOutcome =
                chunkProcessingService.processAndStoreChunks("ignored", SOURCE_URL, UPDATED_TITLE, PACKAGE_NAME);

        assertTrue(processingOutcome.documents().isEmpty());
        assertEquals(1, processingOutcome.skippedChunks());
        verify(localStoreService, never()).saveChunkText(SOURCE_URL, 0, CHUNK_TEXT, chunkHash);
    }
}

