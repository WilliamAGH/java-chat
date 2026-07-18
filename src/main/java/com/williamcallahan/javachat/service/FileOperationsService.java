package com.williamcallahan.javachat.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Service;

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
     * @param text The text to write
     * @throws IOException If file operations fail
     */
    public void saveTextFile(Path filePath, String text) throws IOException {
        ensureParentDirectoryExists(filePath);
        Files.writeString(filePath, text, StandardCharsets.UTF_8);
    }

    /**
     * Reads UTF-8 text from a file and rejects malformed or unmappable input.
     *
     * @param filePath The path to the file
     * @return The decoded file text
     * @throws IOException If the file cannot be read or contains invalid UTF-8
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
        ensureParentDirectoryExists(filePath);
    }

    /**
     * Ensures the parent directory exists for a given file path, creating it if necessary.
     *
     * @param filePath the file path whose parent directory should exist
     * @throws IOException if the parent is null (root path) or directory creation fails
     */
    private void ensureParentDirectoryExists(Path filePath) throws IOException {
        if (filePath == null) {
            throw new IOException("File path is required");
        }
        Path parentDirectory = filePath.getParent();
        if (parentDirectory == null) {
            throw new IOException("File path has no parent directory: " + filePath);
        }
        Files.createDirectories(parentDirectory);
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
