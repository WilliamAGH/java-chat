package com.williamcallahan.javachat.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/** Verifies structured log values cannot forge fields or physical log lines. */
class StructuredLogValueTest {

    @Test
    void removesQuotedFieldDelimitersAndLineSeparators() {
        String hostileValue = "session\" event=\"forged\\path\nnext\u2028paragraph\u2029end\u202E";

        String safeValue = StructuredLogValue.bounded(hostileValue, 256).text();

        assertEquals("session? event=?forged?path?next?paragraph?end?", safeValue);
    }

    @Test
    void boundsValuesAndRepresentsMissingValuesExplicitly() {
        assertEquals("abc", StructuredLogValue.bounded("abcdef", 3).text());
        assertEquals("unknown", StructuredLogValue.bounded(null, 32).text());
    }

    @Test
    void rejectsNonPositiveBounds() {
        assertThrows(IllegalArgumentException.class, () -> StructuredLogValue.bounded("field", 0));
    }
}
