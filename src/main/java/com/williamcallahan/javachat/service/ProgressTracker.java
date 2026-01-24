package com.williamcallahan.javachat.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks ingestion progress by counting parsed and indexed chunk artifacts on disk and in memory.
 */
@Service
public class ProgressTracker {
    private static final Logger log = LoggerFactory.getLogger(ProgressTracker.class);

    private final Path parsedDir;
    private final Path indexDir;
    private final AtomicLong parsedCount = new AtomicLong(0);
    private final AtomicLong indexedCount = new AtomicLong(0);

    /**
     * Creates a tracker rooted at the parsed and index directories.
     */
    public ProgressTracker(
        @Value("${app.docs.parsed-dir}") String parsedDir,
        @Value("${app.docs.index-dir}") String indexDir) {
        this.parsedDir = Path.of(parsedDir);
        this.indexDir = Path.of(indexDir);
    }

    /**
     * Initializes counters from the filesystem to provide a best-effort progress snapshot after restart.
     */
    @PostConstruct
    public void init() {
        try {
            long parsed = 0;
            if (parsedDir != null && Files.isDirectory(parsedDir)) {
                try (var pathStream = Files.walk(parsedDir)) {
                    parsed = pathStream.filter(pathCandidate -> !Files.isDirectory(pathCandidate))
                              .filter(pathCandidate -> {
                                  Path fileName = pathCandidate.getFileName();
                                  return fileName != null && fileName.toString().endsWith(".txt");
                              })
                              .count();
                }
            }
            parsedCount.set(parsed);
        } catch (IOException exception) {
            log.debug("Progress init: unable to count parsed chunks (exception type: {})",
                exception.getClass().getSimpleName());
        }
        try {
            long indexed = 0;
            if (indexDir != null && Files.isDirectory(indexDir)) {
                try (var fileStream = Files.list(indexDir)) {
                    indexed = fileStream.count();
                }
            }
            indexedCount.set(indexed);
        } catch (IOException exception) {
            log.debug("Progress init: unable to count indexed chunks (exception type: {})",
                exception.getClass().getSimpleName());
        }
        log.info("[INDEXING] Progress initialized: parsed={}, indexed={}", parsedCount.get(), indexedCount.get());
    }

    /**
     * Increments the parsed chunk counter after writing a parsed chunk file.
     */
    public void markChunkParsed() {
        parsedCount.incrementAndGet();
    }

    /**
     * Increments the indexed chunk counter after a chunk is successfully ingested.
     */
    public void markChunkIndexed() {
        indexedCount.incrementAndGet();
    }

    /**
     * Returns the current count of parsed chunks.
     */
    public long getParsedCount() {
        return parsedCount.get();
    }

    /**
     * Returns the current count of indexed chunks.
     */
    public long getIndexedCount() {
        return indexedCount.get();
    }

    /**
     * Computes a best-effort completion percentage based on parsed versus indexed chunk counts.
     */
    public double percentComplete() {
        long parsed = parsedCount.get();
        if (parsed <= 0) return 0.0;
        return Math.max(0.0, Math.min(100.0, (indexedCount.get() * 100.0) / parsed));
    }

    /**
     * Formats the current completion percentage for logs and UI displays.
     */
    public String formatPercent() {
        return String.format("%.1f%%", percentComplete());
    }
}
