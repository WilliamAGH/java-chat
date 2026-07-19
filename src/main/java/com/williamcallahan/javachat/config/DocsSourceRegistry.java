package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Resolves documentation mirror paths into citation and provenance values used by the JVM runtime.
 *
 * <p>The executable fetch script owns crawling policy. This class contains only the smaller set of values the
 * application actually consumes while ingesting and citing already-mirrored documentation.</p>
 */
public final class DocsSourceRegistry {
    private static final String REDACTED_LOCAL_URL = "(local file path redacted)";
    private static final String OFFICIAL_DOCUMENTATION_SOURCE_KIND = "official";
    private static final String LOCAL_DOCS_ROOT = "/data/docs/";
    private static final String LOCAL_DOCS_BOOKS = LOCAL_DOCS_ROOT + "books/";
    private static final String PUBLIC_PDFS_BASE = "/pdfs/";
    private static final String PDF_EXTENSION = ".pdf";
    private static final String HTTPS_PREFIX = "https://";
    private static final String DOCS_ORACLE_HOST_MARKER = "docs.oracle.com/";
    private static final String SPRING_DOCS_HOST_MARKER = "docs.spring.io/";
    private static final String SPRING_DOCS_HTTPS_PREFIX = HTTPS_PREFIX + SPRING_DOCS_HOST_MARKER;
    private static final String EMPTY_TEXT = "";
    private static final String PATH_SEPARATOR_TEXT = "/";
    private static final String SPRING_FRAMEWORK_MARKER = "spring-framework";
    private static final String SPRING_FRAMEWORK_LEGACY_DUPLICATE_JAVADOC_PREFIX =
            "docs/current/api/current/javadoc-api/";
    private static final String SPRING_FRAMEWORK_LEGACY_API_CURRENT_PREFIX = "api/current/javadoc-api/";
    private static final String SPRING_FRAMEWORK_DOCS_JAVADOC_PREFIX = "docs/current/javadoc-api/";
    private static final String SPRING_FRAMEWORK_LEGACY_DOCS_JAVADOC_JAVA_PREFIX = "docs/current/javadoc-api/java/";
    private static final String SPRING_FRAMEWORK_LEGACY_JAVADOC_JAVA_PREFIX = "javadoc-api/java/";
    private static final String SPRING_FRAMEWORK_JAVADOC_PREFIX = "javadoc-api/";
    private static final String SPRING_BOOT_MARKER = "spring-boot";
    private static final String DOCS_SEGMENT = "docs";
    private static final String CURRENT_SEGMENT = "current";
    private static final String REFERENCE_SEGMENT = "reference";
    private static final String API_SEGMENT = "api";
    private static final String JAVADOC_API_SEGMENT = "javadoc-api";
    private static final String JAVA_SEGMENT = "java";
    private static final String VERSION_SEPARATOR = ".";
    private static final String DOCS_API_SUFFIX = "/docs/api";
    private static final String DOCS_API_PREFIX = "docs/api/";
    private static final String API_SUFFIX = "/api";
    private static final String API_PREFIX = "api/";

    private static final String SPRING_FRAMEWORK_REFERENCE_URL_PREFIX =
            SPRING_DOCS_HTTPS_PREFIX + SPRING_FRAMEWORK_MARKER + "/reference/current";
    private static final String SPRING_FRAMEWORK_JAVADOC_URL_PREFIX =
            SPRING_DOCS_HTTPS_PREFIX + SPRING_FRAMEWORK_MARKER + "/docs/current/javadoc-api";
    private static final String SPRING_BOOT_REFERENCE_URL_PREFIX =
            SPRING_DOCS_HTTPS_PREFIX + SPRING_BOOT_MARKER + "/reference/current";
    private static final String SPRING_BOOT_API_URL_PREFIX =
            SPRING_DOCS_HTTPS_PREFIX + SPRING_BOOT_MARKER + "/docs/current/api";

    private static final char WINDOWS_PATH_SEPARATOR = '\\';
    private static final char UNIX_PATH_SEPARATOR = '/';
    private static final char VERSION_PREFIX = 'v';

    private static final int MINIMUM_SPRING_PROJECT_SEGMENTS = 2;
    private static final int SPRING_PROJECT_SEGMENT_INDEX = 0;
    private static final int FIRST_SPRING_PATH_SEGMENT_INDEX = 1;
    private static final int SPRING_URL_BUFFER_PADDING = 64;

