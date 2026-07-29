package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies progress reconstruction counts only real chunk-hash markers. */
class ProgressTrackerTest {
    private static final String CHUNK_HASH = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void excludesFileAndRunMarkersFromIndexedChunkCount(@TempDir Path temporaryDirectory) throws IOException {
        Path generationDirectory = temporaryDirectory.resolve("qwen3-embedding-4b-2560/local");
        Path parsedDirectory = generationDirectory.resolve("parsed");
        Path indexDirectory = generationDirectory.resolve("index");
        Files.createDirectories(parsedDirectory);
        Files.createDirectories(indexDirectory);
        Files.writeString(parsedDirectory.resolve("chunk.txt"), "chunk");
        Files.writeString(indexDirectory.resolve(CHUNK_HASH), "1");
        Files.writeString(indexDirectory.resolve("file_document.marker"), "marker");
        Files.writeString(indexDirectory.resolve("local-ingestion-docs.json"), "{}");
        Files.writeString(indexDirectory.resolve("local-ingestion-docs.lock"), "");

        ProgressTracker progressTracker =
                new ProgressTracker(parsedDirectory.toString(), indexDirectory.toString(), "local");
        progressTracker.init();

        assertEquals(1, progressTracker.getParsedCount());
        assertEquals(1, progressTracker.getIndexedCount());
        assertEquals(100.0, progressTracker.percentComplete());
    }
}
