package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.core.http.Headers;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;

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
        Optional<Instant> parsedResetInstant = parser.parseResetHeader("1700000000");
        assertNotNull(parsedResetInstant);
        assertEquals(Instant.ofEpochSecond(1700000000L), parsedResetInstant.orElseThrow());
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

    @Test
    void parseResetInstant_waitsForLatestPositiveRateLimitBucket() {
        Headers headers = Headers.builder()
                .put("x-ratelimit-reset-requests", "1s")
                .put("x-ratelimit-reset-tokens", "60s")
                .build();
        RateLimitHeaderParser parser = new RateLimitHeaderParser();

        Instant parsedResetTime = parser.parseResetInstant(headers).orElseThrow();
        long remainingResetSeconds =
                Duration.between(Instant.now(), parsedResetTime).getSeconds();

        assertTrue(remainingResetSeconds >= 58, "Expected the 60-second token reset window");
    }
}