    private static final String[] DOCS_CURRENT_REFERENCE_SEQUENCE = {DOCS_SEGMENT, CURRENT_SEGMENT, REFERENCE_SEGMENT};
    private static final String[] REFERENCE_ROOT_SEQUENCE = {REFERENCE_SEGMENT};
    private static final String[] FRAMEWORK_API_CURRENT_SEQUENCE = {
        DOCS_SEGMENT, CURRENT_SEGMENT, API_SEGMENT, CURRENT_SEGMENT, JAVADOC_API_SEGMENT
    };
    private static final String[] FRAMEWORK_API_JAVA_SEQUENCE = {
        DOCS_SEGMENT, CURRENT_SEGMENT, JAVADOC_API_SEGMENT, JAVA_SEGMENT
    };
    private static final String[] BOOT_API_JAVA_SEQUENCE = {DOCS_SEGMENT, CURRENT_SEGMENT, API_SEGMENT, JAVA_SEGMENT};

    private static final String ORACLE_JAVASE_BASE_SETTING = "ORACLE_JAVASE_BASE";
    private static final String IBM_ARTICLES_BASE_SETTING = "IBM_ARTICLES_BASE";
    private static final String JETBRAINS_IDEA_2025_09_BASE_SETTING = "JETBRAINS_IDEA_2025_09_BASE";
    private static final String SPRING_FRAMEWORK_BASE_SETTING = "SPRING_FRAMEWORK_BASE";
    private static final String SPRING_AI_BASE_SETTING = "SPRING_AI_BASE";
    private static final String SPRING_FRAMEWORK_API_BASE_SETTING = "SPRING_FRAMEWORK_API_BASE";
    private static final String SPRING_AI_API_STABLE_BASE_SETTING = "SPRING_AI_API_STABLE_BASE";
    private static final String SPRING_AI_API_2_BASE_SETTING = "SPRING_AI_API_2_BASE";

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

    /** Provenance type stored for Java API documentation. */
    public static final String JAVA_API_DOCUMENT_TYPE = "api-docs";

    public static final String ORACLE_JAVASE_BASE =
            resolveRuntimeBaseUrl(ORACLE_JAVASE_BASE_SETTING, DEFAULT_ORACLE_JAVASE_BASE);
    public static final String IBM_ARTICLES_BASE =
            resolveRuntimeBaseUrl(IBM_ARTICLES_BASE_SETTING, DEFAULT_IBM_ARTICLES_BASE);
    public static final String JETBRAINS_IDEA_2025_09_BASE =
            resolveRuntimeBaseUrl(JETBRAINS_IDEA_2025_09_BASE_SETTING, DEFAULT_JETBRAINS_IDEA_2025_09_BASE);
    public static final String SPRING_FRAMEWORK_BASE =
            resolveRuntimeBaseUrl(SPRING_FRAMEWORK_BASE_SETTING, DEFAULT_SPRING_FRAMEWORK_BASE);
    public static final String SPRING_AI_BASE = resolveRuntimeBaseUrl(SPRING_AI_BASE_SETTING, DEFAULT_SPRING_AI_BASE);
    public static final String SPRING_FRAMEWORK_API_BASE =
            resolveRuntimeBaseUrl(SPRING_FRAMEWORK_API_BASE_SETTING, DEFAULT_SPRING_FRAMEWORK_API_BASE);
    public static final String SPRING_AI_API_STABLE_BASE =
            resolveRuntimeBaseUrl(SPRING_AI_API_STABLE_BASE_SETTING, DEFAULT_SPRING_AI_API_STABLE_BASE);
    public static final String SPRING_AI_API_2_BASE =
            resolveRuntimeBaseUrl(SPRING_AI_API_2_BASE_SETTING, DEFAULT_SPRING_AI_API_2_BASE);

    private static final List<JavaApiDocumentationSource> JAVA_API_DOCUMENTATION_SOURCES = List.of(
            new JavaApiDocumentationSource(
                    "21",
                    "https://docs.oracle.com/en/java/javase/21/docs/api/",
                    "java/java21-complete",
                    "Java 21 Complete API"),
            new JavaApiDocumentationSource(
                    "24",
                    "https://docs.oracle.com/en/java/javase/24/docs/api/",
                    "java/java24-complete",
                    "Java 24 Complete API"),
            new JavaApiDocumentationSource(
                    "25",
                    "https://docs.oracle.com/en/java/javase/25/docs/api/",
                    "java/java25-complete",
                    "Java 25 Complete API"));

