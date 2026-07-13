package com.williamcallahan.javachat.application.completion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

/** Verifies completion requests have one application-owned invariant boundary. */
class CompletionRequestConfigurationTest {

    @Test
    void factoriesCreateOnlyExecutableCompletionContracts() {
        CompletionRequestConfiguration defaultText = CompletionRequestConfiguration.defaultText();
        CompletionRequestConfiguration boundedText = CompletionRequestConfiguration.boundedText(256);
        Duration jsonRequestTimeout = Duration.ofSeconds(12);
        CompletionRequestConfiguration jsonObject = CompletionRequestConfiguration.jsonObject(512, jsonRequestTimeout);

        assertTrue(defaultText.maximumOutputTokens().isEmpty());
        assertFalse(defaultText.requireJsonObject());
        assertEquals(OptionalInt.of(256), boundedText.maximumOutputTokens());
        assertFalse(boundedText.requireJsonObject());
        assertEquals(OptionalInt.of(512), jsonObject.maximumOutputTokens());
        assertTrue(jsonObject.requireJsonObject());
        assertEquals(jsonRequestTimeout, jsonObject.requestTimeout());
    }

    @Test
    void constructorRejectsInvalidCompletionContracts() {
        Duration requestTimeout = Duration.ofSeconds(5);

        assertThrows(NullPointerException.class, () -> new CompletionRequestConfiguration(null, false, requestTimeout));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompletionRequestConfiguration(OptionalInt.of(0), false, requestTimeout));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompletionRequestConfiguration(OptionalInt.empty(), true, requestTimeout));
        assertThrows(
                NullPointerException.class, () -> new CompletionRequestConfiguration(OptionalInt.empty(), false, null));
        assertThrows(
                IllegalArgumentException.class,
                () -> new CompletionRequestConfiguration(OptionalInt.empty(), false, Duration.ZERO));
    }
}
