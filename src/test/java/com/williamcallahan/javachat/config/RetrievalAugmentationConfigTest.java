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
        RetrievalAugmentationConfig retrievalConfiguration = new RetrievalAugmentationConfig();

        assertDoesNotThrow(retrievalConfiguration::validateConfiguration);
        assertEquals(Duration.ofSeconds(8), retrievalConfiguration.getRerankerTimeout());
    }

    @Test
    void validateConfigurationRejectsNonPositiveRerankerTimeout() {
        RetrievalAugmentationConfig retrievalConfiguration = new RetrievalAugmentationConfig();
        retrievalConfiguration.setRerankerTimeout(Duration.ZERO);

        assertThrows(IllegalArgumentException.class, retrievalConfiguration::validateConfiguration);
    }

    @Test
    void validateConfigurationAcceptsRerankerTimeoutBelowResponsePreparationDeadline() {
        RetrievalAugmentationConfig retrievalConfiguration = new RetrievalAugmentationConfig();
        retrievalConfiguration.setRerankerTimeout(
                RetrievalAugmentationConfig.RESPONSE_PREPARATION_TIMEOUT.minusNanos(1));

        assertDoesNotThrow(retrievalConfiguration::validateConfiguration);
    }

    @Test
    void validateConfigurationRejectsRerankerTimeoutAtResponsePreparationDeadline() {
        RetrievalAugmentationConfig retrievalConfiguration = new RetrievalAugmentationConfig();
        retrievalConfiguration.setRerankerTimeout(RetrievalAugmentationConfig.RESPONSE_PREPARATION_TIMEOUT);

        assertThrows(IllegalArgumentException.class, retrievalConfiguration::validateConfiguration);
    }

    @Test
    void validateConfigurationRejectsRerankerTimeoutAboveResponsePreparationDeadline() {
        RetrievalAugmentationConfig retrievalConfiguration = new RetrievalAugmentationConfig();
        retrievalConfiguration.setRerankerTimeout(
                RetrievalAugmentationConfig.RESPONSE_PREPARATION_TIMEOUT.plusNanos(1));

        assertThrows(IllegalArgumentException.class, retrievalConfiguration::validateConfiguration);
    }

    @Test
    void validateConfigurationRejectsNegativeRerankerTimeout() {
        RetrievalAugmentationConfig retrievalConfiguration = new RetrievalAugmentationConfig();
        retrievalConfiguration.setRerankerTimeout(Duration.ofNanos(-1));

        assertThrows(IllegalArgumentException.class, retrievalConfiguration::validateConfiguration);
    }

    @Test
    void validateConfigurationRejectsMissingRerankerTimeout() {
        RetrievalAugmentationConfig retrievalConfiguration = new RetrievalAugmentationConfig();
        retrievalConfiguration.setRerankerTimeout(null);

        assertThrows(IllegalArgumentException.class, retrievalConfiguration::validateConfiguration);
    }
}