    private static final List<DocumentationSource> DOCUMENTATION_SOURCES = List.of(
            new DocumentationSource(
                    "https://dev.java/learn/", "dev-java", "Dev.java Learning", "dev-java", "official", "tutorial", ""),
            new DocumentationSource(
                    "https://kotlinlang.org/docs/",
                    "kotlin",
                    "Kotlin Documentation",
                    "kotlin",
                    "official",
                    "language-reference",
                    ""),
            new DocumentationSource(
                    "https://docs.scala-lang.org/scala3/reference/",
                    "scala",
                    "Scala 3 Documentation",
                    "scala",
                    "official",
                    "language-reference",
                    ""),
            new DocumentationSource(
                    "https://docs.groovy-lang.org/docs/groovy-5.0.7/html/documentation/",
                    "groovy/5.0.7",
                    "Groovy 5.0.7 Documentation",
                    "groovy",
                    "official",
                    "language-reference",
                    "5.0.7"),
            new DocumentationSource(
                    "https://clojure.org/guides/",
                    "clojure",
                    "Clojure Guides",
                    "clojure",
                    "official",
                    "language-guide",
                    ""),
            new DocumentationSource(
                    "https://docs.spring.io/spring-boot/reference/",
                    "spring-boot",
                    "Spring Boot Reference",
                    "spring-boot",
                    "official",
                    "framework-reference",
                    ""),
            new DocumentationSource(
                    "https://quarkus.io/guides/",
                    "quarkus",
                    "Quarkus Guides",
                    "quarkus",
                    "official",
                    "framework-guide",
                    ""));

    private static final List<String> OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES = Stream.concat(
                    DOCUMENTATION_SOURCES.stream()
                            .filter(documentationSource ->
                                    OFFICIAL_DOCUMENTATION_SOURCE_KIND.equals(documentationSource.sourceKind()))
                            .map(DocumentationSource::docSet),
                    JAVA_API_DOCUMENTATION_SOURCES.stream().map(JavaApiDocumentationSource::relativeMirrorPath))
            .toList();

    private static final String[] EMBEDDED_HOST_MARKERS = {DOCS_ORACLE_HOST_MARKER, SPRING_DOCS_HOST_MARKER};
    private static final Map<String, String> LOCAL_PREFIX_TO_REMOTE_BASE = buildLocalPrefixLookup();

    private DocsSourceRegistry() {}

    /** Describes the Java API identity and citation fields consumed by JVM ingestion. */
    public record JavaApiDocumentationSource(
            String javaRelease, String remoteBaseUrl, String relativeMirrorPath, String displayName) {
        public JavaApiDocumentationSource {
            Objects.requireNonNull(javaRelease, "javaRelease");
            Objects.requireNonNull(remoteBaseUrl, "remoteBaseUrl");
            Objects.requireNonNull(relativeMirrorPath, "relativeMirrorPath");
            Objects.requireNonNull(displayName, "displayName");
        }
    }

    /** Returns the Java API sources the JVM can recognize in already-mirrored content. */
    public static List<JavaApiDocumentationSource> javaApiDocumentationSources() {
        return JAVA_API_DOCUMENTATION_SOURCES;
    }

    /** Describes provenance and citation fields consumed by JVM ingestion. */
    public record DocumentationSource(
            String citationBaseUrl,
            String relativeMirrorPath,
            String displayName,
            String docSet,
            String sourceKind,
            String docType,
            String docVersion) {
        public DocumentationSource {
            Objects.requireNonNull(citationBaseUrl, "citationBaseUrl");
            Objects.requireNonNull(relativeMirrorPath, "relativeMirrorPath");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(docSet, "docSet");
            Objects.requireNonNull(sourceKind, "sourceKind");
            Objects.requireNonNull(docType, "docType");
            Objects.requireNonNull(docVersion, "docVersion");
        }
    }

    /** Returns the non-Java documentation sources the JVM can recognize in already-mirrored content. */
    public static List<DocumentationSource> documentationSources() {
        return DOCUMENTATION_SOURCES;
    }

