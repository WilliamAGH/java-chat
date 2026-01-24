package com.williamcallahan.javachat.config;

import java.util.Map;
import java.util.Optional;

/**
 * Normalizes local documentation paths into remote doc URLs, handling Spring layout quirks
 * so source links stay stable across different doc structures.
 */
final class DocsLocalPathMapper {
    private static final char WINDOWS_PATH_SEPARATOR = '\\';
    private static final char UNIX_PATH_SEPARATOR = '/';

    private static final String EMPTY_TEXT = "";
    private static final String PATH_SEPARATOR_TEXT = "/";

    private static final String SPRING_FRAMEWORK_MARKER = "spring-framework";
    private static final String SPRING_BOOT_MARKER = "spring-boot";

    private static final String SPRING_FRAMEWORK_DUPLICATE_JAVADOC_PREFIX =
        "docs/current/api/current/javadoc-api/";
    private static final String SPRING_FRAMEWORK_DUPLICATE_JAVADOC_BASE =
        "docs/current/api/current/";
    private static final String SPRING_FRAMEWORK_API_CURRENT_PREFIX = "api/current/javadoc-api/";
    private static final String SPRING_FRAMEWORK_DOCS_JAVADOC_PREFIX = "docs/current/javadoc-api/";
    private static final String SPRING_FRAMEWORK_JAVADOC_JAVA_PREFIX = "javadoc-api/java/";
    private static final String SPRING_FRAMEWORK_JAVADOC_PREFIX = "javadoc-api/";

    private static final String SPRING_BOOT_DOCS_API_PREFIX = "docs/current/api/";
    private static final String SPRING_BOOT_API_JAVA_PREFIX = "api/java/";
    private static final String SPRING_BOOT_API_PREFIX = "api/";

    private static final String DOCS_API_SUFFIX = "/docs/api";
    private static final String DOCS_API_PREFIX = "docs/api/";
    private static final String API_SUFFIX = "/api";
    private static final String API_PREFIX = "api/";

    private DocsLocalPathMapper() {
    }

    static Optional<String> mapLocalPrefixToRemote(
        final String localPath,
        final Map<String, String> localPrefixLookup
    ) {
        Optional<String> mappedUrl = Optional.empty();
        if (localPath != null) {
            final String normalizedPath = localPath.replace(WINDOWS_PATH_SEPARATOR, UNIX_PATH_SEPARATOR);
            for (final Map.Entry<String, String> prefixEntry : localPrefixLookup.entrySet()) {
                final String localPrefix = prefixEntry.getKey();
                if (normalizedPath.contains(localPrefix)) {
                    final String relativePath = normalizedPath.substring(
                        normalizedPath.indexOf(localPrefix) + localPrefix.length()
                    );
                    final String adjustedPath = normalizeRelativePath(localPrefix, relativePath);
                    mappedUrl = joinBaseAndRel(prefixEntry.getValue(), adjustedPath);
                    break;
                }
            }
        }
        return mappedUrl;
    }

    private static String normalizeRelativePath(final String localPrefix, final String relativePath) {
        String adjustedPath = relativePath == null ? EMPTY_TEXT : relativePath;
        if (localPrefix.contains(SPRING_FRAMEWORK_MARKER)) {
            adjustedPath = normalizeSpringFrameworkRelativePath(adjustedPath);
        }
        if (localPrefix.contains(SPRING_BOOT_MARKER)) {
            adjustedPath = normalizeSpringBootRelativePath(adjustedPath);
        }
        return adjustedPath;
    }

    private static String normalizeSpringFrameworkRelativePath(final String relativePath) {
        String adjustedPath = relativePath;
        if (adjustedPath.startsWith(SPRING_FRAMEWORK_DUPLICATE_JAVADOC_PREFIX)) {
            adjustedPath = adjustedPath.substring(SPRING_FRAMEWORK_DUPLICATE_JAVADOC_BASE.length());
        } else if (adjustedPath.startsWith(SPRING_FRAMEWORK_API_CURRENT_PREFIX)) {
            adjustedPath = adjustedPath.substring(SPRING_FRAMEWORK_API_CURRENT_PREFIX.length());
        } else if (adjustedPath.startsWith(SPRING_FRAMEWORK_DOCS_JAVADOC_PREFIX)) {
            adjustedPath = adjustedPath.substring(SPRING_FRAMEWORK_DOCS_JAVADOC_PREFIX.length());
        }
        if (adjustedPath.startsWith(SPRING_FRAMEWORK_JAVADOC_JAVA_PREFIX)) {
            adjustedPath = SPRING_FRAMEWORK_JAVADOC_PREFIX
                + adjustedPath.substring(SPRING_FRAMEWORK_JAVADOC_JAVA_PREFIX.length());
        }
        return adjustedPath;
    }

    private static String normalizeSpringBootRelativePath(final String relativePath) {
        String adjustedPath = relativePath;
        if (adjustedPath.startsWith(SPRING_BOOT_DOCS_API_PREFIX)) {
            adjustedPath = adjustedPath.substring(SPRING_BOOT_DOCS_API_PREFIX.length());
        }
        if (adjustedPath.startsWith(SPRING_BOOT_API_JAVA_PREFIX)) {
            adjustedPath = SPRING_BOOT_API_PREFIX
                + adjustedPath.substring(SPRING_BOOT_API_JAVA_PREFIX.length());
        }
        return adjustedPath;
    }

    private static Optional<String> joinBaseAndRel(final String baseUrl, final String relativePath) {
        Optional<String> joinedUrl = Optional.empty();
        if (baseUrl != null) {
            final String normalizedBase = trimTrailingSlashes(baseUrl);
            String normalizedRel = relativePath == null
                ? EMPTY_TEXT
                : relativePath.replace(WINDOWS_PATH_SEPARATOR, UNIX_PATH_SEPARATOR);
            normalizedRel = trimLeadingSlashes(normalizedRel);

            // Avoid duplicate 'docs/api' or 'api' in path
            if (normalizedBase.endsWith(DOCS_API_SUFFIX)) {
                if (normalizedRel.startsWith(DOCS_API_PREFIX)) {
                    normalizedRel = normalizedRel.substring(DOCS_API_PREFIX.length());
                } else if (normalizedRel.startsWith(API_PREFIX)) {
                    normalizedRel = normalizedRel.substring(API_PREFIX.length());
                }
            } else if (normalizedBase.endsWith(API_SUFFIX)) {
                if (normalizedRel.startsWith(API_PREFIX)) {
                    normalizedRel = normalizedRel.substring(API_PREFIX.length());
                }
            }

            joinedUrl = Optional.of(normalizedBase + PATH_SEPARATOR_TEXT + normalizedRel);
        }
        return joinedUrl;
    }

    private static String trimLeadingSlashes(final String pathText) {
        String trimmedPath = pathText;
        while (trimmedPath.startsWith(PATH_SEPARATOR_TEXT)) {
            trimmedPath = trimmedPath.substring(1);
        }
        return trimmedPath;
    }

    private static String trimTrailingSlashes(final String baseUrl) {
        int endIndex = baseUrl.length();
        while (endIndex > 0 && baseUrl.charAt(endIndex - 1) == UNIX_PATH_SEPARATOR) {
            endIndex--;
        }
        return baseUrl.substring(0, endIndex);
    }
}
