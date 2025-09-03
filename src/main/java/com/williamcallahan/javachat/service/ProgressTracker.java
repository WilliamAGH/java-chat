package com.williamcallahan.javachat.service;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ProgressTracker {
    private static final Logger log = LoggerFactory.getLogger(ProgressTracker.class);

    private final Path parsedDir;
    private final Path indexDir;
    private final AtomicLong parsedCount = new AtomicLong(0);
    private final AtomicLong indexedCount = new AtomicLong(0);

    public ProgressTracker(
            @Value("${app.docs.parsed-dir}") String parsedDir,
            @Value("${app.docs.index-dir}") String indexDir) {
        this.parsedDir = Paths.get(parsedDir);
        this.indexDir = Paths.get(indexDir);
    }

    @PostConstruct
    public void init() {
        try {
            long parsed = 0;
            if (parsedDir != null && Files.isDirectory(parsedDir)) {
                try (var s = Files.walk(parsedDir)) {
                    parsed = s.filter(p -> !Files.isDirectory(p))
                              .filter(p -> p.getFileName().toString().endsWith(".txt"))
                              .count();
                }
            }
            parsedCount.set(parsed);
        } catch (IOException e) {
            log.debug("Progress init: unable to count parsed chunks: {}", e.getMessage());
        }
        try {
            long indexed = 0;
            if (indexDir != null && Files.isDirectory(indexDir)) {
                try (var s = Files.list(indexDir)) {
                    indexed = s.count();
                }
            }
            indexedCount.set(indexed);
        } catch (IOException e) {
            log.debug("Progress init: unable to count indexed chunks: {}", e.getMessage());
        }
        log.info("[INDEXING] Progress initialized: parsed={}, indexed={}", parsedCount.get(), indexedCount.get());
    }

    public void markChunkParsed() {
        parsedCount.incrementAndGet();
    }

    public void markChunkIndexed() {
        indexedCount.incrementAndGet();
    }

    public long getParsedCount() { return parsedCount.get(); }
    public long getIndexedCount() { return indexedCount.get(); }

    public double percentComplete() {
        long parsed = parsedCount.get();
        if (parsed <= 0) return 0.0;
        return Math.max(0.0, Math.min(100.0, (indexedCount.get() * 100.0) / parsed));
    }

    public String formatPercent() {
        return String.format("%.1f%%", percentComplete());
    }
}
