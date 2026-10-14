package com.williamcallahan.javachat.service;

/**
 * Defines the request tiers understood by the LLM gateway.
 *
 * <p>Live user-facing work uses {@link #LIVE}. Background ingestion and scheduled
 * embedding probes use {@link #BATCH} so live requests keep reserved capacity.</p>
 */
public enum LlmGatewayTier {
    /** User-facing request tier with production reserved capacity. */
    LIVE("production-z"),

    /** Background request tier for ingestion, backfills, and scheduled probes. */
    BATCH("batch");

    /** HTTP header used by the gateway to classify request capacity. */
    public static final String REQUEST_TIER_HEADER = "X-Tier";
    /** HTTP header used by the gateway to attribute the request workload. */
    public static final String REQUEST_WORKLOAD_HEADER = "X-User-ID";

    private final String requestHeader;

    LlmGatewayTier(String requestHeader) {
        this.requestHeader = requestHeader;
    }

    /**
     * Returns the gateway header payload for this request tier.
     *
     * @return header payload sent in {@link #REQUEST_TIER_HEADER}
     */
    public String requestHeader() {
        return requestHeader;
    }

    /** Returns the stable Java Chat workload label for this request tier. */
    public String workloadHeader(String operation) {
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation must be non-blank");
        }
        return "java-chat:" + operation + ":" + (this == LIVE ? "live" : "batch");
    }
}
