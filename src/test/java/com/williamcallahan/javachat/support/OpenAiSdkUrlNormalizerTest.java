package com.williamcallahan.javachat.support;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        "https://example.com, https://example.com/v1",

        // Whitespace trimmed
        "  https://api.openai.com/v1  , https://api.openai.com/v1"
    })
    void normalizeHandlesVariousFormats(String input, String expected) {
        assertEquals(expected, OpenAiSdkUrlNormalizer.normalize(input));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void normalizeThrowsOnNullOrBlank(String input) {
        IllegalStateException ex = assertThrows(
            IllegalStateException.class,
            () -> OpenAiSdkUrlNormalizer.normalize(input)
        );
        assertEquals("OpenAI SDK base URL is not configured", ex.getMessage());
    }

    @Test
    void normalizeOrNullReturnsNullForNull() {
        assertNull(OpenAiSdkUrlNormalizer.normalizeOrNull(null));
    }

    @Test
    void normalizeOrNullReturnsBlankForBlank() {
        assertEquals("", OpenAiSdkUrlNormalizer.normalizeOrNull(""));
    }

    @Test
    void normalizeOrNullNormalizesValidUrl() {
        assertEquals(
            "https://models.github.ai/inference/v1",
            OpenAiSdkUrlNormalizer.normalizeOrNull("https://models.github.ai/inference")
        );
    }

    @Test
    void normalizeOrNullReturnsWhitespaceAsIs() {
        assertEquals("   ", OpenAiSdkUrlNormalizer.normalizeOrNull("   "));
    }
}
