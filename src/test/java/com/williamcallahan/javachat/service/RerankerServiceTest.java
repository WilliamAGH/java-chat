package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.errors.OpenAIIoException;
import com.williamcallahan.javachat.config.AppProperties;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import reactor.core.publisher.Mono;

/** Verifies reranker ordering and failure behavior. */
class RerankerServiceTest {
    private static final Duration TEST_RERANKER_TIMEOUT = Duration.ofSeconds(45);
    private static final Duration REMAINING_STAGE_BUDGET_TEST_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration NEARLY_EXHAUSTED_STAGE_BUDGET = Duration.ofSeconds(5);
    private static final Duration CACHE_WAIT_DEADLINE_TEST_TIMEOUT = Duration.ofMillis(500);
    private static final Duration CACHE_WAIT_COMPLETION_TOLERANCE = Duration.ofSeconds(1);
    private static final Duration CACHE_TEST_COMPLETION_TIMEOUT = Duration.ofSeconds(5);
    private static final double TEST_RERANKER_TEMPERATURE = 0.2;
    private static final int TEST_RERANKER_OUTPUT_TOKEN_BUDGET = 384;
    private static final String RERANKER_CACHE_NAME = "reranker-cache";
    private static final int OWNER_POST_ADMISSION_CACHE_LOOKUP = 2;

    @Test
    void rerankPreservesAtomicProviderAdmissionFailure() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        ConfiguredProviderTemporarilyUnavailableException admissionFailure =
                new ConfiguredProviderTemporarilyUnavailableException(RateLimitService.ApiProvider.OPENAI);
        when(streamingService.completeJsonObject(
                        anyString(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                        eq(TEST_RERANKER_TIMEOUT)))
                .thenReturn(Mono.error(admissionFailure));

        RerankerService rerankerService = createRerankerService(streamingService);
        List<Document> sourceDocuments = List.of(new Document("first"), new Document("second"));

        RerankingFailureException rerankingFailure = assertThrows(
                RerankingFailureException.class,
                () -> rerankerService.rerank(
                        "query", sourceDocuments, 2, stageDeadlineNanos(REMAINING_STAGE_BUDGET_TEST_TIMEOUT)));

        assertEquals(admissionFailure, rerankingFailure.getCause());
    }

