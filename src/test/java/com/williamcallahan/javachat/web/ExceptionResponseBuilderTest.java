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
    private static final String TEST_HEADER_NAME = "X-Test";
    private static final String TEST_HEADER_VALUE = "1";
    private static final String HTTP_STATUS_TEXT_BAD_REQUEST = "Bad Request";
    private static final String HTTP_STATUS_TEXT_BLANK = "";
    private static final String RESPONSE_BODY_PROBLEM = "problem";
    private static final String EXPECTED_HTTP_STATUS_TOKEN = "httpStatus=400";
    private static final String EXPECTED_BODY_TOKEN = "body=problem";
    private static final String EXPECTED_HEADERS_TOKEN = "headers=";

    @Test
    void describeException_includesHttpStatusAndBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(TEST_HEADER_NAME, TEST_HEADER_VALUE);
        HttpClientErrorException exception = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                HTTP_STATUS_TEXT_BAD_REQUEST,
                headers,
                RESPONSE_BODY_PROBLEM.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        ExceptionResponseBuilder builder = new ExceptionResponseBuilder();
        String details = builder.describeException(exception);

        assertTrue(details.contains(EXPECTED_HTTP_STATUS_TOKEN), details);
        assertTrue(details.contains(EXPECTED_BODY_TOKEN), details);
        assertTrue(details.contains(EXPECTED_HEADERS_TOKEN), details);
    }

    @Test
    void describeException_handlesBlankStatusTextWithoutThrowing() {
        HttpHeaders headers = new HttpHeaders();
        HttpClientErrorException exception = HttpClientErrorException.create(
                HttpStatus.BAD_REQUEST,
                HTTP_STATUS_TEXT_BLANK,
                headers,
                RESPONSE_BODY_PROBLEM.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);

        ExceptionResponseBuilder builder = new ExceptionResponseBuilder();
        String details = assertDoesNotThrow(() -> builder.describeException(exception));
        assertTrue(details.contains(EXPECTED_HTTP_STATUS_TOKEN), details);
    }
}
