package com.williamcallahan.javachat.web;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

/**
 * Verifies exception descriptions include HTTP details when available.
 */
class ExceptionResponseBuilderTest {

    @Test
    void describeException_includesHttpStatusAndBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Test", "1");
        HttpClientErrorException exception = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                "Bad Request",
                headers,
                "problem".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        ExceptionResponseBuilder builder = new ExceptionResponseBuilder();
        String details = builder.describeException(exception);

        assertTrue(details.contains("httpStatus=400"), details);
        assertTrue(details.contains("body=problem"), details);
        assertTrue(details.contains("headers="), details);
    }

    @Test
    void describeException_handlesNullStatusTextWithoutThrowing() {
        HttpHeaders headers = new HttpHeaders();
        HttpClientErrorException exception = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                null,
                headers,
                "problem".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        ExceptionResponseBuilder builder = new ExceptionResponseBuilder();
        assertDoesNotThrow(() -> builder.describeException(exception));
    }
}
