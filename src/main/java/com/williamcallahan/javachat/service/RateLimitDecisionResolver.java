package com.williamcallahan.javachat.service;

import com.openai.core.http.Headers;
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
            if (containsHeader(headers, RETRY_AFTER_HEADER)) {
                return RateLimitDecision.fromRetryAfterSeconds(retryAfterSeconds);
            }

            return headerParser
                    .parseResetInstant(headers)
                    .map(RateLimitDecision::fromResetTime)
                    .orElseThrow(() -> new RateLimitDecisionException(
                            "OpenAI rate-limit headers did not include Retry-After or reset timing"));
        } catch (IllegalArgumentException parseError) {
            throw new RateLimitDecisionException("OpenAI rate-limit headers are invalid", parseError);
        }
    }

    /**
     * Resolves a gateway decision only from its authoritative Retry-After header.
     *
     * @throws RateLimitDecisionException when Retry-After is missing or invalid
     */
    RateLimitDecision resolveFromOpenAiRetryAfterHeaders(Headers headers) {
        if (headers == null || headers.isEmpty() || !containsHeader(headers, RETRY_AFTER_HEADER)) {
            throw new RateLimitDecisionException("Gateway rate-limit response did not include Retry-After");
        }
        try {
            return RateLimitDecision.fromRetryAfterSeconds(headerParser.parseRetryAfterSeconds(headers));
        } catch (IllegalArgumentException parseError) {
            throw new RateLimitDecisionException("Gateway Retry-After header is invalid", parseError);
        }
    }

    /**
     * Resolves a decision from Spring WebClient response headers.
     *
     * @throws RateLimitDecisionException when headers are missing or invalid
     */
    RateLimitDecision resolveFromWebClientException(WebClientResponseException webClientError) {
        Objects.requireNonNull(webClientError, "webClientError");

        try {
            String retryAfterHeader = webClientError.getHeaders().getFirst(RETRY_AFTER_HEADER);
            long retryAfterSeconds = headerParser.parseRetryAfterHeader(retryAfterHeader);
            if (retryAfterHeader != null && !retryAfterHeader.isBlank()) {
                return RateLimitDecision.fromRetryAfterSeconds(retryAfterSeconds);
            }

            return headerParser
                    .parseResetHeader(webClientError.getHeaders().getFirst(RESET_HEADER))
                    .map(RateLimitDecision::fromResetTime)
                    .orElseThrow(() -> new RateLimitDecisionException(
                            "WebClient rate-limit headers did not include Retry-After or X-RateLimit-Reset"));
        } catch (IllegalArgumentException parseError) {
            throw new RateLimitDecisionException("WebClient rate-limit headers are invalid", parseError);
        }
    }

    private boolean containsHeader(Headers headers, String expectedHeaderName) {
        for (String headerName : headers.names()) {
            if (headerName != null && headerName.equalsIgnoreCase(expectedHeaderName)) {
                return true;
            }
        }
        return false;
    }
}