    /** Returns retrieval identities used by official-document filters. */
    public static List<String> officialDocumentationSourceIdentities() {
        return List.copyOf(OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES);
    }

    /** Finds an exact documentation mirror root. */
    public static Optional<DocumentationSource> documentationSourceForRelativeMirrorPath(String relativeMirrorPath) {
        if (relativeMirrorPath == null || relativeMirrorPath.isBlank()) {
            return Optional.empty();
        }
        return DOCUMENTATION_SOURCES.stream()
                .filter(documentationSource ->
                        documentationSource.relativeMirrorPath().equals(relativeMirrorPath))
                .findFirst();
    }

    /** Finds the longest documentation mirror root containing a relative document path. */
    public static Optional<DocumentationSource> documentationSourceForRelativeDocumentPath(
            String relativeDocumentPath) {
        if (relativeDocumentPath == null || relativeDocumentPath.isBlank()) {
            return Optional.empty();
        }
        String normalizedDocumentPath = relativeDocumentPath.replace('\\', '/');
        return DOCUMENTATION_SOURCES.stream()
                .filter(documentationSource -> normalizedDocumentPath.equals(documentationSource.relativeMirrorPath())
                        || normalizedDocumentPath.startsWith(documentationSource.relativeMirrorPath() + "/"))
                .max(Comparator.comparingInt(documentationSource ->
                        documentationSource.relativeMirrorPath().length()));
    }

    /** Finds the Java API mirror root containing a relative document path. */
    public static Optional<JavaApiDocumentationSource> javaApiDocumentationSourceForRelativeDocumentPath(
            String relativeDocumentPath) {
        if (relativeDocumentPath == null || relativeDocumentPath.isBlank()) {
            return Optional.empty();
        }
        String normalizedDocumentPath = relativeDocumentPath.replace('\\', '/');
        return JAVA_API_DOCUMENTATION_SOURCES.stream()
                .filter(javaApiDocumentationSource -> normalizedDocumentPath.equals(
                                javaApiDocumentationSource.relativeMirrorPath())
                        || normalizedDocumentPath.startsWith(javaApiDocumentationSource.relativeMirrorPath() + "/"))
                .max(Comparator.comparingInt(javaApiDocumentationSource ->
                        javaApiDocumentationSource.relativeMirrorPath().length()));
    }

    /** Resolves a citation base by preferring a JVM property, then process environment, then built-in default. */
    static String resolveRuntimeBaseUrl(String settingKey, String defaultBaseUrl) {
        String systemPropertyBaseUrl = System.getProperty(settingKey);
        if (systemPropertyBaseUrl != null) {
            return systemPropertyBaseUrl;
        }
        String environmentBaseUrl = System.getenv(settingKey);
        return environmentBaseUrl != null ? environmentBaseUrl : defaultBaseUrl;
    }

    private static Map<String, String> buildLocalPrefixLookup() {
        Map<String, String> prefixLookup = new LinkedHashMap<>();
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
        prefixLookup.put(LOCAL_DOCS_ROOT + "spring-framework/", SPRING_FRAMEWORK_BASE);
        prefixLookup.put(LOCAL_DOCS_ROOT + "spring-framework-complete/", SPRING_FRAMEWORK_BASE);
        prefixLookup.put(LOCAL_DOCS_ROOT + "spring-ai/", SPRING_AI_BASE);
        prefixLookup.put(LOCAL_DOCS_ROOT + "spring-ai-complete/", SPRING_AI_BASE);
        prefixLookup.put(LOCAL_DOCS_ROOT + "spring-ai-reference/", SPRING_AI_BASE);
        prefixLookup.put(LOCAL_DOCS_ROOT + "spring-ai-reference-2/", SPRING_AI_BASE);
        prefixLookup.put(LOCAL_DOCS_ROOT + "spring-ai-api-stable/", SPRING_AI_BASE);
        prefixLookup.put(LOCAL_DOCS_ROOT + "spring-ai-api-2/", SPRING_AI_BASE);
        prefixLookup.put(LOCAL_DOCS_ROOT + "oracle/javase/", ORACLE_JAVASE_BASE);
        prefixLookup.put(LOCAL_DOCS_ROOT + "ibm/articles/", IBM_ARTICLES_BASE);
        prefixLookup.put(LOCAL_DOCS_ROOT + "jetbrains/idea/2025/09/", JETBRAINS_IDEA_2025_09_BASE);
        return Map.copyOf(prefixLookup);
    }

