package com.williamcallahan.javachat.service;

/**
 * Identifies the logical content buckets stored in separate Qdrant collections.
 *
 * <p>Collections are used to prevent unrelated sources (for example, PDFs vs official docs)
 * from competing in a single index while still allowing cross-collection retrieval.</p>
 */
public enum QdrantCollectionKind {
    BOOKS,
    DOCS,
    ARTICLES,
    PDFS
}
