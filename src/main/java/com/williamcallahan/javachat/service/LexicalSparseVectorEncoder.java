package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.StringHelper;
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
    private static final String TOKEN_STREAM_FIELD = "retrieval_text";

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
        accumulateTokenCounts(normalized, countsByIndex);

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

    private void accumulateTokenCounts(String normalizedText, Map<Long, Integer> countsByIndex) {
        Objects.requireNonNull(normalizedText, "normalizedText");
        Objects.requireNonNull(countsByIndex, "countsByIndex");
        try (StandardAnalyzer standardAnalyzer = new StandardAnalyzer();
                TokenStream tokenStream = standardAnalyzer.tokenStream(TOKEN_STREAM_FIELD, normalizedText)) {
            CharTermAttribute termAttribute = tokenStream.addAttribute(CharTermAttribute.class);
            tokenStream.reset();
            while (tokenStream.incrementToken()) {
                String lexicalToken = termAttribute.toString();
                if (lexicalToken.length() < MIN_TOKEN_LENGTH) {
                    continue;
                }
                long tokenIndex = unsigned32ToLong(murmurHash32(lexicalToken));
                countsByIndex.merge(tokenIndex, 1, (existing, one) -> existing + one);
            }
            tokenStream.end();
        } catch (IOException ioException) {
            throw new IllegalStateException("Failed to tokenize text for sparse vector encoding", ioException);
        }
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
     * <p>Delegates to Lucene's vetted {@link StringHelper#murmurhash3_x86_32} with seed zero
     * to maintain hash compatibility with existing indexed vectors.</p>
     */
    private static int murmurHash32(String token) {
        Objects.requireNonNull(token, "token");
        byte[] tokenBytes = token.getBytes(StandardCharsets.UTF_8);
        return StringHelper.murmurhash3_x86_32(tokenBytes, 0, tokenBytes.length, 0);
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

        /**
         * Returns indices narrowed to {@code int} for Qdrant gRPC APIs that require 32-bit indices.
         *
         * <p>Feature-hashed token indices fit within unsigned 32-bit range, so narrowing is safe.</p>
         */
        public List<Integer> integerIndices() {
            return indices.stream().map(Long::intValue).toList();
        }
    }
}
