package com.williamcallahan.javachat.service;

import com.openai.core.http.Headers;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers header parsing behavior for rate limit calculations.
 */
class RateLimitHeaderParserTest {

    @Test
    void parseRetryAfterHeader_acceptsSeconds() {
        RateLimitHeaderParser parser = new RateLimitHeaderParser();
        assertEquals(120L, parser.parseRetryAfterHeader("120"));
    }

    @Test
    void parseRetryAfterHeader_rejectsInvalidValues() {
        RateLimitHeaderParser parser = new RateLimitHeaderParser();
        assertThrows(IllegalArgumentException.class, () -> parser.parseRetryAfterHeader("abc"));
    }

    @Test
    void parseResetHeader_acceptsEpochSeconds() {
        RateLimitHeaderParser parser = new RateLimitHeaderParser();
        Instant parsed = parser.parseResetHeader("1700000000");
        assertNotNull(parsed);
        assertEquals(Instant.ofEpochSecond(1700000000L), parsed);
    }

    @Test
    void parseResetHeader_rejectsInvalidValues() {
        RateLimitHeaderParser parser = new RateLimitHeaderParser();
        assertThrows(IllegalArgumentException.class, () -> parser.parseResetHeader("not-a-time"));
    }

    @Test
    void parseRetryAfterSeconds_readsHeader() {
        Headers headers = Headers.builder().put("Retry-After", "15").build();
        RateLimitHeaderParser parser = new RateLimitHeaderParser();
        assertEquals(15L, parser.parseRetryAfterSeconds(headers));
    }
}
