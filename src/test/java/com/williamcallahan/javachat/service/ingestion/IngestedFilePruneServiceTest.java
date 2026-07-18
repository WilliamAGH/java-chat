package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.service.ContentHasher;
import com.williamcallahan.javachat.service.FileIngestionMarkerStore;
import com.williamcallahan.javachat.service.FileIngestionMarkerStore.FileIngestionRecord;
import com.williamcallahan.javachat.service.HybridVectorService;
import com.williamcallahan.javachat.service.LocalStoreService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

/** Verifies strict multi-collection vector pruning preserves retryable local ingestion state. */
class IngestedFilePruneServiceTest {
    private static final String BOOKS_COLLECTION_NAME = "books-collection";
    private static final String DOCS_COLLECTION_NAME = "docs-collection";
    private static final String SOURCE_URL = "https://docs.example.com/reference/page.html";
    private static final String OBSOLETE_CHUNK_HASH =
            "fedcba654321cccccccccccccccccccccccccccccccccccccccccccccccccccc";
    private static final String OBSOLETE_CHUNK_HASH_PREFIX = "fedcba654321";
    private static final String REPLACEMENT_CHUNK_HASH =
            "abcdef123456aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String REPLACEMENT_CHUNK_HASH_PREFIX = "abcdef123456";

    @Test
    void deletesEveryCollectionBeforeLocalIngestionState() throws IOException {
        HybridVectorService hybridVectorService = mock(HybridVectorService.class);
        LocalStoreService localStoreService = mock(LocalStoreService.class);
        FileIngestionMarkerStore fileMarkerStore = mock(FileIngestionMarkerStore.class);
        IngestedFilePruneService pruneService = new IngestedFilePruneService(
                hybridVectorService, localStoreService, mock(ContentHasher.class), fileMarkerStore);
        FileIngestionRecord priorIngestionRecord = new FileIngestionRecord(
                123L,
                456L,
                "fingerprint",
                "extraction-v1",
                BOOKS_COLLECTION_NAME,
                List.of("first-hash", "second-hash"));

        pruneService.pruneCollectionsFileStrict(
                List.of(BOOKS_COLLECTION_NAME, DOCS_COLLECTION_NAME), SOURCE_URL, priorIngestionRecord);

        InOrder pruneOrder = inOrder(hybridVectorService, localStoreService, fileMarkerStore);
        pruneOrder.verify(hybridVectorService).deleteByUrl(BOOKS_COLLECTION_NAME, SOURCE_URL);
        pruneOrder.verify(hybridVectorService).deleteByUrl(DOCS_COLLECTION_NAME, SOURCE_URL);
        pruneOrder.verify(localStoreService).deleteChunkIngestionMarkers(priorIngestionRecord.chunkHashes());
        pruneOrder.verify(localStoreService).deleteParsedChunksForUrl(SOURCE_URL);
        pruneOrder.verify(fileMarkerStore).deleteFileIngestionRecord(SOURCE_URL);
    }

    @Test
    void retainsLocalIngestionStateWhenACollectionDeleteFails() throws IOException {
        HybridVectorService hybridVectorService = mock(HybridVectorService.class);
        LocalStoreService localStoreService = mock(LocalStoreService.class);
        FileIngestionMarkerStore fileMarkerStore = mock(FileIngestionMarkerStore.class);
        IngestedFilePruneService pruneService = new IngestedFilePruneService(
                hybridVectorService, localStoreService, mock(ContentHasher.class), fileMarkerStore);
        FileIngestionRecord priorIngestionRecord = new FileIngestionRecord(
                123L, 456L, "fingerprint", "extraction-v1", BOOKS_COLLECTION_NAME, List.of("first-hash"));
        doThrow(new IllegalStateException("Qdrant delete failed"))
                .when(hybridVectorService)
                .deleteByUrl(DOCS_COLLECTION_NAME, SOURCE_URL);

        assertThrows(
                IllegalStateException.class,
                () -> pruneService.pruneCollectionsFileStrict(
                        List.of(BOOKS_COLLECTION_NAME, DOCS_COLLECTION_NAME), SOURCE_URL, priorIngestionRecord));

        verify(hybridVectorService).deleteByUrl(BOOKS_COLLECTION_NAME, SOURCE_URL);
        verify(hybridVectorService).deleteByUrl(DOCS_COLLECTION_NAME, SOURCE_URL);
        verify(localStoreService, never()).deleteChunkIngestionMarkers(anyList());
        verify(localStoreService, never()).deleteParsedChunksForUrl(anyString());
        verify(fileMarkerStore, never()).deleteFileIngestionRecord(anyString());
    }

