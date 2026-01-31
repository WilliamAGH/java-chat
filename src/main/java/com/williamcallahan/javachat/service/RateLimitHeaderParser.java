package com.williamcallahan.javachat.service;

import com.openai.core.http.Headers;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * Parses rate limit headers into structured values for backoff decisions.
 */
final class RateLimitHeaderParser {

    private static final long HOURS_PER_DAY = 24;
    private static final long MINUTES_PER_HOUR = 60;

    /**
     * Duration units with their conversion factors to seconds.
     */
    private enum DurationUnit {
        MILLISECONDS("ms", Duration::ofMillis),
        DAYS("d", Duration::ofDays),
        HOURS("h", Duration::ofHours),
        MINUTES("m", Duration::ofMinutes),
        SECONDS("s", Duration::ofSeconds);

        private final String suffix;
        private final java.util.function.LongFunction<Duration> toDuration;

        DurationUnit(String suffix, java.util.function.LongFunction<Duration> toDuration) {
            this.suffix = suffix;
            this.toDuration = toDuration;
        }

        boolean matches(String normalized) {
            return normalized.endsWith(suffix);
        }

        String extractNumber(String normalized) {
            return normalized
                    .substring(0, normalized.length() - suffix.length())
                    .trim();
        }

        Duration convert(long amount) {
            return toDuration.apply(amount);
        }

        long toSeconds(long amount) {
            return convert(amount).getSeconds();
        }
    }

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
                parseDurationSeconds(firstHeaderValue(headers, "X-RateLimit-Reset-Tokens")));
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
        String trimmed = rawDuration.trim().toLowerCase(java.util.Locale.ROOT);
        if (trimmed.isEmpty()) {
            return 0;
        }

        if (isDigits(trimmed)) {
            return Long.parseLong(trimmed);
        }

        for (DurationUnit unit : DurationUnit.values()) {
            if (unit.matches(trimmed)) {
                String numberPart = unit.extractNumber(trimmed);
                if (!isDigits(numberPart)) {
                    throw new IllegalArgumentException("Invalid reset duration header: " + rawDuration);
                }
                long amount = Long.parseLong(numberPart);
                long seconds = unit.toSeconds(amount);
                return unit == DurationUnit.MILLISECONDS ? Math.max(1, seconds) : seconds;
            }
        }

        throw new IllegalArgumentException("Invalid reset duration header: " + rawDuration);
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
        var headerValues = headers.values(name);
        if (headerValues.isEmpty()) {
            return null;
        }
        return headerValues.get(0);
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

    private String requireHeaderValue(String headerValue, String headerName) {
        if (headerValue == null) {
            throw new IllegalArgumentException("Missing header: " + Objects.requireNonNull(headerName, "headerName"));
        }
        return headerValue.trim();
    }

    /**
     * Formats a duration as a human-readable string using days, hours, and minutes.
     *
     * @param duration the duration to format
     * @return formatted string like "2d 3h 15m", "5h 30m", or "45m"
     */
    static String formatDuration(Duration duration) {
        long days = duration.toDays();
        long hours = duration.toHours() % HOURS_PER_DAY;
        long minutes = duration.toMinutes() % MINUTES_PER_HOUR;

        if (days > 0) {
            return String.format("%dd %dh %dm", days, hours, minutes);
        } else if (hours > 0) {
            return String.format("%dh %dm", hours, minutes);
        } else {
            return String.format("%dm", minutes);
        }
    }

    /**
     * Parses a duration string with various unit suffixes into a Duration.
     * Supports: d (days), h (hours), m (minutes), s (seconds), ms (milliseconds).
     *
     * @param durationString the string to parse (e.g., "24h", "1d", "30m", "45s", "500ms")
     * @return the parsed duration
     * @throws IllegalArgumentException when the string cannot be parsed
     */
    Duration parseDuration(String durationString) {
        if (durationString == null) {
            throw new IllegalArgumentException("Duration string cannot be null");
        }
        String trimmed = durationString.trim().toLowerCase(java.util.Locale.ROOT);
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Duration string cannot be empty");
        }

        if (isDigits(trimmed)) {
            return Duration.ofSeconds(Long.parseLong(trimmed));
        }

        for (DurationUnit unit : DurationUnit.values()) {
            if (unit.matches(trimmed)) {
                String numberPart = unit.extractNumber(trimmed);
                if (!isDigits(numberPart)) {
                    throw new IllegalArgumentException("Invalid duration: " + durationString);
                }
                return unit.convert(Long.parseLong(numberPart));
            }
        }

        throw new IllegalArgumentException("Invalid duration suffix: " + durationString);
    }

    /**
     * Parses a duration string with a fallback default for null, empty, or invalid input.
     *
     * @param durationString the string to parse
     * @param fallbackDuration the value to return for null, empty, or unparseable input
     * @return the parsed duration or the default
     */
    Duration parseDurationOrDefault(String durationString, Duration fallbackDuration) {
        if (durationString == null || durationString.isBlank()) {
            return fallbackDuration;
        }
        try {
            return parseDuration(durationString);
        } catch (IllegalArgumentException parseError) {
            return fallbackDuration;
        }
    }
}