    @Test
    void rerankUsesConfiguredCompletionBudgetAndTimeout() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        when(streamingService.completeJsonObject(
                        anyString(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                        eq(TEST_RERANKER_TIMEOUT)))
                .thenReturn(Mono.just("{\"order\":[1,0]}"));

        RerankerService rerankerService = createRerankerService(streamingService);
        List<Document> sourceDocuments = List.of(new Document("first"), new Document("second"));

        List<Document> rankedDocuments = rerankerService.rerank(
                "query", sourceDocuments, 2, stageDeadlineNanos(REMAINING_STAGE_BUDGET_TEST_TIMEOUT));

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> outputBudgetCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(streamingService)
                .completeJsonObject(
                        promptCaptor.capture(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        outputBudgetCaptor.capture(),
                        eq(TEST_RERANKER_TIMEOUT));
        verify(streamingService, never()).complete(anyString(), eq(TEST_RERANKER_TEMPERATURE));
        assertTrue(promptCaptor.getValue().contains("Valid indices are 0 through 1."));
        assertTrue(promptCaptor.getValue().contains("Include each relevant index at most once"));
        assertTrue(promptCaptor.getValue().contains("Return {\"order\":[]} when no document is relevant"));
        assertEquals(TEST_RERANKER_OUTPUT_TOKEN_BUDGET, outputBudgetCaptor.getValue());
        assertEquals(List.of(sourceDocuments.get(1), sourceDocuments.get(0)), rankedDocuments);
    }

    @Test
    void rerankSelectsRelevantSubsetAndDropsUnrelatedDocuments() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        when(streamingService.completeJsonObject(
                        anyString(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                        eq(TEST_RERANKER_TIMEOUT)))
                .thenReturn(Mono.just("{\"order\":[2,0]}"));

        RerankerService rerankerService = createRerankerService(streamingService);
        List<Document> sourceDocuments = List.of(new Document("first"), new Document("second"), new Document("third"));

        List<Document> selectedDocuments = rerankerService.rerank(
                "query", sourceDocuments, 5, stageDeadlineNanos(REMAINING_STAGE_BUDGET_TEST_TIMEOUT));

        assertEquals(List.of(sourceDocuments.get(2), sourceDocuments.get(0)), selectedDocuments);
    }

    @Test
    void rerankReturnsEmptySelectionWhenNoDocumentIsRelevant() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        when(streamingService.completeJsonObject(
                        anyString(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                        eq(TEST_RERANKER_TIMEOUT)))
                .thenReturn(Mono.just("{\"order\":[]}"));

        RerankerService rerankerService = createRerankerService(streamingService);
        List<Document> sourceDocuments = List.of(new Document("irrelevant"));

        List<Document> selectedDocuments = rerankerService.rerank(
                "query", sourceDocuments, 5, stageDeadlineNanos(REMAINING_STAGE_BUDGET_TEST_TIMEOUT));

        assertTrue(selectedDocuments.isEmpty());
    }

    @Test
    void rerankRetainsOneRelevantDocument() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        when(streamingService.completeJsonObject(
                        anyString(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                        eq(TEST_RERANKER_TIMEOUT)))
                .thenReturn(Mono.just("{\"order\":[0]}"));

        RerankerService rerankerService = createRerankerService(streamingService);
        List<Document> sourceDocuments = List.of(new Document("relevant"));

        List<Document> selectedDocuments = rerankerService.rerank(
                "query", sourceDocuments, 5, stageDeadlineNanos(REMAINING_STAGE_BUDGET_TEST_TIMEOUT));

        assertEquals(sourceDocuments, selectedDocuments);
        verify(streamingService)
                .completeJsonObject(
                        anyString(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                        eq(TEST_RERANKER_TIMEOUT));
    }

    @Test
    void rerankRejectsDuplicateInvalidAndMalformedOrderings() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        RerankerService rerankerService = createRerankerService(streamingService);
        List<Document> sourceDocuments =
                List.of(new Document("first"), new Document("second"), new Document("third"), new Document("fourth"));

        List<String> invalidOrderingJsonValues = List.of(
                "{\"order\":[1,1,0,2]}",
                "{\"order\":[null,-1,99,2]}",
                "{\"order\":[0,1,2,3],\"explanation\":\"extra\"}",
                "Here is the order: {\"order\":[0,1,2,3]}",
                "```json\n{\"order\":[0,1,2,3]}\n```",
                "{\"order\":[0,1,2,3]} trailing",
                "{\"order\":[0,1,2,3]}{\"order\":[3,2,1,0]}",
                "{\"order\":[0,1,2,3],\"order\":[3,2,1,0]}",
                "{\"order\":[0.9,1.1,2.2,3.3]}",
                "{\"order\":[\"0\",\"1\",\"2\",\"3\"]}");
        for (String invalidOrderingJson : invalidOrderingJsonValues) {
            when(streamingService.completeJsonObject(
                            anyString(),
                            eq(TEST_RERANKER_TEMPERATURE),
                            eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                            eq(TEST_RERANKER_TIMEOUT)))
                    .thenReturn(Mono.just(invalidOrderingJson));

            assertThrows(
                    RerankingFailureException.class,
                    () -> rerankerService.rerank(
                            "query", sourceDocuments, 4, stageDeadlineNanos(REMAINING_STAGE_BUDGET_TEST_TIMEOUT)));
        }
    }

    @Test
    void emptyCompletionIsTerminalWithoutCallingAnotherCompletionPath() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        when(streamingService.completeJsonObject(
                        anyString(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                        eq(TEST_RERANKER_TIMEOUT)))
                .thenReturn(Mono.empty());
        RerankerService rerankerService = createRerankerService(streamingService);
        List<Document> sourceDocuments = List.of(new Document("first"), new Document("second"));

        RerankingFailureException rerankingFailure = assertThrows(
                RerankingFailureException.class,
                () -> rerankerService.rerank(
                        "query", sourceDocuments, 2, stageDeadlineNanos(REMAINING_STAGE_BUDGET_TEST_TIMEOUT)));

        assertEquals("Reranking response was empty", rerankingFailure.getMessage());
        verify(streamingService, never()).complete(anyString(), eq(TEST_RERANKER_TEMPERATURE));
    }

    @Test
    void rerankTightensItsTimeoutToTheRemainingStageBudget() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        when(streamingService.completeJsonObject(
                        anyString(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                        any(Duration.class)))
                .thenReturn(Mono.just("{\"order\":[1,0]}"));

        RerankerService rerankerService = createRerankerService(streamingService);
        List<Document> sourceDocuments = List.of(new Document("first"), new Document("second"));

        List<Document> rankedDocuments =
                rerankerService.rerank("query", sourceDocuments, 2, stageDeadlineNanos(NEARLY_EXHAUSTED_STAGE_BUDGET));

        ArgumentCaptor<Duration> remainingBudgetCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(streamingService)
                .completeJsonObject(
                        anyString(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                        remainingBudgetCaptor.capture());
        assertTrue(remainingBudgetCaptor.getValue().isPositive());
        assertTrue(remainingBudgetCaptor.getValue().compareTo(NEARLY_EXHAUSTED_STAGE_BUDGET) < 0);
        assertEquals(List.of(sourceDocuments.get(1), sourceDocuments.get(0)), rankedDocuments);
    }

    @Test
    void expiredStageBudgetFailsAsTimeoutBeforeRerankerDispatch() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        RerankerService rerankerService = createRerankerService(streamingService);
        List<Document> sourceDocuments = List.of(new Document("first"), new Document("second"));

        RerankingFailureException deadlineFailure = assertThrows(
                RerankingFailureException.class,
                () -> rerankerService.rerank("query", sourceDocuments, 2, System.nanoTime()));

        assertInstanceOf(TimeoutException.class, deadlineFailure.getCause());
        verifyNoInteractions(streamingService);
    }

    @Test
    void expiredDeadlineRejectsCachedHit() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        when(streamingService.completeJsonObject(
                        anyString(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                        eq(TEST_RERANKER_TIMEOUT)))
                .thenReturn(Mono.just("{\"order\":[1,0]}"));
        RerankerService rerankerService = createRerankerService(streamingService);
        List<Document> sourceDocuments = List.of(new Document("first"), new Document("second"));
        rerankerService.rerank("query", sourceDocuments, 2, stageDeadlineNanos(REMAINING_STAGE_BUDGET_TEST_TIMEOUT));

        RerankingFailureException deadlineFailure = assertThrows(
                RerankingFailureException.class,
                () -> rerankerService.rerank("query", sourceDocuments, 2, System.nanoTime()));

        assertInstanceOf(TimeoutException.class, deadlineFailure.getCause());
        verify(streamingService, times(1))
                .completeJsonObject(
                        anyString(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                        eq(TEST_RERANKER_TIMEOUT));
    }

    @Test
    void sameKeyCacheWaitCannotExtendCallerDeadline()
            throws InterruptedException, ExecutionException, TimeoutException {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        CountDownLatch firstDispatchStarted = new CountDownLatch(1);
        CountDownLatch waitingCallerStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstDispatch = new CountDownLatch(1);
        when(streamingService.completeJsonObject(
                        anyString(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                        any(Duration.class)))
                .thenReturn(Mono.fromCallable(() -> {
                    firstDispatchStarted.countDown();
                    releaseFirstDispatch.await();
                    return "{\"order\":[1,0]}";
                }));
        RerankerService rerankerService = createRerankerService(streamingService);
        List<Document> sourceDocuments = List.of(new Document("first"), new Document("second"));
        ExecutorService rerankerExecutor = Executors.newFixedThreadPool(2);
        try {
            Future<List<Document>> firstRerank = rerankerExecutor.submit(() -> rerankerService.rerank(
                    "query", sourceDocuments, 2, stageDeadlineNanos(CACHE_TEST_COMPLETION_TIMEOUT)));
            assertTrue(firstDispatchStarted.await(CACHE_TEST_COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            long waitingCallerDeadlineNanos = stageDeadlineNanos(CACHE_WAIT_DEADLINE_TEST_TIMEOUT);
            Future<List<Document>> waitingRerank = rerankerExecutor.submit(() -> {
                waitingCallerStarted.countDown();
                return rerankerService.rerank("query", sourceDocuments, 2, waitingCallerDeadlineNanos);
            });
            assertTrue(waitingCallerStarted.await(CACHE_TEST_COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            assertFalse(
                    waitingRerank.isDone(),
                    "A caller admitted before its deadline must wait on the existing same-key rerank");
            ExecutionException waitingFailure = assertThrows(
                    ExecutionException.class,
                    () -> waitingRerank.get(CACHE_TEST_COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            RerankingFailureException deadlineFailure =
                    assertInstanceOf(RerankingFailureException.class, waitingFailure.getCause());
            assertInstanceOf(TimeoutException.class, deadlineFailure.getCause());
            assertTrue(
                    System.nanoTime() <= waitingCallerDeadlineNanos + CACHE_WAIT_COMPLETION_TOLERANCE.toNanos(),
                    "The waiting caller must fail near its own deadline without waiting for the shared rerank");
            releaseFirstDispatch.countDown();
            assertEquals(
                    List.of(sourceDocuments.get(1), sourceDocuments.get(0)),
                    firstRerank.get(CACHE_TEST_COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            verify(streamingService, times(1))
                    .completeJsonObject(
                            anyString(),
                            eq(TEST_RERANKER_TEMPERATURE),
                            eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                            any(Duration.class));
        } finally {
            releaseFirstDispatch.countDown();
            rerankerExecutor.shutdownNow();
        }
    }

    @Test
    void waiterRetriesWhenCoalescedOwnerDiesOnItsStageDeadline()
            throws InterruptedException, ExecutionException, TimeoutException {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        AtomicInteger dispatchCount = new AtomicInteger();
        when(streamingService.completeJsonObject(
                        anyString(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                        any(Duration.class)))
                .thenAnswer(invocation -> {
                    dispatchCount.incrementAndGet();
                    return Mono.just("{\"order\":[1,0]}");
                });
        CountDownLatch ownerLookupBlocked = new CountDownLatch(1);
        CountDownLatch releaseOwnerLookup = new CountDownLatch(1);
        AtomicInteger cacheLookupCount = new AtomicInteger();
        Cache deadlineBlockingCache = new ConcurrentMapCache(RERANKER_CACHE_NAME) {
            @Override
            protected Object lookup(Object key) {
                if (cacheLookupCount.incrementAndGet() == OWNER_POST_ADMISSION_CACHE_LOOKUP) {
                    ownerLookupBlocked.countDown();
                    try {
                        releaseOwnerLookup.await();
                    } catch (InterruptedException interruptedWait) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.lookup(key);
            }
        };
        RerankerService rerankerService = new RerankerService(
                streamingService,
                new ObjectMapper(),
                configuredRerankerProperties(),
                singleCacheManager(deadlineBlockingCache));
        List<Document> sourceDocuments = List.of(new Document("first"), new Document("second"));
        ExecutorService rerankerExecutor = Executors.newFixedThreadPool(2);
        try {
            Future<List<Document>> ownerRerank = rerankerExecutor.submit(() -> rerankerService.rerank(
                    "query", sourceDocuments, 2, stageDeadlineNanos(CACHE_WAIT_DEADLINE_TEST_TIMEOUT)));
            assertTrue(ownerLookupBlocked.await(CACHE_TEST_COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            Future<List<Document>> waitingRerank = rerankerExecutor.submit(() -> rerankerService.rerank(
                    "query", sourceDocuments, 2, stageDeadlineNanos(REMAINING_STAGE_BUDGET_TEST_TIMEOUT)));
            TimeUnit.MILLISECONDS.sleep(CACHE_WAIT_DEADLINE_TEST_TIMEOUT.toMillis() * 2);

            releaseOwnerLookup.countDown();

            assertEquals(
                    List.of(sourceDocuments.get(1), sourceDocuments.get(0)),
                    waitingRerank.get(CACHE_TEST_COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            ExecutionException ownerFailure = assertThrows(
                    ExecutionException.class,
                    () -> ownerRerank.get(CACHE_TEST_COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            RerankingFailureException ownerRerankingFailure =
                    assertInstanceOf(RerankingFailureException.class, ownerFailure.getCause());
            assertInstanceOf(TimeoutException.class, ownerRerankingFailure.getCause());
            assertEquals(1, dispatchCount.get(), "Only the retrying waiter dispatches a rerank call");
        } finally {
            releaseOwnerLookup.countDown();
            rerankerExecutor.shutdownNow();
        }
    }

    @Test
    void waiterInheritsProviderTimeoutOwnerFailureWithoutRetrying()
            throws InterruptedException, ExecutionException, TimeoutException {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        CountDownLatch ownerDispatchStarted = new CountDownLatch(1);
        CountDownLatch waiterCoalesced = new CountDownLatch(1);
        CountDownLatch releaseOwnerTimeout = new CountDownLatch(1);
        AtomicInteger dispatchCount = new AtomicInteger();
        when(streamingService.completeJsonObject(
                        anyString(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                        any(Duration.class)))
                .thenAnswer(invocation -> Mono.defer(() -> {
                    dispatchCount.incrementAndGet();
                    ownerDispatchStarted.countDown();
                    try {
                        releaseOwnerTimeout.await();
                    } catch (InterruptedException interruptedWait) {
                        Thread.currentThread().interrupt();
                    }
                    return Mono.error(new OpenAIIoException("Request failed", new InterruptedIOException("timeout")));
                }));
        RerankerService rerankerService = createRerankerService(streamingService);
        List<Document> sourceDocuments = List.of(new Document("first"), new Document("second"));
        ExecutorService rerankerExecutor = Executors.newFixedThreadPool(2);
        try {
            Future<List<Document>> ownerRerank = rerankerExecutor.submit(() -> rerankerService.rerank(
                    "query", sourceDocuments, 2, stageDeadlineNanos(REMAINING_STAGE_BUDGET_TEST_TIMEOUT)));
            assertTrue(ownerDispatchStarted.await(CACHE_TEST_COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            Future<List<Document>> waitingRerank = rerankerExecutor.submit(() -> {
                waiterCoalesced.countDown();
                return rerankerService.rerank(
                        "query", sourceDocuments, 2, stageDeadlineNanos(REMAINING_STAGE_BUDGET_TEST_TIMEOUT));
            });
            assertTrue(waiterCoalesced.await(CACHE_TEST_COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            TimeUnit.MILLISECONDS.sleep(200);

            releaseOwnerTimeout.countDown();

            ExecutionException waitingFailure = assertThrows(
                    ExecutionException.class,
                    () -> waitingRerank.get(CACHE_TEST_COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            RerankingFailureException waiterRerankingFailure =
                    assertInstanceOf(RerankingFailureException.class, waitingFailure.getCause());
            TimeoutException providerTimeout =
                    assertInstanceOf(TimeoutException.class, waiterRerankingFailure.getCause());
            assertInstanceOf(OpenAIIoException.class, providerTimeout.getCause());
            ExecutionException ownerFailure = assertThrows(
                    ExecutionException.class,
                    () -> ownerRerank.get(CACHE_TEST_COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            assertInstanceOf(RerankingFailureException.class, ownerFailure.getCause());
            assertEquals(
                    1,
                    dispatchCount.get(),
                    "A provider-timeout owner failure must not trigger a second billable rerank call");
        } finally {
            releaseOwnerTimeout.countDown();
            rerankerExecutor.shutdownNow();
        }
    }

    @Test
    void waiterInheritsNonTimeoutOwnerFailureWithoutRetrying()
            throws InterruptedException, ExecutionException, TimeoutException {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        CountDownLatch ownerDispatchStarted = new CountDownLatch(1);
        CountDownLatch waiterCoalesced = new CountDownLatch(1);
        CountDownLatch releaseOwnerFailure = new CountDownLatch(1);
        AtomicInteger dispatchCount = new AtomicInteger();
        when(streamingService.completeJsonObject(
                        anyString(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                        any(Duration.class)))
                .thenAnswer(invocation -> Mono.defer(() -> {
                    dispatchCount.incrementAndGet();
                    ownerDispatchStarted.countDown();
                    try {
                        releaseOwnerFailure.await();
                    } catch (InterruptedException interruptedWait) {
                        Thread.currentThread().interrupt();
                    }
                    return Mono.error(new IllegalStateException("provider defect"));
                }));
        RerankerService rerankerService = createRerankerService(streamingService);
        List<Document> sourceDocuments = List.of(new Document("first"), new Document("second"));
        ExecutorService rerankerExecutor = Executors.newFixedThreadPool(2);
        try {
            Future<List<Document>> ownerRerank = rerankerExecutor.submit(() -> rerankerService.rerank(
                    "query", sourceDocuments, 2, stageDeadlineNanos(REMAINING_STAGE_BUDGET_TEST_TIMEOUT)));
            assertTrue(ownerDispatchStarted.await(CACHE_TEST_COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            Future<List<Document>> waitingRerank = rerankerExecutor.submit(() -> {
                waiterCoalesced.countDown();
                return rerankerService.rerank(
                        "query", sourceDocuments, 2, stageDeadlineNanos(REMAINING_STAGE_BUDGET_TEST_TIMEOUT));
            });
            assertTrue(waiterCoalesced.await(CACHE_TEST_COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            TimeUnit.MILLISECONDS.sleep(200);

            releaseOwnerFailure.countDown();

            ExecutionException waitingFailure = assertThrows(
                    ExecutionException.class,
                    () -> waitingRerank.get(CACHE_TEST_COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            assertInstanceOf(
                    IllegalStateException.class, waitingFailure.getCause().getCause());
            ExecutionException ownerFailure = assertThrows(
                    ExecutionException.class,
                    () -> ownerRerank.get(CACHE_TEST_COMPLETION_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS));
            assertInstanceOf(RerankingFailureException.class, ownerFailure.getCause());
            assertEquals(1, dispatchCount.get(), "A non-timeout owner failure must not trigger a waiter retry");
        } finally {
            releaseOwnerFailure.countDown();
            rerankerExecutor.shutdownNow();
        }
    }

    @Test
    void wholeCallTimeoutPreservesRetrievalTimeoutCause() {
        OpenAIStreamingService streamingService = mock(OpenAIStreamingService.class);
        InterruptedIOException okHttpCallTimeout = new InterruptedIOException("timeout");
        OpenAIIoException providerTimeout = new OpenAIIoException("Request failed", okHttpCallTimeout);
        when(streamingService.completeJsonObject(
                        anyString(),
                        eq(TEST_RERANKER_TEMPERATURE),
                        eq(TEST_RERANKER_OUTPUT_TOKEN_BUDGET),
                        eq(TEST_RERANKER_TIMEOUT)))
                .thenReturn(Mono.error(providerTimeout));
        RerankerService rerankerService = createRerankerService(streamingService);
        List<Document> sourceDocuments = List.of(new Document("first"), new Document("second"));

        RerankingFailureException rerankingFailure = assertThrows(
                RerankingFailureException.class,
                () -> rerankerService.rerank(
                        "query", sourceDocuments, 2, stageDeadlineNanos(REMAINING_STAGE_BUDGET_TEST_TIMEOUT)));

        TimeoutException timeoutFailure = assertInstanceOf(TimeoutException.class, rerankingFailure.getCause());
        assertEquals(providerTimeout, timeoutFailure.getCause());
    }

    @Test
    void cacheIdentitySeparatesUrlAndTextBoundaries() {
        List<Document> firstDocuments = List.of(new Document("c", Map.of(QdrantPayloadFieldSchema.URL_FIELD, "ab")));
        List<Document> secondDocuments = List.of(new Document("bc", Map.of(QdrantPayloadFieldSchema.URL_FIELD, "a")));

        assertNotEquals(
                RerankerService.computeDocsHash(firstDocuments), RerankerService.computeDocsHash(secondDocuments));
    }

    @Test
    void cacheIdentityChangesWhenCitationMetadataChanges() {
        Document originalDocument = new Document(
                "document-id",
                "same text",
                Map.of(
                        QdrantPayloadFieldSchema.URL_FIELD,
                        "https://docs.example/java",
                        QdrantPayloadFieldSchema.TITLE_FIELD,
                        "Original title"));
        Document refreshedDocument = new Document(
                "document-id",
                "same text",
                Map.of(
                        QdrantPayloadFieldSchema.URL_FIELD,
                        "https://docs.example/java",
                        QdrantPayloadFieldSchema.TITLE_FIELD,
                        "Refreshed title"));

        assertNotEquals(
                RerankerService.computeDocsHash(List.of(originalDocument)),
                RerankerService.computeDocsHash(List.of(refreshedDocument)));
    }

    private static long stageDeadlineNanos(Duration stageBudget) {
        return System.nanoTime() + stageBudget.toNanos();
    }

    private static RerankerService createRerankerService(OpenAIStreamingService streamingService) {
        return new RerankerService(
                streamingService,
                new ObjectMapper(),
                configuredRerankerProperties(),
                new ConcurrentMapCacheManager(RERANKER_CACHE_NAME));
    }

    private static CacheManager singleCacheManager(Cache rerankerCache) {
        return new CacheManager() {
            @Override
            public Cache getCache(String name) {
                return rerankerCache;
            }

            @Override
            public Collection<String> getCacheNames() {
                return List.of(RERANKER_CACHE_NAME);
            }
        };
    }

    private static AppProperties configuredRerankerProperties() {
        AppProperties appProperties = new AppProperties();
        appProperties.getRag().setRerankerTimeout(TEST_RERANKER_TIMEOUT);
        appProperties.getLlm().setRerankerTemperature(TEST_RERANKER_TEMPERATURE);
        appProperties.getLlm().setRerankerOutputTokenBudget(TEST_RERANKER_OUTPUT_TOKEN_BUDGET);
        return appProperties;
    }
}
