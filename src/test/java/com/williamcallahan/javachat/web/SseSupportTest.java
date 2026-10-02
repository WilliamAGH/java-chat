package com.williamcallahan.javachat.web;

import static com.williamcallahan.javachat.config.RetrievalAugmentationConfig.RESPONSE_PREPARATION_TIMEOUT;
import static com.williamcallahan.javachat.web.SseConstants.EVENT_ERROR;
import static com.williamcallahan.javachat.web.SseConstants.EVENT_STATUS;
import static com.williamcallahan.javachat.web.SseConstants.HEARTBEAT_INTERVAL_SECONDS;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_CODE_RETRIEVAL_TIMEOUT;
import static com.williamcallahan.javachat.web.SseConstants.STATUS_STAGE_RETRIEVAL;
import static com.williamcallahan.javachat.web.SseConstants.STREAM_BACKPRESSURE_BUFFER_CAPACITY;
import static com.williamcallahan.javachat.web.SseConstants.STREAM_CHUNK_COALESCE_MAX_ITEMS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.service.HybridSearchPartialFailureException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.Disposable;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.test.subscriber.TestSubscriber;

/**
 * Verifies SSE stream preparation semantics used by streaming chat controllers.
 */
@JsonTest
class SseSupportTest {

    private static final int BACKPRESSURE_OVERFLOW_BUFFER_MULTIPLIER = 2;
    private static final int DEADLINE_TEST_MULTIPLIER = 2;
    private static final Duration BACKPRESSURE_TEST_COMPLETION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DELAYED_SUBSCRIPTION_BUDGET = Duration.ofSeconds(1);
    private static final Duration DELAYED_SUBSCRIPTION_DELAY = Duration.ofMillis(1_250);
    private static final Duration DELAYED_SUBSCRIPTION_VERIFICATION_TIMEOUT = Duration.ofMillis(1_750);

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void prepareDataStreamPublishesEveryChunkOnce() {
        SseSupport sseSupport = createSseSupport();

        List<String> upstreamChunks = List.of("```java\n", "int x = 10;\n", "```");
        List<String> consumedChunks = new CopyOnWriteArrayList<>();
        Flux<String> preparedStream =
                sseSupport.prepareDataStream(Flux.fromIterable(upstreamChunks), consumedChunks::add);

        List<String> emittedChunks = new CopyOnWriteArrayList<>();

        preparedStream.subscribe(emittedChunks::add);

        String expectedContent = String.join("", upstreamChunks);
        String emittedContent = String.join("", emittedChunks);
        String consumedContent = String.join("", consumedChunks);

        assertEquals(expectedContent, emittedContent, "Subscriber should receive full stream content");
        assertEquals(expectedContent, consumedContent, "Chunk consumer should observe full stream content");
    }

    @Test
    void sourceAvailabilityNoteDependsOnRetainedProviderContext() {
        SseSupport sseSupport = createSseSupport();
        Flux<String> answerChunks = Flux.just("The answer.");

        StepVerifier.create(sseSupport.appendSourceAvailabilityNote(answerChunks, false))
                .expectNext("The answer.", SseSupport.GENERAL_KNOWLEDGE_SOURCE_NOTE)
                .verifyComplete();
        StepVerifier.create(sseSupport.appendSourceAvailabilityNote(answerChunks, true))
                .expectNext("The answer.")
                .verifyComplete();
        StepVerifier.create(sseSupport.appendSourceAvailabilityNote(
                        Flux.just(
                                "Answer.\n\nNote: Matching source documents were not available to JavaChat for ",
                                "this question, so this answer uses the model's general knowledge."),
                        false))
                .expectNext(
                        "Answer.\n\nNote: Matching source documents were not available to JavaChat for ",
                        "this question, so this answer uses the model's general knowledge.")
                .verifyComplete();
        StepVerifier.create(sseSupport.appendSourceAvailabilityNote(
                        Flux.just(
                                "Answer.\n\nNote: Matching source documents for Rust 1.99 were not available ",
                                "to JavaChat, so this answer uses the model's general knowledge."),
                        false))
                .expectNext(
                        "Answer.\n\nNote: Matching source documents for Rust 1.99 were not available ",
                        "to JavaChat, so this answer uses the model's general knowledge.")
                .verifyComplete();
        StepVerifier.create(sseSupport.appendSourceAvailabilityNote(
                        Flux.just("Source unav", "ailable: Rust 1.99\n\nAnswer."), false))
                .expectNext("Source unav", "ailable: Rust 1.99\n\nAnswer.")
                .verifyComplete();
        StepVerifier.create(sseSupport.appendSourceAvailabilityNote(
                        Flux.just("The UI may say `Note: Matching source documents were not available to JavaChat`."),
                        false))
                .expectNext(
                        "The UI may say `Note: Matching source documents were not available to JavaChat`.",
                        SseSupport.GENERAL_KNOWLEDGE_SOURCE_NOTE)
                .verifyComplete();
        StepVerifier.create(sseSupport.appendSourceAvailabilityNote(Flux.empty(), false))
                .verifyComplete();
    }

