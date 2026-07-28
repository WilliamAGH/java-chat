package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(Duration.ofSeconds(8), config.getRerankerTimeout());
    }

    @Test
    void validateConfigurationRejectsNonPositiveRerankerTimeout() {
        RetrievalAugmentationConfig config = new RetrievalAugmentationConfig();
        config.setRerankerTimeout(Duration.ZERO);

        assertThrows(IllegalArgumentException.class, config::validateConfiguration);
    }

    @Test
    void validateConfigurationAcceptsRerankerTimeoutBelowResponsePreparationDeadline() {
        RetrievalAugmentationConfig config = new RetrievalAugmentationConfig();
        config.setRerankerTimeout(RetrievalAugmentationConfig.RESPONSE_PREPARATION_TIMEOUT.minusNanos(1));

        assertDoesNotThrow(config::validateConfiguration);
    }

    @Test
    void validateConfigurationRejectsRerankerTimeoutAtResponsePreparationDeadline() {
        RetrievalAugmentationConfig config = new RetrievalAugmentationConfig();
        config.setRerankerTimeout(RetrievalAugmentationConfig.RESPONSE_PREPARATION_TIMEOUT);

        assertThrows(IllegalArgumentException.class, config::validateConfiguration);
    }

    @Test
    void validateConfigurationRejectsRerankerTimeoutAboveResponsePreparationDeadline() {
        RetrievalAugmentationConfig config = new RetrievalAugmentationConfig();
        config.setRerankerTimeout(RetrievalAugmentationConfig.RESPONSE_PREPARATION_TIMEOUT.plusNanos(1));

        assertThrows(IllegalArgumentException.class, config::validateConfiguration);
    }

    @Test
    void validateConfigurationRejectsNegativeRerankerTimeout() {
        RetrievalAugmentationConfig config = new RetrievalAugmentationConfig();
        config.setRerankerTimeout(Duration.ofNanos(-1));

        assertThrows(IllegalArgumentException.class, config::validateConfiguration);
    }

    @Test
    void validateConfigurationRejectsMissingRerankerTimeout() {
        RetrievalAugmentationConfig config = new RetrievalAugmentationConfig();
        config.setRerankerTimeout(null);

        assertThrows(IllegalArgumentException.class, config::validateConfiguration);
    }
}
