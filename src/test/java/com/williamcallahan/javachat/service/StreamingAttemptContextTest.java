package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.openai.client.OpenAIClient;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Sinks;

/**
 * Verifies bounded streaming attempts for single- and multi-provider routing.
 */
class StreamingAttemptContextTest {
    private static final int EXPECTED_MAX_ATTEMPTS = 2;

    @Test
    void singleProviderGetsExactlyOneBoundedRetry() {
        OpenAiProviderCandidate onlyProvider = provider(RateLimitService.ApiProvider.OPENAI);
        StreamingAttemptContext firstAttempt = StreamingAttemptContext.first(List.of(onlyProvider), noticeSink());

        assertAttempt(firstAttempt, onlyProvider, 1, true);

        StreamingAttemptContext retryAttempt = firstAttempt.withNextAttempt();

        assertAttempt(retryAttempt, onlyProvider, 2, false);
        assertThrows(IllegalStateException.class, retryAttempt::withNextAttempt);
    }

    @Test
    void multipleProvidersAreAttemptedExactlyOnceEach() {
        OpenAiProviderCandidate primaryProvider = provider(RateLimitService.ApiProvider.OPENAI);
        OpenAiProviderCandidate secondaryProvider = provider(RateLimitService.ApiProvider.GITHUB_MODELS);
        StreamingAttemptContext firstAttempt =
                StreamingAttemptContext.first(List.of(primaryProvider, secondaryProvider), noticeSink());

        assertAttempt(firstAttempt, primaryProvider, 1, true);

        StreamingAttemptContext secondAttempt = firstAttempt.withNextAttempt();

        assertAttempt(secondAttempt, secondaryProvider, 2, false);
        assertThrows(IllegalStateException.class, secondAttempt::withNextAttempt);
    }

    private static OpenAiProviderCandidate provider(RateLimitService.ApiProvider provider) {
        return new OpenAiProviderCandidate(mock(OpenAIClient.class), provider);
    }

    private static Sinks.Many<StreamingNotice> noticeSink() {
        return Sinks.many().multicast().onBackpressureBuffer();
    }

    private static void assertAttempt(
            StreamingAttemptContext attemptContext,
            OpenAiProviderCandidate expectedProvider,
            int expectedAttempt,
            boolean expectedHasNextAttempt) {
        assertSame(expectedProvider, attemptContext.currentProvider());
        assertEquals(expectedAttempt, attemptContext.currentAttempt());
        assertEquals(EXPECTED_MAX_ATTEMPTS, attemptContext.maxAttempts());
        assertEquals(expectedHasNextAttempt, attemptContext.hasNextAttempt());
    }
}
