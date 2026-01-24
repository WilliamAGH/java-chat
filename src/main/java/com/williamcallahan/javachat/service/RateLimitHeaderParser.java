package com.williamcallahan.javachat.service;

import com.openai.core.http.Headers;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Parses rate limit headers into structured values for backoff decisions.
 */
final class RateLimitHeaderParser {

    /**
     * Parses the X-RateLimit-Reset header (epoch seconds or ISO instant).
     *
     * @param resetHeader raw header value
     * @return parsed instant, or null when header is absent
     * @throws IllegalArgumentException when a non-empty header is unparseable
     */
    Instant parseResetHeader(String resetHeader) {
        if (resetHeader == null) {
            return null;
        }
        String trimmed = resetHeader.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (isDigits(trimmed)) {
            return Instant.ofEpochSecond(Long.parseLong(trimmed));
        }
        try {
            return Instant.parse(trimmed);
        } catch (DateTimeParseException parseException) {
            throw new IllegalArgumentException("Invalid X-RateLimit-Reset header: " + trimmed, parseException);
        }
    }

    /**
     * Parses the Retry-After header as seconds.
     *
     * @param retryAfter raw header value
     * @return seconds to wait, or 0 when header is absent
     * @throws IllegalArgumentException when a non-empty header is unparseable
     */
    long parseRetryAfterHeader(String retryAfter) {
        if (retryAfter == null) {
            return 0;
        }
        String trimmed = retryAfter.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }
        if (!isDigits(trimmed)) {
            throw new IllegalArgumentException("Invalid Retry-After header: " + trimmed);
        }
        return Long.parseLong(trimmed);
    }

    /**
     * Parses Retry-After headers from OpenAI header containers.
     *
     * @param headers response headers
     * @return retry-after seconds, or 0 when absent
     * @throws IllegalArgumentException when a provided header is unparseable
     */
    long parseRetryAfterSeconds(Headers headers) {
        String retryAfter = firstHeaderValue(headers, "Retry-After");
        if (retryAfter == null) {
            return 0;
        }
        String trimmed = retryAfter.trim();
        if (isDigits(trimmed)) {
            return Long.parseLong(trimmed);
        }
        try {
            ZonedDateTime httpDate =
                ZonedDateTime.parse(trimmed, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
            long seconds = Duration.between(Instant.now(), httpDate.toInstant()).getSeconds();
            return Math.max(0, seconds);
        } catch (RuntimeException parseFailure) {
            throw new IllegalArgumentException("Invalid Retry-After header: " + trimmed, parseFailure);
        }
    }

    /**
     * Parses rate limit reset instants from known OpenAI headers.
     *
     * @param headers response headers
     * @return parsed instant, or null when no reset headers are present
     * @throws IllegalArgumentException when a provided header is unparseable
     */
    Instant parseResetInstant(Headers headers) {
        String resetSeconds = firstHeaderValue(headers, "X-RateLimit-Reset");
        if (resetSeconds != null) {
            return parseInstantSecondsFromEpoch(resetSeconds);
        }

        long candidateSeconds = minPositive(
            parseDurationSeconds(firstHeaderValue(headers, "x-ratelimit-reset-requests")),
            parseDurationSeconds(firstHeaderValue(headers, "x-ratelimit-reset-tokens")),
            parseDurationSeconds(firstHeaderValue(headers, "x-ratelimit-reset")),
            parseDurationSeconds(firstHeaderValue(headers, "X-RateLimit-Reset-Requests")),
            parseDurationSeconds(firstHeaderValue(headers, "X-RateLimit-Reset-Tokens"))
        );
        if (candidateSeconds > 0) {
            return Instant.now().plusSeconds(candidateSeconds);
        }

        return null;
    }

    private Instant parseInstantSecondsFromEpoch(String rawSeconds) {
        String trimmed = requireHeaderValue(rawSeconds, "X-RateLimit-Reset");
        if (!isDigits(trimmed)) {
            throw new IllegalArgumentException("Invalid X-RateLimit-Reset header: " + trimmed);
        }
        return Instant.ofEpochSecond(Long.parseLong(trimmed));
    }

    private long parseDurationSeconds(String rawDuration) {
        if (rawDuration == null) {
            return 0;
        }
        String trimmed = rawDuration.trim();
        if (trimmed.isEmpty()) {
            return 0;
        }

        int lastIndex = trimmed.length() - 1;
        char lastChar = trimmed.charAt(lastIndex);
        if (isDigits(trimmed)) {
            return Long.parseLong(trimmed);
        }

        if (lastChar == 's') {
            return parsePositiveSeconds(trimmed.substring(0, lastIndex).trim(), trimmed);
        }
        if (lastChar == 'm') {
            long minutes = parsePositiveSeconds(trimmed.substring(0, lastIndex).trim(), trimmed);
            return TimeUnit.MINUTES.toSeconds(minutes);
        }
        if (lastChar == 'h') {
            long hours = parsePositiveSeconds(trimmed.substring(0, lastIndex).trim(), trimmed);
            return TimeUnit.HOURS.toSeconds(hours);
        }
        if (trimmed.endsWith("ms")) {
            String numberPart = trimmed.substring(0, trimmed.length() - 2).trim();
            if (!isDigits(numberPart)) {
                throw new IllegalArgumentException("Invalid reset duration header: " + trimmed);
            }
            long millis = Long.parseLong(numberPart);
            return Math.max(1, TimeUnit.MILLISECONDS.toSeconds(millis));
        }

        throw new IllegalArgumentException("Invalid reset duration header: " + trimmed);
    }

    private long parsePositiveSeconds(String rawNumber, String fullValue) {
        if (!isDigits(rawNumber)) {
            throw new IllegalArgumentException("Invalid reset duration header: " + fullValue);
        }
        return Long.parseLong(rawNumber);
    }

    private String firstHeaderValue(Headers headers, String name) {
        if (headers == null || name == null || name.isBlank()) {
            return null;
        }
        if (!headers.names().contains(name)) {
            for (String headerName : headers.names()) {
                if (headerName != null && headerName.equalsIgnoreCase(name)) {
                    return firstHeaderValue(headers, headerName);
                }
            }
            return null;
        }
        var values = headers.values(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.get(0);
    }

    private long minPositive(long... candidates) {
        long min = 0;
        for (long candidate : candidates) {
            if (candidate <= 0) {
                continue;
            }
            if (min == 0 || candidate < min) {
                min = candidate;
            }
        }
        return min;
    }

    private boolean isDigits(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        for (int index = 0; index < candidate.length(); index++) {
            if (!Character.isDigit(candidate.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    private String requireHeaderValue(String value, String headerName) {
        if (value == null) {
            throw new IllegalArgumentException("Missing header: " + Objects.requireNonNull(headerName, "headerName"));
        }
        return value.trim();
    }
}
