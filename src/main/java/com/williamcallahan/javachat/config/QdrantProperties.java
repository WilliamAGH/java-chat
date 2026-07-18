package com.williamcallahan.javachat.config;

import java.time.Duration;

/**
 * Owns non-secret Qdrant vector-store configuration and its cross-field invariants.
 */
public class QdrantProperties {
    private static final String QDRANT_COLLECTIONS_CONFIGURATION_KEY = "app.qdrant.collections";

    private boolean ensurePayloadIndexes = true;
    private QdrantCollectionNames collections = new QdrantCollectionNames();
    private String denseVectorName = "dense";
    private String sparseVectorName = "bm25";
    private boolean ensureCollections = true;
    private int prefetchLimit = 20;
    private int rrfK = 60;
    private boolean failOnPartialSearchError = true;
    private Duration queryTimeout = Duration.ofSeconds(5);

    /**
     * Creates Qdrant configuration with application defaults.
     */
    public QdrantProperties() {}

    /**
     * Indicates whether startup creates the required payload indexes.
     *
     * @return true when payload indexes must be ensured
     */
    public boolean isEnsurePayloadIndexes() {
        return ensurePayloadIndexes;
    }

    /**
     * Selects whether startup creates the required payload indexes.
     *
     * @param ensurePayloadIndexes true when payload indexes must be ensured
     */
    public void setEnsurePayloadIndexes(boolean ensurePayloadIndexes) {
        this.ensurePayloadIndexes = ensurePayloadIndexes;
    }

    /**
     * Returns an isolated snapshot of collection names used for ingestion and retrieval routing.
     *
     * @return copied collection-name configuration
     */
    public QdrantCollectionNames getCollections() {
        return collections.copy();
    }

    /**
     * Replaces collection routing names without retaining a mutable caller-owned reference.
     *
     * @param collections collection-name configuration
     * @throws IllegalArgumentException when collections is null
     */
    public void setCollections(QdrantCollectionNames collections) {
        if (collections == null) {
            throw new IllegalArgumentException(QDRANT_COLLECTIONS_CONFIGURATION_KEY + " must not be null");
        }
        this.collections = collections.copy();
    }

    /**
     * Returns the configured dense vector name used for embeddings.
     *
     * @return dense vector schema key
     */
    public String getDenseVectorName() {
        return denseVectorName;
    }

    /**
     * Sets the configured dense vector name used for embeddings.
     *
     * @param denseVectorName dense vector schema key
     */
    public void setDenseVectorName(String denseVectorName) {
        this.denseVectorName = denseVectorName;
    }

    /**
     * Returns the configured sparse vector name used for lexical retrieval.
     *
     * @return sparse vector schema key
     */
    public String getSparseVectorName() {
        return sparseVectorName;
    }

    /**
     * Sets the configured sparse vector name used for lexical retrieval.
     *
     * @param sparseVectorName sparse vector schema key
     */
    public void setSparseVectorName(String sparseVectorName) {
        this.sparseVectorName = sparseVectorName;
    }

    /**
     * Returns the timeout budget for hybrid query fan-out.
     *
     * @return positive query timeout
     */
    public Duration getQueryTimeout() {
        return queryTimeout;
    }

    /**
     * Sets the timeout budget for hybrid query fan-out.
     *
     * @param queryTimeout positive query timeout
     */
    public void setQueryTimeout(Duration queryTimeout) {
        this.queryTimeout = queryTimeout;
    }

    /**
     * Indicates whether startup ensures hybrid-capable collections exist.
     *
     * @return true when collections must be ensured
     */
    public boolean isEnsureCollections() {
        return ensureCollections;
    }

    /**
     * Selects whether startup ensures hybrid-capable collections exist.
     *
     * @param ensureCollections true when collections must be ensured
     */
    public void setEnsureCollections(boolean ensureCollections) {
        this.ensureCollections = ensureCollections;
    }

    /**
     * Returns the per-collection candidate count for each hybrid prefetch stage.
     *
     * @return positive prefetch limit
     */
    public int getPrefetchLimit() {
        return prefetchLimit;
    }

    /**
     * Sets the per-collection candidate count for each hybrid prefetch stage.
     *
     * @param prefetchLimit positive prefetch limit
     */
    public void setPrefetchLimit(int prefetchLimit) {
        this.prefetchLimit = prefetchLimit;
    }

    /**
     * Returns the reciprocal-rank-fusion constant used for hybrid search.
     *
     * @return positive reciprocal-rank-fusion constant
     */
    public int getRrfK() {
        return rrfK;
    }

    /**
     * Sets the reciprocal-rank-fusion constant used for hybrid search.
     *
     * @param rrfK positive reciprocal-rank-fusion constant
     */
    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }

    /**
     * Indicates whether one failed collection query fails the entire retrieval.
     *
     * @return true when partial search failures are fatal
     */
    public boolean isFailOnPartialSearchError() {
        return failOnPartialSearchError;
    }

    /**
     * Selects whether one failed collection query fails the entire retrieval.
     *
     * @param failOnPartialSearchError true when partial search failures are fatal
     */
    public void setFailOnPartialSearchError(boolean failOnPartialSearchError) {
        this.failOnPartialSearchError = failOnPartialSearchError;
    }

    QdrantProperties validateConfiguration() {
        if (collections == null) {
            throw new IllegalArgumentException(QDRANT_COLLECTIONS_CONFIGURATION_KEY + " must not be null");
        }
        collections.validateConfiguration();

        if (denseVectorName == null || denseVectorName.isBlank()) {
            throw new IllegalArgumentException("app.qdrant.dense-vector-name must not be blank");
        }
        if (sparseVectorName == null || sparseVectorName.isBlank()) {
            throw new IllegalArgumentException("app.qdrant.sparse-vector-name must not be blank");
        }
        if (prefetchLimit <= 0) {
            throw new IllegalArgumentException("app.qdrant.prefetch-limit must be positive, got: " + prefetchLimit);
        }
        if (rrfK <= 0) {
            throw new IllegalArgumentException("app.qdrant.rrf-k must be positive, got: " + rrfK);
        }
        if (queryTimeout == null || queryTimeout.isNegative() || queryTimeout.isZero()) {
            throw new IllegalArgumentException("app.qdrant.query-timeout must be positive");
        }
        return this;
    }
}
