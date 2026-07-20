package com.williamcallahan.javachat.web;

import static com.williamcallahan.javachat.web.SseConstants.EVENT_STATUS;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_CODE_STREAM_PREPARING;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_STAGE_RETRIEVAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.config.ModelConfiguration;
import com.williamcallahan.javachat.config.RequiredCredentialValidation;
import com.williamcallahan.javachat.domain.prompt.StructuredPrompt;
import com.williamcallahan.javachat.service.ChatMemoryService;
import com.williamcallahan.javachat.service.ChatService;
import com.williamcallahan.javachat.service.EmbeddingClient;
import com.williamcallahan.javachat.service.EmbeddingModelKeepAlive;
import com.williamcallahan.javachat.service.ExternalServiceHealth;
import com.williamcallahan.javachat.service.OpenAIStreamingService;
import com.williamcallahan.javachat.service.RateLimitService;
import com.williamcallahan.javachat.service.RetrievalService;
import com.williamcallahan.javachat.service.StreamingResult;
import io.qdrant.client.QdrantClient;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.reactivestreams.Subscription;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.FluxExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Verifies the embedded servlet server flushes chat admission before blocking preparation finishes. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChatPreparationSseIntegrationTest {
    private static final String CSRF_REFRESH_ENDPOINT = "/api/security/csrf";
    private static final String CHAT_STREAM_ENDPOINT = "/api/chat/stream";
    private static final String CSRF_COOKIE_NAME = "XSRF-TOKEN";
    private static final String CSRF_HEADER_NAME = "X-XSRF-TOKEN";
    private static final String SESSION_ID = "preparation-boundary-session";
    private static final String USER_QUERY = "Explain sealed classes";
    private static final Duration SSE_BOUNDARY_TIMEOUT = Duration.ofSeconds(5);
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_EVENT_TYPE =
            new ParameterizedTypeReference<>() {};

    @Autowired
    WebTestClient webTestClient;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    ChatMemoryService chatMemoryService;

    @MockitoBean
    ChatService chatService;

    @MockitoBean
    OpenAIStreamingService streamingService;

    @MockitoBean
    RetrievalService retrievalService;

    @MockitoBean
    RequiredCredentialValidation credentialValidation;

    @MockitoBean(answers = Answers.RETURNS_MOCKS)
    EmbeddingClient embeddingClient;

    @MockitoBean
    QdrantClient qdrantClient;

    @MockitoBean
    ExternalServiceHealth externalServiceHealth;

    @MockitoBean
    EmbeddingModelKeepAlive embeddingModelKeepAlive;

    @Test
    void flushesPreparationEventWhileRetrievalRemainsBlocked() throws JsonProcessingException, InterruptedException {
        CountDownLatch retrievalStarted = new CountDownLatch(1);
        CountDownLatch releaseRetrieval = new CountDownLatch(1);
        CountDownLatch retrievalFinished = new CountDownLatch(1);
        CountDownLatch firstSseEventObserved = new CountDownLatch(1);
        CountDownLatch chatExchangePersisted = new CountDownLatch(1);
        CountDownLatch responseBodyTerminated = new CountDownLatch(1);
        AtomicReference<ServerSentEvent<String>> firstSseEvent = new AtomicReference<>();
        AtomicReference<Throwable> clientFailure = new AtomicReference<>();

        when(streamingService.isAvailable()).thenReturn(true);
        when(chatMemoryService.getHistory(SESSION_ID)).thenAnswer(ignoredInvocation -> {
            retrievalStarted.countDown();
            try {
                releaseRetrieval.await();
                return List.of();
            } finally {
                retrievalFinished.countDown();
            }
        });
        when(chatService.buildStructuredPromptWithContextOutcome(
                        anyList(), eq(USER_QUERY), eq(ModelConfiguration.DEFAULT_MODEL)))
                .thenReturn(new ChatService.StructuredPromptOutcome(
                        StructuredPrompt.fromRawPrompt("test", 1), List.of(), List.of()));
        when(retrievalService.toCitationsForQuery(eq(USER_QUERY), anyList()))
                .thenReturn(new RetrievalService.CitationOutcome(List.of(), 0));
        when(streamingService.streamResponse(any(StructuredPrompt.class), anyDouble()))
                .thenReturn(Mono.just(new StreamingResult(Flux.just("Complete"), RateLimitService.ApiProvider.OPENAI)));
        doAnswer(ignoredInvocation -> {
                    chatExchangePersisted.countDown();
                    return null;
                })
                .when(chatMemoryService)
                .addExchange(eq(SESSION_ID), eq(USER_QUERY), any());

        WebTestClient boundaryClient =
                webTestClient.mutate().responseTimeout(SSE_BOUNDARY_TIMEOUT).build();
        ResponseCookie csrfCookie = requestCsrfCookie(boundaryClient);
        FirstSseEventSubscriber firstEventSubscriber = new FirstSseEventSubscriber(
                firstSseEvent, clientFailure, firstSseEventObserved, responseBodyTerminated);
        boolean retrievalTerminatedCleanly;
        boolean chatExchangePersistedCleanly;
        boolean responseBodyTerminatedCleanly;

        try {
            FluxExchangeResult<ServerSentEvent<String>> chatStreamExchange = boundaryClient
                    .post()
                    .uri(CHAT_STREAM_ENDPOINT)
                    .cookie(CSRF_COOKIE_NAME, csrfCookie.getValue())
                    .header(CSRF_HEADER_NAME, csrfCookie.getValue())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .bodyValue(new ChatStreamRequest(SESSION_ID, USER_QUERY))
                    .exchange()
                    .expectStatus()
                    .isOk()
                    .expectHeader()
                    .contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                    .returnResult(SSE_EVENT_TYPE);
            chatStreamExchange.getResponseBody().subscribe(firstEventSubscriber);

            assertTrue(
                    firstSseEventObserved.await(SSE_BOUNDARY_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                    "the first SSE event should arrive before retrieval completes");
            assertNull(clientFailure.get());
            assertTrue(
                    retrievalStarted.await(SSE_BOUNDARY_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                    "retrieval should start after the preparation event is emitted");
            assertEquals(1L, releaseRetrieval.getCount(), "retrieval should remain blocked after the first SSE event");

            ServerSentEvent<String> preparationEvent = Objects.requireNonNull(firstSseEvent.get(), "first SSE event");
            assertEquals(EVENT_STATUS, preparationEvent.event());
            SseSupport.SseEventPayload preparationStatus = objectMapper.readValue(
                    Objects.requireNonNull(preparationEvent.data(), "preparation event data"),
                    SseSupport.SseEventPayload.class);
            assertEquals(STATUS_CODE_STREAM_PREPARING, preparationStatus.code());
            assertEquals(STATUS_STAGE_RETRIEVAL, preparationStatus.stage());
        } finally {
            releaseRetrieval.countDown();
            firstEventSubscriber.requestUnbounded();
            retrievalTerminatedCleanly = retrievalFinished.await(SSE_BOUNDARY_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            chatExchangePersistedCleanly =
                    chatExchangePersisted.await(SSE_BOUNDARY_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            responseBodyTerminatedCleanly =
                    responseBodyTerminated.await(SSE_BOUNDARY_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            firstEventSubscriber.dispose();
        }

        assertTrue(retrievalTerminatedCleanly, "retrieval should terminate during test cleanup");
        assertTrue(chatExchangePersistedCleanly, "the finite server pipeline should persist its completed exchange");
        assertTrue(responseBodyTerminatedCleanly, "the client should consume the finite SSE stream to completion");
        assertNull(clientFailure.get());
    }

    private ResponseCookie requestCsrfCookie(WebTestClient boundaryClient) {
        EntityExchangeResult<byte[]> csrfExchange = boundaryClient
                .get()
                .uri(CSRF_REFRESH_ENDPOINT)
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .returnResult();
        ResponseCookie csrfCookie = csrfExchange.getResponseCookies().getFirst(CSRF_COOKIE_NAME);
        assertNotNull(csrfCookie);
        return csrfCookie;
    }

    /** Holds demand at the first decoded frame until the test has verified the preparation boundary. */
    private static final class FirstSseEventSubscriber extends BaseSubscriber<ServerSentEvent<String>> {
        private final AtomicReference<ServerSentEvent<String>> firstSseEvent;
        private final AtomicReference<Throwable> clientFailure;
        private final CountDownLatch firstSseEventObserved;
        private final CountDownLatch responseBodyTerminated;

        private FirstSseEventSubscriber(
                AtomicReference<ServerSentEvent<String>> firstSseEvent,
                AtomicReference<Throwable> clientFailure,
                CountDownLatch firstSseEventObserved,
                CountDownLatch responseBodyTerminated) {
            this.firstSseEvent = firstSseEvent;
            this.clientFailure = clientFailure;
            this.firstSseEventObserved = firstSseEventObserved;
            this.responseBodyTerminated = responseBodyTerminated;
        }

        /** Requests exactly one decoded SSE frame so the connection remains open for the boundary assertion. */
        @Override
        protected void hookOnSubscribe(Subscription subscription) {
            request(1);
        }

        /** Captures the first decoded SSE frame without requesting a second frame. */
        @Override
        protected void hookOnNext(ServerSentEvent<String> streamEvent) {
            firstSseEvent.set(streamEvent);
            firstSseEventObserved.countDown();
        }

        /** Makes HTTP or decoding failures observable to the waiting test thread. */
        @Override
        protected void hookOnError(Throwable streamFailure) {
            clientFailure.set(streamFailure);
            firstSseEventObserved.countDown();
            responseBodyTerminated.countDown();
        }

        /** Records clean HTTP-body completion after every remaining SSE frame is consumed. */
        @Override
        protected void hookOnComplete() {
            responseBodyTerminated.countDown();
        }
    }
}
