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
public final class QdrantPayloadFieldSchema {

    private QdrantPayloadFieldSchema() {}

    /** Payload field key for the document text content. */
    public static final String DOC_CONTENT_FIELD = "doc_content";

    /** Payload metadata key for a canonical source URL. */
    public static final String URL_FIELD = "url";

    /** Payload metadata key for a human-readable document title. */
    public static final String TITLE_FIELD = "title";

    /** Payload metadata key for a Java package name. */
    public static final String PACKAGE_FIELD = "package";

    /** Payload metadata key for an exact member anchor within a canonical Javadoc page. */
    public static final String ANCHOR_FIELD = "anchor";

    /** Payload metadata key for the terminal Javadoc type HTML page. */
    public static final String JAVA_API_TYPE_PAGE_FIELD = "javaApiTypePage";

    /** Payload metadata key for the deterministic document content hash. */
    public static final String HASH_FIELD = "hash";

    /** Payload metadata key for the documentation-set provenance token. */
    public static final String DOC_SET_FIELD = "docSet";

    /** Payload metadata key for the path within a documentation set. */
    public static final String DOC_PATH_FIELD = "docPath";

    /** Payload metadata key for the source display name. */
    public static final String SOURCE_NAME_FIELD = "sourceName";

    /** Payload metadata key for the source provenance kind. */
    public static final String SOURCE_KIND_FIELD = "sourceKind";

    /** Payload metadata key for the source documentation version. */
    public static final String DOC_VERSION_FIELD = "docVersion";

    /** Payload metadata key for documentation classification. */
    public static final String DOC_TYPE_FIELD = "docType";

    /** Payload metadata key for a source-file path. */
    public static final String FILE_PATH_FIELD = "filePath";

    /** Payload metadata key for a source language. */
    public static final String LANGUAGE_FIELD = "language";

    /** Payload metadata key for a repository URL. */
    public static final String REPO_URL_FIELD = "repoUrl";

    /** Payload metadata key for a repository owner. */
    public static final String REPO_OWNER_FIELD = "repoOwner";

    /** Payload metadata key for a repository name. */
    public static final String REPO_NAME_FIELD = "repoName";

    /** Payload metadata key for a stable repository identity key. */
    public static final String REPO_KEY_FIELD = "repoKey";

    /** Payload metadata key for a repository branch. */
    public static final String REPO_BRANCH_FIELD = "repoBranch";

    /** Payload metadata key for a repository commit hash. */
    public static final String COMMIT_HASH_FIELD = "commitHash";

    /** Payload metadata key for a repository license. */
    public static final String LICENSE_FIELD = "license";

    /** Payload metadata key for a repository description. */
    public static final String REPO_DESCRIPTION_FIELD = "repoDescription";

    /** Payload metadata key for a chunk's zero-based position. */
    public static final String CHUNK_INDEX_FIELD = "chunkIndex";

    /** Payload metadata key for the first page represented by a PDF chunk. */
    public static final String PAGE_START_FIELD = "pageStart";

    /** Payload metadata key for the final page represented by a PDF chunk. */
    public static final String PAGE_END_FIELD = "pageEnd";

    /** Metadata fields stored and retrieved as Qdrant string values. */
    static final Set<String> STRING_METADATA_FIELDS = Set.of(
            URL_FIELD,
            TITLE_FIELD,
            PACKAGE_FIELD,
            ANCHOR_FIELD,
            JAVA_API_TYPE_PAGE_FIELD,
            HASH_FIELD,
            DOC_SET_FIELD,
            DOC_PATH_FIELD,
            SOURCE_NAME_FIELD,
            SOURCE_KIND_FIELD,
            DOC_VERSION_FIELD,
            DOC_TYPE_FIELD,
            FILE_PATH_FIELD,
            LANGUAGE_FIELD,
            REPO_URL_FIELD,
            REPO_OWNER_FIELD,
            REPO_NAME_FIELD,
            REPO_KEY_FIELD,
            REPO_BRANCH_FIELD,
            COMMIT_HASH_FIELD,
            LICENSE_FIELD,
            REPO_DESCRIPTION_FIELD);

    /** Metadata fields stored and retrieved as Qdrant integer values. */
    static final Set<String> INTEGER_METADATA_FIELDS = Set.of(CHUNK_INDEX_FIELD, PAGE_START_FIELD, PAGE_END_FIELD);

    /** Union of all metadata field names for type-agnostic iteration (e.g., upsert write path). */
    static final Set<String> ALL_METADATA_FIELDS;

    static {
        LinkedHashSet<String> combined = new LinkedHashSet<>(STRING_METADATA_FIELDS);
        combined.addAll(INTEGER_METADATA_FIELDS);
        ALL_METADATA_FIELDS = Set.copyOf(combined);
    }
}