    @Test
    void prepareDataStreamTerminatesWithOverflowInsteadOfDroppingCoalescedChunks() {
        SseSupport sseSupport = createSseSupport();
        int coalescedChunkCount = (STREAM_BACKPRESSURE_BUFFER_CAPACITY * BACKPRESSURE_OVERFLOW_BUFFER_MULTIPLIER) + 1;
        int rawChunkCount = coalescedChunkCount * STREAM_CHUNK_COALESCE_MAX_ITEMS;
        Flux<String> preparedStream = sseSupport.prepareDataStream(
                Flux.range(0, rawChunkCount).map(rawChunkIndex -> "chunk-" + rawChunkIndex), ignoredChunk -> {});
        TestSubscriber<String> textSubscriber =
                TestSubscriber.builder().initialRequest(0).build();

        preparedStream.subscribe(textSubscriber);

        textSubscriber.request(Long.MAX_VALUE);
        textSubscriber.block(BACKPRESSURE_TEST_COMPLETION_TIMEOUT);

        List<String> deliveredChunks = textSubscriber.getReceivedOnNext();
        assertTrue(textSubscriber.isTerminatedError());
        assertTrue(Exceptions.isOverflow(textSubscriber.expectTerminalError()));
        assertEquals(STREAM_BACKPRESSURE_BUFFER_CAPACITY, deliveredChunks.size());
        assertTrue(deliveredChunks.getFirst().startsWith("chunk-0"));
    }

    @Test
    void cancellingTextAndHeartbeatStreamCancelsUpstream() {
        SseSupport sseSupport = createSseSupport();
        AtomicInteger upstreamSubscriptionCount = new AtomicInteger();
        AtomicInteger upstreamCancellationCount = new AtomicInteger();
        Flux<String> upstreamStream = Flux.<String>never()
                .doOnSubscribe(ignoredSubscription -> upstreamSubscriptionCount.incrementAndGet())
                .doOnCancel(upstreamCancellationCount::incrementAndGet);
        Flux<String> preparedStream = sseSupport.prepareDataStream(upstreamStream, ignoredChunk -> {});
        Flux<ServerSentEvent<String>> sseEventStream =
                sseSupport.withHeartbeats(preparedStream.map(sseSupport::textEvent));

        Disposable clientSubscription = sseEventStream.subscribe();

        assertEquals(1, upstreamSubscriptionCount.get(), "Text and heartbeat subscribers should share one upstream");
        assertEquals(0, upstreamCancellationCount.get(), "Active client should keep the upstream connected");

        clientSubscription.dispose();

        assertEquals(1, upstreamCancellationCount.get(), "Client cancellation should cancel the shared upstream");
    }

