package com.williamcallahan.javachat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Computes stable hashes for content and chunk metadata.
 */
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
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            byte[] hash = messageDigest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hashBuilder = new StringBuilder();
            for (byte hashByte : hash) {
                hashBuilder.append(String.format("%02x", hashByte));
            }
            return hashBuilder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
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

    /**
     * Produces a deterministic UUID string from a stable hash input.
     *
     * @param hash stable hash text
     * @return UUID string derived from the hash
     */
    public String uuidFromHash(String hash) {
        Objects.requireNonNull(hash, "hash must not be null");
        if (hash.isBlank()) {
            throw new IllegalArgumentException("hash must not be blank");
        }
        UUID uuid = UUID.nameUUIDFromBytes(hash.getBytes(StandardCharsets.UTF_8));
        return uuid.toString();
    }
}
