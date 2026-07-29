package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.williamcallahan.javachat.config.AppProperties;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

/**
 * Selects and orders retrieved documents by relevance to the query using an LLM.
 *
 * <p>Beyond ordering, the reranker is the retrieval pipeline's relevance gate: documents the LLM
 * judges unrelated to the query are dropped from the selection, so off-topic queries yield an
 * empty selection instead of surfacing weakly matched documents as prompt context and citations.</p>
 */
@Service
public class RerankerService {

    private static final Logger log = LoggerFactory.getLogger(RerankerService.class);

    /** Maximum character length of document text included in the rerank prompt. */
    private static final int RERANK_PROMPT_TEXT_MAX_LENGTH = 500;

    private static final String RERANKER_CACHE_NAME = "reranker-cache";
    private static final String OK_HTTP_CALL_TIMEOUT_MESSAGE = "timeout";

    private final OpenAIStreamingService openAIStreamingService;
    private final ObjectMapper mapper;
    private final Cache rerankerCache;
    private final ConcurrentMap<RerankerCacheKey, CompletableFuture<CachedRerank>> inFlightReranks =
            new ConcurrentHashMap<>();
    private final Duration rerankerTimeout;
    private final double rerankerTemperature;
    private final int rerankerOutputTokenBudget;

    /**
     * Creates a reranker backed by the streaming LLM client.
     *
     * @param openAIStreamingService streaming LLM client
     * @param objectMapper Jackson object mapper
     * @param appProperties application configuration containing reranker request settings
     * @param cacheManager application cache manager
     */
    public RerankerService(
            OpenAIStreamingService openAIStreamingService,
            ObjectMapper objectMapper,
            AppProperties appProperties,
            CacheManager cacheManager) {
        this.openAIStreamingService = Objects.requireNonNull(openAIStreamingService, "openAIStreamingService");
        this.mapper = Objects.requireNonNull(objectMapper, "objectMapper")
                .copy()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION.mappedFeature());
        CacheManager configuredCacheManager = Objects.requireNonNull(cacheManager, "cacheManager");
        this.rerankerCache =
                Objects.requireNonNull(configuredCacheManager.getCache(RERANKER_CACHE_NAME), "rerankerCache");
        this.mapper
                .coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
        AppProperties configuredAppProperties = Objects.requireNonNull(appProperties, "appProperties");
        AppProperties.Llm llmConfiguration = configuredAppProperties.getLlm();
        this.rerankerTimeout = configuredAppProperties.getRag().getRerankerTimeout();
        this.rerankerTemperature = llmConfiguration.getRerankerTemperature();
        this.rerankerOutputTokenBudget = llmConfiguration.getRerankerOutputTokenBudget();
    }

    /**
     * Selects and orders documents relevant to the query using the LLM.
     *
     * <p>Returns only the documents the reranker judged relevant, most relevant first, capped at
     * {@code returnK}; the result is empty when no candidate is relevant. The cache key includes
     * document identities to prevent returning results for wrong document sets.</p>
     *
     * @param query retrieval query
     * @param documents candidate documents to order
     * @param returnK maximum number of documents to return
     * @return relevant documents ordered most relevant first
     */
    public List<Document> rerank(String query, List<Document> documents, int returnK) {
        return rerank(query, documents, returnK, System.nanoTime() + rerankerTimeout.toNanos());
    }

    /**
     * Reranks candidates within the caller's absolute retrieval-stage deadline.
     *
     * @param query retrieval query
     * @param documents candidate documents to order
     * @param returnK maximum number of documents to return
     * @param stageDeadlineNanos absolute {@link System#nanoTime()} deadline owned by the caller
     * @return relevant documents ordered most relevant first
     */
    public List<Document> rerank(String query, List<Document> documents, int returnK, long stageDeadlineNanos) {
        requireRemainingStageBudget(stageDeadlineNanos);
        if (documents.size() <= 1) {
            return documents;
        }

        RerankerCacheKey cacheKey = new RerankerCacheKey(query, computeDocsHash(documents), returnK);
        requireRemainingStageBudget(stageDeadlineNanos);
        CachedRerank cachedRerank = rerankerCache.get(cacheKey, CachedRerank.class);
        if (cachedRerank != null) {
            requireRemainingStageBudget(stageDeadlineNanos);
            return cachedRerank.documents();
        }

        while (true) {
            CompletableFuture<CachedRerank> ownedRerank = new CompletableFuture<>();
            CompletableFuture<CachedRerank> existingRerank = inFlightReranks.putIfAbsent(cacheKey, ownedRerank);
            if (existingRerank != null) {
                try {
                    return awaitInFlightRerank(existingRerank, stageDeadlineNanos)
                            .documents();
                } catch (RerankingFailureException inFlightFailure) {
                    if (!hasRemainingStageBudget(stageDeadlineNanos) || !causedByDeadlineTimeout(inFlightFailure)) {
                        throw inFlightFailure;
                    }
                    // The coalesced attempt died on its owner's tighter stage deadline; this waiter
                    // still owns budget, so it evicts the dead future and retries as the result owner
                    // instead of inheriting a timeout caused by a deadline it never had.
                    inFlightReranks.remove(cacheKey, existingRerank);
                    continue;
                }
            }

            try {
                requireRemainingStageBudget(stageDeadlineNanos);
                CachedRerank completedDuringAdmission = rerankerCache.get(cacheKey, CachedRerank.class);
                if (completedDuringAdmission != null) {
                    ownedRerank.complete(completedDuringAdmission);
                    requireRemainingStageBudget(stageDeadlineNanos);
                    return completedDuringAdmission.documents();
                }
                CachedRerank completedRerank =
                        new CachedRerank(rerankUncached(query, documents, returnK, stageDeadlineNanos));
                rerankerCache.put(cacheKey, completedRerank);
                ownedRerank.complete(completedRerank);
                requireRemainingStageBudget(stageDeadlineNanos);
                return completedRerank.documents();
            } catch (RuntimeException | Error rerankingFailure) {
                ownedRerank.completeExceptionally(rerankingFailure);
                throw rerankingFailure;
            } finally {
                inFlightReranks.remove(cacheKey, ownedRerank);
            }
        }
    }

    private List<Document> rerankUncached(
            String query, List<Document> documents, int returnK, long stageDeadlineNanos) {
        requireRemainingStageBudget(stageDeadlineNanos);
        log.debug("Reranking {} documents", documents.size());

        String prompt = buildRerankPrompt(query, documents);
        Duration rerankRequestTimeout = tighterRerankTimeout(requireRemainingStageBudget(stageDeadlineNanos));
        Optional<String> llmOutputOptional = callLlmForReranking(prompt, rerankRequestTimeout);
        if (llmOutputOptional.isEmpty() || llmOutputOptional.get().isBlank()) {
            throw new RerankingFailureException("Reranking response was empty");
        }

        List<Document> reordered;
        try {
            reordered = parseRerankResponse(llmOutputOptional.get(), documents);
        } catch (JsonProcessingException jsonException) {
            throw new RerankingFailureException("Reranking response parse failed", jsonException);
        }

        log.debug("Successfully reranked {} documents", reordered.size());
        return limitDocuments(reordered, returnK);
    }

    private record RerankerCacheKey(String query, String documentsHash, int returnK) {
        private RerankerCacheKey {
            query = Objects.requireNonNull(query, "query");
            documentsHash = Objects.requireNonNull(documentsHash, "documentsHash");
        }
    }

    private record CachedRerank(List<Document> documents) {
        private CachedRerank {
            documents = List.copyOf(Objects.requireNonNull(documents, "documents"));
        }
    }

    private static CachedRerank awaitInFlightRerank(
            CompletableFuture<CachedRerank> inFlightRerank, long stageDeadlineNanos) {
        Duration remainingStageBudget = requireRemainingStageBudget(stageDeadlineNanos);
        try {
            CachedRerank completedRerank = inFlightRerank.get(remainingStageBudget.toNanos(), TimeUnit.NANOSECONDS);
            requireRemainingStageBudget(stageDeadlineNanos);
            return completedRerank;
        } catch (TimeoutException cacheWaitTimeout) {
            throw new RerankingFailureException(
                    "Retrieval stage deadline elapsed while waiting for reranking", cacheWaitTimeout);
        } catch (InterruptedException interruptedWait) {
            Thread.currentThread().interrupt();
            throw new RerankingFailureException("Interrupted while waiting for reranking", interruptedWait);
        } catch (ExecutionException completedRerankFailure) {
            Throwable rerankingFailure = completedRerankFailure.getCause();
            if (rerankingFailure instanceof RerankingFailureException rerankingException) {
                throw rerankingException;
            }
            if (rerankingFailure instanceof Error error) {
                throw error;
            }
            throw new RerankingFailureException("Reranking request failed", rerankingFailure);
        }
    }

    private static Duration requireRemainingStageBudget(long stageDeadlineNanos) {
        long remainingStageNanos = stageDeadlineNanos - System.nanoTime();
        if (remainingStageNanos <= 0) {
            String failureMessage = "Retrieval stage deadline elapsed before reranking";
            throw new RerankingFailureException(failureMessage, new TimeoutException(failureMessage));
        }
        return Duration.ofNanos(remainingStageNanos);
    }

    private static boolean hasRemainingStageBudget(long stageDeadlineNanos) {
        return stageDeadlineNanos - System.nanoTime() > 0;
    }

    /**
     * Reports whether the failure chain contains a caller stage-deadline timeout, the only
     * failure a fresh attempt owned by a caller with remaining budget can outlive; permanent
     * failures such as empty or unparsable rerank responses must keep propagating unchanged.
     *
     * <p>Provider transport timeouts converted by {@link #preserveProviderTimeout} also surface
     * as {@link TimeoutException}, but they carry their OkHttp timeout markers in the cause
     * chain. They are excluded here so a waiter never issues a second billable rerank call after
     * a genuine provider timeout.</p>
     */
    private static boolean causedByDeadlineTimeout(Throwable failure) {
        Set<Throwable> inspectedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable failureInChain = failure;
        while (failureInChain != null && inspectedFailures.add(failureInChain)) {
            if (failureInChain instanceof TimeoutException) {
                return !containsProviderTransportTimeout(failureInChain.getCause());
            }
            failureInChain = failureInChain.getCause();
        }
        return false;
    }

    private Duration tighterRerankTimeout(Duration remainingStageBudget) {
        Duration requiredRemainingStageBudget = Objects.requireNonNull(remainingStageBudget, "remainingStageBudget");
        return requiredRemainingStageBudget.compareTo(rerankerTimeout) < 0
                ? requiredRemainingStageBudget
                : rerankerTimeout;
    }

    /**
     * Calls LLM service to get reranking order.
     *
     * @return reranking response when the configured provider completes within its timeout
     */
    private Optional<String> callLlmForReranking(String prompt, Duration rerankRequestTimeout) {
        try {
            return openAIStreamingService
                    .completeJsonObject(prompt, rerankerTemperature, rerankerOutputTokenBudget, rerankRequestTimeout)
                    .doOnError(
                            timeoutOrApiError -> log.debug("Reranker LLM call timed out or failed", timeoutOrApiError))
                    .blockOptional();
        } catch (RuntimeException rerankFailure) {
            Throwable preservedFailure = preserveProviderTimeout(rerankFailure, rerankRequestTimeout);
            throw new RerankingFailureException(
                    "Reranking request failed within timeout " + rerankRequestTimeout, preservedFailure);
        }
    }

    private static Throwable preserveProviderTimeout(RuntimeException providerFailure, Duration requestTimeout) {
        if (containsProviderTransportTimeout(providerFailure)) {
            TimeoutException timeoutFailure =
                    new TimeoutException("Reranking request exceeded timeout " + requestTimeout);
            timeoutFailure.initCause(providerFailure);
            return timeoutFailure;
        }
        return providerFailure;
    }

    /**
     * Reports whether the failure chain carries the provider transport-timeout markers the
     * OpenAI SDK raises for an expired OkHttp call, distinguishing a genuine provider timeout
     * from a caller-owned stage-deadline timeout.
     */
    private static boolean containsProviderTransportTimeout(Throwable failure) {
        Set<Throwable> inspectedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable failureInChain = failure;
        while (failureInChain != null && inspectedFailures.add(failureInChain)) {
            if (failureInChain instanceof SocketTimeoutException
                    || (failureInChain.getClass().equals(InterruptedIOException.class)
                            && OK_HTTP_CALL_TIMEOUT_MESSAGE.equals(failureInChain.getMessage()))) {
                return true;
            }
            failureInChain = failureInChain.getCause();
        }
        return false;
    }

    /**
     * Build the prompt for the reranking LLM call.
     */
    private String buildRerankPrompt(String query, List<Document> documents) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a document re-ranker for the Java learning assistant system.\n");
        prompt.append("Select the documents that are relevant to the query and order them by relevance.\n");
        prompt.append("Consider Java-specific context, version relevance, and learning value.\n");
        prompt.append("Prefer official documentation over blogs or third-party sources.\n");
        prompt.append("Prefer stable release documentation over early-access or preview content.\n");
        prompt.append("Exclude every document that is not actually about the query's subject; ");
        prompt.append("a document that merely shares an isolated word with the query is not relevant.\n");
        prompt.append("There are exactly ")
                .append(documents.size())
                .append(" documents. Valid indices are 0 through ")
                .append(documents.size() - 1)
                .append(".\n");
        prompt.append("Include each relevant index at most once and do not return any other values.\n");
        prompt.append("Return only JSON: {\"order\":[indices...]} with 0-based indices, most relevant first.\n");
        prompt.append("Return {\"order\":[]} when no document is relevant to the query.\n");
        prompt.append("Do not include markdown, prose, or explanations.\n\n");
        prompt.append("Query: ").append(query).append("\n\n");

        for (int docIndex = 0; docIndex < documents.size(); docIndex++) {
            Document document = documents.get(docIndex);
            Map<String, ?> metadata = document.getMetadata();
            String title = extractMetadataString(metadata, QdrantPayloadFieldSchema.TITLE_FIELD);
            String url = extractMetadataString(metadata, QdrantPayloadFieldSchema.URL_FIELD);
            String text = document.getText();
            prompt.append("[")
                    .append(docIndex)
                    .append("] ")
                    .append(title)
                    .append(" | ")
                    .append(url)
                    .append("\n")
                    .append(trim(text == null ? "" : text, RERANK_PROMPT_TEXT_MAX_LENGTH))
                    .append("\n\n");
        }

        return prompt.toString();
    }

    /**
     * Parses an untrusted LLM ordering into the selected documents.
     *
     * <p>The reranker acts as the relevance gate for retrieval: it returns only the indices of
     * documents it judged relevant, most relevant first, so the parsed list may be a strict
     * subset of the source documents and may be empty when nothing is relevant.</p>
     */
    private List<Document> parseRerankResponse(String llmOutput, List<Document> documents)
            throws JsonProcessingException {
        List<Document> reordered = new ArrayList<>();
        Set<Integer> includedDocumentIndices = new HashSet<>();
        RerankOrderResponse orderResponse = parseRerankOrderResponse(llmOutput);
        if (orderResponse == null || orderResponse.order() == null) {
            throw new RerankParsingException("Rerank order must be a JSON object with an order array");
        }
        if (orderResponse.order().size() > documents.size()) {
            throw new RerankParsingException("Rerank order cannot select more documents than it was given");
        }
        for (Integer documentIndex : orderResponse.order()) {
            if (documentIndex == null
                    || documentIndex < 0
                    || documentIndex >= documents.size()
                    || !includedDocumentIndices.add(documentIndex)) {
                throw new RerankParsingException("Rerank order must contain unique valid source indices");
            }
            reordered.add(documents.get(documentIndex));
        }
        return reordered;
    }

    /** Parses the complete structured response without prose or fenced compatibility paths. */
    private RerankOrderResponse parseRerankOrderResponse(String llmOutput) throws JsonProcessingException {
        if (llmOutput == null || llmOutput.isBlank()) {
            throw new RerankParsingException("Rerank response was empty");
        }
        try {
            return mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .forType(RerankOrderResponse.class)
                    .readValue(llmOutput);
        } catch (JsonProcessingException jsonProcessingException) {
            throw new RerankParsingException("Failed to parse rerank JSON payload", jsonProcessingException);
        }
    }

    private record RerankOrderResponse(
            @JsonProperty("order") List<Integer> order) {}

    /**
     * Limit document list to returnK elements.
     */
    private List<Document> limitDocuments(List<Document> documents, int returnK) {
        return documents.subList(0, Math.min(returnK, documents.size()));
    }

    private String trim(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "…";
    }

    private static String extractMetadataString(Map<String, ?> metadata, String key) {
        if (metadata == null) {
            return "";
        }
        Object metadataValue = metadata.get(key);
        if (metadataValue == null) {
            return "";
        }
        return String.valueOf(metadataValue);
    }

    /**
     * Computes a stable cache identity for the complete ordered document inputs.
     *
     * <p>The cache returns the original {@link Document} instances in ranked order, so every
     * downstream-visible identity field must participate. This prevents metadata-only refreshes
     * from returning stale citation documents.</p>
     */
    public static String computeDocsHash(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return "empty";
        }
        try {
            MessageDigest documentDigest = MessageDigest.getInstance("SHA-256");
            for (Document document : documents) {
                updateDigest(documentDigest, document.getId());
                String documentText = document.getText();
                updateDigest(documentDigest, documentText == null ? "" : documentText);
                document.getMetadata().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(metadataEntry -> {
                            updateDigest(documentDigest, metadataEntry.getKey());
                            Object metadataField = metadataEntry.getValue();
                            updateDigest(documentDigest, metadataField == null ? "" : metadataField.toString());
                        });
            }
            return HexFormat.of().formatHex(documentDigest.digest());
        } catch (NoSuchAlgorithmException algorithmFailure) {
            throw new IllegalStateException("SHA-256 is unavailable for reranker cache identity", algorithmFailure);
        }
    }

    private static void updateDigest(MessageDigest documentDigest, String documentIdentityPart) {
        byte[] identityBytes = documentIdentityPart.getBytes(StandardCharsets.UTF_8);
        documentDigest.update((byte) (identityBytes.length >>> 24));
        documentDigest.update((byte) (identityBytes.length >>> 16));
        documentDigest.update((byte) (identityBytes.length >>> 8));
        documentDigest.update((byte) identityBytes.length);
        documentDigest.update(identityBytes);
    }

    /**
     * Signals that a rerank response could not be parsed into the expected JSON structure.
     */
    private static final class RerankParsingException extends JsonProcessingException {
        private static final long serialVersionUID = 1L;

        private RerankParsingException(String message) {
            super(message);
        }

        private RerankParsingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
