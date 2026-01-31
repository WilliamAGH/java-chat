package com.williamcallahan.javachat.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies OpenAI SDK base URL normalization stays deterministic across formats.
 */
class OpenAiSdkUrlNormalizerTest {

    @ParameterizedTest(name = "normalize(\"{0}\") = \"{1}\"")
    @CsvSource({
        // GitHub Models: /inference → /inference/v1
        "https://models.github.ai/inference, https://models.github.ai/inference/v1",

        // OpenAI URL: already has /v1, unchanged
        "https://api.openai.com/v1, https://api.openai.com/v1",

        // Trailing slash stripped
        "https://api.openai.com/v1/, https://api.openai.com/v1",

        // Embeddings suffix stripped from /v1/embeddings
        "https://example.com/v1/embeddings, https://example.com/v1",

        // Embeddings suffix stripped, /v1 appended
        "https://example.com/embeddings, https://example.com/v1",

        // No /v1 suffix: appends /v1
        "https://example.com, https://example.com/v1"
    })
    void normalizeHandlesVariousFormats(String input, String expected) {
        assertEquals(expected, OpenAiSdkUrlNormalizer.normalize(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"  https://api.openai.com/v1  "})
    void normalizeTrimsWhitespace(String input) {
        assertEquals("https://api.openai.com/v1", OpenAiSdkUrlNormalizer.normalize(input));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void normalizeThrowsOnNullOrBlank(String input) {
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> OpenAiSdkUrlNormalizer.normalize(input));
        assertEquals("OpenAI SDK base URL is not configured", ex.getMessage());
    }
}
