package com.williamcallahan.javachat.application.ingestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Verifies ingestion bounds cannot represent zero or negative work limits. */
class IngestionLimitTest {

    @Test
    void pageLimitRejectsNonPositiveBounds() {
        assertThrows(IllegalArgumentException.class, () -> new PageLimit(0));
        assertThrows(IllegalArgumentException.class, () -> new PageLimit(-1));
    }

    @Test
    void fileLimitRejectsNonPositiveBounds() {
        assertThrows(IllegalArgumentException.class, () -> new FileLimit(0));
        assertThrows(IllegalArgumentException.class, () -> new FileLimit(-1));
    }

    @Test
    void pageLimitRetainsPositiveBound() {
        assertEquals(17, new PageLimit(17).maximumPages());
    }

    @Test
    void fileLimitRetainsPositiveBound() {
        assertEquals(29, new FileLimit(29).maximumFiles());
    }
}
