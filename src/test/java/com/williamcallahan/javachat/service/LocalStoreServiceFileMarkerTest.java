package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.service.ingestion.IngestedFilePruneService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies file-level ingestion marker persistence and parsed-chunk cleanup behavior.
 */
class LocalStoreServiceFileMarkerTest {

    private static final String COLLIDING_CHUNK_HASH_PREFIX = "abcdef123456";
    private static final String STALE_FULL_CHUNK_HASH =
            "abcdef123456bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String UNRELATED_FULL_CHUNK_HASH =
            "abcdef123456aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    private Path generationStateDirectory(Path temporaryDirectory) {
        return temporaryDirectory.resolve("qwen3-embedding-4b-2560/local");
    }

    @Test
    void recordsAndReadsFileIngestionRecordWithChunkHashes(@TempDir Path temporaryDirectory) throws IOException {
        Path generationStateDirectory = generationStateDirectory(temporaryDirectory);
        Path snapshotDirectory = generationStateDirectory.resolve("snapshots");
        Path parsedDirectory = generationStateDirectory.resolve("parsed");
        Path indexDirectory = generationStateDirectory.resolve("index");

        LocalStoreService localStore = new LocalStoreService(
                snapshotDirectory.toString(), parsedDirectory.toString(), indexDirectory.toString(), "local", null);
        localStore.createStoreDirectories();
        FileIngestionMarkerStore fileIngestionMarkerStore = new FileIngestionMarkerStore(localStore);

        String sourceUrl = "https://docs.example.com/reference/page.html";
        long fileSizeBytes = 1234L;
        long lastModifiedMillis = 5678L;
        String ingestionFingerprint = "abc123";
        String extractionSemanticsVersion = "utf8-javadoc-extraction-v1";
        String collectionName = "java-api-docs";
        List<String> chunkHashes = List.of("a1", "b2", "c3");

        fileIngestionMarkerStore.markFileIngested(
                sourceUrl,
                new FileIngestionMarkerStore.FileIngestionRecord(
                        fileSizeBytes,
                        lastModifiedMillis,
                        ingestionFingerprint,
                        extractionSemanticsVersion,
                        collectionName,
                        chunkHashes));

        Path markerPath = indexDirectory.resolve("file_" + localStore.toSafeName(sourceUrl) + ".marker");
        assertEquals(
                List.of(
                        "size=" + fileSizeBytes,
                        "mtime=" + lastModifiedMillis,
                        "fingerprint=" + ingestionFingerprint,
                        "extractorSemanticsVersion=" + extractionSemanticsVersion,
                        "collectionName=" + collectionName,
                        "hash=a1",
                        "hash=b2",
                        "hash=c3"),
                Files.readAllLines(markerPath, StandardCharsets.UTF_8));

        FileIngestionMarkerStore.FileIngestionRecord persistedIngestionRecord =
                fileIngestionMarkerStore.readFileIngestionRecord(sourceUrl).orElseThrow();
        assertEquals(fileSizeBytes, persistedIngestionRecord.fileSizeBytes());
        assertEquals(lastModifiedMillis, persistedIngestionRecord.lastModifiedMillis());
        assertEquals(ingestionFingerprint, persistedIngestionRecord.ingestionFingerprint());
        assertEquals(extractionSemanticsVersion, persistedIngestionRecord.extractionSemanticsVersion());
        assertEquals(collectionName, persistedIngestionRecord.collectionName());
        assertTrue(persistedIngestionRecord.hasCollectionIdentity());
        assertEquals(chunkHashes, persistedIngestionRecord.chunkHashes());
    }

    @Test
    void readsFileMarkerWithoutCollectionIdentity(@TempDir Path temporaryDirectory) throws IOException {
        Path generationStateDirectory = generationStateDirectory(temporaryDirectory);
        Path snapshotDirectory = generationStateDirectory.resolve("snapshots");
        Path parsedDirectory = generationStateDirectory.resolve("parsed");
        Path indexDirectory = generationStateDirectory.resolve("index");
        LocalStoreService localStore = new LocalStoreService(
                snapshotDirectory.toString(), parsedDirectory.toString(), indexDirectory.toString(), "local", null);
        localStore.createStoreDirectories();
        FileIngestionMarkerStore fileIngestionMarkerStore = new FileIngestionMarkerStore(localStore);
        String sourceUrl = "https://docs.example.com/reference/unbound.html";
        Path markerPath = indexDirectory.resolve("file_" + localStore.toSafeName(sourceUrl) + ".marker");
        Files.writeString(
                markerPath,
                "size=123\nmtime=456\nfingerprint=unbound\nextractorSemanticsVersion=v1\nhash=old-hash\n",
                StandardCharsets.UTF_8);

        FileIngestionMarkerStore.FileIngestionRecord unboundIngestionRecord =
                fileIngestionMarkerStore.readFileIngestionRecord(sourceUrl).orElseThrow();

        assertFalse(unboundIngestionRecord.hasCollectionIdentity());
        assertTrue(unboundIngestionRecord.collectionName().isBlank());
    }

    @Test
    void bindsMarkerRecordToCanonicalCollectionIdentity() {
        FileIngestionMarkerStore.FileIngestionRecord unboundIngestionRecord =
                new FileIngestionMarkerStore.FileIngestionRecord(123L, 456L, "fingerprint", "v1", "", List.of("h1"));

        FileIngestionMarkerStore.FileIngestionRecord boundIngestionRecord =
                unboundIngestionRecord.bindCollectionIdentity("github-openai-java-chat");

        assertEquals("github-openai-java-chat", boundIngestionRecord.collectionName());
        assertEquals(unboundIngestionRecord.fileSizeBytes(), boundIngestionRecord.fileSizeBytes());
        assertEquals(unboundIngestionRecord.lastModifiedMillis(), boundIngestionRecord.lastModifiedMillis());
        assertEquals(unboundIngestionRecord.ingestionFingerprint(), boundIngestionRecord.ingestionFingerprint());
        assertEquals(
                unboundIngestionRecord.extractionSemanticsVersion(), boundIngestionRecord.extractionSemanticsVersion());
        assertEquals(unboundIngestionRecord.chunkHashes(), boundIngestionRecord.chunkHashes());
    }

    @Test
    void deletesParsedChunksForUrlBySafeNamePrefix(@TempDir Path temporaryDirectory) throws IOException {
        Path generationStateDirectory = generationStateDirectory(temporaryDirectory);
        Path snapshotDirectory = generationStateDirectory.resolve("snapshots");
        Path parsedDirectory = generationStateDirectory.resolve("parsed");
        Path indexDirectory = generationStateDirectory.resolve("index");

        LocalStoreService localStore = new LocalStoreService(
                snapshotDirectory.toString(), parsedDirectory.toString(), indexDirectory.toString(), "local", null);
        localStore.createStoreDirectories();
        String sourceUrl = "https://docs.example.com/api/foo.html";
        String safeSourceName = localStore.toSafeName(sourceUrl);

        Path parsedChunkToDelete = parsedDirectory.resolve(safeSourceName + "_0_deadbeef.txt");
        Files.writeString(parsedChunkToDelete, "chunk", StandardCharsets.UTF_8);

        Path unrelatedParsedChunk =
                parsedDirectory.resolve(localStore.toSafeName("https://other.example.com/x") + "_0_deadbeef.txt");
        Files.writeString(unrelatedParsedChunk, "chunk", StandardCharsets.UTF_8);

        localStore.deleteParsedChunksForUrl(sourceUrl);

        assertFalse(Files.exists(parsedChunkToDelete), "Expected parsed chunk for URL to be deleted");
        assertTrue(Files.exists(unrelatedParsedChunk), "Expected unrelated parsed chunk to remain");
    }

    @Test
    void retainsUnrelatedFullHashMarkerWhenLegacyPrefixCollides(@TempDir Path temporaryDirectory) throws IOException {
        Path generationStateDirectory = generationStateDirectory(temporaryDirectory);
        Path snapshotDirectory = generationStateDirectory.resolve("snapshots");
        Path parsedDirectory = generationStateDirectory.resolve("parsed");
        Path indexDirectory = generationStateDirectory.resolve("index");
        LocalStoreService localStoreService = new LocalStoreService(
                snapshotDirectory.toString(), parsedDirectory.toString(), indexDirectory.toString(), "local", null);
        localStoreService.createStoreDirectories();
        FileIngestionMarkerStore fileIngestionMarkerStore = new FileIngestionMarkerStore(localStoreService);
        ContentHasher contentHasher = mock(ContentHasher.class);
        IngestedFilePruneService pruneService = new IngestedFilePruneService(
                mock(HybridVectorService.class), localStoreService, fileIngestionMarkerStore, contentHasher);

        String sourceUrl = "https://docs.example.com/reference/page.html";
        String staleChunkText = "legacy stale member text";
        String safeSourceName = localStoreService.toSafeName(sourceUrl);
        Path staleParsedChunk = parsedDirectory.resolve(safeSourceName + "_0_" + COLLIDING_CHUNK_HASH_PREFIX + ".txt");
        Files.writeString(staleParsedChunk, staleChunkText, StandardCharsets.UTF_8);
        localStoreService.markHashIngested(STALE_FULL_CHUNK_HASH, "stale", "example.stale");
        localStoreService.markHashIngested(UNRELATED_FULL_CHUNK_HASH, "unrelated", "example.unrelated");
        when(contentHasher.generateChunkHash(sourceUrl, 0, staleChunkText)).thenReturn(STALE_FULL_CHUNK_HASH);

        pruneService.pruneObsoleteLocalStateAfterReplacement(sourceUrl, null, List.of());

        assertFalse(Files.exists(staleParsedChunk));
        assertFalse(Files.exists(indexDirectory.resolve(STALE_FULL_CHUNK_HASH)));
        assertTrue(Files.exists(indexDirectory.resolve(UNRELATED_FULL_CHUNK_HASH)));
    }

    @Test
    void throwsWhenFileMarkerIsMalformed(@TempDir Path temporaryDirectory) throws IOException {
        Path generationStateDirectory = generationStateDirectory(temporaryDirectory);
        Path snapshotDirectory = generationStateDirectory.resolve("snapshots");
        Path parsedDirectory = generationStateDirectory.resolve("parsed");
        Path indexDirectory = generationStateDirectory.resolve("index");

        LocalStoreService localStore = new LocalStoreService(
                snapshotDirectory.toString(), parsedDirectory.toString(), indexDirectory.toString(), "local", null);
        localStore.createStoreDirectories();
        FileIngestionMarkerStore fileIngestionMarkerStore = new FileIngestionMarkerStore(localStore);
        String sourceUrl = "https://docs.example.com/broken.html";
        Path markerPath = indexDirectory.resolve("file_" + localStore.toSafeName(sourceUrl) + ".marker");
        Files.writeString(markerPath, "size=not-a-number\n", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> fileIngestionMarkerStore.readFileIngestionRecord(sourceUrl));
    }

    @Test
    void computesDeterministicFileFingerprint(@TempDir Path temporaryDirectory) throws IOException {
        Path firstDocument = temporaryDirectory.resolve("first.html");
        Path secondDocument = temporaryDirectory.resolve("second.html");
        Files.writeString(firstDocument, "<h1>same</h1>", StandardCharsets.UTF_8);
        Files.writeString(secondDocument, "<h1>same</h1>", StandardCharsets.UTF_8);

        ContentHasher contentHasher = new ContentHasher();
        String firstFingerprint = contentHasher.sha256(firstDocument);
        String secondFingerprint = contentHasher.sha256(secondDocument);

        assertEquals(firstFingerprint, secondFingerprint);
        assertFalse(firstFingerprint.isBlank());
    }
}
