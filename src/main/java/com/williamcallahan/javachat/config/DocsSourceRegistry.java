package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central registry for documentation source URL mappings.
 *
 * Provides a single place to define how locally mirrored paths map back to
 * authoritative remote URLs for citations and ingestion.
 *
 * Source of truth for remote base URLs is src/main/resources/docs-sources.properties.
 * Scripts also source the same file to avoid DRY.
 */
public final class DocsSourceRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocsSourceRegistry.class);

    private static final String DOCS_SOURCES_RESOURCE = "/docs-sources.properties";
    private static final String LOG_DOCS_SOURCES_LOADED = "Loaded docs-sources.properties with {} entries";
    private static final String LOG_DOCS_SOURCES_MISSING =
            "docs-sources.properties not found on classpath; using default URL mappings";
    private static final String LOG_DOCS_SOURCES_LOAD_FAILED =
            "Failed to load docs-sources.properties (exceptionType={}) - using default URL mappings";

    private static final String JAVA24_API_BASE_KEY = "JAVA24_API_BASE";
    private static final String JAVA25_API_BASE_KEY = "JAVA25_API_BASE";
    private static final String ORACLE_JAVASE_BASE_KEY = "ORACLE_JAVASE_BASE";
    private static final String IBM_ARTICLES_BASE_KEY = "IBM_ARTICLES_BASE";
    private static final String JETBRAINS_IDEA_2025_09_BASE_KEY = "JETBRAINS_IDEA_2025_09_BASE";
    private static final String SPRING_BOOT_BASE_KEY = "SPRING_BOOT_BASE";
    private static final String SPRING_FRAMEWORK_BASE_KEY = "SPRING_FRAMEWORK_BASE";
    private static final String SPRING_AI_BASE_KEY = "SPRING_AI_BASE";

    private static final String SPRING_BOOT_API_BASE_KEY = "SPRING_BOOT_API_BASE";
    private static final String SPRING_FRAMEWORK_API_BASE_KEY = "SPRING_FRAMEWORK_API_BASE";
    private static final String SPRING_AI_API_BASE_KEY = "SPRING_AI_API_BASE";

    private static final String REDACTED_LOCAL_URL = "(local file path redacted)";

    private static final String DEFAULT_JAVA24 = "https://docs.oracle.com/en/java/javase/24/docs/api/";
    private static final String DEFAULT_JAVA25 = "https://docs.oracle.com/en/java/javase/25/docs/api/";
    private static final String DEFAULT_ORACLE_JAVASE_BASE = "https://www.oracle.com/java/technologies/javase/";
    private static final String DEFAULT_IBM_ARTICLES_BASE = "https://developer.ibm.com/articles/";
    private static final String DEFAULT_JETBRAINS_IDEA_2025_09_BASE = "https://blog.jetbrains.com/idea/2025/09/";

    private static final String DEFAULT_SPRING_BOOT_BASE = "https://docs.spring.io/spring-boot/";
    private static final String DEFAULT_SPRING_FRAMEWORK_BASE = "https://docs.spring.io/spring-framework/";
    private static final String DEFAULT_SPRING_AI_BASE = "https://docs.spring.io/spring-ai/";

    private static final String DEFAULT_SPRING_BOOT_API_BASE = "https://docs.spring.io/spring-boot/api/";
    private static final String DEFAULT_SPRING_FRAMEWORK_API_BASE =
            "https://docs.spring.io/spring-framework/docs/current/javadoc-api/";
    private static final String DEFAULT_SPRING_AI_API_BASE = "https://docs.spring.io/spring-ai/docs/2.0.x/api/";

    private static final String LOCAL_DOCS_ROOT = "/data/docs/";
    private static final String LOCAL_DOCS_JAVA24 = LOCAL_DOCS_ROOT + "java24/";
    private static final String LOCAL_DOCS_JAVA24_NESTED = LOCAL_DOCS_ROOT + "java/java24/";
    private static final String LOCAL_DOCS_JAVA24_COMPLETE = LOCAL_DOCS_ROOT + "java/java24-complete/";
    private static final String LOCAL_DOCS_JAVA25 = LOCAL_DOCS_ROOT + "java25/";
    private static final String LOCAL_DOCS_JAVA25_NESTED = LOCAL_DOCS_ROOT + "java/java25/";
    private static final String LOCAL_DOCS_JAVA25_COMPLETE = LOCAL_DOCS_ROOT + "java/java25-complete/";
    private static final String LOCAL_DOCS_SPRING_BOOT = LOCAL_DOCS_ROOT + "spring-boot/";
    private static final String LOCAL_DOCS_SPRING_BOOT_COMPLETE = LOCAL_DOCS_ROOT + "spring-boot-complete/";
    private static final String LOCAL_DOCS_SPRING_FRAMEWORK = LOCAL_DOCS_ROOT + "spring-framework/";
    private static final String LOCAL_DOCS_SPRING_FRAMEWORK_COMPLETE = LOCAL_DOCS_ROOT + "spring-framework-complete/";
    private static final String LOCAL_DOCS_SPRING_AI = LOCAL_DOCS_ROOT + "spring-ai/";
    private static final String LOCAL_DOCS_SPRING_AI_COMPLETE = LOCAL_DOCS_ROOT + "spring-ai-complete/";
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

    public static final String JAVA24_API_BASE = resolveSetting(JAVA24_API_BASE_KEY, DEFAULT_JAVA24);
    public static final String JAVA25_API_BASE = resolveSetting(JAVA25_API_BASE_KEY, DEFAULT_JAVA25);
    public static final String ORACLE_JAVASE_BASE = resolveSetting(ORACLE_JAVASE_BASE_KEY, DEFAULT_ORACLE_JAVASE_BASE);
    public static final String IBM_ARTICLES_BASE = resolveSetting(IBM_ARTICLES_BASE_KEY, DEFAULT_IBM_ARTICLES_BASE);
    public static final String JETBRAINS_IDEA_2025_09_BASE =
            resolveSetting(JETBRAINS_IDEA_2025_09_BASE_KEY, DEFAULT_JETBRAINS_IDEA_2025_09_BASE);

    public static final String SPRING_BOOT_BASE = resolveSetting(SPRING_BOOT_BASE_KEY, DEFAULT_SPRING_BOOT_BASE);
    public static final String SPRING_FRAMEWORK_BASE =
            resolveSetting(SPRING_FRAMEWORK_BASE_KEY, DEFAULT_SPRING_FRAMEWORK_BASE);
    public static final String SPRING_AI_BASE = resolveSetting(SPRING_AI_BASE_KEY, DEFAULT_SPRING_AI_BASE);

    public static final String SPRING_BOOT_API_BASE =
            resolveSetting(SPRING_BOOT_API_BASE_KEY, DEFAULT_SPRING_BOOT_API_BASE);
    public static final String SPRING_FRAMEWORK_API_BASE =
            resolveSetting(SPRING_FRAMEWORK_API_BASE_KEY, DEFAULT_SPRING_FRAMEWORK_API_BASE);
    public static final String SPRING_AI_API_BASE = resolveSetting(SPRING_AI_API_BASE_KEY, DEFAULT_SPRING_AI_API_BASE);

    private static final String[] EMBEDDED_HOST_MARKERS = {DOCS_ORACLE_HOST_MARKER, SPRING_DOCS_HOST_MARKER};

    private static final Map<String, String> LOCAL_PREFIX_TO_REMOTE_BASE = buildLocalPrefixLookup();

    private DocsSourceRegistry() {}

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

        // Java SE 24 API (Oracle)
        prefixLookup.put(LOCAL_DOCS_JAVA24, JAVA24_API_BASE);
        prefixLookup.put(LOCAL_DOCS_JAVA24_NESTED, JAVA24_API_BASE);
        prefixLookup.put(LOCAL_DOCS_JAVA24_COMPLETE, JAVA24_API_BASE);

        // Java SE 25 documentation (Oracle) - includes alternate local paths for backwards compatibility
        prefixLookup.put(LOCAL_DOCS_JAVA25, JAVA25_API_BASE);
        prefixLookup.put(LOCAL_DOCS_JAVA25_NESTED, JAVA25_API_BASE);
        prefixLookup.put(LOCAL_DOCS_JAVA25_COMPLETE, JAVA25_API_BASE);

        // Spring Boot documentation
        prefixLookup.put(LOCAL_DOCS_SPRING_BOOT, SPRING_BOOT_BASE);
        prefixLookup.put(LOCAL_DOCS_SPRING_BOOT_COMPLETE, SPRING_BOOT_BASE);

        // Spring Framework documentation
        prefixLookup.put(LOCAL_DOCS_SPRING_FRAMEWORK, SPRING_FRAMEWORK_BASE);
        prefixLookup.put(LOCAL_DOCS_SPRING_FRAMEWORK_COMPLETE, SPRING_FRAMEWORK_BASE);

        // Spring AI documentation
        prefixLookup.put(LOCAL_DOCS_SPRING_AI, SPRING_AI_BASE);
        prefixLookup.put(LOCAL_DOCS_SPRING_AI_COMPLETE, SPRING_AI_BASE);

        // External Java 25 sources
        prefixLookup.put(LOCAL_DOCS_ORACLE_JAVASE, ORACLE_JAVASE_BASE);
        prefixLookup.put(LOCAL_DOCS_IBM_ARTICLES, IBM_ARTICLES_BASE);
        prefixLookup.put(LOCAL_DOCS_JETBRAINS_IDEA_2025_09, JETBRAINS_IDEA_2025_09_BASE);

        return prefixLookup;
    }

    /**
     * If the given local filesystem-like path contains an embedded known host,
     * reconstruct an HTTPS URL to that embedded path.
     */
    public static Optional<String> reconstructFromEmbeddedHost(final String localPath) {
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

    /**
     * Map a local mirrored path to its authoritative remote base URL.
     *
     * @param localPath local filesystem-like path
     * @return authoritative remote URL when a mapping is found
     */
    public static Optional<String> mapLocalPrefixToRemote(final String localPath) {
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
            result = result.replace("/spring-boot/docs/current/api/java/", "/spring-boot/api/java/");
            result = result.replace("/spring-boot/docs/current/api/", "/spring-boot/api/");
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