    private static Optional<String> reconstructFromEmbeddedHost(String localPath) {
        if (localPath == null) {
            return Optional.empty();
        }
        String normalizedPath = localPath.replace('\\', '/');
        for (String hostMarker : EMBEDDED_HOST_MARKERS) {
            int markerIndex = normalizedPath.indexOf(hostMarker);
            if (markerIndex >= 0) {
                String candidateUrl = HTTPS_PREFIX + normalizedPath.substring(markerIndex);
                String normalizedUrl = candidateUrl.startsWith(SPRING_DOCS_HTTPS_PREFIX)
                        ? normalizeEmbeddedSpringDocsUrl(candidateUrl)
                        : candidateUrl;
                return Optional.of(normalizedUrl);
            }
        }
        return Optional.empty();
    }

    private static String normalizeEmbeddedSpringDocsUrl(String url) {
        String normalizedUrl = url;
        if (url != null && url.startsWith(SPRING_DOCS_HTTPS_PREFIX)) {
            String path = url.substring(SPRING_DOCS_HTTPS_PREFIX.length());
            String[] segments = path.split(PATH_SEPARATOR_TEXT);
            if (segments.length >= MINIMUM_SPRING_PROJECT_SEGMENTS) {
                String projectSegment = segments[SPRING_PROJECT_SEGMENT_INDEX];
                if (SPRING_FRAMEWORK_MARKER.equals(projectSegment)) {
                    normalizedUrl = normalizeSpringFrameworkEmbeddedUrl(segments, url);
                } else if (SPRING_BOOT_MARKER.equals(projectSegment)) {
                    normalizedUrl = normalizeSpringBootEmbeddedUrl(segments, url);
                }
            }
        }
        return normalizedUrl;
    }

    private static String normalizeSpringFrameworkEmbeddedUrl(String[] segments, String originalUrl) {
        String normalizedUrl = normalizeSpringFrameworkReference(segments);
        if (normalizedUrl == null) {
            normalizedUrl = normalizeSpringFrameworkReferenceRoot(segments);
        }
        if (normalizedUrl == null) {
            normalizedUrl = normalizeSpringFrameworkApiCurrent(segments);
        }
        if (normalizedUrl == null) {
            normalizedUrl = normalizeSpringFrameworkApiJava(segments);
        }
        return normalizedUrl == null ? originalUrl : normalizedUrl;
    }

    private static String normalizeSpringBootEmbeddedUrl(String[] segments, String originalUrl) {
        String normalizedUrl = normalizeSpringBootReference(segments);
        if (normalizedUrl == null) {
            normalizedUrl = normalizeSpringBootReferenceRoot(segments);
        }
        if (normalizedUrl == null) {
            normalizedUrl = normalizeSpringBootApiJava(segments);
        }
        return normalizedUrl == null ? originalUrl : normalizedUrl;
    }

    private static String normalizeSpringFrameworkReference(String[] segments) {
        if (!matchesSequence(segments, FIRST_SPRING_PATH_SEGMENT_INDEX, DOCS_CURRENT_REFERENCE_SEQUENCE)) {
            return null;
        }
        int payloadIndex = FIRST_SPRING_PATH_SEGMENT_INDEX + DOCS_CURRENT_REFERENCE_SEQUENCE.length;
        int contentIndex = skipSpringVersionSegment(segments, payloadIndex);
        return buildSpringDocsUrl(SPRING_FRAMEWORK_REFERENCE_URL_PREFIX, segments, contentIndex);
    }

    private static String normalizeSpringFrameworkReferenceRoot(String[] segments) {
        if (!matchesSequence(segments, FIRST_SPRING_PATH_SEGMENT_INDEX, REFERENCE_ROOT_SEQUENCE)) {
            return null;
        }
        int versionIndex = FIRST_SPRING_PATH_SEGMENT_INDEX + REFERENCE_ROOT_SEQUENCE.length;
        if (!hasSegmentAt(segments, versionIndex) || !isSpringVersion(segments[versionIndex])) {
            return null;
        }
        return buildSpringDocsUrl(SPRING_FRAMEWORK_REFERENCE_URL_PREFIX, segments, versionIndex + 1);
    }

