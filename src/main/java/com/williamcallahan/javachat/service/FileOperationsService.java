package com.williamcallahan.javachat.service;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Centralized service for file operations to eliminate duplication
 * of file handling patterns found across LocalStoreService and DocsIngestionService.
 */
@Service
public class FileOperationsService {

    /**
     * Saves text content to a file, creating parent directories as needed.
     *
     * @param filePath The path to the file
     * @param content The text content to write
     * @throws IOException If file operations fail
     */
    public void saveTextFile(Path filePath, String content) throws IOException {
        Files.createDirectories(filePath.getParent());
        Files.writeString(filePath, content, StandardCharsets.UTF_8);
    }

    /**
     * Reads text content from a file.
     *
     * @param filePath The path to the file
     * @return The file content as a string
     * @throws IOException If file operations fail
     */
    public String readTextFile(Path filePath) throws IOException {
        return Files.readString(filePath, StandardCharsets.UTF_8);
    }

    /**
     * Checks if a file exists.
     *
     * @param filePath The path to check
     * @return true if the file exists, false otherwise
     */
    public boolean fileExists(Path filePath) {
        return Files.exists(filePath);
    }

    /**
     * Creates all necessary parent directories for a file path.
     *
     * @param filePath The file path for which to create directories
     * @throws IOException If directory creation fails
     */
    public void createDirectories(Path filePath) throws IOException {
        Files.createDirectories(filePath.getParent());
    }

    /**
     * Safely converts a URL to a filesystem-safe name.
     * This eliminates duplication of filename sanitization logic.
     *
     * @param url The URL to convert
     * @return A filesystem-safe filename
     */
    public String safeName(String url) {
        return url.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}