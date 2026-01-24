package com.williamcallahan.javachat.service;

import com.openai.core.http.Headers;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.RateLimitException;
import com.openai.errors.UnauthorizedException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAIStreamingServiceTest {

    @Test
    void isRetryablePrimaryFailureTreatsSdkIoAsRetryable() throws Exception {
        OpenAIStreamingService service = new OpenAIStreamingService(null, new Chunker());
        boolean retryable = invokeIsRetryablePrimaryFailure(service, new OpenAIIoException("io"));
        assertTrue(retryable);
    }

    @Test
    void isRetryablePrimaryFailureTreats401AsRetryableForPrimaryFailover() throws Exception {
        OpenAIStreamingService service = new OpenAIStreamingService(null, new Chunker());
        Headers headers = Headers.builder().build();
        UnauthorizedException unauthorized = UnauthorizedException.builder().headers(headers).build();
        boolean retryable = invokeIsRetryablePrimaryFailure(service, unauthorized);
        assertTrue(retryable);
    }

    @Test
    void isRetryablePrimaryFailureTreats429AsRetryable() throws Exception {
        OpenAIStreamingService service = new OpenAIStreamingService(null, new Chunker());
        Headers headers = Headers.builder().build();
        RateLimitException rateLimit = RateLimitException.builder().headers(headers).build();
        boolean retryable = invokeIsRetryablePrimaryFailure(service, rateLimit);
        assertTrue(retryable);
    }

    @Test
    void isRetryablePrimaryFailureDoesNotTreatGenericRuntimeAsRetryable() throws Exception {
        OpenAIStreamingService service = new OpenAIStreamingService(null, new Chunker());
        boolean retryable = invokeIsRetryablePrimaryFailure(service, new IllegalArgumentException("no"));
        assertFalse(retryable);
    }

    private boolean invokeIsRetryablePrimaryFailure(OpenAIStreamingService service, Throwable throwable) throws Exception {
        Method method = OpenAIStreamingService.class.getDeclaredMethod("isRetryablePrimaryFailure", Throwable.class);
        method.setAccessible(true);
        Object result = method.invoke(service, throwable);
        return (boolean) result;
    }
}

