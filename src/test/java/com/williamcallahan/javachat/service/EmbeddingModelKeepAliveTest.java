package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the keep-alive probe contacts the embedding provider and treats provider
 * unavailability as a logged monitoring signal rather than a propagated failure.
 */
class EmbeddingModelKeepAliveTest {

    @Test
    void keepEmbeddingModelWarmProbesTheEmbeddingProvider() {
        RecordingEmbeddingClient recordingEmbeddingClient = new RecordingEmbeddingClient();

        new EmbeddingModelKeepAlive(recordingEmbeddingClient).keepEmbeddingModelWarm();

        assertEquals(1, recordingEmbeddingClient.warmUpInvocationCount);
    }

    @Test
    void keepEmbeddingModelWarmDoesNotPropagateProviderUnavailability() {
        EmbeddingModelKeepAlive keepAlive = new EmbeddingModelKeepAlive(new UnavailableEmbeddingClient());

        assertDoesNotThrow(keepAlive::keepEmbeddingModelWarm);
    }

    /**
     * Records scheduled warm-up calls without allowing pipeline embedding calls.
     */
    private static final class RecordingEmbeddingClient implements EmbeddingClient {
        private int warmUpInvocationCount;

        @Override
        public List<float[]> embed(List<String> texts, LlmGatewayTier requestTier) {
            throw new AssertionError("keep-alive probes must not call embed(List, LlmGatewayTier)");
        }

        @Override
        public void warmUp() {
            warmUpInvocationCount++;
        }

        @Override
        public int dimensions() {
            return 1;
        }
    }

    /**
     * Simulates a provider that is unavailable during scheduled warm-up.
     */
    private static final class UnavailableEmbeddingClient implements EmbeddingClient {
        @Override
        public List<float[]> embed(List<String> texts, LlmGatewayTier requestTier) {
            throw new EmbeddingServiceUnavailableException("provider offline for test");
        }

        @Override
        public void warmUp() {
            throw new EmbeddingServiceUnavailableException("provider offline for test");
        }

        @Override
        public int dimensions() {
            return 1;
        }
    }
}
