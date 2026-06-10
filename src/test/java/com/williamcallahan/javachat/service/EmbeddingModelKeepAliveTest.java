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

        assertEquals(1, recordingEmbeddingClient.embedInvocationCount);
    }

    @Test
    void keepEmbeddingModelWarmDoesNotPropagateProviderUnavailability() {
        EmbeddingModelKeepAlive keepAlive = new EmbeddingModelKeepAlive(new UnavailableEmbeddingClient());

        assertDoesNotThrow(keepAlive::keepEmbeddingModelWarm);
    }

    private static final class RecordingEmbeddingClient implements EmbeddingClient {
        private int embedInvocationCount;

        @Override
        public List<float[]> embed(List<String> texts) {
            embedInvocationCount++;
            return List.of(new float[] {0.0f});
        }

        @Override
        public int dimensions() {
            return 1;
        }
    }

    private static final class UnavailableEmbeddingClient implements EmbeddingClient {
        @Override
        public List<float[]> embed(List<String> texts) {
            throw new EmbeddingServiceUnavailableException("provider offline for test");
        }

        @Override
        public int dimensions() {
            return 1;
        }
    }
}
