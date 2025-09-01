package com.williamcallahan.javachat.service;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Component
public class ContentHasher {

    /**
     * Generates SHA-256 hash for any text content.
     *
     * @param text The text to hash
     * @return Hexadecimal string representation of the hash
     */
    public String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Generates a standardized hash for document chunks.
     * This eliminates duplication of hash generation logic found in DocsIngestionService.
     *
     * @param url The source URL
     * @param chunkIndex The chunk index within the document
     * @param text The chunk text content
     * @return Standardized hash string for the chunk
     */
    public String generateChunkHash(String url, int chunkIndex, String text) {
        String hashInput = url + "#" + chunkIndex + ":" + text;
        return sha256(hashInput);
    }
}


