package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Verifies app property validation for newly added retrieval and embedding settings.
 */
class AppPropertiesValidationTest {

    @Test
    void rejectsNonPositiveRrfK() {
        AppProperties appProperties = new AppProperties();
        appProperties.getQdrant().setRrfK(0);

        assertThrows(IllegalArgumentException.class, appProperties::validateConfiguration);
    }

    @Test
    void rejectsNonPositiveLocalEmbeddingBatchSize() {
        AppProperties appProperties = new AppProperties();
        appProperties.getLocalEmbedding().setBatchSize(0);

        assertThrows(IllegalArgumentException.class, appProperties::validateConfiguration);
    }
}