    @Test
    void heartbeatsDoNotOverflowWhenDownstreamStartsWithZeroDemand() {
        SseSupport sseSupport = createSseSupport();

        StepVerifier.withVirtualTime(() -> sseSupport.heartbeats(Flux.never()), 0)
                .thenAwait(Duration.ofSeconds((long) HEARTBEAT_INTERVAL_SECONDS * 3))
                .thenRequest(1)
                .thenAwait(Duration.ofSeconds(HEARTBEAT_INTERVAL_SECONDS))
                .assertNext(heartbeat -> assertTrue(!heartbeat.comment().isBlank()))
                .thenCancel()
                .verify();
    }

    @Test
    void responsePreparationDeadlineCancelsAnOperationThatNeverEmits() {
        SseSupport sseSupport = createSseSupport();
        AtomicInteger operationCancellationCount = new AtomicInteger();
        Flux<ServerSentEvent<String>> stalledOperationEvents =
                Flux.<ServerSentEvent<String>>never().doOnCancel(operationCancellationCount::incrementAndGet);

        StepVerifier.withVirtualTime(() -> sseSupport.enforceResponsePreparationDeadline(stalledOperationEvents))
                .thenAwait(RESPONSE_PREPARATION_TIMEOUT)
                .expectError(TimeoutException.class)
                .verify();

        assertEquals(1, operationCancellationCount.get());
    }

    @Test
    void responsePreparationDeadlineStopsAfterTheFirstOperationEvent() {
        SseSupport sseSupport = createSseSupport();
        ServerSentEvent<String> firstOperationEvent = sseSupport.providerEvent("OpenAI");
        Flux<ServerSentEvent<String>> operationEvents = Flux.concat(Flux.just(firstOperationEvent), Flux.never());

        StepVerifier.withVirtualTime(() -> sseSupport.enforceResponsePreparationDeadline(operationEvents))
                .expectNext(firstOperationEvent)
                .thenAwait(RESPONSE_PREPARATION_TIMEOUT.multipliedBy(DEADLINE_TEST_MULTIPLIER))
                .thenCancel()
                .verify();
    }

    @Test
    void responsePreparationDeadlineUsesTheRemainingBudgetAtSubscription() {
        SseSupport sseSupport = createSseSupport();
        AtomicInteger operationSubscriptionCount = new AtomicInteger();
        Flux<ServerSentEvent<String>> stalledOperationEvents = Flux.defer(() -> {
            operationSubscriptionCount.incrementAndGet();
            return Flux.never();
        });
        long responsePreparationDeadlineNanos = System.nanoTime() + DELAYED_SUBSCRIPTION_BUDGET.toNanos();
        Flux<ServerSentEvent<String>> deadlineBoundEvents =
                sseSupport.enforceResponsePreparationDeadline(stalledOperationEvents, responsePreparationDeadlineNanos);

        StepVerifier.create(deadlineBoundEvents.delaySubscription(DELAYED_SUBSCRIPTION_DELAY))
                .expectError(TimeoutException.class)
                .verify(DELAYED_SUBSCRIPTION_VERIFICATION_TIMEOUT);

        assertEquals(0, operationSubscriptionCount.get());
    }

    @Test
    void responsePreparationTimeoutErrorUsesTheStableRetryableContract() throws JsonProcessingException {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        SseSupport sseSupport = new SseSupport(objectMapper, meterRegistry);
        Flux<ServerSentEvent<String>> timeoutEvents = sseSupport.responsePreparationTimeoutError();
        StepVerifier.create(timeoutEvents, 0).thenCancel().verify();
        assertEquals(
                0.0,
                meterRegistry
                        .get(SseSupport.RETRIEVAL_TIMEOUT_COUNTER_NAME)
                        .counter()
                        .count());

        ServerSentEvent<String> timeoutEvent =
                Objects.requireNonNull(timeoutEvents.blockFirst(), "response preparation timeout event");
        SseSupport.SseEventPayload timeoutEventPayload = objectMapper.readValue(
                Objects.requireNonNull(timeoutEvent.data(), "response preparation timeout event data"),
                SseSupport.SseEventPayload.class);

        assertEquals(EVENT_ERROR, timeoutEvent.event());
        assertEquals(STATUS_CODE_RETRIEVAL_TIMEOUT, timeoutEventPayload.code());
        assertEquals(STATUS_STAGE_RETRIEVAL, timeoutEventPayload.stage());
        assertEquals(Boolean.TRUE, timeoutEventPayload.retryable());
        assertEquals(
                1.0,
                meterRegistry
                        .get(SseSupport.RETRIEVAL_TIMEOUT_COUNTER_NAME)
                        .counter()
                        .count());
    }

