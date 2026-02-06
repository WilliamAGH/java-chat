package com.williamcallahan.javachat.service;

import com.openai.core.http.Headers;
import java.time.Instant;
import java.util.Objects;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Resolves strict rate-limit decisions from provider headers.
 *
 * <p>This resolver intentionally does not use free-form exception messages. It only accepts
 * explicit header-based timing signals and fails fast when the response is ambiguous.</p>
 */
final class RateLimitDecisionResolver {
    private static final String RETRY_AFTER_HEADER = "Retry-After";
    private static final String RESET_HEADER = "X-RateLimit-Reset";

    private final RateLimitHeaderParser headerParser;

    /**
     * Creates a resolver backed by the shared rate-limit header parser.
     */
    RateLimitDecisionResolver(RateLimitHeaderParser headerParser) {
        this.headerParser = Objects.requireNonNull(headerParser, "headerParser");
    }

    /**
     * Resolves a decision from OpenAI SDK headers.
     *
     * @throws RateLimitDecisionException when headers are missing or invalid
     */
    RateLimitDecision resolveFromOpenAiHeaders(Headers headers) {
        if (headers == null || headers.isEmpty()) {
            throw new RateLimitDecisionException("OpenAI rate-limit headers are missing");
        }

        try {
            long retryAfterSeconds = headerParser.parseRetryAfterSeconds(headers);
            if (retryAfterSeconds > 0) {
                return RateLimitDecision.fromRetryAfterSeconds(retryAfterSeconds);
            }

            Instant resetTime = headerParser.parseResetInstant(headers);
            if (resetTime != null) {
                return RateLimitDecision.fromResetTime(resetTime);
            }
        } catch (IllegalArgumentException parseError) {
            throw new RateLimitDecisionException("OpenAI rate-limit headers are invalid", parseError);
        }

        throw new RateLimitDecisionException("OpenAI rate-limit headers did not include Retry-After or reset timing");
    }

    /**
     * Resolves a decision from Spring WebClient response headers.
     *
     * @throws RateLimitDecisionException when headers are missing or invalid
     */
    RateLimitDecision resolveFromWebClientException(WebClientResponseException webClientError) {
        Objects.requireNonNull(webClientError, "webClientError");

        try {
            long retryAfterSeconds = headerParser.parseRetryAfterHeader(
                    webClientError.getHeaders().getFirst(RETRY_AFTER_HEADER));
            if (retryAfterSeconds > 0) {
                return RateLimitDecision.fromRetryAfterSeconds(retryAfterSeconds);
            }

            Instant resetTime =
                    headerParser.parseResetHeader(webClientError.getHeaders().getFirst(RESET_HEADER));
            if (resetTime != null) {
                return RateLimitDecision.fromResetTime(resetTime);
            }
        } catch (IllegalArgumentException parseError) {
            throw new RateLimitDecisionException("WebClient rate-limit headers are invalid", parseError);
        }

        throw new RateLimitDecisionException(
                "WebClient rate-limit headers did not include Retry-After or X-RateLimit-Reset");
    }
}
