package com.williamcallahan.javachat.service;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * A graceful embedding model that handles multiple fallback scenarios:
 * 1. Primary embedding service (OpenAI, GitHub Models, Local server)
 * 2. Secondary embedding service (if configured)
 * 3. Hash-based fallback (deterministic but not semantic)
 * 4. Complete degradation (returns empty embeddings)
 */
public class GracefulEmbeddingModel implements EmbeddingModel {

    private static final Logger log = LoggerFactory.getLogger(
        GracefulEmbeddingModel.class
    );

    private final EmbeddingModel primaryModel;
    private final EmbeddingModel secondaryModel;
    private final EmbeddingModel hashingModel;
    private final boolean enableHashFallback;

    // Circuit breaker state
    private boolean primaryAvailable = true;
    private boolean secondaryAvailable = true;
    private long lastPrimaryCheck = 0;
    private long lastSecondaryCheck = 0;
    private static final long CIRCUIT_BREAKER_TIMEOUT = 60000; // 1 minute

    public GracefulEmbeddingModel(
        EmbeddingModel primaryModel,
        EmbeddingModel secondaryModel,
        EmbeddingModel hashingModel,
        boolean enableHashFallback
    ) {
        this.primaryModel = primaryModel;
        this.secondaryModel = secondaryModel;
        this.hashingModel = hashingModel;
        this.enableHashFallback = enableHashFallback;
    }

    // Constructor for single fallback (primary + hashing)
    public GracefulEmbeddingModel(
        EmbeddingModel primaryModel,
        EmbeddingModel hashingModel,
        boolean enableHashFallback
    ) {
        this(primaryModel, null, hashingModel, enableHashFallback);
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        // Try primary model first
        if (primaryAvailable || shouldRetryPrimary()) {
            try {
                EmbeddingResponse response = primaryModel.call(request);
                if (response != null && !response.getResults().isEmpty()) {
                    if (!primaryAvailable) {
                        log.info(
                            "[EMBEDDING] Primary embedding service recovered"
                        );
                        primaryAvailable = true;
                    }
                    return response;
                }
            } catch (Exception e) {
                log.warn(
                    "[EMBEDDING] Primary embedding service failed: {}",
                    e.getMessage()
                );
                primaryAvailable = false;
                lastPrimaryCheck = System.currentTimeMillis();
            }
        }

        // Try secondary model if available
        if (
            secondaryModel != null &&
            (secondaryAvailable || shouldRetrySecondary())
        ) {
            try {
                log.info("[EMBEDDING] Attempting secondary embedding service");
                EmbeddingResponse response = secondaryModel.call(request);
                if (response != null && !response.getResults().isEmpty()) {
                    if (!secondaryAvailable) {
                        log.info(
                            "[EMBEDDING] Secondary embedding service recovered"
                        );
                        secondaryAvailable = true;
                    }
                    return response;
                }
            } catch (Exception e) {
                log.warn(
                    "[EMBEDDING] Secondary embedding service failed: {}",
                    e.getMessage()
                );
                secondaryAvailable = false;
                lastSecondaryCheck = System.currentTimeMillis();
            }
        }

        // Try hash-based fallback if enabled
        if (enableHashFallback && hashingModel != null) {
            try {
                log.info(
                    "[EMBEDDING] Using hash-based fallback embeddings (limited semantic meaning)"
                );
                return hashingModel.call(request);
            } catch (Exception e) {
                log.error(
                    "[EMBEDDING] Hash-based fallback failed: {}",
                    e.getMessage()
                );
            }
        }

        // Complete degradation - return empty response
        log.error(
            "[EMBEDDING] All embedding services failed. Vector search will be unavailable."
        );
        throw new EmbeddingServiceUnavailableException(
            "All embedding services are unavailable"
        );
    }

    private boolean shouldRetryPrimary() {
        return (
            System.currentTimeMillis() - lastPrimaryCheck >
            CIRCUIT_BREAKER_TIMEOUT
        );
    }

    private boolean shouldRetrySecondary() {
        return (
            System.currentTimeMillis() - lastSecondaryCheck >
            CIRCUIT_BREAKER_TIMEOUT
        );
    }

    @Override
    public int dimensions() {
        if (primaryModel != null) {
            try {
                return primaryModel.dimensions();
            } catch (Exception e) {
                log.debug(
                    "[EMBEDDING] Could not get dimensions from primary model: {}",
                    e.getMessage()
                );
            }
        }

        if (secondaryModel != null) {
            try {
                return secondaryModel.dimensions();
            } catch (Exception e) {
                log.debug(
                    "[EMBEDDING] Could not get dimensions from secondary model: {}",
                    e.getMessage()
                );
            }
        }

        if (hashingModel != null) {
            return hashingModel.dimensions();
        }

        return 4096; // Default dimension to match Qdrant collection
    }

    /**
     * Embed a single document.
     *
     * Behavior is consistent with call(): if all embedding services fail,
     * this method throws EmbeddingServiceUnavailableException rather than
     * silently returning a zero vector (which would pollute the vector store).
     *
     * @throws EmbeddingServiceUnavailableException if all embedding services are unavailable
     */
    @Override
    public float[] embed(Document document) {
        // Delegate to call() and let exceptions propagate for consistent error handling.
        // Previously this method caught exceptions and returned zero vectors, which was
        // inconsistent with call() and caused silent data corruption in vector stores.
        EmbeddingRequest request = new EmbeddingRequest(
            List.of(document.getText()),
            null
        );
        EmbeddingResponse response = call(request);
        if (!response.getResults().isEmpty()) {
            return response.getResults().get(0).getOutput();
        }
        // This shouldn't happen since call() either returns results or throws
        throw new EmbeddingServiceUnavailableException(
            "Embedding returned empty results"
        );
    }

    /**
     * Custom exception for when all embedding services are unavailable
     */
    public static class EmbeddingServiceUnavailableException
        extends RuntimeException
    {

        public EmbeddingServiceUnavailableException(String message) {
            super(message);
        }
    }
}
