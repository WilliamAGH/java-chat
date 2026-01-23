package com.williamcallahan.javachat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

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

    private static final String JAVA24_BASE_DEFAULT = "https://docs.oracle.com/en/java/javase/24/docs/api/";
    private static final String JAVA25_EA_BASE_DEFAULT = "https://download.java.net/java/early_access/jdk25/docs/api/";
    private static final String SPRING_BOOT_BASE_DEFAULT = "https://docs.spring.io/spring-boot/docs/current/api/";
    private static final String SPRING_FRAMEWORK_BASE_DEFAULT = "https://docs.spring.io/spring-framework/docs/current/javadoc-api/";
    private static final String SPRING_AI_BASE_DEFAULT = "https://docs.spring.io/spring-ai/reference/1.0/api/";

    private static final String SPRING_DOCS_HOST = "https://docs.spring.io/";
    private static final String HTTPS_PREFIX = "https://";
    private static final String DOCS_API_SUFFIX = "/docs/api";
    private static final String API_SUFFIX = "/api";
    private static final String DOCS_API_SEGMENT = "docs/api/";
    private static final String API_SEGMENT = "api/";
    private static final String PDF_SUFFIX = ".pdf";

    private static final char WINDOWS_SEPARATOR = '\\';
    private static final char UNIX_SEPARATOR = '/';
    private static final int INDEX_NOT_FOUND = -1;

    private static final String JAVA24_PREFIX = "/data/docs/java24/";
    private static final String JAVA24_ALT_PREFIX = "/data/docs/java/java24/";
    private static final String JAVA24_COMPLETE_PREFIX = "/data/docs/java/java24-complete/";
    private static final String JAVA25_PREFIX = "/data/docs/java25/";
    private static final String JAVA25_ALT_PREFIX = "/data/docs/java/java25/";
    private static final String JAVA25_EA_COMPLETE_PREFIX = "/data/docs/java/java25-ea-complete/";
    private static final String JAVA25_COMPLETE_PREFIX = "/data/docs/java/java25-complete/";
    private static final String SPRING_BOOT_PREFIX = "/data/docs/spring-boot/";
    private static final String SPRING_BOOT_COMPLETE_PREFIX = "/data/docs/spring-boot-complete/";
    private static final String SPRING_FRAMEWORK_PREFIX = "/data/docs/spring-framework/";
    private static final String SPRING_FRAMEWORK_COMPLETE_PREFIX = "/data/docs/spring-framework-complete/";
    private static final String SPRING_AI_PREFIX = "/data/docs/spring-ai/";
    private static final String SPRING_AI_COMPLETE_PREFIX = "/data/docs/spring-ai-complete/";
    private static final String SPRING_FRAMEWORK_MARKER = "spring-framework";
    private static final String SPRING_BOOT_MARKER = "spring-boot";

    public static final String JAVA24_API;
    public static final String JAVA25_EA_API;
    public static final String SPRING_BOOT_API;
    public static final String SPRING_FRAMEWORK_API;
    public static final String SPRING_AI_API;

    public static final String BOOKS_PREFIX = "/data/docs/books/";
    public static final String PUBLIC_PDF_BASE = "/pdfs/";

    private static final String[] EMBEDDED_HOSTS = {
        "docs.oracle.com/",
        "download.java.net/",
        "docs.spring.io/"
    };

    private static final Properties SOURCE_PROPERTIES = new Properties();
    private static final Map<String, String> LOCAL_PREFIX_MAP = new LinkedHashMap<>();

    static {
        loadProperties();
        JAVA24_API = resolveProperty("JAVA24_API_BASE", JAVA24_BASE_DEFAULT);
        JAVA25_EA_API = resolveProperty("JAVA25_EA_API_BASE", JAVA25_EA_BASE_DEFAULT);
        SPRING_BOOT_API = resolveProperty("SPRING_BOOT_API_BASE", SPRING_BOOT_BASE_DEFAULT);
        SPRING_FRAMEWORK_API = resolveProperty("SPRING_FRAMEWORK_API_BASE", SPRING_FRAMEWORK_BASE_DEFAULT);
        SPRING_AI_API = resolveProperty("SPRING_AI_API_BASE", SPRING_AI_BASE_DEFAULT);
        registerPrefixMappings();
    }

    private DocsSourceRegistry() {
    }

    private static void loadProperties() {
        try (InputStream docsSourceStream = DocsSourceRegistry.class.getResourceAsStream(DOCS_SOURCES_RESOURCE)) {
            if (docsSourceStream != null) {
                SOURCE_PROPERTIES.load(docsSourceStream);
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Loaded docs-sources.properties with {} entries", SOURCE_PROPERTIES.size());
                }
            } else if (LOGGER.isInfoEnabled()) {
                LOGGER.info("docs-sources.properties not found on classpath; using default URL mappings");
            }
        } catch (IOException configLoadError) {
            if (LOGGER.isWarnEnabled()) {
                LOGGER.warn(
                    "Failed to load docs-sources.properties: {} - using default URL mappings",
                    configLoadError.getMessage()
                );
            }
        }
    }

    private static String resolveProperty(final String propertyKey, final String fallbackSetting) {
        String resolvedSetting = System.getProperty(propertyKey);
        if (resolvedSetting == null) {
            resolvedSetting = System.getenv(propertyKey);
        }
        if (resolvedSetting == null) {
            resolvedSetting = SOURCE_PROPERTIES.getProperty(propertyKey);
        }
        if (resolvedSetting == null) {
            resolvedSetting = fallbackSetting;
        }
        return resolvedSetting;
    }

    private static void registerPrefixMappings() {
        LOCAL_PREFIX_MAP.put(JAVA24_PREFIX, JAVA24_API);
        LOCAL_PREFIX_MAP.put(JAVA24_ALT_PREFIX, JAVA24_API);
        LOCAL_PREFIX_MAP.put(JAVA24_COMPLETE_PREFIX, JAVA24_API);

        LOCAL_PREFIX_MAP.put(JAVA25_PREFIX, JAVA25_EA_API);
        LOCAL_PREFIX_MAP.put(JAVA25_ALT_PREFIX, JAVA25_EA_API);
        LOCAL_PREFIX_MAP.put(JAVA25_EA_COMPLETE_PREFIX, JAVA25_EA_API);
        LOCAL_PREFIX_MAP.put(JAVA25_COMPLETE_PREFIX, JAVA25_EA_API);

        LOCAL_PREFIX_MAP.put(SPRING_BOOT_PREFIX, SPRING_BOOT_API);
        LOCAL_PREFIX_MAP.put(SPRING_BOOT_COMPLETE_PREFIX, SPRING_BOOT_API);

        LOCAL_PREFIX_MAP.put(SPRING_FRAMEWORK_PREFIX, SPRING_FRAMEWORK_API);
        LOCAL_PREFIX_MAP.put(SPRING_FRAMEWORK_COMPLETE_PREFIX, SPRING_FRAMEWORK_API);

        LOCAL_PREFIX_MAP.put(SPRING_AI_PREFIX, SPRING_AI_API);
        LOCAL_PREFIX_MAP.put(SPRING_AI_COMPLETE_PREFIX, SPRING_AI_API);
    }

    /**
     * If the given local filesystem-like path contains an embedded known host,
     * reconstruct an HTTPS URL to that embedded path.
     *
     * @param localPath local filesystem-like path
     * @return reconstructed URL or null if no embedded host is found
     */
    public static String reconstructFromEmbeddedHost(final String localPath) {
        String reconstructedUrl = null;
        String normalizedPath = normalizePathSeparators(localPath);
        for (String hostMarker : EMBEDDED_HOSTS) {
            int markerIndex = normalizedPath.indexOf(hostMarker);
            if (markerIndex > INDEX_NOT_FOUND) {
                reconstructedUrl = HTTPS_PREFIX + normalizedPath.substring(markerIndex);
                if (reconstructedUrl.startsWith(SPRING_DOCS_HOST)) {
                    reconstructedUrl = SpringDocsUtils.normalizeUrl(reconstructedUrl);
                }
                break;
            }
        }
        return reconstructedUrl;
    }

    /**
     * Map a local mirrored path to its authoritative remote base URL. Returns null if not matched.
     *
     * @param localPath local filesystem-like path
     * @return authoritative remote URL or null if not matched
     */
    public static String mapLocalPrefixToRemote(final String localPath) {
        String resolvedUrl = null;
        String normalizedPath = normalizePathSeparators(localPath);
        for (Map.Entry<String, String> mappingEntry : LOCAL_PREFIX_MAP.entrySet()) {
            String prefix = mappingEntry.getKey();
            int prefixIndex = normalizedPath.indexOf(prefix);
            if (prefixIndex > INDEX_NOT_FOUND) {
                String relativePath = normalizedPath.substring(prefixIndex + prefix.length());
                boolean springFrameworkPath = prefix.contains(SPRING_FRAMEWORK_MARKER);
                boolean springBootPath = prefix.contains(SPRING_BOOT_MARKER);
                if (springFrameworkPath || springBootPath) {
                    relativePath = SpringDocsUtils.cleanRelativePath(relativePath, springFrameworkPath, springBootPath);
                }
                resolvedUrl = joinBaseAndRel(mappingEntry.getValue(), relativePath);
                break;
            }
        }
        return resolvedUrl;
    }

    private static String joinBaseAndRel(final String baseUrl, final String relRaw) {
        String joinedUrl = null;
        if (baseUrl != null) {
            String trimmedBase = trimTrailingSlashes(baseUrl);
            String normalizedRelative = normalizeRelativePath(relRaw);
            normalizedRelative = removeDuplicateApiSegments(trimmedBase, normalizedRelative);
            joinedUrl = trimmedBase + UNIX_SEPARATOR + normalizedRelative;
        }
        return joinedUrl;
    }

    private static String removeDuplicateApiSegments(final String baseUrl, final String relativePath) {
        String adjustedRelative = relativePath;
        if (baseUrl.endsWith(DOCS_API_SUFFIX)) {
            if (adjustedRelative.startsWith(DOCS_API_SEGMENT)) {
                adjustedRelative = adjustedRelative.substring(DOCS_API_SEGMENT.length());
            } else if (adjustedRelative.startsWith(API_SEGMENT)) {
                adjustedRelative = adjustedRelative.substring(API_SEGMENT.length());
            }
        } else if (baseUrl.endsWith(API_SUFFIX) && adjustedRelative.startsWith(API_SEGMENT)) {
            adjustedRelative = adjustedRelative.substring(API_SEGMENT.length());
        }
        return adjustedRelative;
    }

    private static String trimTrailingSlashes(final String baseUrl) {
        int endIndex = baseUrl.length();
        while (endIndex > 0 && baseUrl.charAt(endIndex - 1) == UNIX_SEPARATOR) {
            endIndex -= 1;
        }
        return baseUrl.substring(0, endIndex);
    }

    private static String normalizeRelativePath(final String relRaw) {
        String relativePath = relRaw == null ? "" : relRaw;
        relativePath = normalizePathSeparators(relativePath);
        while (relativePath.startsWith(String.valueOf(UNIX_SEPARATOR))) {
            relativePath = relativePath.substring(1);
        }
        return relativePath;
    }

    private static String normalizePathSeparators(final String rawPath) {
        return rawPath.replace(WINDOWS_SEPARATOR, UNIX_SEPARATOR);
    }

    /**
     * Map a local book PDF under data/docs/books to a server-hosted public PDF path (/pdfs/...).
     * Returns null if the local path is not a recognized book PDF.
     *
     * @param localPath local filesystem-like path
     * @return public PDF URL or null if not matched
     */
    public static String mapBookLocalToPublic(final String localPath) {
        String publicPdfUrl = null;
        String normalizedPath = normalizePathSeparators(localPath);
        String lowerPath = normalizedPath.toLowerCase(Locale.ROOT);
        if (lowerPath.endsWith(PDF_SUFFIX)) {
            int prefixIndex = normalizedPath.indexOf(BOOKS_PREFIX);
            if (prefixIndex > INDEX_NOT_FOUND) {
                String fileSegment = normalizedPath.substring(prefixIndex + BOOKS_PREFIX.length());
                String fileName = stripDirectories(fileSegment);
                publicPdfUrl = PUBLIC_PDF_BASE + fileName;
            }
        }
        return publicPdfUrl;
    }

    private static String stripDirectories(final String fileSegment) {
        int lastSeparatorIndex = fileSegment.lastIndexOf(UNIX_SEPARATOR);
        String fileName = fileSegment;
        if (lastSeparatorIndex > INDEX_NOT_FOUND) {
            fileName = fileSegment.substring(lastSeparatorIndex + 1);
        }
        return fileName;
    }
}
