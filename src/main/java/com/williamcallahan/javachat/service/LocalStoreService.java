package com.williamcallahan.javachat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class LocalStoreService {
    private final Path snapshotDir;
    private final Path parsedDir;
    private final Path indexDir;

    public LocalStoreService(@Value("${app.docs.snapshot-dir}") String snapshotDir,
                             @Value("${app.docs.parsed-dir}") String parsedDir,
                             @Value("${app.docs.index-dir}") String indexDir) throws IOException {
        this.snapshotDir = Paths.get(snapshotDir);
        this.parsedDir = Paths.get(parsedDir);
        this.indexDir = Paths.get(indexDir);
        Files.createDirectories(this.snapshotDir);
        Files.createDirectories(this.parsedDir);
        Files.createDirectories(this.indexDir);
    }

    public void saveHtml(String url, String html) throws IOException {
        Path p = snapshotDir.resolve(safeName(url) + ".html");
        Files.createDirectories(p.getParent());
        Files.writeString(p, html, StandardCharsets.UTF_8);
    }

    public void saveChunkText(String url, int index, String text, String hash) throws IOException {
        Path p = parsedDir.resolve(safeName(url) + "_" + index + "_" + hash.substring(0, 12) + ".txt");
        Files.createDirectories(p.getParent());
        Files.writeString(p, text, StandardCharsets.UTF_8);
    }

    public boolean isHashIngested(String hash) {
        Path marker = indexDir.resolve(hash);
        return Files.exists(marker);
    }

    public void markHashIngested(String hash) throws IOException {
        Path marker = indexDir.resolve(hash);
        if (!Files.exists(marker)) {
            Files.writeString(marker, "1", StandardCharsets.UTF_8);
        }
    }

    private String safeName(String url) {
        return url.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}