    private static String normalizeSpringFrameworkApiCurrent(String[] segments) {
        if (!matchesSequence(segments, FIRST_SPRING_PATH_SEGMENT_INDEX, FRAMEWORK_API_CURRENT_SEQUENCE)) {
            return null;
        }
        int contentIndex = FIRST_SPRING_PATH_SEGMENT_INDEX + FRAMEWORK_API_CURRENT_SEQUENCE.length;
        return buildSpringDocsUrl(SPRING_FRAMEWORK_JAVADOC_URL_PREFIX, segments, contentIndex);
    }

    private static String normalizeSpringFrameworkApiJava(String[] segments) {
        if (!matchesSequence(segments, FIRST_SPRING_PATH_SEGMENT_INDEX, FRAMEWORK_API_JAVA_SEQUENCE)) {
            return null;
        }
        int contentIndex = FIRST_SPRING_PATH_SEGMENT_INDEX + FRAMEWORK_API_JAVA_SEQUENCE.length;
        return buildSpringDocsUrl(SPRING_FRAMEWORK_JAVADOC_URL_PREFIX, segments, contentIndex);
    }

    private static String normalizeSpringBootReference(String[] segments) {
        if (!matchesSequence(segments, FIRST_SPRING_PATH_SEGMENT_INDEX, DOCS_CURRENT_REFERENCE_SEQUENCE)) {
            return null;
        }
        int payloadIndex = FIRST_SPRING_PATH_SEGMENT_INDEX + DOCS_CURRENT_REFERENCE_SEQUENCE.length;
        int contentIndex = skipSpringVersionSegment(segments, payloadIndex);
        return buildSpringDocsUrl(SPRING_BOOT_REFERENCE_URL_PREFIX, segments, contentIndex);
    }

    private static String normalizeSpringBootReferenceRoot(String[] segments) {
        if (!matchesSequence(segments, FIRST_SPRING_PATH_SEGMENT_INDEX, REFERENCE_ROOT_SEQUENCE)) {
            return null;
        }
        int versionIndex = FIRST_SPRING_PATH_SEGMENT_INDEX + REFERENCE_ROOT_SEQUENCE.length;
        if (!hasSegmentAt(segments, versionIndex) || !isSpringVersion(segments[versionIndex])) {
            return null;
        }
        return buildSpringDocsUrl(SPRING_BOOT_REFERENCE_URL_PREFIX, segments, versionIndex + 1);
    }

    private static String normalizeSpringBootApiJava(String[] segments) {
        if (!matchesSequence(segments, FIRST_SPRING_PATH_SEGMENT_INDEX, BOOT_API_JAVA_SEQUENCE)) {
            return null;
        }
        int contentIndex = FIRST_SPRING_PATH_SEGMENT_INDEX + BOOT_API_JAVA_SEQUENCE.length;
        return buildSpringDocsUrl(SPRING_BOOT_API_URL_PREFIX, segments, contentIndex);
    }

    private static String buildSpringDocsUrl(String normalizedPrefix, String[] segments, int startIndex) {
        StringBuilder urlBuilder = new StringBuilder(normalizedPrefix.length() + SPRING_URL_BUFFER_PADDING);
        urlBuilder.append(normalizedPrefix);
        for (int segmentIndex = startIndex; segmentIndex < segments.length; segmentIndex++) {
            urlBuilder.append(UNIX_PATH_SEPARATOR).append(segments[segmentIndex]);
        }
        return urlBuilder.toString();
    }

    private static boolean matchesSequence(String[] segments, int startIndex, String[] expectedSegments) {
        int expectedLength = expectedSegments.length;
        if (segments.length < startIndex + expectedLength) {
            return false;
        }
        for (int offsetIndex = 0; offsetIndex < expectedLength; offsetIndex++) {
            String expectedSegment = expectedSegments[offsetIndex];
            if (!expectedSegment.equals(segments[startIndex + offsetIndex])) {
                return false;
            }
        }
        return true;
    }

    private static int skipSpringVersionSegment(String[] segments, int startIndex) {
        if (hasSegmentAt(segments, startIndex) && isSpringVersion(segments[startIndex])) {
            return startIndex + 1;
        }
        return startIndex;
    }

    private static boolean hasSegmentAt(String[] segments, int segmentIndex) {
        return segments.length > segmentIndex;
    }

