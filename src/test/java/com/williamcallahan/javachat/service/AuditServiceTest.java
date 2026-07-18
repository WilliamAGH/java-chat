package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Verifies audit discovery recognizes current and legacy parsed chunk names. */
class AuditServiceTest {

    private static final String SAFE_SOURCE_NAME = "https___docs_example_com_api_foo_html";
    private static final int FULL_CHUNK_HASH_LENGTH = 64;
    private static final int LEGACY_CHUNK_HASH_PREFIX_LENGTH = 12;
    private static final int UNSUPPORTED_CHUNK_HASH_LENGTH = 13;

    @Test
    void recognizesFullContentHashChunkName() {
        String fullHash = "a".repeat(FULL_CHUNK_HASH_LENGTH);

        assertTrue(AuditService.parsedChunkPattern(SAFE_SOURCE_NAME)
                .matcher(SAFE_SOURCE_NAME + "_0_" + fullHash + ".txt")
                .matches());
    }

    @Test
    void recognizesLegacyHashPrefixChunkName() {
        String legacyHashPrefix = "b".repeat(LEGACY_CHUNK_HASH_PREFIX_LENGTH);

        assertTrue(AuditService.parsedChunkPattern(SAFE_SOURCE_NAME)
                .matcher(SAFE_SOURCE_NAME + "_1_" + legacyHashPrefix + ".txt")
                .matches());
    }

    @Test
    void rejectsUnsupportedHashLength() {
        String malformedHash = "c".repeat(UNSUPPORTED_CHUNK_HASH_LENGTH);

        assertFalse(AuditService.parsedChunkPattern(SAFE_SOURCE_NAME)
                .matcher(SAFE_SOURCE_NAME + "_2_" + malformedHash + ".txt")
                .matches());
    }
}
