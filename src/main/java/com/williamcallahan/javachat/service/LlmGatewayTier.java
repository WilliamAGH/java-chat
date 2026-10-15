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
    public String workloadHeader(Purpose purpose) {
        return "java-chat:" + java.util.Objects.requireNonNull(purpose, "purpose").label + ":"
                + (this == LIVE ? "live" : "batch");
    }

    /** Identifies the application job independently of its wire protocol and capacity tier. */
    public enum Purpose {
        /** Answers a chat question. */
        CHAT_ANSWER("chatAnswer"),
        /** Generates a guided lesson. */
        GUIDED_LEARNING("guidedLearning"),
        /** Enriches rendered markdown. */
        MARKDOWN_ENRICHMENT("markdownEnrichment"),
        /** Ranks retrieved search candidates. */
        SEARCH_RERANK("searchRerank"),
        /** Embeds a live retrieval query. */
        SEARCH_QUERY_EMBEDDING("searchQueryEmbedding"),
        /** Embeds content supplied directly to the batch executor. */
        CONTENT_INDEX_EMBEDDING("contentIndexEmbedding"),
        /** Indexes repository source files. */
        REPOSITORY_INDEX_EMBEDDING("repositoryIndexEmbedding"),
        /** Indexes documentation. */
        DOCUMENTATION_INDEX_EMBEDDING("documentationIndexEmbedding"),
        /** Indexes book content. */
        BOOK_INDEX_EMBEDDING("bookIndexEmbedding"),
        /** Indexes articles. */
        ARTICLE_INDEX_EMBEDDING("articleIndexEmbedding"),
        /** Indexes PDF content. */
        PDF_INDEX_EMBEDDING("pdfIndexEmbedding"),
        /** Keeps the embedding model resident. */
        EMBEDDING_WARMUP("embeddingWarmup");

        private final String label;

        Purpose(String label) {
            this.label = label;
        }
    }
}
