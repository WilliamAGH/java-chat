package com.williamcallahan.javachat.config;

import java.time.Duration;
import java.util.Locale;

/**
 * Retrieval augmentation settings.
 */
public class RetrievalAugmentationConfig {

    private static final int TOP_K_DEF = 10;
    private static final int RETURN_K_DEF = 5;
    private static final int CHUNK_MAX_DEF = 900;
    private static final int OVERLAP_DEF = 150;
    private static final int CITE_DEF = 3;
    private static final double MMR_LAMBDA_DEF = 0.5d;
    private static final Duration RERANK_TIMEOUT_DEF = Duration.ofSeconds(12);
    private static final int MIN_POSITIVE = 1;
    private static final int MIN_NON_NEG = 0;
    private static final double MMR_MIN = 0.0d;
    private static final double MMR_MAX = 1.0d;
    private static final String TOP_K_KEY = "app.rag.search-top-k";
    private static final String RETURN_K_KEY = "app.rag.search-return-k";
    private static final String CHUNK_MAX_KEY = "app.rag.chunk-max-tokens";
    private static final String OVERLAP_KEY = "app.rag.chunk-overlap-tokens";
    private static final String CITE_KEY = "app.rag.search-citations";
    private static final String MMR_KEY = "app.rag.search-mmr-lambda";
    private static final String RERANK_TIMEOUT_KEY = "app.rag.reranker-timeout";
    private static final String POSITIVE_FMT = "%s must be greater than 0.";
    private static final String NON_NEG_FMT = "%s must be 0 or greater.";
    private static final String RANGE_FMT = "%s must be between %s and %s.";

    private int searchTopK = TOP_K_DEF;
    private int searchReturnK = RETURN_K_DEF;
    private int chunkMaxTokens = CHUNK_MAX_DEF;
    private int overlapTokens = OVERLAP_DEF;
    private int searchCitations = CITE_DEF;
    private double searchMmrLambda = MMR_LAMBDA_DEF;
    private Duration rerankerTimeout = RERANK_TIMEOUT_DEF;

    /**
     * Creates retrieval augmentation configuration.
     */
    public RetrievalAugmentationConfig() {}

    private static final String RETURN_K_BOUND_MSG = "%s must be less than or equal to %s (got %d > %d).";
    private static final String OVERLAP_BOUND_MSG = "%s must be less than %s (got %d >= %d).";

    /**
     * Validates retrieval settings.
     */
    public void validateConfiguration() {
        requirePositiveCount(TOP_K_KEY, searchTopK);
        requirePositiveCount(RETURN_K_KEY, searchReturnK);
        requirePositiveCount(CHUNK_MAX_KEY, chunkMaxTokens);
        requireNonNegativeCount(OVERLAP_KEY, overlapTokens);
        requireNonNegativeCount(CITE_KEY, searchCitations);
        requireLambdaRange();
        requirePositiveDuration(RERANK_TIMEOUT_KEY, rerankerTimeout);
        if (searchReturnK > searchTopK) {
            throw new IllegalArgumentException(
                    String.format(Locale.ROOT, RETURN_K_BOUND_MSG, RETURN_K_KEY, TOP_K_KEY, searchReturnK, searchTopK));
        }
        if (overlapTokens >= chunkMaxTokens) {
            throw new IllegalArgumentException(String.format(
                    Locale.ROOT, OVERLAP_BOUND_MSG, OVERLAP_KEY, CHUNK_MAX_KEY, overlapTokens, chunkMaxTokens));
        }
    }

    /**
     * Returns the number of top matches to fetch.
     *
     * @return number of top matches to fetch
     */
    public int getSearchTopK() {
        return searchTopK;
    }

    /**
     * Sets the number of top matches to fetch.
     *
     * @param searchTopK number of top matches to fetch
     */
    public void setSearchTopK(final int searchTopK) {
        this.searchTopK = searchTopK;
    }

    /**
     * Returns the number of matches to return to the model.
     *
     * @return number of matches to return to the model
     */
    public int getSearchReturnK() {
        return searchReturnK;
    }

    /**
     * Sets the number of matches to return to the model.
     *
     * @param searchReturnK number of matches to return to the model
     */
    public void setSearchReturnK(final int searchReturnK) {
        this.searchReturnK = searchReturnK;
    }

    /**
     * Returns the maximum token count per chunk.
     *
     * @return maximum token count per chunk
     */
    public int getChunkMaxTokens() {
        return chunkMaxTokens;
    }

    /**
     * Sets the maximum token count per chunk.
     *
     * @param chunkMaxTokens maximum token count per chunk
     */
    public void setChunkMaxTokens(final int chunkMaxTokens) {
        this.chunkMaxTokens = chunkMaxTokens;
    }

    /**
     * Returns the overlap token count for chunking.
     *
     * @return overlap token count for chunking
     */
    public int getChunkOverlapTokens() {
        return overlapTokens;
    }

    /**
     * Sets the overlap token count for chunking.
     *
     * @param overlapTokens overlap token count for chunking
     */
    public void setChunkOverlapTokens(final int overlapTokens) {
        this.overlapTokens = overlapTokens;
    }

    /**
     * Returns the number of citations to include.
     *
     * @return number of citations to include
     */
    public int getSearchCitations() {
        return searchCitations;
    }

    /**
     * Sets the number of citations to include.
     *
     * @param searchCitations number of citations to include
     */
    public void setSearchCitations(final int searchCitations) {
        this.searchCitations = searchCitations;
    }

    /**
     * Returns the MMR lambda value for retrieval.
     *
     * @return MMR lambda value
     */
    public double getSearchMmrLambda() {
        return searchMmrLambda;
    }

    /**
     * Sets the MMR lambda value for retrieval.
     *
     * @param searchMmrLambda MMR lambda value
     */
    public void setSearchMmrLambda(final double searchMmrLambda) {
        this.searchMmrLambda = searchMmrLambda;
    }

    /**
     * Returns the timeout budget for reranker LLM calls.
     *
     * @return reranker timeout duration
     */
    public Duration getRerankerTimeout() {
        return rerankerTimeout;
    }

    /**
     * Sets the timeout budget for reranker LLM calls.
     *
     * @param rerankerTimeout reranker timeout duration
     */
    public void setRerankerTimeout(Duration rerankerTimeout) {
        this.rerankerTimeout = rerankerTimeout;
    }

    private void requirePositiveCount(final String propertyKey, final int count) {
        if (count < MIN_POSITIVE) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, POSITIVE_FMT, propertyKey));
        }
    }

    private void requireNonNegativeCount(final String propertyKey, final int count) {
        if (count < MIN_NON_NEG) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, NON_NEG_FMT, propertyKey));
        }
    }

    private void requireLambdaRange() {
        if (searchMmrLambda < MMR_MIN || searchMmrLambda > MMR_MAX) {
            throw new IllegalArgumentException(
                    String.format(Locale.ROOT, RANGE_FMT, MMR_KEY, Double.toString(MMR_MIN), Double.toString(MMR_MAX)));
        }
    }

    private void requirePositiveDuration(String propertyKey, Duration duration) {
        if (duration == null || duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, POSITIVE_FMT, propertyKey));
        }
    }
}
