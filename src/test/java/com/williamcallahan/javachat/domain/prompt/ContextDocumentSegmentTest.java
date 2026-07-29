package com.williamcallahan.javachat.domain.prompt;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Verifies context documents retain a stable identity across prompt truncation boundaries. */
class ContextDocumentSegmentTest {

    @Test
    void rejectsNullAndBlankDocumentIdentities() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ContextDocumentSegment(1, null, "https://example.test", "Reference", 1));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ContextDocumentSegment(1, " \t\n ", "https://example.test", "Reference", 1));
    }
}
