package com.williamcallahan.javachat.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Computes stable hashes for content and chunk metadata.
 */
@Component
public class ContentHasher {
    private static final String SHA_256_ALGORITHM = "SHA-256";
    private static final int FILE_HASH_BUFFER_BYTES = 8192;

    /**
     * Generates SHA-256 hash for any text content.
     *
     * @param text The text to hash
     * @return Hexadecimal string representation of the hash
     */
    public String sha256(String text) {
        MessageDigest messageDigest = newSha256Digest();
        byte[] hash = messageDigest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hashBuilder = new StringBuilder();
        for (byte hashByte : hash) {
            hashBuilder.append(String.format("%02x", hashByte));
        }
        return hashBuilder.toString();
    }

    /**
     * Generates a SHA-256 fingerprint from the exact bytes stored in a file.
     *
     * @param filePath file whose bytes define the fingerprint
     * @return lowercase hexadecimal SHA-256 fingerprint
     * @throws IOException when the file cannot be read
     */
    public String sha256(Path filePath) throws IOException {
        Objects.requireNonNull(filePath, "filePath");
        MessageDigest messageDigest = newSha256Digest();
        byte[] hashBuffer = new byte[FILE_HASH_BUFFER_BYTES];
        try (InputStream fileStream = Files.newInputStream(filePath)) {
            int bytesRead = fileStream.read(hashBuffer);
            while (bytesRead >= 0) {
                if (bytesRead > 0) {
                    messageDigest.update(hashBuffer, 0, bytesRead);
                }
                bytesRead = fileStream.read(hashBuffer);
            }
        }
        return HexFormat.of().formatHex(messageDigest.digest());
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

    private MessageDigest newSha256Digest() {
        try {
            return MessageDigest.getInstance(SHA_256_ALGORITHM);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 MessageDigest is not available", exception);
        }
    }
}
