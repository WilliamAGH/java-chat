package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Encodes document text into a sparse lexical vector suitable for hybrid retrieval in Qdrant.
 *
 * <p>This encoder uses feature hashing to map tokens to integer indices. Values are term
 * frequencies. When paired with Qdrant sparse vector {@code modifier=idf}, this enables
 * lexical-style retrieval that can be fused with dense embeddings.</p>
 */
@Service
public class LexicalSparseVectorEncoder {

    private static final int MIN_TOKEN_LENGTH = 2;
    private static final int MAX_UNIQUE_TOKENS = 256;

    /**
     * Encodes the provided text into a sparse vector representation.
     *
     * @param text document text (null treated as empty)
     * @return sparse vector with stable indices and term-frequency values
     */
    public SparseVector encode(String text) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return SparseVector.empty();
        }

        Map<Long, Integer> countsByIndex = new HashMap<>();
        StringBuilder token = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                token.append(ch);
                continue;
            }
            flushToken(token, countsByIndex);
        }
        flushToken(token, countsByIndex);

        if (countsByIndex.isEmpty()) {
            return SparseVector.empty();
        }

        List<TokenCount> tokenCounts = new ArrayList<>(countsByIndex.size());
        for (Map.Entry<Long, Integer> entry : countsByIndex.entrySet()) {
            long index = entry.getKey();
            int count = entry.getValue() == null ? 0 : entry.getValue();
            if (count > 0) {
                tokenCounts.add(new TokenCount(index, count));
            }
        }

        if (tokenCounts.isEmpty()) {
            return SparseVector.empty();
        }

        tokenCounts.sort(Comparator.comparingInt(TokenCount::count).reversed().thenComparingLong(TokenCount::index));
        if (tokenCounts.size() > MAX_UNIQUE_TOKENS) {
            tokenCounts = tokenCounts.subList(0, MAX_UNIQUE_TOKENS);
        }
        tokenCounts.sort(Comparator.comparingLong(TokenCount::index));

        List<Long> indices = new ArrayList<>(tokenCounts.size());
        List<Float> values = new ArrayList<>(tokenCounts.size());
        for (TokenCount tc : tokenCounts) {
            indices.add(tc.index());
            values.add((float) tc.count());
        }
        return new SparseVector(indices, values);
    }

    private void flushToken(StringBuilder token, Map<Long, Integer> countsByIndex) {
        if (token.isEmpty()) {
            return;
        }
        String rawToken = token.toString();
        token.setLength(0);
        if (rawToken.length() < MIN_TOKEN_LENGTH) {
            return;
        }
        long index = unsigned32ToLong(murmur3_32(rawToken));
        countsByIndex.merge(index, 1, Integer::sum);
    }

    private static String normalize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String ascii = AsciiTextNormalizer.toLowerAscii(text);
        return ascii.toLowerCase(Locale.ROOT);
    }

    private static long unsigned32ToLong(int value) {
        return value & 0xFFFF_FFFFL;
    }

    /**
     * Murmur3 32-bit hash for stable token feature hashing.
     *
     * <p>This is intentionally self-contained to avoid introducing additional hashing dependencies.</p>
     */
    private static int murmur3_32(String token) {
        Objects.requireNonNull(token, "token");
        byte[] data = token.getBytes(StandardCharsets.UTF_8);

        final int seed = 0;
        final int c1 = 0xcc9e2d51;
        final int c2 = 0x1b873593;

        int h1 = seed;
        int length = data.length;
        int roundedEnd = length & 0xFFFFFFFC; // round down to 4 byte block

        for (int i = 0; i < roundedEnd; i += 4) {
            int k1 = (data[i] & 0xff)
                    | ((data[i + 1] & 0xff) << 8)
                    | ((data[i + 2] & 0xff) << 16)
                    | ((data[i + 3] & 0xff) << 24);
            k1 *= c1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= c2;

            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int k1 = 0;
        int remainder = length & 3;
        if (remainder >= 3) {
            k1 ^= (data[roundedEnd + 2] & 0xff) << 16;
        }
        if (remainder >= 2) {
            k1 ^= (data[roundedEnd + 1] & 0xff) << 8;
        }
        if (remainder >= 1) {
            k1 ^= (data[roundedEnd] & 0xff);
            k1 *= c1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= c2;
            h1 ^= k1;
        }

        h1 ^= length;
        h1 ^= (h1 >>> 16);
        h1 *= 0x85ebca6b;
        h1 ^= (h1 >>> 13);
        h1 *= 0xc2b2ae35;
        h1 ^= (h1 >>> 16);
        return h1;
    }

    private record TokenCount(long index, int count) {}

    /**
     * Sparse vector payload matching Qdrant's {@code SparseVector} shape.
     */
    public record SparseVector(List<Long> indices, List<Float> values) {
        public SparseVector {
            indices = indices == null ? List.of() : List.copyOf(indices);
            values = values == null ? List.of() : List.copyOf(values);
            if (indices.size() != values.size()) {
                throw new IllegalArgumentException("Sparse vector indices and values must be same length");
            }
        }

        /**
         * Returns an empty sparse vector with no indices or values.
         */
        public static SparseVector empty() {
            return new SparseVector(List.of(), List.of());
        }
    }
}
