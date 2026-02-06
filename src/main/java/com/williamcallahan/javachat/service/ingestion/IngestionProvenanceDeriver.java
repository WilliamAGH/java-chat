package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.nio.file.Path;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Derives provenance metadata (doc set, type, version, source) for ingested local files.
 *
 * <p>Provenance is used for routing into Qdrant collections and for downstream citation/audit workflows.</p>
 */
@Service
public class IngestionProvenanceDeriver {
    private static final String DEFAULT_DOCS_ROOT = "data/docs";

    /**
     * Builds provenance tokens for a local file.
     *
     * @param root root directory being ingested
     * @param file current file under the root
     * @param url canonical URL for the file
     * @return derived provenance
     */
    public IngestionProvenance derive(Path root, Path file, String url) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(file, "file");

        Path baseDocsDir = Path.of(DEFAULT_DOCS_ROOT).toAbsolutePath().normalize();
        String docSet = "";
        if (root.startsWith(baseDocsDir)) {
            docSet = baseDocsDir.relativize(root).toString();
        }

        String docPath = "";
        if (file.startsWith(root)) {
            docPath = root.relativize(file).toString();
        }

        String sourceName = deriveSourceName(docSet, url);
        String sourceKind = deriveSourceKind(sourceName);
        String docType = deriveDocType(docSet, url);
        String docVersion = deriveDocVersion(docSet, url);

        return new IngestionProvenance(
                sanitize(docSet),
                sanitize(docPath),
                sanitize(sourceName),
                sanitize(sourceKind),
                sanitize(docVersion),
                sanitize(docType));
    }

    private static String deriveSourceName(String docSet, String url) {
        if (docSet != null) {
            String normalized = docSet.replace('\\', '/');
            if (normalized.startsWith("oracle/") || normalized.startsWith("java/")) {
                return "oracle";
            }
            if (normalized.startsWith("ibm/")) {
                return "ibm";
            }
            if (normalized.startsWith("jetbrains/")) {
                return "jetbrains";
            }
            int slash = normalized.indexOf('/');
            if (slash > 0) {
                return normalized.substring(0, slash);
            }
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        if (url != null) {
            String lower = AsciiTextNormalizer.toLowerAscii(url);
            if (lower.contains("docs.oracle.com") || lower.contains("oracle.com")) {
                return "oracle";
            }
            if (lower.contains("developer.ibm.com") || lower.contains("ibm.com")) {
                return "ibm";
            }
            if (lower.contains("jetbrains.com")) {
                return "jetbrains";
            }
        }
        return "";
    }

    private static String deriveSourceKind(String sourceName) {
        if (sourceName == null || sourceName.isBlank()) {
            return "";
        }
        String lower = AsciiTextNormalizer.toLowerAscii(sourceName);
        if ("oracle".equals(lower)) {
            return "official";
        }
        if ("ibm".equals(lower) || "jetbrains".equals(lower)) {
            return "vendor";
        }
        return "unknown";
    }

    private static String deriveDocType(String docSet, String url) {
        String normalized = docSet == null ? "" : docSet.replace('\\', '/');
        if (normalized.startsWith("books")) {
            return "book";
        }
        if (normalized.startsWith("java/")) {
            return "api-docs";
        }
        if (normalized.startsWith("oracle/javase")) {
            return "release-notes";
        }
        if (normalized.startsWith("ibm/articles") || normalized.startsWith("jetbrains/")) {
            return "blog";
        }
        if (url != null) {
            String lower = AsciiTextNormalizer.toLowerAscii(url);
            if (lower.contains("docs.oracle.com/en/java/javase/")) {
                return "api-docs";
            }
            if (lower.contains("/pdfs/")) {
                return "pdf";
            }
        }
        return "";
    }

    private static String deriveDocVersion(String docSet, String url) {
        String normalized = docSet == null ? "" : docSet.replace('\\', '/');
        String fromDocSet = firstNumberToken(normalized);
        if (!fromDocSet.isBlank()) {
            return fromDocSet;
        }
        if (url != null) {
            String fromUrl = firstNumberToken(AsciiTextNormalizer.toLowerAscii(url));
            return fromUrl;
        }
        return "";
    }

    private static String firstNumberToken(String input) {
        if (input == null || input.isBlank()) {
            return "";
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digits.append(ch);
            } else if (!digits.isEmpty()) {
                break;
            }
        }
        return digits.toString();
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? "" : trimmed;
    }

    /**
     * Immutable provenance tokens attached to ingested chunks.
     *
     * @param docSet doc set identifier (directory-relative)
     * @param docPath path relative to the ingested root
     * @param sourceName logical source identifier
     * @param sourceKind source category ("official", "vendor", "unknown")
     * @param docVersion version token when available
     * @param docType doc type token ("api-docs", "release-notes", "blog", "pdf", etc.)
     */
    public record IngestionProvenance(
            String docSet, String docPath, String sourceName, String sourceKind, String docVersion, String docType) {
        public IngestionProvenance {
            docSet = sanitize(docSet);
            docPath = sanitize(docPath);
            sourceName = sanitize(sourceName);
            sourceKind = sanitize(sourceKind);
            docVersion = sanitize(docVersion);
            docType = sanitize(docType);
        }
    }
}
