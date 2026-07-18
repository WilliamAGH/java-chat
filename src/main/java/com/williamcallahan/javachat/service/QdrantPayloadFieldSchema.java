package com.williamcallahan.javachat.service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Single source of truth for Qdrant payload field names that survive the document
 * write→store→read round-trip.
 *
 * <p>Both the upsert path ({@link HybridVectorService}) and the retrieval path
 * ({@link HybridSearchService}) reference these sets to ensure metadata fields
 * are written and read consistently.</p>
 */
final class QdrantPayloadFieldSchema {

    private QdrantPayloadFieldSchema() {}

    /** Payload field key for the document text content. */
    static final String DOC_CONTENT_FIELD = "doc_content";

    /** Payload metadata key for a canonical source URL. */
    static final String URL_FIELD = "url";

    /** Payload metadata key for documentation classification. */
    static final String DOC_TYPE_FIELD = "docType";

    /** Metadata fields stored and retrieved as Qdrant string values. */
    static final Set<String> STRING_METADATA_FIELDS = Set.of(
            URL_FIELD,
            "title",
            "package",
            "hash",
            "docSet",
            "docPath",
            "sourceName",
            "sourceKind",
            "docVersion",
            DOC_TYPE_FIELD,
            "filePath",
            "language",
            "repoUrl",
            "repoOwner",
            "repoName",
            "repoKey",
            "repoBranch",
            "commitHash",
            "license",
            "repoDescription");

    /** Metadata fields stored and retrieved as Qdrant integer values. */
    static final Set<String> INTEGER_METADATA_FIELDS = Set.of("chunkIndex", "pageStart", "pageEnd");

    /** Union of all metadata field names for type-agnostic iteration (e.g., upsert write path). */
    static final Set<String> ALL_METADATA_FIELDS;

    static {
        LinkedHashSet<String> combined = new LinkedHashSet<>(STRING_METADATA_FIELDS);
        combined.addAll(INTEGER_METADATA_FIELDS);
        ALL_METADATA_FIELDS = Set.copyOf(combined);
    }
}