    private static boolean isSpringVersion(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        char firstCharacter = text.charAt(0);
        return text.contains(VERSION_SEPARATOR)
                && (Character.isDigit(firstCharacter) || firstCharacter == VERSION_PREFIX);
    }

    private static Optional<String> mapLocalPrefixToRemote(String localPath) {
        Optional<String> mappedUrl = Optional.empty();
        if (localPath != null) {
            String normalizedPath = localPath.replace(WINDOWS_PATH_SEPARATOR, UNIX_PATH_SEPARATOR);
            for (Map.Entry<String, String> prefixEntry : LOCAL_PREFIX_TO_REMOTE_BASE.entrySet()) {
                String localPrefix = prefixEntry.getKey();
                if (normalizedPath.contains(localPrefix)) {
                    String relativePath =
                            normalizedPath.substring(normalizedPath.indexOf(localPrefix) + localPrefix.length());
                    String adjustedPath = normalizeRelativePath(localPrefix, relativePath);
                    mappedUrl = joinBaseAndRel(prefixEntry.getValue(), adjustedPath);
                    break;
                }
            }
        }
        return mappedUrl;
    }

    private static String normalizeRelativePath(String localPrefix, String relativePath) {
        String adjustedPath = relativePath == null ? EMPTY_TEXT : relativePath;
        if (localPrefix.contains(SPRING_FRAMEWORK_MARKER)) {
            adjustedPath = normalizeSpringFrameworkRelativePath(adjustedPath);
        }
        return adjustedPath;
    }

    private static String normalizeSpringFrameworkRelativePath(String relativePath) {
        String adjustedPath = relativePath;
        if (adjustedPath.startsWith(SPRING_FRAMEWORK_LEGACY_DUPLICATE_JAVADOC_PREFIX)) {
            adjustedPath = SPRING_FRAMEWORK_DOCS_JAVADOC_PREFIX
                    + adjustedPath.substring(SPRING_FRAMEWORK_LEGACY_DUPLICATE_JAVADOC_PREFIX.length());
        } else if (adjustedPath.startsWith(SPRING_FRAMEWORK_LEGACY_API_CURRENT_PREFIX)) {
            adjustedPath = SPRING_FRAMEWORK_DOCS_JAVADOC_PREFIX
                    + adjustedPath.substring(SPRING_FRAMEWORK_LEGACY_API_CURRENT_PREFIX.length());
        }
        if (adjustedPath.startsWith(SPRING_FRAMEWORK_LEGACY_DOCS_JAVADOC_JAVA_PREFIX)) {
            adjustedPath = SPRING_FRAMEWORK_DOCS_JAVADOC_PREFIX
                    + adjustedPath.substring(SPRING_FRAMEWORK_LEGACY_DOCS_JAVADOC_JAVA_PREFIX.length());
        } else if (adjustedPath.startsWith(SPRING_FRAMEWORK_LEGACY_JAVADOC_JAVA_PREFIX)) {
            adjustedPath = SPRING_FRAMEWORK_JAVADOC_PREFIX
                    + adjustedPath.substring(SPRING_FRAMEWORK_LEGACY_JAVADOC_JAVA_PREFIX.length());
        }
        return adjustedPath;
    }

    private static Optional<String> joinBaseAndRel(String baseUrl, String relativePath) {
        Optional<String> joinedUrl = Optional.empty();
        if (baseUrl != null) {
            String normalizedBase = trimTrailingSlashes(baseUrl);
            String normalizedRelativePath = relativePath == null
                    ? EMPTY_TEXT
                    : relativePath.replace(WINDOWS_PATH_SEPARATOR, UNIX_PATH_SEPARATOR);
            normalizedRelativePath = trimLeadingSlashes(normalizedRelativePath);

            if (normalizedBase.endsWith(DOCS_API_SUFFIX)) {
                if (normalizedRelativePath.startsWith(DOCS_API_PREFIX)) {
                    normalizedRelativePath = normalizedRelativePath.substring(DOCS_API_PREFIX.length());
                } else if (normalizedRelativePath.startsWith(API_PREFIX)) {
                    normalizedRelativePath = normalizedRelativePath.substring(API_PREFIX.length());
                }
            } else if (normalizedBase.endsWith(API_SUFFIX) && normalizedRelativePath.startsWith(API_PREFIX)) {
                normalizedRelativePath = normalizedRelativePath.substring(API_PREFIX.length());
            }

            joinedUrl = Optional.of(normalizedBase + PATH_SEPARATOR_TEXT + normalizedRelativePath);
        }
        return joinedUrl;
    }

