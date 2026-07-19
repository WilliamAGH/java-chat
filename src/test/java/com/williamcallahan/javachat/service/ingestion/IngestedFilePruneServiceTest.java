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
import static org.mockito.Mockito.verifyNoInteractions;
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
    private static final int LEGACY_CHUNK_HASH_PREFIX_LENGTH = 12;
    private static final String BOOKS_COLLECTION_NAME = "books-collection";
    private static final String DOCS_COLLECTION_NAME = "docs-collection";
    private static final String SOURCE_URL = "https://docs.example.com/reference/page.html";
    private static final String REPLACEMENT_CHUNK_HASH =
            "abcdef123456aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String COLLIDING_CHUNK_HASH_PREFIX = "abcdef123456";
    private static final String COLLIDING_STALE_CHUNK_HASH =
            "abcdef123456bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String ANCHORED_CHUNK_HASH =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String MARKERLESS_STALE_CHUNK_HASH =
            "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

    @Test
    void deletesEveryCollectionBeforeLocalIngestionState() throws IOException {
        HybridVectorService hybridVectorService = mock(HybridVectorService.class);
        LocalStoreService localStoreService = mock(LocalStoreService.class);
        FileIngestionMarkerStore fileIngestionMarkerStore = mock(FileIngestionMarkerStore.class);
        IngestedFilePruneService pruneService = new IngestedFilePruneService(
                hybridVectorService, localStoreService, fileIngestionMarkerStore, mock(ContentHasher.class));
        FileIngestionRecord priorIngestionRecord = new FileIngestionRecord(
                123L,
                456L,
                "fingerprint",
                "extraction-v1",
                BOOKS_COLLECTION_NAME,
                List.of("first-hash", "second-hash"));

        pruneService.pruneCollectionsFileStrict(
                List.of(BOOKS_COLLECTION_NAME, DOCS_COLLECTION_NAME), SOURCE_URL, priorIngestionRecord);

        InOrder pruneOrder = inOrder(hybridVectorService, localStoreService, fileIngestionMarkerStore);
        pruneOrder.verify(hybridVectorService).deleteByUrl(BOOKS_COLLECTION_NAME, SOURCE_URL);
        pruneOrder.verify(hybridVectorService).deleteByUrl(DOCS_COLLECTION_NAME, SOURCE_URL);
        pruneOrder.verify(localStoreService).deleteChunkIngestionMarkers(priorIngestionRecord.chunkHashes());
        pruneOrder.verify(localStoreService).deleteParsedChunksForUrl(SOURCE_URL);
        pruneOrder.verify(fileIngestionMarkerStore).deleteFileIngestionRecord(SOURCE_URL);
    }

    @Test
    void retainsLocalIngestionStateWhenACollectionDeleteFails() throws IOException {
        HybridVectorService hybridVectorService = mock(HybridVectorService.class);
        LocalStoreService localStoreService = mock(LocalStoreService.class);
        FileIngestionMarkerStore fileIngestionMarkerStore = mock(FileIngestionMarkerStore.class);
        IngestedFilePruneService pruneService = new IngestedFilePruneService(
                hybridVectorService, localStoreService, fileIngestionMarkerStore, mock(ContentHasher.class));
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
        verify(fileIngestionMarkerStore, never()).deleteFileIngestionRecord(anyString());
    }

    @Test
    void removesLegacyStaleParsedChunkWhenFullHashesShareStoredPrefix(@TempDir Path parsedChunkDirectory)
            throws IOException {
        HybridVectorService hybridVectorService = mock(HybridVectorService.class);
        LocalStoreService localStoreService = mock(LocalStoreService.class);
        FileIngestionMarkerStore fileIngestionMarkerStore = mock(FileIngestionMarkerStore.class);
        ContentHasher contentHasher = mock(ContentHasher.class);
        IngestedFilePruneService pruneService = new IngestedFilePruneService(
                hybridVectorService, localStoreService, fileIngestionMarkerStore, contentHasher);
        String legacyStaleText = "legacy stale member text";
        Path staleParsedChunk = parsedChunkDirectory.resolve("source_0_" + COLLIDING_CHUNK_HASH_PREFIX + ".txt");
        Files.writeString(staleParsedChunk, legacyStaleText, StandardCharsets.UTF_8);
        when(localStoreService.getParsedDir()).thenReturn(parsedChunkDirectory);
        when(localStoreService.toSafeName(SOURCE_URL)).thenReturn("source");
        when(contentHasher.generateChunkHash(SOURCE_URL, 0, legacyStaleText)).thenReturn(COLLIDING_STALE_CHUNK_HASH);

        pruneService.pruneObsoleteStateAfterReplacement(List.of(), SOURCE_URL, null, List.of(REPLACEMENT_CHUNK_HASH));

        assertFalse(Files.exists(staleParsedChunk));
        verify(localStoreService).deleteChunkIngestionMarkers(List.of(COLLIDING_STALE_CHUNK_HASH));
        verify(fileIngestionMarkerStore, never()).deleteFileIngestionRecord(anyString());
    }

    @Test
    void reconstructsLegacyPrefixChunkHashesForExactMigration(@TempDir Path parsedChunkDirectory) throws IOException {
        HybridVectorService hybridVectorService = mock(HybridVectorService.class);
        LocalStoreService localStoreService = mock(LocalStoreService.class);
        FileIngestionMarkerStore fileIngestionMarkerStore = mock(FileIngestionMarkerStore.class);
        ContentHasher contentHasher = new ContentHasher();
        IngestedFilePruneService pruneService = new IngestedFilePruneService(
                hybridVectorService, localStoreService, fileIngestionMarkerStore, contentHasher);
        String legacyReplacementText = "legacy replacement member text";
        String legacyStaleText = "legacy stale member text";
        String legacyReplacementHash = contentHasher.generateChunkHash(SOURCE_URL, 0, legacyReplacementText);
        String legacyStaleHash = contentHasher.generateChunkHash(SOURCE_URL, 1, legacyStaleText);
        Path replacementParsedChunk = parsedChunkDirectory.resolve(
                "source_0_" + legacyReplacementHash.substring(0, LEGACY_CHUNK_HASH_PREFIX_LENGTH) + ".txt");
        Path staleParsedChunk = parsedChunkDirectory.resolve(
                "source_1_" + legacyStaleHash.substring(0, LEGACY_CHUNK_HASH_PREFIX_LENGTH) + ".txt");
        Files.writeString(replacementParsedChunk, legacyReplacementText, StandardCharsets.UTF_8);
        Files.writeString(staleParsedChunk, legacyStaleText, StandardCharsets.UTF_8);
        when(localStoreService.getParsedDir()).thenReturn(parsedChunkDirectory);
        when(localStoreService.toSafeName(SOURCE_URL)).thenReturn("source");

        pruneService.pruneObsoleteStateAfterReplacement(List.of(), SOURCE_URL, null, List.of(legacyReplacementHash));

        assertTrue(Files.exists(replacementParsedChunk));
        assertFalse(Files.exists(staleParsedChunk));
        verify(localStoreService).deleteChunkIngestionMarkers(List.of(legacyStaleHash));
        verify(fileIngestionMarkerStore, never()).deleteFileIngestionRecord(anyString());
    }

    @Test
    void retainsAnchoredParsedChunkByItsExactFullHash(@TempDir Path parsedChunkDirectory) throws IOException {
        HybridVectorService hybridVectorService = mock(HybridVectorService.class);
        LocalStoreService localStoreService = mock(LocalStoreService.class);
        FileIngestionMarkerStore fileIngestionMarkerStore = mock(FileIngestionMarkerStore.class);
        IngestedFilePruneService pruneService = new IngestedFilePruneService(
                hybridVectorService, localStoreService, fileIngestionMarkerStore, new ContentHasher());
        Path anchoredParsedChunk = parsedChunkDirectory.resolve("source_0_" + ANCHORED_CHUNK_HASH + ".txt");
        Files.writeString(
                anchoredParsedChunk, "member text whose anchor participates in its hash", StandardCharsets.UTF_8);
        when(localStoreService.getParsedDir()).thenReturn(parsedChunkDirectory);
        when(localStoreService.toSafeName(SOURCE_URL)).thenReturn("source");

        pruneService.pruneObsoleteStateAfterReplacement(List.of(), SOURCE_URL, null, List.of(ANCHORED_CHUNK_HASH));

        assertTrue(Files.exists(anchoredParsedChunk));
        verify(localStoreService, never()).deleteChunkIngestionMarkers(anyList());
    }

    @Test
    void deletesMarkerlessStaleMarkerByParsedFullHash(@TempDir Path parsedChunkDirectory) throws IOException {
        HybridVectorService hybridVectorService = mock(HybridVectorService.class);
        LocalStoreService localStoreService = mock(LocalStoreService.class);
        FileIngestionMarkerStore fileIngestionMarkerStore = mock(FileIngestionMarkerStore.class);
        IngestedFilePruneService pruneService = new IngestedFilePruneService(
                hybridVectorService, localStoreService, fileIngestionMarkerStore, new ContentHasher());
        Path staleParsedChunk = parsedChunkDirectory.resolve("source_0_" + MARKERLESS_STALE_CHUNK_HASH + ".txt");
        Files.writeString(staleParsedChunk, "stale anchored member text", StandardCharsets.UTF_8);
        when(localStoreService.getParsedDir()).thenReturn(parsedChunkDirectory);
        when(localStoreService.toSafeName(SOURCE_URL)).thenReturn("source");

        pruneService.pruneObsoleteStateAfterReplacement(List.of(), SOURCE_URL, null, List.of());

        assertFalse(Files.exists(staleParsedChunk));
        verify(localStoreService).deleteChunkIngestionMarkers(List.of(MARKERLESS_STALE_CHUNK_HASH));
    }

    @Test
    void deletesParsedChunkWithMalformedIndexTokenWithoutDeletingAnUnverifiedMarker(@TempDir Path parsedChunkDirectory)
            throws IOException {
        assertUnverifiedParsedChunkIsDeletedWithoutDeletingItsMarker(
                parsedChunkDirectory, "source_not-an-index_abcdef123456.txt");
    }

    @Test
    void deletesParsedChunkWithUnsupportedHashLengthWithoutDeletingAnUnverifiedMarker(
            @TempDir Path parsedChunkDirectory) throws IOException {
        assertUnverifiedParsedChunkIsDeletedWithoutDeletingItsMarker(
                parsedChunkDirectory, "source_0_abcdef1234567.txt");
    }

    @Test
    void deletesParsedChunkWithInvalidHexadecimalHashWithoutDeletingAnUnverifiedMarker(
            @TempDir Path parsedChunkDirectory) throws IOException {
        assertUnverifiedParsedChunkIsDeletedWithoutDeletingItsMarker(parsedChunkDirectory, "source_0_abcdef12345g.txt");
    }

    @Test
    void deletesParsedChunkWithoutHashSuffixWithoutDeletingAnUnverifiedMarker(@TempDir Path parsedChunkDirectory)
            throws IOException {
        assertUnverifiedParsedChunkIsDeletedWithoutDeletingItsMarker(parsedChunkDirectory, "source_0_.txt");
    }

    private void assertUnverifiedParsedChunkIsDeletedWithoutDeletingItsMarker(
            Path parsedChunkDirectory, String unverifiedParsedChunkFileName) throws IOException {
        HybridVectorService hybridVectorService = mock(HybridVectorService.class);
        LocalStoreService localStoreService = mock(LocalStoreService.class);
        FileIngestionMarkerStore fileIngestionMarkerStore = mock(FileIngestionMarkerStore.class);
        ContentHasher contentHasher = mock(ContentHasher.class);
        IngestedFilePruneService pruneService = new IngestedFilePruneService(
                hybridVectorService, localStoreService, fileIngestionMarkerStore, contentHasher);
        Path unverifiedParsedChunk = parsedChunkDirectory.resolve(unverifiedParsedChunkFileName);
        Files.writeString(unverifiedParsedChunk, "unverified parsed chunk", StandardCharsets.UTF_8);
        when(localStoreService.getParsedDir()).thenReturn(parsedChunkDirectory);
        when(localStoreService.toSafeName(SOURCE_URL)).thenReturn("source");

        pruneService.pruneObsoleteStateAfterReplacement(List.of(), SOURCE_URL, null, List.of(REPLACEMENT_CHUNK_HASH));

        assertFalse(Files.exists(unverifiedParsedChunk));
        verify(localStoreService, never()).deleteChunkIngestionMarkers(anyList());
        verifyNoInteractions(contentHasher);
    }
}
