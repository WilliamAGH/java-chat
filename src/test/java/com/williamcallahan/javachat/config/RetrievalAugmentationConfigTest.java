package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Verifies retrieval augmentation configuration invariants.
 */
class RetrievalAugmentationConfigTest {

    @Test
    void validateConfigurationAcceptsDefaultRerankerTimeout() {
        RetrievalAugmentationConfig config = new RetrievalAugmentationConfig();

        assertDoesNotThrow(config::validateConfiguration);
    }

    @Test
    void validateConfigurationRejectsNonPositiveRerankerTimeout() {
        RetrievalAugmentationConfig config = new RetrievalAugmentationConfig();
        config.setRerankerTimeout(Duration.ZERO);

        assertThrows(IllegalArgumentException.class, config::validateConfiguration);
    }
}
