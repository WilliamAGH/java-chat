package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalStoreServiceFileMarkerTest {

    @Test
    void recordsAndReadsFileIngestionRecordWithChunkHashes(@TempDir Path tempDir) throws IOException {
        Path snapshotDir = tempDir.resolve("snapshots");
        Path parsedDir = tempDir.resolve("parsed");
        Path indexDir = tempDir.resolve("index");

        LocalStoreService localStore =
                new LocalStoreService(snapshotDir.toString(), parsedDir.toString(), indexDir.toString(), null);
        localStore.createStoreDirectories();

        String url = "https://docs.example.com/reference/page.html";
        long size = 1234L;
        long mtime = 5678L;
        List<String> hashes = List.of("a1", "b2", "c3");

        localStore.markFileIngested(url, size, mtime, hashes);

        LocalStoreService.FileIngestionRecord record =
                localStore.readFileIngestionRecord(url).orElseThrow();
        assertEquals(size, record.fileSizeBytes());
        assertEquals(mtime, record.lastModifiedMillis());
        assertEquals(hashes, record.chunkHashes());

        assertTrue(localStore.isFileIngestedAndUnchanged(url, size, mtime));
        assertFalse(localStore.isFileIngestedAndUnchanged(url, size + 1, mtime));
        assertFalse(localStore.isFileIngestedAndUnchanged(url, size, mtime + 1));
    }

    @Test
    void deletesParsedChunksForUrlBySafeNamePrefix(@TempDir Path tempDir) throws IOException {
        Path snapshotDir = tempDir.resolve("snapshots");
        Path parsedDir = tempDir.resolve("parsed");
        Path indexDir = tempDir.resolve("index");

        LocalStoreService localStore =
                new LocalStoreService(snapshotDir.toString(), parsedDir.toString(), indexDir.toString(), null);
        localStore.createStoreDirectories();

        String url = "https://docs.example.com/api/foo.html";
        String safeName = localStore.toSafeName(url);

        Path shouldDelete = parsedDir.resolve(safeName + "_0_deadbeef.txt");
        Files.writeString(shouldDelete, "chunk", StandardCharsets.UTF_8);

        Path shouldRemain = parsedDir.resolve(localStore.toSafeName("https://other.example.com/x") + "_0_deadbeef.txt");
        Files.writeString(shouldRemain, "chunk", StandardCharsets.UTF_8);

        localStore.deleteParsedChunksForUrl(url);

        assertFalse(Files.exists(shouldDelete), "Expected parsed chunk for URL to be deleted");
        assertTrue(Files.exists(shouldRemain), "Expected unrelated parsed chunk to remain");
    }
}
