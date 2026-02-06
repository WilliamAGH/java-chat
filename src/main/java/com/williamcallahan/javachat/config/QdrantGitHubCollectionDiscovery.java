package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.service.EmbeddingClient;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Collections.CollectionInfo;
import io.qdrant.client.grpc.Collections.VectorParams;
import io.qdrant.client.grpc.Collections.VectorParamsMap;
import io.qdrant.client.grpc.Collections.VectorsConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Discovers Qdrant collections with the {@code github-} prefix at application startup.
 *
 * <p>After the core collections are validated by {@link QdrantIndexInitializer}, this component
 * scans for dynamically created GitHub repository collections and validates that their dense
 * vector dimensions match the current embedding model. Valid collections are exposed via
 * {@link #getDiscoveredCollections()} for inclusion in hybrid search fan-out.</p>
 *
 * <p>Discovery failures are logged as warnings and do not prevent application startup;
 * the app functions normally without GitHub search if no collections are found.</p>
 */
@Component
@Profile("!test")
public class QdrantGitHubCollectionDiscovery {
    private static final Logger log = LoggerFactory.getLogger(QdrantGitHubCollectionDiscovery.class);

    private static final String GITHUB_COLLECTION_PREFIX = "github-";
    private static final long GRPC_TIMEOUT_SECONDS = 10;

    private final QdrantClient qdrantClient;
    private final EmbeddingClient embeddingClient;
    private final AppProperties appProperties;

    private volatile List<String> discoveredCollections = List.of();

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
    }

    /**
     * Scans Qdrant for {@code github-*} collections after the application is ready.
     *
     * <p>Runs after {@link QdrantIndexInitializer} to avoid interfering with core collection setup.</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(Ordered.LOWEST_PRECEDENCE)
    public void discoverGitHubCollections() {
        try {
            List<String> allCollectionNames =
                    qdrantClient.listCollectionsAsync().get(GRPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            List<String> gitHubCandidates = allCollectionNames.stream()
                    .filter(name -> name.startsWith(GITHUB_COLLECTION_PREFIX))
                    .toList();

            if (gitHubCandidates.isEmpty()) {
                log.info("[QDRANT] No GitHub collections found");
                return;
            }

            int expectedDimensions = embeddingClient.dimensions();
            String denseVectorName = appProperties.getQdrant().getDenseVectorName();
            String sparseVectorName = appProperties.getQdrant().getSparseVectorName();

            List<String> validatedCollections = new ArrayList<>();
            for (String candidateCollection : gitHubCandidates) {
                if (validateGitHubCollection(
                        candidateCollection, expectedDimensions, denseVectorName, sparseVectorName)) {
                    validatedCollections.add(candidateCollection);
                }
            }

            discoveredCollections = List.copyOf(validatedCollections);
            if (!validatedCollections.isEmpty()) {
                log.info(
                        "[QDRANT] Discovered {} GitHub collection(s): {}",
                        validatedCollections.size(),
                        validatedCollections);
            }
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            log.warn("[QDRANT] GitHub collection discovery interrupted");
        } catch (ExecutionException executionException) {
            log.warn(
                    "[QDRANT] GitHub collection discovery failed (cause={}): {}",
                    executionException.getCause() != null
                            ? executionException.getCause().getClass().getSimpleName()
                            : "unknown",
                    executionException.getMessage(),
                    executionException);
        } catch (TimeoutException timeoutException) {
            log.warn(
                    "[QDRANT] GitHub collection discovery timed out after {}s", GRPC_TIMEOUT_SECONDS, timeoutException);
        }
    }

    /**
     * Returns the list of validated GitHub collections discovered at startup.
     *
     * @return immutable list of GitHub collection names with matching vector configuration
     */
    public List<String> getDiscoveredCollections() {
        return discoveredCollections;
    }

    private boolean validateGitHubCollection(
            String collectionName, int expectedDimensions, String denseVectorName, String sparseVectorName) {
        try {
            CollectionInfo collectionInfo =
                    qdrantClient.getCollectionInfoAsync(collectionName).get(GRPC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            VectorsConfig vectorsConfig = collectionInfo.getConfig().getParams().getVectorsConfig();
            if (!vectorsConfig.hasParamsMap()) {
                log.warn("[QDRANT] GitHub collection '{}' does not use named vectors; skipping", collectionName);
                return false;
            }

            VectorParamsMap paramsMap = vectorsConfig.getParamsMap();
            Map<String, VectorParams> vectorNameToParams = paramsMap.getMapMap();

            VectorParams denseParams = vectorNameToParams.get(denseVectorName);
            if (denseParams == null) {
                log.warn(
                        "[QDRANT] GitHub collection '{}' missing dense vector '{}'; skipping",
                        collectionName,
                        denseVectorName);
                return false;
            }

            long actualDimensions = denseParams.getSize();
            if (actualDimensions != expectedDimensions) {
                log.warn(
                        "[QDRANT] GitHub collection '{}' dimension mismatch: expected {} but found {}; skipping",
                        collectionName,
                        expectedDimensions,
                        actualDimensions);
                return false;
            }

            io.qdrant.client.grpc.Collections.SparseVectorConfig sparseVectorConfig =
                    collectionInfo.getConfig().getParams().getSparseVectorsConfig();
            if (!sparseVectorConfig.containsMap(sparseVectorName)) {
                log.warn(
                        "[QDRANT] GitHub collection '{}' missing sparse vector '{}'; skipping",
                        collectionName,
                        sparseVectorName);
                return false;
            }

            return true;
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            log.warn("[QDRANT] Validation interrupted for GitHub collection '{}'", collectionName);
            return false;
        } catch (ExecutionException executionException) {
            log.warn(
                    "[QDRANT] Failed to validate GitHub collection '{}' (cause={})",
                    collectionName,
                    executionException.getCause() != null
                            ? executionException.getCause().getClass().getSimpleName()
                            : "unknown",
                    executionException);
            return false;
        } catch (TimeoutException timeoutException) {
            log.warn(
                    "[QDRANT] Validation timed out for GitHub collection '{}' after {}s",
                    collectionName,
                    GRPC_TIMEOUT_SECONDS,
                    timeoutException);
            return false;
        }
    }
}
