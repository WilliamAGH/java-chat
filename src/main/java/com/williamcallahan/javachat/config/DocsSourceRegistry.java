package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central registry for documentation source URL mappings.
 *
 * Provides a single place to define how locally mirrored paths map back to
 * authoritative remote URLs for citations and ingestion.
 *
 * Complete Java API sources are governed by {@code java-api-documentation-sources.manifest},
 * official non-Java sources are governed by {@code documentation-sources.manifest}, and legacy
 * citation-only bases remain in {@code docs-sources.properties}.
 */
public final class DocsSourceRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocsSourceRegistry.class);

    private static final String DOCS_SOURCES_RESOURCE = "/docs-sources.properties";
    private static final String LOG_DOCS_SOURCES_LOADED = "Loaded docs-sources.properties with {} entries";
    private static final String LOG_DOCS_SOURCES_MISSING = "docs-sources.properties not found on classpath";
    private static final String LOG_DOCS_SOURCES_LOAD_FAILED =
            "Failed to load docs-sources.properties (exceptionType={})";

    private static final String ORACLE_JAVASE_BASE_KEY = "ORACLE_JAVASE_BASE";
    private static final String IBM_ARTICLES_BASE_KEY = "IBM_ARTICLES_BASE";
    private static final String JETBRAINS_IDEA_2025_09_BASE_KEY = "JETBRAINS_IDEA_2025_09_BASE";
    private static final String SPRING_FRAMEWORK_BASE_KEY = "SPRING_FRAMEWORK_BASE";
    private static final String SPRING_AI_BASE_KEY = "SPRING_AI_BASE";

    private static final String SPRING_FRAMEWORK_API_BASE_KEY = "SPRING_FRAMEWORK_API_BASE";
    private static final String SPRING_AI_API_STABLE_BASE_KEY = "SPRING_AI_API_STABLE_BASE";
    private static final String SPRING_AI_API_2_BASE_KEY = "SPRING_AI_API_2_BASE";

    private static final String REDACTED_LOCAL_URL = "(local file path redacted)";
    private static final String OFFICIAL_DOCUMENTATION_SOURCE_KIND = "official";

    private static final String DEFAULT_ORACLE_JAVASE_BASE = "https://www.oracle.com/java/technologies/javase/";
    private static final String DEFAULT_IBM_ARTICLES_BASE = "https://developer.ibm.com/articles/";
    private static final String DEFAULT_JETBRAINS_IDEA_2025_09_BASE = "https://blog.jetbrains.com/idea/2025/09/";

    private static final String DEFAULT_SPRING_FRAMEWORK_BASE = "https://docs.spring.io/spring-framework/";
    private static final String DEFAULT_SPRING_AI_BASE = "https://docs.spring.io/spring-ai/";

    private static final String DEFAULT_SPRING_FRAMEWORK_API_BASE =
            "https://docs.spring.io/spring-framework/docs/current/javadoc-api/";
    private static final String DEFAULT_SPRING_AI_API_STABLE_BASE =
            "https://docs.spring.io/spring-ai/docs/current/api/";
    private static final String DEFAULT_SPRING_AI_API_2_BASE = "https://docs.spring.io/spring-ai/docs/2.0.x/api/";

    private static final String LOCAL_DOCS_ROOT = "/data/docs/";
    private static final String RELATIVE_MIRROR_PATH_SEPARATOR = "/";
    private static final String LOCAL_DOCS_SPRING_FRAMEWORK = LOCAL_DOCS_ROOT + "spring-framework/";
    private static final String LOCAL_DOCS_SPRING_FRAMEWORK_COMPLETE = LOCAL_DOCS_ROOT + "spring-framework-complete/";
    private static final String LOCAL_DOCS_SPRING_AI = LOCAL_DOCS_ROOT + "spring-ai/";
    private static final String LOCAL_DOCS_SPRING_AI_COMPLETE = LOCAL_DOCS_ROOT + "spring-ai-complete/";
    private static final String LOCAL_DOCS_SPRING_AI_REFERENCE = LOCAL_DOCS_ROOT + "spring-ai-reference/";
    private static final String LOCAL_DOCS_SPRING_AI_REFERENCE_2 = LOCAL_DOCS_ROOT + "spring-ai-reference-2/";
    private static final String LOCAL_DOCS_SPRING_AI_API_STABLE = LOCAL_DOCS_ROOT + "spring-ai-api-stable/";
    private static final String LOCAL_DOCS_SPRING_AI_API_2 = LOCAL_DOCS_ROOT + "spring-ai-api-2/";
    private static final String LOCAL_DOCS_BOOKS = LOCAL_DOCS_ROOT + "books/";
    private static final String LOCAL_DOCS_ORACLE_JAVASE = LOCAL_DOCS_ROOT + "oracle/javase/";
    private static final String LOCAL_DOCS_IBM_ARTICLES = LOCAL_DOCS_ROOT + "ibm/articles/";
    private static final String LOCAL_DOCS_JETBRAINS_IDEA_2025_09 = LOCAL_DOCS_ROOT + "jetbrains/idea/2025/09/";

    private static final String PUBLIC_PDFS_BASE = "/pdfs/";
    private static final String PDF_EXTENSION = ".pdf";

    private static final String HTTPS_PREFIX = "https://";
    private static final String DOCS_ORACLE_HOST_MARKER = "docs.oracle.com/";
    private static final String SPRING_DOCS_HOST_MARKER = "docs.spring.io/";
    private static final String SPRING_DOCS_HTTPS_PREFIX = HTTPS_PREFIX + SPRING_DOCS_HOST_MARKER;

    private static final Properties PROPS = loadDocsSourceProperties();
    private static final List<JavaApiDocumentationSource> JAVA_API_DOCUMENTATION_SOURCES =
            JavaApiDocumentationManifest.load();
    private static final List<DocumentationSource> DOCUMENTATION_SOURCES = DocumentationSourceManifest.load();
    private static final List<String> OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES =
            projectOfficialDocumentationSourceIdentities(DOCUMENTATION_SOURCES, JAVA_API_DOCUMENTATION_SOURCES);

    public static final String ORACLE_JAVASE_BASE = resolveSetting(ORACLE_JAVASE_BASE_KEY, DEFAULT_ORACLE_JAVASE_BASE);
    public static final String IBM_ARTICLES_BASE = resolveSetting(IBM_ARTICLES_BASE_KEY, DEFAULT_IBM_ARTICLES_BASE);
    public static final String JETBRAINS_IDEA_2025_09_BASE =
            resolveSetting(JETBRAINS_IDEA_2025_09_BASE_KEY, DEFAULT_JETBRAINS_IDEA_2025_09_BASE);

    public static final String SPRING_FRAMEWORK_BASE =
            resolveSetting(SPRING_FRAMEWORK_BASE_KEY, DEFAULT_SPRING_FRAMEWORK_BASE);
    public static final String SPRING_AI_BASE = resolveSetting(SPRING_AI_BASE_KEY, DEFAULT_SPRING_AI_BASE);

    public static final String SPRING_FRAMEWORK_API_BASE =
            resolveSetting(SPRING_FRAMEWORK_API_BASE_KEY, DEFAULT_SPRING_FRAMEWORK_API_BASE);
    public static final String SPRING_AI_API_STABLE_BASE =
            resolveSetting(SPRING_AI_API_STABLE_BASE_KEY, DEFAULT_SPRING_AI_API_STABLE_BASE);
    public static final String SPRING_AI_API_2_BASE =
            resolveSetting(SPRING_AI_API_2_BASE_KEY, DEFAULT_SPRING_AI_API_2_BASE);

    private static final String[] EMBEDDED_HOST_MARKERS = {DOCS_ORACLE_HOST_MARKER, SPRING_DOCS_HOST_MARKER};

    private static final Map<String, String> LOCAL_PREFIX_TO_REMOTE_BASE = buildLocalPrefixLookup();

    static {
        validateLifecycleMirrorRoots(DOCUMENTATION_SOURCES);
    }

    private DocsSourceRegistry() {}

    /**
     * Describes one complete Java API mirror projected from the canonical manifest.
     *
     * @param javaRelease Java release number used by retrieval provenance
     * @param remoteBaseUrl authoritative Javadoc base URL
     * @param relativeMirrorPath canonical path beneath {@code data/docs}
     * @param displayName operator-facing ingestion name
     * @param cutDirectories number of leading remote path segments removed during mirroring
     * @param minimumHtmlFiles minimum accepted mirror size
     * @param rejectRegex crawler exclusion expression, or blank when no exclusion applies
     * @param allowPartial whether the fetcher accepts a validated partial mirror
     */
    public record JavaApiDocumentationSource(
            String javaRelease,
            String remoteBaseUrl,
            String relativeMirrorPath,
            String displayName,
            int cutDirectories,
            int minimumHtmlFiles,
            String rejectRegex,
            boolean allowPartial) {
        public JavaApiDocumentationSource {
            int parsedJavaRelease =
                    DocumentationManifestFieldRules.requireCanonicalUnsignedInteger(javaRelease, "javaRelease");
            if (parsedJavaRelease < 1) {
                throw new IllegalArgumentException("Java release must be positive");
            }
            DocumentationManifestFieldRules.requireHttpsRemoteBaseUrl(remoteBaseUrl, "remoteBaseUrl");
            DocumentationManifestFieldRules.requireNormalizedRelativeMirrorPath(relativeMirrorPath);
            DocumentationManifestFieldRules.requireManifestText(displayName, "displayName", false);
            if (cutDirectories < 0) {
                throw new IllegalArgumentException("Java API cut directories cannot be negative");
            }
            if (minimumHtmlFiles < 1) {
                throw new IllegalArgumentException("Java API minimum HTML files must be positive");
            }
            DocumentationManifestFieldRules.requireManifestText(rejectRegex, "rejectRegex", true);
        }

        /**
         * Serializes this validated source with the canonical manifest grammar.
         *
         * @return exact manifest record row
         */
        public String toManifestRow() {
            return JavaApiDocumentationManifest.serialize(this);
        }
    }

    /**
     * Returns complete Java API sources projected from the canonical manifest.
     *
     * @return immutable sources in manifest order
     */
    public static List<JavaApiDocumentationSource> javaApiDocumentationSources() {
        return List.copyOf(JAVA_API_DOCUMENTATION_SOURCES);
    }

    /**
     * Describes one official non-Java documentation mirror projected from the canonical manifest.
     *
     * @param fetchUrl upstream URL mirrored by the fetch script
     * @param citationBaseUrl authoritative URL prefix used for local citation reconstruction
     * @param relativeMirrorPath canonical path beneath {@code data/docs}
     * @param displayName operator-facing ingestion name
     * @param docSet canonical Qdrant documentation-set token
     * @param sourceKind provenance category for the upstream publisher
     * @param docType content classification for retrieval metadata
     * @param docVersion upstream release token, or blank for an unversioned source
     * @param minimumHtmlFiles minimum accepted mirror size
     * @param rejectRegex crawler exclusion expression, or blank when no exclusion applies
     * @param allowPartial whether the fetcher accepts a validated partial mirror
     * @param seedDocumentType structured discovery document type, or blank for recursive mirroring
     * @param seedDiscoveryUrl structured discovery document URL, or blank for recursive mirroring
     * @param seedSourcePrefix exact discovered URL prefix mapped onto {@code fetchUrl}
     * @param supersededRelativeMirrorPath prior mirror root quarantined only after its canonical replacement succeeds;
     *     manifest validation keeps lifecycle roots unique and disjoint from every other active source root
     */
    public record DocumentationSource(
            String fetchUrl,
            String citationBaseUrl,
            String relativeMirrorPath,
            String displayName,
            String docSet,
            String sourceKind,
            String docType,
            String docVersion,
            int minimumHtmlFiles,
            String rejectRegex,
            boolean allowPartial,
            String seedDocumentType,
            String seedDiscoveryUrl,
            String seedSourcePrefix,
            String supersededRelativeMirrorPath) {
        public DocumentationSource {
            DocumentationManifestFieldRules.requireHttpsRemoteBaseUrl(fetchUrl, "fetchUrl");
            DocumentationManifestFieldRules.requireHttpsRemoteBaseUrl(citationBaseUrl, "citationBaseUrl");
            DocumentationManifestFieldRules.requireNormalizedRelativeMirrorPath(relativeMirrorPath);
            DocumentationManifestFieldRules.requireManifestText(displayName, "displayName", false);
            DocumentationManifestFieldRules.requireDocSet(docSet);
            DocumentationManifestFieldRules.requireManifestText(sourceKind, "sourceKind", false);
            DocumentationManifestFieldRules.requireManifestText(docType, "docType", false);
            DocumentationManifestFieldRules.requireManifestText(docVersion, "docVersion", true);
            if (minimumHtmlFiles < 1) {
                throw new IllegalArgumentException("Documentation minimum HTML files must be positive");
            }
            DocumentationManifestFieldRules.requireManifestText(rejectRegex, "rejectRegex", true);
            DocumentationManifestFieldRules.requireManifestText(seedDocumentType, "seedDocumentType", true);
            DocumentationManifestFieldRules.requireManifestText(seedDiscoveryUrl, "seedDiscoveryUrl", true);
            DocumentationManifestFieldRules.requireManifestText(seedSourcePrefix, "seedSourcePrefix", true);
            DocumentationManifestFieldRules.requireOptionalNormalizedRelativeMirrorPath(
                    supersededRelativeMirrorPath, "supersededRelativeMirrorPath");
            if (!supersededRelativeMirrorPath.isEmpty()
                    && mirrorRootContains(supersededRelativeMirrorPath, relativeMirrorPath)) {
                throw new IllegalArgumentException(
                        "Superseded mirror path must not equal or contain the canonical mirror path");
            }
            boolean hasSeedDiscovery = !seedDiscoveryUrl.isEmpty();
            if (hasSeedDiscovery != !seedDocumentType.isEmpty() || hasSeedDiscovery != !seedSourcePrefix.isEmpty()) {
                throw new IllegalArgumentException("Documentation seed discovery fields must be all blank or all set");
            }
            if (hasSeedDiscovery) {
                DocumentationManifestFieldRules.requireCanonicalSeedDocumentType(seedDocumentType);
                DocumentationSeedDocumentTypeCatalog.requireSupported(seedDocumentType);
                DocumentationManifestFieldRules.requireHttpsRemoteUrl(seedDiscoveryUrl, "seedDiscoveryUrl");
                DocumentationManifestFieldRules.requireHttpRemoteBaseUrl(seedSourcePrefix, "seedSourcePrefix");
            }
        }

        static boolean mirrorRootContains(String containingMirrorRoot, String candidateMirrorRoot) {
            return candidateMirrorRoot.equals(containingMirrorRoot)
                    || candidateMirrorRoot.startsWith(containingMirrorRoot + RELATIVE_MIRROR_PATH_SEPARATOR);
        }

        static boolean mirrorRootsOverlap(String firstMirrorRoot, String secondMirrorRoot) {
            return mirrorRootContains(firstMirrorRoot, secondMirrorRoot)
                    || mirrorRootContains(secondMirrorRoot, firstMirrorRoot);
        }

        /**
         * Serializes this validated source with the canonical manifest grammar.
         *
         * @return exact manifest record row
         */
        public String toManifestRow() {
            return DocumentationSourceManifest.serialize(this);
        }
    }

    /**
     * Returns official non-Java documentation sources projected from the canonical manifest.
     *
     * @return immutable sources in manifest order
     */
    public static List<DocumentationSource> documentationSources() {
        return List.copyOf(DOCUMENTATION_SOURCES);
    }

    /**
     * Returns retrieval identities for every official documentation source in the canonical manifests.
     *
     * <p>Non-Java documentation is identified by its canonical {@code docSet}; Java API documentation
     * is identified by its canonical relative mirror path because that is the ingestion-owned
     * {@code docSet} projection.</p>
     *
     * @return immutable source identities in non-Java then Java API manifest order
     */
    public static List<String> officialDocumentationSourceIdentities() {
        return List.copyOf(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
    }

    static List<String> projectOfficialDocumentationSourceIdentities(
            List<DocumentationSource> documentationSources,
            List<JavaApiDocumentationSource> javaApiDocumentationSources) {
        Objects.requireNonNull(documentationSources, "documentationSources");
        Objects.requireNonNull(javaApiDocumentationSources, "javaApiDocumentationSources");
        return Stream.concat(
                        documentationSources.stream()
                                .filter(documentationSource ->
                                        OFFICIAL_DOCUMENTATION_SOURCE_KIND.equals(documentationSource.sourceKind()))
                                .map(DocumentationSource::docSet),
                        javaApiDocumentationSources.stream().map(JavaApiDocumentationSource::relativeMirrorPath))
                .toList();
    }

    /**
     * Finds the canonical source for a local mirror path.
     *
     * @param relativeMirrorPath path beneath {@code data/docs}
     * @return matching source when the path is manifest governed
     */
    public static Optional<DocumentationSource> documentationSourceForRelativeMirrorPath(String relativeMirrorPath) {
        if (relativeMirrorPath == null || relativeMirrorPath.isBlank()) {
            return Optional.empty();
        }
        return DOCUMENTATION_SOURCES.stream()
                .filter(documentationSource ->
                        documentationSource.relativeMirrorPath().equals(relativeMirrorPath))
                .findFirst();
    }

    /**
     * Finds the canonical source governing a file path beneath {@code data/docs}.
     *
     * <p>The longest matching mirror root owns the file, so a catalog can safely contain nested
     * source roots.</p>
     *
     * @param relativeDocumentPath file path beneath {@code data/docs}
     * @return matching source when the path is manifest governed
     */
    public static Optional<DocumentationSource> documentationSourceForRelativeDocumentPath(
            String relativeDocumentPath) {
        if (relativeDocumentPath == null || relativeDocumentPath.isBlank()) {
            return Optional.empty();
        }
        String normalizedDocumentPath = relativeDocumentPath.replace('\\', '/');
        return DOCUMENTATION_SOURCES.stream()
                .filter(documentationSource -> normalizedDocumentPath.equals(documentationSource.relativeMirrorPath())
                        || normalizedDocumentPath.startsWith(
                                documentationSource.relativeMirrorPath() + RELATIVE_MIRROR_PATH_SEPARATOR))
                .max(Comparator.comparingInt(documentationSource ->
                        documentationSource.relativeMirrorPath().length()));
    }

    static void validateLifecycleMirrorRoots(List<DocumentationSource> candidateDocumentationSources) {
        for (DocumentationSource documentationSource : candidateDocumentationSources) {
            String supersededRelativeMirrorPath = documentationSource.supersededRelativeMirrorPath();
            if (supersededRelativeMirrorPath.isEmpty()) {
                continue;
            }
            for (String activeLocalPrefix : LOCAL_PREFIX_TO_REMOTE_BASE.keySet()) {
                String activeRelativeMirrorPath = relativeMirrorPathFromLocalPrefix(activeLocalPrefix);
                if (activeRelativeMirrorPath.equals(documentationSource.relativeMirrorPath())) {
                    continue;
                }
                if (DocumentationSource.mirrorRootsOverlap(supersededRelativeMirrorPath, activeRelativeMirrorPath)) {
                    throw new IllegalStateException("Documentation source superseded mirror path "
                            + supersededRelativeMirrorPath
                            + " overlaps active registry mirror path "
                            + activeRelativeMirrorPath);
                }
            }
        }
    }

    private static String relativeMirrorPathFromLocalPrefix(String activeLocalPrefix) {
        if (!activeLocalPrefix.startsWith(LOCAL_DOCS_ROOT)
                || !activeLocalPrefix.endsWith(RELATIVE_MIRROR_PATH_SEPARATOR)) {
            throw new IllegalStateException("Documentation registry contains an invalid local mirror prefix");
        }
        return activeLocalPrefix.substring(LOCAL_DOCS_ROOT.length(), activeLocalPrefix.length() - 1);
    }

    private static Properties loadDocsSourceProperties() {
        final Properties docsSourceProperties = new Properties();
        try (InputStream inputStream = DocsSourceRegistry.class.getResourceAsStream(DOCS_SOURCES_RESOURCE)) {
            if (inputStream != null) {
                docsSourceProperties.load(inputStream);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug(LOG_DOCS_SOURCES_LOADED, docsSourceProperties.size());
                }
            } else if (LOGGER.isInfoEnabled()) {
                LOGGER.info(LOG_DOCS_SOURCES_MISSING);
            }
        } catch (IOException configLoadError) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                        LOG_DOCS_SOURCES_LOAD_FAILED, configLoadError.getClass().getName());
            }
        }
        return docsSourceProperties;
    }

    private static String resolveSetting(final String settingKey, final String fallbackText) {
        final String systemProperty = System.getProperty(settingKey);
        final String envSetting = systemProperty != null ? systemProperty : System.getenv(settingKey);
        final String propertySetting = envSetting != null ? envSetting : PROPS.getProperty(settingKey);
        return Objects.requireNonNullElse(propertySetting, fallbackText);
    }

    private static Map<String, String> buildLocalPrefixLookup() {
        final Map<String, String> prefixLookup = new LinkedHashMap<>();

        for (JavaApiDocumentationSource javaApiDocumentationSource : JAVA_API_DOCUMENTATION_SOURCES) {
            prefixLookup.put(
                    LOCAL_DOCS_ROOT + javaApiDocumentationSource.relativeMirrorPath() + "/",
                    javaApiDocumentationSource.remoteBaseUrl());
        }

        for (DocumentationSource documentationSource : DOCUMENTATION_SOURCES) {
            prefixLookup.put(
                    LOCAL_DOCS_ROOT + documentationSource.relativeMirrorPath() + "/",
                    documentationSource.citationBaseUrl());
        }

        // Spring Framework documentation
        prefixLookup.put(LOCAL_DOCS_SPRING_FRAMEWORK, SPRING_FRAMEWORK_BASE);
        prefixLookup.put(LOCAL_DOCS_SPRING_FRAMEWORK_COMPLETE, SPRING_FRAMEWORK_BASE);

        // Spring AI documentation
        prefixLookup.put(LOCAL_DOCS_SPRING_AI, SPRING_AI_BASE);
        prefixLookup.put(LOCAL_DOCS_SPRING_AI_COMPLETE, SPRING_AI_BASE);
        prefixLookup.put(LOCAL_DOCS_SPRING_AI_REFERENCE, SPRING_AI_BASE);
        prefixLookup.put(LOCAL_DOCS_SPRING_AI_REFERENCE_2, SPRING_AI_BASE);
        prefixLookup.put(LOCAL_DOCS_SPRING_AI_API_STABLE, SPRING_AI_BASE);
        prefixLookup.put(LOCAL_DOCS_SPRING_AI_API_2, SPRING_AI_BASE);

        // External Java 25 sources
        prefixLookup.put(LOCAL_DOCS_ORACLE_JAVASE, ORACLE_JAVASE_BASE);
        prefixLookup.put(LOCAL_DOCS_IBM_ARTICLES, IBM_ARTICLES_BASE);
        prefixLookup.put(LOCAL_DOCS_JETBRAINS_IDEA_2025_09, JETBRAINS_IDEA_2025_09_BASE);

        return prefixLookup;
    }

    private static Optional<String> reconstructFromEmbeddedHost(final String localPath) {
        Optional<String> reconstructedUrl = Optional.empty();
        if (localPath != null) {
            final String normalizedPath = localPath.replace('\\', '/');
            for (final String hostMarker : EMBEDDED_HOST_MARKERS) {
                final int markerIndex = normalizedPath.indexOf(hostMarker);
                if (markerIndex >= 0) {
                    final String candidateUrl = HTTPS_PREFIX + normalizedPath.substring(markerIndex);
                    // Fix Spring URLs using proper string parsing
                    final String normalizedUrl = candidateUrl.startsWith(SPRING_DOCS_HTTPS_PREFIX)
                            ? SpringDocsUrlNormalizer.normalize(candidateUrl)
                            : candidateUrl;
                    reconstructedUrl = Optional.of(normalizedUrl);
                    break;
                }
            }
        }
        return reconstructedUrl;
    }

    private static Optional<String> mapLocalPrefixToRemote(final String localPath) {
        Optional<String> mappedUrl = Optional.empty();
        if (localPath != null && !localPath.isBlank()) {
            mappedUrl = DocsLocalPathMapper.mapLocalPrefixToRemote(localPath, LOCAL_PREFIX_TO_REMOTE_BASE);
        }
        return mappedUrl;
    }

    /**
     * Map a local book PDF under data/docs/books to a server-hosted public PDF path (/pdfs/...).
     *
     * @param localPath local filesystem-like path
     * @return public PDF URL when the local path maps to a book PDF
     */
    public static Optional<String> mapBookLocalToPublic(final String localPath) {
        Optional<String> publicPdfUrl = Optional.empty();
        if (localPath != null) {
            final String normalizedPath = localPath.replace('\\', '/');
            if (AsciiTextNormalizer.toLowerAscii(normalizedPath).endsWith(PDF_EXTENSION)) {
                final int markerIndex = normalizedPath.indexOf(LOCAL_DOCS_BOOKS);
                if (markerIndex >= 0) {
                    final String fileName = normalizedPath.substring(markerIndex + LOCAL_DOCS_BOOKS.length());
                    // Only map the basename to avoid subfolder leakage
                    final int lastSlash = fileName.lastIndexOf('/');
                    final String baseName = lastSlash >= 0 ? fileName.substring(lastSlash + 1) : fileName;
                    publicPdfUrl = Optional.of(PUBLIC_PDFS_BASE + baseName);
                }
            }
        }
        return publicPdfUrl;
    }

    /**
     * Canonicalizes HTTP/HTTPS documentation URLs by fixing common path duplications
     * and collapsing double slashes.
     *
     * @param url HTTP/HTTPS URL to canonicalize
     * @return canonicalized URL with path duplications fixed
     */
    public static String canonicalizeHttpDocUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        String result = url;
        // Collapse duplicated segments for Oracle and EA docs
        result = result.replace("/docs/api/api/", "/docs/api/");
        result = result.replace("/api/api/", "/api/");
        // Fix malformed Spring docs paths that accidentally include '/java/' segment
        if (result.contains(SPRING_DOCS_HTTPS_PREFIX)) {
            // Legacy path normalization for older local mirrors
            result = result.replace(
                    "/spring-framework/docs/current/javadoc-api/java/", "/spring-framework/docs/current/javadoc-api/");
        }
        // Remove accidental double slashes (but keep protocol)
        int protoIdx = result.indexOf("://");
        String prefix = protoIdx >= 0 ? result.substring(0, protoIdx + 3) : "";
        String rest = protoIdx >= 0 ? result.substring(protoIdx + 3) : result;
        rest = rest.replaceAll("/+", "/");
        return prefix + rest;
    }

    /**
     * Resolves a local filesystem path to its authoritative remote URL.
     * Tries book PDF mapping, embedded host reconstruction, and prefix-based mapping in order.
     * Returns empty for null or blank paths (defensive null handling for chained Optional calls).
     *
     * @param absolutePath absolute local filesystem path (forward slashes normalized), may be null
     * @return resolved remote URL, or empty if no mapping found or path is null/blank
     */
    public static Optional<String> resolveLocalPath(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank()) {
            return Optional.empty();
        }
        return mapBookLocalToPublic(absolutePath)
                .or(() -> reconstructFromEmbeddedHost(absolutePath))
                .or(() -> mapLocalPrefixToRemote(absolutePath));
    }

    /**
     * Normalizes a documentation URL from local file paths or mirrors to authoritative remote URLs.
     * Handles file:// URLs, embedded host paths, and already-HTTP URLs.
     *
     * @param rawUrl raw URL that may be file://, local path, or HTTP(S)
     * @return normalized authoritative URL
     */
    public static String normalizeDocUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return rawUrl;
        }
        String trimmedUrl = rawUrl.trim();

        // Already HTTP(S): canonicalize and return
        if (trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")) {
            return canonicalizeHttpDocUrl(trimmedUrl);
        }

        // Map book PDFs to public server path
        String resolvedPath = trimmedUrl.startsWith("file://") ? trimmedUrl.substring("file://".length()) : trimmedUrl;
        Optional<String> publicPdf = mapBookLocalToPublic(resolvedPath);
        if (publicPdf.isPresent()) {
            return publicPdf.get();
        }

        // Only handle file:// beyond this point
        if (!trimmedUrl.startsWith("file://")) {
            return trimmedUrl;
        }

        String localPath = trimmedUrl.substring("file://".length());

        // Try embedded host reconstruction first
        Optional<String> embeddedUrl = reconstructFromEmbeddedHost(localPath);
        if (embeddedUrl.isPresent()) {
            return canonicalizeHttpDocUrl(embeddedUrl.get());
        }

        // Try local prefix mapping
        Optional<String> mappedUrl = mapLocalPrefixToRemote(localPath);
        return mappedUrl.map(DocsSourceRegistry::canonicalizeHttpDocUrl).orElse(REDACTED_LOCAL_URL);
    }
}
