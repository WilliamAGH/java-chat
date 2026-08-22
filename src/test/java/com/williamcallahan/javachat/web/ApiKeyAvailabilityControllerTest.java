package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.application.auth.ApiKeyLifecycle;
import org.junit.jupiter.api.Test;

/** Verifies deployment readiness for browser-created API keys. */
class ApiKeyAvailabilityControllerTest {

    @Test
    void reportsOnlyConfiguredLifecycleAsAvailable() {
        ApiKeyLifecycle apiKeyLifecycle = mock(ApiKeyLifecycle.class);
        ApiKeyAvailabilityController controller = new ApiKeyAvailabilityController(apiKeyLifecycle);

        assertEquals(503, controller.apiKeyAvailability().getStatusCode().value());

        when(apiKeyLifecycle.isAvailable()).thenReturn(true);
        assertEquals(204, controller.apiKeyAvailability().getStatusCode().value());
    }
}
