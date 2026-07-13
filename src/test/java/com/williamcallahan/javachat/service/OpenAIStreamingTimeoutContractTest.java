package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import com.openai.core.Timeout;
import com.williamcallahan.javachat.application.streaming.StreamingFailureReporter;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Verifies that provider gateways remain the sole owner of streaming subphase deadlines. */
class OpenAIStreamingTimeoutContractTest {

    @Test
    void streamingReadDeadlineInheritsWholeRequestDeadline() {
        OpenAIStreamingService streamingService = new OpenAIStreamingService(
                mock(RateLimitService.class),
                mock(OpenAiRequestFactory.class),
                mock(OpenAiProviderRoutingService.class),
                mock(StreamingFailureReporter.class));
        ReflectionTestUtils.setField(streamingService, "streamingRequestTimeoutSeconds", 600L);

        Timeout streamingTimeout = ReflectionTestUtils.invokeMethod(streamingService, "streamingTimeout");

        assertEquals(Duration.ofSeconds(600), streamingTimeout.request());
        assertEquals(streamingTimeout.request(), streamingTimeout.read());
    }
}
