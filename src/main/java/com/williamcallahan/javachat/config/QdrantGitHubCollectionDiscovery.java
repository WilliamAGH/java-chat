package com.williamcallahan.javachat.config;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ListenableFuture;
import com.williamcallahan.javachat.service.EmbeddingClient;
import com.williamcallahan.javachat.service.QdrantPayloadFieldSchema;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.Distance;
import io.qdrant.client.grpc.Collections.Modifier;
import io.qdrant.client.grpc.Collections.PayloadSchemaType;
import io.qdrant.client.grpc.Collections.SparseVectorParams;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorParamsMap;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Discovers Qdrant collections for the shared embedding generation at startup.
 *
 * <p>After the core collections are validated by {@link QdrantIndexInitializer}, this component
 * scans for dynamically created GitHub repository collections and validates that their dense
 * vector dimensions match the current embedding model. Valid collections are exposed via
 * {@link #getDiscoveredCollections()} for inclusion in hybrid search fan-out.</p>
 *
 * <p>Discovery and schema failures make readiness fail so retrieval cannot silently omit a governed cohort.</p>
 */
@Component
@Profile("!test")
public final class QdrantGitHubCollectionDiscovery {
    private static final Logger log = LoggerFactory.getLogger(QdrantGitHubCollectionDiscovery.class);

    private static final String GITHUB_COLLECTION_PREFIX = "github-qwen3-embedding-4b-2560-";
    private static final long GRPC_TIMEOUT_SECONDS = 10;
    private static final long DISCOVERY_RETRY_MILLIS = 30_000L;
    private static final int COLLECTION_VALIDATION_BATCH_SIZE = 8;
    private static final Map<String, PayloadSchemaType> REQUIRED_PAYLOAD_INDEXES = Map.ofEntries(
            Map.entry(QdrantPayloadFieldSchema.URL_FIELD, PayloadSchemaType.Keyword),
            Map.entry(QdrantPayloadFieldSchema.HASH_FIELD, PayloadSchemaType.Keyword),
            Map.entry(QdrantPayloadFieldSchema.CHUNK_INDEX_FIELD, PayloadSchemaType.Integer),
            Map.entry(QdrantPayloadFieldSchema.DOC_SET_FIELD, PayloadSchemaType.Keyword),
            Map.entry(QdrantPayloadFieldSchema.DOC_PATH_FIELD, PayloadSchemaType.Keyword),
            Map.entry(QdrantPayloadFieldSchema.SOURCE_NAME_FIELD, PayloadSchemaType.Keyword),
            Map.entry(QdrantPayloadFieldSchema.SOURCE_KIND_FIELD, PayloadSchemaType.Keyword),
            Map.entry(QdrantPayloadFieldSchema.DOC_VERSION_FIELD, PayloadSchemaType.Keyword),
            Map.entry(QdrantPayloadFieldSchema.DOC_TYPE_FIELD, PayloadSchemaType.Keyword),
            Map.entry(QdrantPayloadFieldSchema.FILE_PATH_FIELD, PayloadSchemaType.Keyword),
            Map.entry(QdrantPayloadFieldSchema.LANGUAGE_FIELD, PayloadSchemaType.Keyword),
            Map.entry(QdrantPayloadFieldSchema.REPO_URL_FIELD, PayloadSchemaType.Keyword),
            Map.entry(QdrantPayloadFieldSchema.REPO_OWNER_FIELD, PayloadSchemaType.Keyword),
            Map.entry(QdrantPayloadFieldSchema.REPO_NAME_FIELD, PayloadSchemaType.Keyword),
            Map.entry(QdrantPayloadFieldSchema.REPO_KEY_FIELD, PayloadSchemaType.Keyword),
            Map.entry(QdrantPayloadFieldSchema.REPO_BRANCH_FIELD, PayloadSchemaType.Keyword),
            Map.entry(QdrantPayloadFieldSchema.COMMIT_HASH_FIELD, PayloadSchemaType.Keyword),
            Map.entry(QdrantPayloadFieldSchema.LICENSE_FIELD, PayloadSchemaType.Keyword),
            Map.entry(QdrantPayloadFieldSchema.REPO_DESCRIPTION_FIELD, PayloadSchemaType.Keyword));

    private final QdrantClient qdrantClient;
    private final EmbeddingClient embeddingClient;
    private final AppProperties appProperties;
    private final String generationCollectionPrefix;

    private final AtomicReference<List<String>> discoveredCollections = new AtomicReference<>(List.of());
    private final AtomicReference<GitHubDiscoveryState> discoveryState =
            new AtomicReference<>(GitHubDiscoveryState.PENDING);
    private List<String> currentInventoryCollections = List.of();
    private long inventoryRefreshSucceededNanos = Long.MIN_VALUE;
    private long inventoryRefreshFailedNanos = Long.MIN_VALUE;

    /**
     * Wires Qdrant gRPC client and embedding client for collection discovery and validation.
     *
     * @param qdrantClient Qdrant gRPC client for listing and inspecting collections
     * @param embeddingClient embedding client for resolving expected vector dimensions
     * @param appProperties application configuration for vector name resolution
     */
    public QdrantGitHubCollectionDiscovery(
            QdrantClient qdrantClient, EmbeddingClient embeddingClient, AppProperties appProperties) {
        this.qdrantClient = Objects.requireNonNull(qdrantClient, "qdrantClient");
        this.embeddingClient = Objects.requireNonNull(embeddingClient, "embeddingClient");
        this.appProperties = Objects.requireNonNull(appProperties, "appProperties");
        this.generationCollectionPrefix = GITHUB_COLLECTION_PREFIX;
    }

    /**
     * Scans Qdrant for collections under the exact shared-generation prefix after the application is ready.
     *
     * <p>Runs after {@link QdrantIndexInitializer} to avoid interfering with core collection setup.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void discoverGitHubCollections() {
        attemptDiscovery(true);
    }

    /** Retries governed discovery after transient Qdrant transport failures. */
    @Scheduled(initialDelay = DISCOVERY_RETRY_MILLIS, fixedDelay = DISCOVERY_RETRY_MILLIS)
    void retryPendingDiscovery() {
        attemptDiscovery(false);
    }

    private synchronized void attemptDiscovery(boolean startupAttempt) {
        if (discoveryState.get() != GitHubDiscoveryState.PENDING) {
            return;
        }
        try {
            List<String> gitHubCandidates = loadValidatedGitHubCollections();
            discoveredCollections.set(gitHubCandidates);
            discoveryState.set(GitHubDiscoveryState.READY);
            if (gitHubCandidates.isEmpty()) {
                log.info(
                        "[QDRANT] No GitHub collections found for shared-generation prefix '{}'",
                        generationCollectionPrefix);
                return;
            }

            log.info("[QDRANT] Discovered {} GitHub collection(s): {}", gitHubCandidates.size(), gitHubCandidates);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            log.debug("[QDRANT] GitHub collection discovery interrupted while pending");
        } catch (ExecutionException executionException) {
            handleDiscoveryExecutionFailure(executionException, startupAttempt);
        } catch (TimeoutException timeoutException) {
            logPendingDiscovery(
                    startupAttempt, "GitHub collection discovery timed out after " + GRPC_TIMEOUT_SECONDS + "s");
        } catch (GitHubDiscoveryUnavailableException unavailableException) {
            logPendingDiscovery(startupAttempt, unavailableException.getMessage());
        } catch (RuntimeException schemaException) {
            discoveredCollections.set(List.of());
            discoveryState.set(GitHubDiscoveryState.FAILED);
            log.error(
                    "[QDRANT] GitHub collection schema validation failed (exceptionType={}): {}",
                    schemaException.getClass().getSimpleName(),
                    schemaException.getMessage(),
                    schemaException);
        }
    }

    private List<String> loadValidatedGitHubCollections()
            throws InterruptedException, ExecutionException, TimeoutException {
        ListenableFuture<List<String>> collectionNamesRequest =
                qdrantClient.listCollectionsAsync(Duration.ofSeconds(GRPC_TIMEOUT_SECONDS));
        List<String> allCollectionNames;
        try {
            allCollectionNames = collectionNamesRequest.get(GRPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            if (!collectionNamesRequest.isDone()) {
                collectionNamesRequest.cancel(true);
            }
        }
        List<String> gitHubCandidates = allCollectionNames.stream()
                .filter(name -> name.startsWith(generationCollectionPrefix))
                .toList();
        if (gitHubCandidates.isEmpty()) {
            return List.of();
        }

        int expectedDimensions = embeddingClient.dimensions();
        String denseVectorName = appProperties.getQdrant().getDenseVectorName();
        String sparseVectorName = appProperties.getQdrant().getSparseVectorName();
        for (List<String> candidateBatch : Lists.partition(gitHubCandidates, COLLECTION_VALIDATION_BATCH_SIZE)) {
            List<ListenableFuture<CollectionInfo>> collectionInfoRequests = candidateBatch.stream()
                    .map(qdrantClient::getCollectionInfoAsync)
                    .toList();
            try {
                for (int collectionIndex = 0; collectionIndex < candidateBatch.size(); collectionIndex++) {
                    validateGitHubCollection(
                            candidateBatch.get(collectionIndex),
                            collectionInfoRequests.get(collectionIndex),
                            expectedDimensions,
                            denseVectorName,
                            sparseVectorName);
                }
            } finally {
                collectionInfoRequests.stream()
                        .filter(collectionInfoRequest -> !collectionInfoRequest.isDone())
                        .forEach(collectionInfoRequest -> collectionInfoRequest.cancel(true));
            }
        }
        return gitHubCandidates;
    }

    private void handleDiscoveryExecutionFailure(ExecutionException executionException, boolean startupAttempt) {
        Throwable failureCause = executionException.getCause();
        if (isTransientGrpcFailure(failureCause)) {
            logPendingDiscovery(
                    startupAttempt,
                    "Qdrant is unavailable for GitHub collection discovery (cause="
                            + failureCause.getClass().getSimpleName() + ")");
            return;
        }
        discoveredCollections.set(List.of());
        discoveryState.set(GitHubDiscoveryState.FAILED);
        log.error(
                "[QDRANT] GitHub collection discovery failed (cause={}): {}",
                failureCause != null ? failureCause.getClass().getSimpleName() : "unknown",
                executionException.getMessage(),
                executionException);
    }

    private void logPendingDiscovery(boolean startupAttempt, String failureDescription) {
        if (startupAttempt) {
            log.warn("[QDRANT] GitHub collection discovery deferred: {}", failureDescription);
        } else {
            log.debug("[QDRANT] GitHub collection discovery still pending: {}", failureDescription);
        }
    }

    /**
     * Returns the list of validated GitHub collections discovered at startup.
     *
     * @return immutable list of GitHub collection names with matching vector configuration
     */
    public List<String> getDiscoveredCollections() {
        return discoveredCollections.get();
    }

    /**
     * Refreshes and returns the validated GitHub collection set for a live inventory read.
     *
     * <p>Repository ingestion can create or remove collections after application startup. Inventory
     * callers therefore need a current snapshot rather than the retrieval fan-out's startup cache.</p>
     */
    public List<String> refreshDiscoveredCollections() {
        long inventoryRequestStartedNanos = System.nanoTime();
        synchronized (this) {
            long latestInventoryRefreshNanos = Math.max(inventoryRefreshSucceededNanos, inventoryRefreshFailedNanos);
            if (latestInventoryRefreshNanos >= inventoryRequestStartedNanos) {
                if (inventoryRefreshFailedNanos > inventoryRefreshSucceededNanos) {
                    throw new IllegalStateException("Concurrent GitHub collection discovery failed");
                }
                return List.copyOf(currentInventoryCollections);
            }
            try {
                currentInventoryCollections = loadValidatedGitHubCollections();
                inventoryRefreshSucceededNanos = System.nanoTime();
                return List.copyOf(currentInventoryCollections);
            } catch (InterruptedException interruptedFailure) {
                Thread.currentThread().interrupt();
                inventoryRefreshFailedNanos = System.nanoTime();
                throw new IllegalStateException(
                        "Current GitHub collection discovery was interrupted", interruptedFailure);
            } catch (ExecutionException | TimeoutException discoveryFailure) {
                inventoryRefreshFailedNanos = System.nanoTime();
                throw new IllegalStateException("Current GitHub collection discovery is unavailable", discoveryFailure);
            } catch (IllegalStateException discoveryFailure) {
                inventoryRefreshFailedNanos = System.nanoTime();
                throw discoveryFailure;
            }
        }
    }

    Health discoveryHealth() {
        return switch (discoveryState.get()) {
            case READY ->
                Health.up().withDetail("githubCollectionDiscovery", "ready").build();
            case PENDING ->
                Health.down().withDetail("githubCollectionDiscovery", "pending").build();
            case FAILED ->
                Health.down().withDetail("githubCollectionDiscovery", "failed").build();
        };
    }

    static Map<String, PayloadSchemaType> requiredPayloadIndexes() {
        return REQUIRED_PAYLOAD_INDEXES;
    }

    private void validateGitHubCollection(
            String collectionName,
            ListenableFuture<CollectionInfo> collectionInfoRequest,
            int expectedDimensions,
            String denseVectorName,
            String sparseVectorName) {
        try {
            CollectionInfo collectionInfo = collectionInfoRequest.get(GRPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            VectorsConfig vectorsConfig = collectionInfo.getConfig().getParams().getVectorsConfig();
            if (!vectorsConfig.hasParamsMap()) {
                throw new IllegalStateException(
                        "GitHub collection '" + collectionName + "' does not use named vectors");
            }

            VectorParamsMap paramsMap = vectorsConfig.getParamsMap();
            Map<String, VectorParams> vectorNameToParams = paramsMap.getMapMap();

            VectorParams denseParams = vectorNameToParams.get(denseVectorName);
            if (denseParams == null) {
                throw new IllegalStateException(
                        "GitHub collection '" + collectionName + "' is missing dense vector '" + denseVectorName + "'");
            }

            long actualDimensions = denseParams.getSize();
            if (actualDimensions != expectedDimensions) {
                throw new IllegalStateException("GitHub collection '" + collectionName
                        + "' dimension mismatch: expected " + expectedDimensions + " but found " + actualDimensions);
            }
            if (denseParams.getDistance() != Distance.Cosine) {
                throw new IllegalStateException(
                        "GitHub collection '" + collectionName + "' dense vector distance must be Cosine");
            }

            io.qdrant.client.grpc.Collections.SparseVectorConfig sparseVectorConfig =
                    collectionInfo.getConfig().getParams().getSparseVectorsConfig();
            if (!sparseVectorConfig.containsMap(sparseVectorName)) {
                throw new IllegalStateException("GitHub collection '" + collectionName + "' is missing sparse vector '"
                        + sparseVectorName + "'");
            }
            SparseVectorParams sparseVectorParams = sparseVectorConfig.getMapOrThrow(sparseVectorName);
            if (!sparseVectorParams.hasModifier() || sparseVectorParams.getModifier() != Modifier.Idf) {
                throw new IllegalStateException(
                        "GitHub collection '" + collectionName + "' sparse vector modifier must be Idf");
            }
            if (!collectionInfo.getConfig().getParams().getOnDiskPayload()) {
                throw new IllegalStateException(
                        "GitHub collection '" + collectionName + "' must store payload on disk");
            }
            for (Map.Entry<String, PayloadSchemaType> payloadIndex : REQUIRED_PAYLOAD_INDEXES.entrySet()) {
                if (!collectionInfo.containsPayloadSchema(payloadIndex.getKey())
                        || collectionInfo
                                        .getPayloadSchemaOrThrow(payloadIndex.getKey())
                                        .getDataType()
                                != payloadIndex.getValue()) {
                    throw new IllegalStateException("GitHub collection '" + collectionName
                            + "' is missing required " + payloadIndex.getValue() + " payload index '"
                            + payloadIndex.getKey() + "'");
                }
            }
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub collection validation interrupted for '" + collectionName + "'");
        } catch (ExecutionException executionException) {
            if (isTransientGrpcFailure(executionException.getCause())) {
                throw new GitHubDiscoveryUnavailableException(
                        "Qdrant is unavailable while validating GitHub collection '" + collectionName + "'",
                        executionException);
            }
            throw new IllegalStateException(
                    "Failed to validate GitHub collection '" + collectionName + "'", executionException);
        } catch (TimeoutException timeoutException) {
            throw new GitHubDiscoveryUnavailableException(
                    "Validation timed out for GitHub collection '" + collectionName + "' after " + GRPC_TIMEOUT_SECONDS
                            + "s",
                    timeoutException);
        }
    }

    private static boolean isTransientGrpcFailure(Throwable failureCause) {
        Status.Code statusCode;
        if (failureCause instanceof StatusRuntimeException statusRuntimeException) {
            statusCode = statusRuntimeException.getStatus().getCode();
        } else if (failureCause instanceof StatusException statusException) {
            statusCode = statusException.getStatus().getCode();
        } else {
            return false;
        }
        return statusCode == Status.Code.UNAVAILABLE
                || statusCode == Status.Code.DEADLINE_EXCEEDED
                || statusCode == Status.Code.RESOURCE_EXHAUSTED
                || statusCode == Status.Code.ABORTED;
    }

    /** Represents whether governed collection discovery may serve retrieval traffic. */
    private enum GitHubDiscoveryState {
        PENDING,
        READY,
        FAILED
    }

    /** Signals a retryable Qdrant transport failure without disguising schema defects. */
    private static final class GitHubDiscoveryUnavailableException extends IllegalStateException {
        private GitHubDiscoveryUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
