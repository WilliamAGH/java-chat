package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

/** Verifies CLI profiles retain health state without scheduling background embedding requests. */
class EmbeddingModelKeepAliveSchedulerTest {

    @Test
    void defaultProfileRegistersHealthContributorAndScheduler() {
        try (AnnotationConfigApplicationContext applicationContext = applicationContext()) {
            assertTrue(applicationContext.containsBean("embeddingModelKeepAlive"));
            assertTrue(applicationContext.containsBean("embeddingModelKeepAliveScheduler"));
        }
    }

    @Test
    void cliProfileRetainsHealthContributorWithoutScheduler() {
        try (AnnotationConfigApplicationContext applicationContext = applicationContext("cli")) {
            assertTrue(applicationContext.containsBean("embeddingModelKeepAlive"));
            assertFalse(applicationContext.containsBean("embeddingModelKeepAliveScheduler"));
        }
    }

    @Test
    void githubCliProfileRetainsHealthContributorWithoutScheduler() {
        try (AnnotationConfigApplicationContext applicationContext = applicationContext("cli-github")) {
            assertTrue(applicationContext.containsBean("embeddingModelKeepAlive"));
            assertFalse(applicationContext.containsBean("embeddingModelKeepAliveScheduler"));
        }
    }

    private static AnnotationConfigApplicationContext applicationContext(String... activeProfiles) {
        EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
        when(embeddingClient.modelName()).thenReturn("test-embedding-model");
        AnnotationConfigApplicationContext applicationContext = new AnnotationConfigApplicationContext();
        applicationContext.getEnvironment().setActiveProfiles(activeProfiles);
        applicationContext.registerBean(EmbeddingClient.class, () -> embeddingClient);
        applicationContext.register(EmbeddingModelKeepAlive.class, EmbeddingModelKeepAliveScheduler.class);
        applicationContext.refresh();
        return applicationContext;
    }
}
