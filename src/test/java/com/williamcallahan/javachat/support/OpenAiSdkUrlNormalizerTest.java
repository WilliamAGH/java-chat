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
        // Gateway URL: already has /v1, unchanged
        "https://api.llm-gateway.iocloudhost.net/v1, https://api.llm-gateway.iocloudhost.net/v1",

        // Trailing slash stripped
        "https://api.llm-gateway.iocloudhost.net/v1/, https://api.llm-gateway.iocloudhost.net/v1",

        // Version suffix appended for the canonical gateway host
        "https://api.llm-gateway.iocloudhost.net, https://api.llm-gateway.iocloudhost.net/v1"
    })
    void normalizeHandlesVariousFormats(String input, String expected) {
        assertEquals(expected, OpenAiSdkUrlNormalizer.normalize(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"  https://api.llm-gateway.iocloudhost.net/v1  "})
    void normalizeTrimsWhitespace(String input) {
        assertEquals("https://api.llm-gateway.iocloudhost.net/v1", OpenAiSdkUrlNormalizer.normalize(input));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    void normalizeThrowsOnNullOrBlank(String input) {
        IllegalStateException ex =
                assertThrows(IllegalStateException.class, () -> OpenAiSdkUrlNormalizer.normalize(input));
        assertEquals("OpenAI SDK base URL is not configured", ex.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://api.openai.com", "https://api.openai.com/v1", "https://gateway.example/v1"})
    void normalizeRejectsAnyEndpointOtherThanTheSharedGateway(String input) {
        IllegalStateException configurationFailure =
                assertThrows(IllegalStateException.class, () -> OpenAiSdkUrlNormalizer.normalize(input));

        assertEquals(
                "OPENAI_BASE_URL must be https://api.llm-gateway.iocloudhost.net/v1",
                configurationFailure.getMessage());
    }
}