    @Test
    void responsePreparationTimeoutDetectionTraversesWrappedFailures() {
        SseSupport sseSupport = createSseSupport();
        RuntimeException wrappedTimeout =
                new RuntimeException("retrieval failed", new TimeoutException("embedding deadline"));

        assertTrue(sseSupport.isResponsePreparationTimeout(wrappedTimeout));
    }

    @Test
    void responsePreparationTimeoutDetectionTraversesSuppressedDependencyFailures() {
        SseSupport sseSupport = createSseSupport();
        RuntimeException fanOutFailure = new RuntimeException("retrieval fan-out failed");
        fanOutFailure.addSuppressed(
                new IllegalStateException("later collection failed", new TimeoutException("Qdrant deadline")));

        assertTrue(sseSupport.isResponsePreparationTimeout(fanOutFailure));
    }

    @Test
    void responsePreparationTimeoutDoesNotHidePermanentFanOutFailure() {
        SseSupport sseSupport = createSseSupport();
        HybridSearchPartialFailureException mixedFanOutFailure = new HybridSearchPartialFailureException(
                "retrieval fan-out failed",
                List.of(
                        new HybridSearchPartialFailureException.CollectionSearchFailure(
                                "docs",
                                "InvalidArgument",
                                "invalid filter",
                                HybridSearchPartialFailureException.FailureDisposition.PERMANENT),
                        new HybridSearchPartialFailureException.CollectionSearchFailure(
                                "pdfs",
                                "Timeout",
                                "deadline exceeded",
                                HybridSearchPartialFailureException.FailureDisposition.TRANSIENT)),
                List.of(new IllegalArgumentException("invalid filter"), new TimeoutException("Qdrant deadline")));

        assertFalse(sseSupport.isResponsePreparationTimeout(mixedFanOutFailure));
    }

    @Test
    void citationPartialFailureStatusFluxEmitsTheNonRetryableWarning() throws JsonProcessingException {
        SseSupport sseSupport = createSseSupport();

        List<ServerSentEvent<String>> citationStatusEvents = Objects.requireNonNull(
                sseSupport.citationPartialFailureStatusFlux(2).collectList().block(), "citation status events");

        assertEquals(1, citationStatusEvents.size());
        ServerSentEvent<String> citationStatusEvent = citationStatusEvents.getFirst();
        assertEquals(EVENT_STATUS, citationStatusEvent.event());
        SseSupport.SseEventPayload citationPartialFailureStatus = objectMapper.readValue(
                Objects.requireNonNull(citationStatusEvent.data(), "citation status data"),
                SseSupport.SseEventPayload.class);
        assertEquals("Some citations could not be loaded (2 failed)", citationPartialFailureStatus.message());
        assertEquals("Citations could not be loaded", citationPartialFailureStatus.details());
        assertEquals("citation.partial-failure", citationPartialFailureStatus.code());
        assertEquals(Boolean.FALSE, citationPartialFailureStatus.retryable());
        assertEquals("citation", citationPartialFailureStatus.stage());
    }

    @Test
    void citationPartialFailureStatusFluxEmitsNothingWhenAllCitationsConvert() {
        SseSupport sseSupport = createSseSupport();

        StepVerifier.create(sseSupport.citationPartialFailureStatusFlux(0)).verifyComplete();
    }

    private SseSupport createSseSupport() {
        return new SseSupport(objectMapper, new SimpleMeterRegistry());
    }
}
