package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.williamcallahan.javachat.application.search.LexicalSparseVectorEncoder;
import org.junit.jupiter.api.Test;

/**
 * Verifies deterministic sparse lexical encoding behavior.
 */
class LexicalSparseVectorEncoderTest {

    private final LexicalSparseVectorEncoder encoder = new LexicalSparseVectorEncoder();

    @Test
    void returnsEmptyVectorForBlankInput() {
        LexicalSparseVectorEncoder.SparseVector sparseVector = encoder.encode("   ");

        assertTrue(sparseVector.indices().isEmpty());
        assertTrue(sparseVector.termFrequencies().isEmpty());
    }

    @Test
    void producesDeterministicVectorsForSameInput() {
        LexicalSparseVectorEncoder.SparseVector firstVector = encoder.encode("Java streams streams");
        LexicalSparseVectorEncoder.SparseVector secondVector = encoder.encode("Java streams streams");

        assertEquals(firstVector, secondVector);
    }

    @Test
    void tokenizesPunctuationRichInput() {
        LexicalSparseVectorEncoder.SparseVector sparseVector = encoder.encode("Map<String, Integer> stream().count()");

        assertFalse(sparseVector.indices().isEmpty());
        assertEquals(
                sparseVector.indices().size(), sparseVector.termFrequencies().size());
    }
}
