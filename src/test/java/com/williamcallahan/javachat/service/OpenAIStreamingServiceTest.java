package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.core.http.Headers;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.williamcallahan.javachat.application.prompt.PromptTruncator;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;

/**
 * Verifies primary-provider backoff classification for OpenAI streaming failures.
 *
 * <p>The {@code shouldBackoffPrimary} method determines whether a failure type
 * should trigger temporary backoff of the primary provider, so that subsequent
 * calls route to the secondary. These tests verify correct classification of
 * transient vs. permanent failure types.</p>
 */
class OpenAIStreamingServiceTest {

    private OpenAIStreamingService createService() {
        return new OpenAIStreamingService(null, new Chunker(), new PromptTruncator());
    }

    @Test
    void shouldBackoffPrimaryTreatsSdkIoAsBackoffEligible() throws ReflectiveOperationException {
        OpenAIStreamingService service = createService();
        boolean backoffEligible = invokeShouldBackoffPrimary(service, new OpenAIIoException("io"));
        assertTrue(backoffEligible);
    }

    @Test
    void shouldBackoffPrimaryTreats401AsBackoffEligible() throws ReflectiveOperationException {
        OpenAIStreamingService service = createService();
        Headers headers = Headers.builder().build();
        UnauthorizedException unauthorized =
                UnauthorizedException.builder().headers(headers).build();
        boolean backoffEligible = invokeShouldBackoffPrimary(service, unauthorized);
        assertTrue(backoffEligible);
    }

    @Test
    void shouldBackoffPrimaryTreats429AsBackoffEligible() throws ReflectiveOperationException {
        OpenAIStreamingService service = createService();
        Headers headers = Headers.builder().build();
        RateLimitException rateLimit =
                RateLimitException.builder().headers(headers).build();
        boolean backoffEligible = invokeShouldBackoffPrimary(service, rateLimit);
        assertTrue(backoffEligible);
    }

    @Test
    void shouldBackoffPrimaryDoesNotBackoffOnGenericRuntime() throws ReflectiveOperationException {
        OpenAIStreamingService service = createService();
        boolean backoffEligible = invokeShouldBackoffPrimary(service, new IllegalArgumentException("no"));
        assertFalse(backoffEligible);
    }

    private boolean invokeShouldBackoffPrimary(OpenAIStreamingService service, Throwable throwable)
            throws ReflectiveOperationException {
        Method method = OpenAIStreamingService.class.getDeclaredMethod("shouldBackoffPrimary", Throwable.class);
        method.setAccessible(true);
        Object methodResult = method.invoke(service, throwable);
        return (boolean) methodResult;
    }
}