    private static String trimLeadingSlashes(String pathText) {
        String trimmedPath = pathText;
        while (trimmedPath.startsWith(PATH_SEPARATOR_TEXT)) {
            trimmedPath = trimmedPath.substring(1);
        }
        return trimmedPath;
    }

    private static String trimTrailingSlashes(String baseUrl) {
        int endIndex = baseUrl.length();
        while (endIndex > 0 && baseUrl.charAt(endIndex - 1) == UNIX_PATH_SEPARATOR) {
            endIndex--;
        }
        return baseUrl.substring(0, endIndex);
    }

    /** Maps a locally mirrored book PDF to its public application path. */
    public static Optional<String> mapBookLocalToPublic(String localPath) {
        if (localPath == null) {
            return Optional.empty();
        }
        String normalizedPath = localPath.replace('\\', '/');
        if (!AsciiTextNormalizer.toLowerAscii(normalizedPath).endsWith(PDF_EXTENSION)) {
            return Optional.empty();
        }
        int markerIndex = normalizedPath.indexOf(LOCAL_DOCS_BOOKS);
        if (markerIndex < 0) {
            return Optional.empty();
        }
        String fileName = normalizedPath.substring(markerIndex + LOCAL_DOCS_BOOKS.length());
        int lastSlash = fileName.lastIndexOf('/');
        String baseName = lastSlash >= 0 ? fileName.substring(lastSlash + 1) : fileName;
        return Optional.of(PUBLIC_PDFS_BASE + baseName);
    }

    /** Canonicalizes common duplicated path segments in HTTP documentation URLs. */
    public static String canonicalizeHttpDocUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        String canonicalUrl = url.replace("/docs/api/api/", "/docs/api/").replace("/api/api/", "/api/");
        if (canonicalUrl.contains(SPRING_DOCS_HTTPS_PREFIX)) {
            canonicalUrl = canonicalUrl.replace(
                    "/spring-framework/docs/current/javadoc-api/java/", "/spring-framework/docs/current/javadoc-api/");
        }
        int protocolIndex = canonicalUrl.indexOf("://");
        String protocolPrefix = protocolIndex >= 0 ? canonicalUrl.substring(0, protocolIndex + 3) : "";
        String urlRemainder = protocolIndex >= 0 ? canonicalUrl.substring(protocolIndex + 3) : canonicalUrl;
        return protocolPrefix + urlRemainder.replaceAll("/+", "/");
    }

    /** Resolves a local filesystem path to its authoritative remote URL. */
    public static Optional<String> resolveLocalPath(String absolutePath) {
        if (absolutePath == null || absolutePath.isBlank()) {
            return Optional.empty();
        }
        return mapBookLocalToPublic(absolutePath)
                .or(() -> reconstructFromEmbeddedHost(absolutePath))
                .or(() -> mapLocalPrefixToRemote(absolutePath));
    }

    /** Normalizes a local or remote documentation URL for citations. */
    public static String normalizeDocUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return rawUrl;
        }
        String trimmedUrl = rawUrl.trim();
        if (trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")) {
            return canonicalizeHttpDocUrl(trimmedUrl);
        }
        String resolvedPath = trimmedUrl.startsWith("file://") ? trimmedUrl.substring("file://".length()) : trimmedUrl;
        Optional<String> publicPdfUrl = mapBookLocalToPublic(resolvedPath);
        if (publicPdfUrl.isPresent()) {
            return publicPdfUrl.get();
        }
        if (!trimmedUrl.startsWith("file://")) {
            return trimmedUrl;
        }
        String localPath = trimmedUrl.substring("file://".length());
        Optional<String> embeddedUrl = reconstructFromEmbeddedHost(localPath);
        if (embeddedUrl.isPresent()) {
            return canonicalizeHttpDocUrl(embeddedUrl.get());
        }
        return mapLocalPrefixToRemote(localPath)
                .map(DocsSourceRegistry::canonicalizeHttpDocUrl)
                .orElse(REDACTED_LOCAL_URL);
    }
}
