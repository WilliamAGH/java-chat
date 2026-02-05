package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Routes ingested and cached documents to the correct Qdrant collection.
 *
 * <p>Routing is based on ingestion provenance (doc set, doc type, and path) so the
 * pipeline remains deterministic across incremental runs.</p>
 */
@Service
public class QdrantCollectionRouter {

    private static final String METADATA_DOC_SET = "docSet";
    private static final String METADATA_DOC_PATH = "docPath";
    private static final String METADATA_DOC_TYPE = "docType";

    /**
     * Determines the collection bucket for a document based on its metadata.
     *
     * @param metadata document metadata (may be empty)
     * @param url canonical URL or file URL for the document
     * @return collection kind for routing
     */
    public QdrantCollectionKind route(Map<String, ?> metadata, String url) {
        String docSet = stringMetadata(metadata, METADATA_DOC_SET);
        String docPath = stringMetadata(metadata, METADATA_DOC_PATH);
        String docType = stringMetadata(metadata, METADATA_DOC_TYPE);

        return route(docSet, docPath, docType, url);
    }

    /**
     * Determines the collection bucket for a document based on its provenance tokens.
     */
    public QdrantCollectionKind route(String docSet, String docPath, String docType, String url) {
        String normalizedDocSet = normalizeToken(docSet);
        String normalizedDocPath = normalizeToken(docPath);
        String normalizedDocType = normalizeToken(docType);
        String normalizedUrl = normalizeToken(url);

        if (normalizedDocSet.startsWith("books")) {
            return QdrantCollectionKind.BOOKS;
        }

        if ("blog".equals(normalizedDocType)
                || normalizedDocSet.startsWith("ibm/articles")
                || normalizedDocSet.startsWith("jetbrains/")
                || normalizedDocSet.startsWith("jetbrains\\")) {
            return QdrantCollectionKind.ARTICLES;
        }

        boolean isPdf = normalizedDocPath.endsWith(".pdf") || normalizedUrl.contains("/pdfs/");
        if (isPdf || "pdf".equals(normalizedDocType)) {
            return QdrantCollectionKind.PDFS;
        }

        return QdrantCollectionKind.DOCS;
    }

    private static String stringMetadata(Map<String, ?> metadata, String key) {
        if (metadata == null || metadata.isEmpty() || key == null || key.isBlank()) {
            return "";
        }
        Object value = metadata.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static String normalizeToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String trimmed = raw.trim();
        return AsciiTextNormalizer.toLowerAscii(trimmed).toLowerCase(Locale.ROOT);
    }
}
