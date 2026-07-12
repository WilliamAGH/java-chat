package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.openai.core.http.Headers;
import com.openai.errors.BadRequestException;
import com.openai.models.ErrorObject;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** Verifies exception descriptions expose only client-safe diagnostics. */
class ExceptionResponseBuilderTest {
    private static final String TEST_AUTHORIZATION_HEADER_VALUE = "Bearer authorization-secret";
    private static final String TEST_COOKIE_HEADER_VALUE = "session=cookie-secret";
    private static final String TEST_RESPONSE_BODY_SECRET = "response-body-secret";
    private static final String TEST_PREPARED_EXCEPTION_MESSAGE = "400 Bad Request: " + TEST_RESPONSE_BODY_SECRET;
    private static final String TEST_OPENAI_ERROR_CODE = "provider-error-code-secret";
    private static final String TEST_OPENAI_ERROR_PARAMETER = "provider-parameter-secret";
    private static final String TEST_OPENAI_ERROR_TYPE = "provider-error-type-secret";

    private final ExceptionResponseBuilder exceptionResponseBuilder = new ExceptionResponseBuilder();

    @Test
    void shouldExposeOnlyTypeAndStatusForRestClientFailures() {
        HttpClientErrorException exception = HttpClientErrorException.create(
                TEST_PREPARED_EXCEPTION_MESSAGE,
                HttpStatus.BAD_REQUEST,
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                sensitiveSpringHeaders(),
                TEST_RESPONSE_BODY_SECRET.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        String clientDiagnostic = exceptionResponseBuilder.describeException(exception);

        assertClientDiagnostic(
                "BadRequest [httpStatus=400]",
                clientDiagnostic,
                TEST_PREPARED_EXCEPTION_MESSAGE,
                HttpHeaders.AUTHORIZATION,
                TEST_AUTHORIZATION_HEADER_VALUE,
                HttpHeaders.COOKIE,
                TEST_COOKIE_HEADER_VALUE,
                TEST_RESPONSE_BODY_SECRET,
                HttpStatus.BAD_REQUEST.getReasonPhrase());
    }

    @Test
    void shouldExposeOnlyTypeAndStatusForWebClientFailures() {
        WebClientResponseException exception = WebClientResponseException.create(
                HttpStatus.BAD_GATEWAY.value(),
                HttpStatus.BAD_GATEWAY.getReasonPhrase(),
                sensitiveSpringHeaders(),
                TEST_RESPONSE_BODY_SECRET.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        String clientDiagnostic = exceptionResponseBuilder.describeException(exception);

        assertClientDiagnostic(
                "BadGateway [httpStatus=502]",
                clientDiagnostic,
                HttpHeaders.AUTHORIZATION,
                TEST_AUTHORIZATION_HEADER_VALUE,
                HttpHeaders.COOKIE,
                TEST_COOKIE_HEADER_VALUE,
                TEST_RESPONSE_BODY_SECRET,
                HttpStatus.BAD_GATEWAY.getReasonPhrase());
    }

    @Test
    void shouldExposeOnlyTypeAndStatusForOpenAiFailures() {
        Headers providerHeaders = Headers.builder()
                .put(HttpHeaders.AUTHORIZATION, TEST_AUTHORIZATION_HEADER_VALUE)
                .put(HttpHeaders.COOKIE, TEST_COOKIE_HEADER_VALUE)
                .build();
        ErrorObject providerError = ErrorObject.builder()
                .message(TEST_RESPONSE_BODY_SECRET)
                .code(TEST_OPENAI_ERROR_CODE)
                .param(TEST_OPENAI_ERROR_PARAMETER)
                .type(TEST_OPENAI_ERROR_TYPE)
                .build();
        BadRequestException exception = BadRequestException.builder()
                .headers(providerHeaders)
                .error(providerError)
                .build();

        String clientDiagnostic = exceptionResponseBuilder.describeException(exception);

        assertClientDiagnostic(
                "BadRequestException [httpStatus=400]",
                clientDiagnostic,
                HttpHeaders.AUTHORIZATION,
                TEST_AUTHORIZATION_HEADER_VALUE,
                HttpHeaders.COOKIE,
                TEST_COOKIE_HEADER_VALUE,
                TEST_RESPONSE_BODY_SECRET,
                TEST_OPENAI_ERROR_CODE,
                TEST_OPENAI_ERROR_PARAMETER,
                TEST_OPENAI_ERROR_TYPE);
    }

    private static HttpHeaders sensitiveSpringHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.AUTHORIZATION, TEST_AUTHORIZATION_HEADER_VALUE);
        headers.add(HttpHeaders.COOKIE, TEST_COOKIE_HEADER_VALUE);
        return headers;
    }

    private static void assertClientDiagnostic(
            String expectedDiagnostic, String clientDiagnostic, String... prohibitedTokens) {
        assertEquals(expectedDiagnostic, clientDiagnostic);
        for (String prohibitedToken : prohibitedTokens) {
            assertFalse(clientDiagnostic.contains(prohibitedToken), clientDiagnostic);
        }
    }
}
