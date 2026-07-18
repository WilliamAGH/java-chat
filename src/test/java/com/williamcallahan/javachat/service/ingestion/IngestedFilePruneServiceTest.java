package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.williamcallahan.javachat.service.ContentHasher;
import com.williamcallahan.javachat.service.FileIngestionMarkerStore;
import com.williamcallahan.javachat.service.FileIngestionMarkerStore.FileIngestionRecord;
import com.williamcallahan.javachat.service.HybridVectorService;
import com.williamcallahan.javachat.service.LocalStoreService;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** Verifies strict multi-collection vector pruning preserves retryable local ingestion state. */
class IngestedFilePruneServiceTest {
    private static final String BOOKS_COLLECTION_NAME = "books-collection";
    private static final String DOCS_COLLECTION_NAME = "docs-collection";
    private static final String SOURCE_URL = "https://docs.example.com/reference/page.html";

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
}