    @Test
    void retainsReplacementParsedChunkByStoredHashPrefix(@TempDir Path parsedChunkDirectory) throws IOException {
        HybridVectorService hybridVectorService = mock(HybridVectorService.class);
        LocalStoreService localStoreService = mock(LocalStoreService.class);
        FileIngestionMarkerStore fileMarkerStore = mock(FileIngestionMarkerStore.class);
        IngestedFilePruneService pruneService = new IngestedFilePruneService(
                hybridVectorService, localStoreService, new ContentHasher(), fileMarkerStore);
        Path replacementParsedChunk =
                parsedChunkDirectory.resolve("source_0_" + REPLACEMENT_CHUNK_HASH_PREFIX + ".txt");
        Path obsoleteParsedChunk = parsedChunkDirectory.resolve("source_1_" + OBSOLETE_CHUNK_HASH_PREFIX + ".txt");
        Files.writeString(replacementParsedChunk, "anchored member text", StandardCharsets.UTF_8);
        Files.writeString(obsoleteParsedChunk, "obsolete member text", StandardCharsets.UTF_8);
        when(localStoreService.getParsedDir()).thenReturn(parsedChunkDirectory);
        when(localStoreService.toSafeName(SOURCE_URL)).thenReturn("source");
        FileIngestionRecord priorIngestionRecord = new FileIngestionRecord(
                123L,
                456L,
                "fingerprint",
                "extraction-v1",
                DOCS_COLLECTION_NAME,
                List.of(REPLACEMENT_CHUNK_HASH, OBSOLETE_CHUNK_HASH));

        pruneService.pruneObsoleteStateAfterReplacement(
                List.of(), SOURCE_URL, priorIngestionRecord, List.of(REPLACEMENT_CHUNK_HASH));

        assertTrue(Files.exists(replacementParsedChunk));
        assertFalse(Files.exists(obsoleteParsedChunk));
        verify(localStoreService).deleteChunkIngestionMarkers(List.of(OBSOLETE_CHUNK_HASH));
        verify(localStoreService)
                .deleteObsoleteChunkIngestionMarkersByHashPrefixes(
                        List.of(OBSOLETE_CHUNK_HASH_PREFIX), List.of(REPLACEMENT_CHUNK_HASH));
        verify(fileMarkerStore, never()).deleteFileIngestionRecord(anyString());
    }

    @Test
    void resolvesObsoleteMarkerPrefixesWithoutFileRecord(@TempDir Path parsedChunkDirectory) throws IOException {
        HybridVectorService hybridVectorService = mock(HybridVectorService.class);
        LocalStoreService localStoreService = mock(LocalStoreService.class);
        FileIngestionMarkerStore fileMarkerStore = mock(FileIngestionMarkerStore.class);
        IngestedFilePruneService pruneService = new IngestedFilePruneService(
                hybridVectorService, localStoreService, new ContentHasher(), fileMarkerStore);
        Path replacementParsedChunk =
                parsedChunkDirectory.resolve("source_0_" + REPLACEMENT_CHUNK_HASH_PREFIX + ".txt");
        Path obsoleteParsedChunk = parsedChunkDirectory.resolve("source_1_" + OBSOLETE_CHUNK_HASH_PREFIX + ".txt");
        Files.writeString(replacementParsedChunk, "replacement member text", StandardCharsets.UTF_8);
        Files.writeString(obsoleteParsedChunk, "obsolete member text", StandardCharsets.UTF_8);
        when(localStoreService.getParsedDir()).thenReturn(parsedChunkDirectory);
        when(localStoreService.toSafeName(SOURCE_URL)).thenReturn("source");

        pruneService.pruneObsoleteStateAfterReplacement(List.of(), SOURCE_URL, null, List.of(REPLACEMENT_CHUNK_HASH));

        assertTrue(Files.exists(replacementParsedChunk));
        assertFalse(Files.exists(obsoleteParsedChunk));
        verify(localStoreService, never()).deleteChunkIngestionMarkers(anyList());
        verify(localStoreService)
                .deleteObsoleteChunkIngestionMarkersByHashPrefixes(
                        List.of(OBSOLETE_CHUNK_HASH_PREFIX), List.of(REPLACEMENT_CHUNK_HASH));
        verify(fileMarkerStore, never()).deleteFileIngestionRecord(anyString());
    }
}
