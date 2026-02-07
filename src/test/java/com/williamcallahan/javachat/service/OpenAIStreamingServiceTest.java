package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.openai.core.http.Headers;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import com.williamcallahan.javachat.application.prompt.PromptTruncator;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import reactor.core.Exceptions;

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
        RateLimitService rateLimitService = mock(RateLimitService.class);
        OpenAiRequestFactory requestFactory =
                new OpenAiRequestFactory(new Chunker(), new PromptTruncator(), "gpt-5.2", "gpt-5", "");
        OpenAiProviderRoutingService providerRoutingService =
                new OpenAiProviderRoutingService(rateLimitService, 600, "github_models");
        return new OpenAIStreamingService(rateLimitService, requestFactory, providerRoutingService);
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

    @Test
    void recoverableStreamingFailureTreatsOverflowAsRetryable() {
        OpenAIStreamingService service = createService();
        boolean retryable = service.isRecoverableStreamingFailure(new RuntimeException("OverflowException in stream"));
        assertTrue(retryable);
    }

    @Test
    void recoverableStreamingFailureTreatsReactorOverflowTypeAsRetryable() {
        OpenAIStreamingService service = createService();
        boolean retryable = service.isRecoverableStreamingFailure(Exceptions.failWithOverflow());
        assertTrue(retryable);
    }

    @Test
    void recoverableStreamingFailureTreatsValidationErrorsAsNonRetryable() {
        OpenAIStreamingService service = createService();
        boolean retryable = service.isRecoverableStreamingFailure(new IllegalArgumentException("bad request payload"));
        assertFalse(retryable);
    }

    private boolean invokeShouldBackoffPrimary(OpenAIStreamingService service, Throwable throwable)
            throws ReflectiveOperationException {
        Method method = OpenAIStreamingService.class.getDeclaredMethod("shouldBackoffPrimary", Throwable.class);
        method.setAccessible(true);
        Object methodResult = method.invoke(service, throwable);
        return (boolean) methodResult;
    }
}
