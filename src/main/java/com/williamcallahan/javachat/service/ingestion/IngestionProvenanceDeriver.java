package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.DocsSourceRegistry.DocumentationSource;
import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
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
        Path absoluteRoot = root.toAbsolutePath().normalize();
        Path absoluteFile = file.toAbsolutePath().normalize();
        String relativeMirrorPath = absoluteRoot.startsWith(baseDocsDir)
                ? baseDocsDir.relativize(absoluteRoot).toString().replace('\\', '/')
                : "";
        String docPath = absoluteFile.startsWith(absoluteRoot)
                ? absoluteRoot.relativize(absoluteFile).toString()
                : "";
        String relativeDocumentPath = absoluteFile.startsWith(baseDocsDir)
                ? baseDocsDir.relativize(absoluteFile).toString().replace('\\', '/')
                : "";

        return DocsSourceRegistry.documentationSourceForRelativeDocumentPath(relativeDocumentPath)
                .map(documentationSource -> officialSourceProvenance(
                        documentationSource, documentPathWithinSource(relativeDocumentPath, documentationSource)))
                .orElseGet(() -> legacyProvenance(relativeMirrorPath, docPath, url));
    }

    private static String documentPathWithinSource(
            String relativeDocumentPath, DocumentationSource documentationSource) {
        String relativeMirrorPath = documentationSource.relativeMirrorPath();
        if (relativeDocumentPath.equals(relativeMirrorPath)) {
            return "";
        }
        return relativeDocumentPath.substring(relativeMirrorPath.length() + 1);
    }

    private static IngestionProvenance officialSourceProvenance(
            DocumentationSource documentationSource, String documentPath) {
        return new IngestionProvenance(
                documentationSource.docSet(),
                documentPath,
                documentationSource.docSet(),
                documentationSource.sourceKind(),
                documentationSource.docVersion(),
                documentationSource.docType());
    }

    private static IngestionProvenance legacyProvenance(String relativeMirrorPath, String docPath, String url) {
        String sourceName = deriveSourceName(relativeMirrorPath, url);
        String sourceKind = deriveSourceKind(sourceName);
        String docType = deriveDocType(relativeMirrorPath, url);
        String docVersion = deriveDocVersion(relativeMirrorPath, url);

        return new IngestionProvenance(
                sanitize(relativeMirrorPath),
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
            return DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE;
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
                return DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE;
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

    private static String firstNumberToken(String candidateText) {
        if (candidateText == null || candidateText.isBlank()) {
            return "";
        }
        StringBuilder digitSequence = new StringBuilder();
        for (int i = 0; i < candidateText.length(); i++) {
            char ch = candidateText.charAt(i);
            if (ch >= '0' && ch <= '9') {
                digitSequence.append(ch);
            } else if (!digitSequence.isEmpty()) {
                break;
            }
        }
        return digitSequence.toString();
    }

    private static String sanitize(String provenanceToken) {
        if (provenanceToken == null) {
            return "";
        }
        String trimmed = provenanceToken.trim();
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
     * @param docType normalized doc type token (for example,
     *     {@link DocsSourceRegistry#JAVA_API_DOCUMENT_TYPE}, {@code release-notes}, {@code blog}, or {@code pdf})
     */
    public record IngestionProvenance(
            String docSet, String docPath, String sourceName, String sourceKind, String docVersion, String docType) {
        private static final String ENCODED_FINGERPRINT_FIELD_SEPARATOR = ".";

        public IngestionProvenance {
            docSet = sanitize(docSet);
            docPath = sanitize(docPath);
            sourceName = sanitize(sourceName);
            sourceKind = sanitize(sourceKind);
            docVersion = sanitize(docVersion);
            docType = sanitize(docType);
        }

        /**
         * Projects the canonical provenance inventory into deterministic fingerprint material.
         *
         * @param fileContentFingerprint fingerprint of the source bytes
         * @return content and provenance fields in canonical record order
         */
        public String fingerprintInput(String fileContentFingerprint) {
            Objects.requireNonNull(fileContentFingerprint, "fileContentFingerprint");
            return String.join(
                    ENCODED_FINGERPRINT_FIELD_SEPARATOR,
                    encodeFingerprintField(fileContentFingerprint),
                    encodeFingerprintField(docSet),
                    encodeFingerprintField(docPath),
                    encodeFingerprintField(sourceName),
                    encodeFingerprintField(sourceKind),
                    encodeFingerprintField(docVersion),
                    encodeFingerprintField(docType));
        }

        private static String encodeFingerprintField(String fingerprintField) {
            byte[] fingerprintFieldBytes = fingerprintField.getBytes(StandardCharsets.UTF_8);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(fingerprintFieldBytes);
        }
    }
}
