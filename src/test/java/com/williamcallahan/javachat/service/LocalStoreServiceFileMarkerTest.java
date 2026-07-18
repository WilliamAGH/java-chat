package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void recordsAndReadsFileIngestionRecordWithChunkHashes(@TempDir Path temporaryDirectory) throws IOException {
        Path snapshotDirectory = temporaryDirectory.resolve("snapshots");
        Path parsedDirectory = temporaryDirectory.resolve("parsed");
        Path indexDirectory = temporaryDirectory.resolve("index");

        LocalStoreService localStore = new LocalStoreService(
                snapshotDirectory.toString(), parsedDirectory.toString(), indexDirectory.toString(), null);
        localStore.createStoreDirectories();

        String sourceUrl = "https://docs.example.com/reference/page.html";
        long fileSizeBytes = 1234L;
        long lastModifiedMillis = 5678L;
        String ingestionFingerprint = "abc123";
        String extractionSemanticsVersion = "utf8-javadoc-extraction-v1";
        String collectionName = "java-api-docs";
        List<String> chunkHashes = List.of("a1", "b2", "c3");

        FileIngestionMarkerStore fileMarkerStore = new FileIngestionMarkerStore(localStore);
        fileMarkerStore.markFileIngested(
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
                fileMarkerStore.readFileIngestionRecord(sourceUrl).orElseThrow();
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
        Path snapshotDirectory = temporaryDirectory.resolve("snapshots");
        Path parsedDirectory = temporaryDirectory.resolve("parsed");
        Path indexDirectory = temporaryDirectory.resolve("index");
        LocalStoreService localStore = new LocalStoreService(
                snapshotDirectory.toString(), parsedDirectory.toString(), indexDirectory.toString(), null);
        localStore.createStoreDirectories();
        FileIngestionMarkerStore fileMarkerStore = new FileIngestionMarkerStore(localStore);

        String sourceUrl = "https://docs.example.com/reference/unbound.html";
        Path markerPath = indexDirectory.resolve("file_" + localStore.toSafeName(sourceUrl) + ".marker");
        Files.writeString(
                markerPath,
                "size=123\nmtime=456\nfingerprint=unbound\nextractorSemanticsVersion=v1\nhash=old-hash\n",
                StandardCharsets.UTF_8);

        FileIngestionMarkerStore.FileIngestionRecord unboundIngestionRecord =
                fileMarkerStore.readFileIngestionRecord(sourceUrl).orElseThrow();

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
        Path snapshotDirectory = temporaryDirectory.resolve("snapshots");
        Path parsedDirectory = temporaryDirectory.resolve("parsed");
        Path indexDirectory = temporaryDirectory.resolve("index");

        LocalStoreService localStore = new LocalStoreService(
                snapshotDirectory.toString(), parsedDirectory.toString(), indexDirectory.toString(), null);
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
    void throwsWhenFileMarkerIsMalformed(@TempDir Path temporaryDirectory) throws IOException {
        Path snapshotDirectory = temporaryDirectory.resolve("snapshots");
        Path parsedDirectory = temporaryDirectory.resolve("parsed");
        Path indexDirectory = temporaryDirectory.resolve("index");

        LocalStoreService localStore = new LocalStoreService(
                snapshotDirectory.toString(), parsedDirectory.toString(), indexDirectory.toString(), null);
        localStore.createStoreDirectories();
        FileIngestionMarkerStore fileMarkerStore = new FileIngestionMarkerStore(localStore);

        String sourceUrl = "https://docs.example.com/broken.html";
        Path markerPath = indexDirectory.resolve("file_" + localStore.toSafeName(sourceUrl) + ".marker");
        Files.writeString(markerPath, "size=not-a-number\n", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> fileMarkerStore.readFileIngestionRecord(sourceUrl));
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
