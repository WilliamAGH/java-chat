package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.application.search.LexicalSparseVectorEncoder;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Groups collaborators that always participate in hybrid query encoding.
 *
 * <p>Bundling these dependencies keeps {@link HybridSearchService} constructor wiring
 * focused on orchestration concerns while preserving one-step query encoding assembly.</p>
 *
 * @param embeddingClient embedding client for dense query vectors
 * @param sparseVectorEncoder sparse encoder for lexical query vectors
 * @param constraintBuilder builder for Qdrant payload filters from retrieval constraints
 */
@Component
public record QueryEncodingServices(
        EmbeddingClient embeddingClient,
        LexicalSparseVectorEncoder sparseVectorEncoder,
        QdrantRetrievalConstraintBuilder constraintBuilder) {
    public QueryEncodingServices {
        Objects.requireNonNull(embeddingClient, "embeddingClient");
        Objects.requireNonNull(sparseVectorEncoder, "sparseVectorEncoder");
        Objects.requireNonNull(constraintBuilder, "constraintBuilder");
    }
}
