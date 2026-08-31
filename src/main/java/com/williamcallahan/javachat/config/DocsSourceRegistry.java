package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final String HTML_EXTENSION = ".html";
    private static final String HTM_EXTENSION = ".htm";
    private static final String JAVA_EXTENSION = ".java";
    private static final String HTML_INDEX_FILE_NAME = "index.html";
    private static final String HTM_INDEX_FILE_NAME = "index.htm";
    private static final String HTTPS_PREFIX = "https://";
    private static final String DOCS_ORACLE_HOST_MARKER = "docs.oracle.com/";
    private static final String SPRING_DOCS_HOST_MARKER = "docs.spring.io/";
    private static final String SPRING_DOCS_HTTPS_PREFIX = HTTPS_PREFIX + SPRING_DOCS_HOST_MARKER;
    private static final String EMPTY_TEXT = "";
    private static final String PATH_SEPARATOR_TEXT = "/";
    private static final String GITHUB_BLOB_PATH = "/blob/";
    private static final String GITHUB_TREE_PATH = "/tree/";
    private static final String INGESTION_IDENTITY_QUERY_PREFIX = "?java-chat-mirror=";
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
    private static final Pattern NUMERIC_VERSION_PREFIX_PATTERN = Pattern.compile("^[0-9]+(?:\\.[0-9]+)*");

    private static final String SPRING_FRAMEWORK_REFERENCE_URL_PREFIX =
            SPRING_DOCS_HTTPS_PREFIX + SPRING_FRAMEWORK_MARKER + "/reference";
    private static final String SPRING_FRAMEWORK_JAVADOC_URL_PREFIX =
            SPRING_DOCS_HTTPS_PREFIX + SPRING_FRAMEWORK_MARKER + "/docs/current/javadoc-api";
    private static final String SPRING_BOOT_REFERENCE_URL_PREFIX =
            SPRING_DOCS_HTTPS_PREFIX + SPRING_BOOT_MARKER + "/reference";
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

    private static final String DEFAULT_ORACLE_JAVASE_BASE = "https://www.oracle.com/java/technologies/javase/";
    private static final String DEFAULT_IBM_ARTICLES_BASE = "https://developer.ibm.com/articles/";
    private static final String DEFAULT_JETBRAINS_IDEA_2025_09_BASE = "https://blog.jetbrains.com/idea/2025/09/";
    private static final String DEFAULT_SPRING_FRAMEWORK_BASE = "https://docs.spring.io/spring-framework/";
    private static final String DEFAULT_SPRING_AI_BASE = "https://docs.spring.io/spring-ai/";
    private static final String DEFAULT_SPRING_FRAMEWORK_API_BASE =
            "https://docs.spring.io/spring-framework/docs/current/javadoc-api/";
    private static final String DEFAULT_SPRING_AI_API_STABLE_BASE = "https://docs.spring.io/spring-ai/docs/1.1.2/api/";

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

    private static final List<JavaApiDocumentationSource> JAVA_API_DOCUMENTATION_SOURCES = List.of(
            new JavaApiDocumentationSource(
                    "21",
                    "https://docs.oracle.com/en/java/javase/21/docs/api/",
                    "java/java21-complete",
                    "Java 21 Complete API"),
            new JavaApiDocumentationSource(
                    "25",
                    "https://docs.oracle.com/en/java/javase/25/docs/api/",
                    "java/java25-complete",
                    "Java 25 Complete API"),
            new JavaApiDocumentationSource(
                    "26",
                    "https://docs.oracle.com/en/java/javase/26/docs/api/",
                    "java/java26-complete",
                    "Java 26 Complete API"));

    private static final List<DocumentationSource> DOCUMENTATION_SOURCES = List.of(
            new DocumentationSource(
                    "https://dev.java/learn/", "dev-java", "Dev.java Learning", "dev-java", "official", "tutorial", ""),
            new DocumentationSource(
                    "https://kotlinlang.org/docs/",
                    "kotlin",
                    "Kotlin Documentation",
                    "kotlin",
                    OFFICIAL_DOCUMENTATION_SOURCE_KIND,
                    "language-reference",
                    "2.4.10"),
            new DocumentationSource(
                    "https://docs.scala-lang.org/scala3/reference/",
                    "scala",
                    "Scala 3 Documentation",
                    "scala",
                    OFFICIAL_DOCUMENTATION_SOURCE_KIND,
                    "language-reference",
                    ""),
            new DocumentationSource(
                    "https://docs.groovy-lang.org/docs/groovy-5.0.7/html/documentation/",
                    "groovy/5.0.7",
                    "Groovy 5.0.7 Documentation",
                    "groovy",
                    OFFICIAL_DOCUMENTATION_SOURCE_KIND,
                    "language-reference",
                    "5.0.7"),
            new DocumentationSource(
                    "https://clojure.org/guides/",
                    "clojure",
                    "Clojure Guides",
                    "clojure",
                    OFFICIAL_DOCUMENTATION_SOURCE_KIND,
                    "language-guide",
                    ""),
            new DocumentationSource(
                    "https://www.jooq.org/doc/3.21.7/manual/",
                    "jooq/3.21/manual",
                    "jOOQ 3.21.7 Manual",
                    "jooq/3.21/manual",
                    "official",
                    "framework-reference",
                    "3.21.7",
                    DocumentationCitationPathStyle.EXTENSIONLESS_HTML),
            new DocumentationSource(
                    "https://www.jooq.org/javadoc/3.21.7/",
                    "jooq/3.21/api",
                    "jOOQ 3.21.7 API",
                    "jooq/3.21/api",
                    "official",
                    "api-docs",
                    "3.21.7"),
            new DocumentationSource(
                    "https://docs.python.org/release/3.14.7/",
                    "python/3.14",
                    "Python 3.14.7 Documentation",
                    "python/3.14",
                    "official",
                    "language-reference",
                    "3.14.7"),
            new DocumentationSource(
                    "https://www.postgresql.org/docs/17/",
                    "postgresql/17",
                    "PostgreSQL 17 Documentation",
                    "postgresql/17",
                    "official",
                    "database-reference",
                    "17.11"),
            new DocumentationSource(
                    "https://www.postgresql.org/docs/18/",
                    "postgresql/18",
                    "PostgreSQL 18 Documentation",
                    "postgresql/18",
                    "official",
                    "database-reference",
                    "18.6"),
            new DocumentationSource(
                    "https://javadoc.io/doc/com.zaxxer/HikariCP/7.1.0/",
                    "hikaricp/7.1.0/api",
                    "HikariCP 7.1.0 API",
                    "hikaricp/7.1.0/api",
                    "official",
                    "api-docs",
                    "7.1.0"),
            new DocumentationSource(
                    "https://javadoc.io/doc/com.zaxxer/HikariCP/7.0.2/",
                    "hikaricp/7.0.2/api",
                    "HikariCP 7.0.2 API (Spring Boot 4.0.6)",
                    "hikaricp/7.0.2/api",
                    "official",
                    "api-docs",
                    "7.0.2"),
            new DocumentationSource(
                    "https://github.com/FasterXML/jackson-databind/blob/jackson-databind-2.22.2/src/main/java/",
                    "jackson/2.22.2/api",
                    "Jackson Databind 2.22.2 API",
                    "jackson/2.22.2/api",
                    "official",
                    "api-docs",
                    "2.22.2",
                    DocumentationCitationPathStyle.JAVA_SOURCE),
            new DocumentationSource(
                    "https://javadoc.io/doc/com.fasterxml.jackson.core/jackson-databind/2.21.2/",
                    "jackson/2.21.2/api",
                    "Jackson Databind 2.21.2 API (Spring Boot 4.0.6)",
                    "jackson/2.21.2/api",
                    "official",
                    "api-docs",
                    "2.21.2"),
            new DocumentationSource(
                    "https://github.com/FasterXML/jackson-databind/blob/jackson-databind-3.2.2/src/main/java/",
                    "jackson/3.2.2/api",
                    "Jackson Databind 3.2.2 API",
                    "jackson/3.2.2/api",
                    "official",
                    "api-docs",
                    "3.2.2",
                    DocumentationCitationPathStyle.JAVA_SOURCE),
            new DocumentationSource(
                    "https://javadoc.io/doc/tools.jackson.core/jackson-databind/3.1.2/",
                    "jackson/3.1.2/api",
                    "Jackson Databind 3.1.2 API (Spring Boot 4.0.6)",
                    "jackson/3.1.2/api",
                    "official",
                    "api-docs",
                    "3.1.2"),
            new DocumentationSource(
                    "https://javadoc.io/doc/org.projectlombok/lombok/1.18.46/",
                    "lombok/1.18.46/api",
                    "Lombok 1.18.46 API (Spring Boot 4.0.6)",
                    "lombok/1.18.46/api",
                    "official",
                    "api-docs",
                    "1.18.46"),
            new DocumentationSource(
                    "https://projectlombok.org/features/",
                    "lombok/1.18.46/reference",
                    "Lombok 1.18.46 Feature Reference",
                    "lombok/1.18.46/reference",
                    "official",
                    "reference",
                    "1.18.46",
                    DocumentationCitationPathStyle.EXTENSIONLESS_HTML),
            new DocumentationSource(
                    "https://docs.docker.com/",
                    "docker",
                    "Docker Documentation",
                    "docker",
                    OFFICIAL_DOCUMENTATION_SOURCE_KIND,
                    "platform-reference",
                    "current",
                    DocumentationCitationPathStyle.EXTENSIONLESS_HTML),
            new DocumentationSource(
                    "https://clerk.com/docs/llms-full.txt",
                    "clerk",
                    "Clerk Documentation",
                    "clerk",
                    OFFICIAL_DOCUMENTATION_SOURCE_KIND,
                    "platform-reference",
                    "current",
                    DocumentationCitationPathStyle.SINGLE_DOCUMENT),
            new DocumentationSource(
                    "https://doc.traefik.io/traefik/",
                    "traefik",
                    "Traefik Proxy Documentation",
                    "traefik",
                    OFFICIAL_DOCUMENTATION_SOURCE_KIND,
                    "platform-reference",
                    "current",
                    DocumentationCitationPathStyle.EXTENSIONLESS_HTML),
            new DocumentationSource(
                    "https://porkbun.com/api/json/v3/documentation",
                    "porkbun",
                    "Porkbun API v3.15 Documentation",
                    "porkbun",
                    OFFICIAL_DOCUMENTATION_SOURCE_KIND,
                    "api-docs",
                    "3.15",
                    DocumentationCitationPathStyle.SINGLE_DOCUMENT),
            new DocumentationSource(
                    "https://github.com/oborseth/Porkbun-MCP/blob/64e8b4f4caad75e99333733bca5f2987afee3c75/README.md",
                    "porkbun-mcp",
                    "Porkbun MCP Server 0.17.1",
                    "porkbun-mcp",
                    OFFICIAL_DOCUMENTATION_SOURCE_KIND,
                    "tool-reference",
                    "0.17.1-64e8b4f4caad",
                    DocumentationCitationPathStyle.SINGLE_DOCUMENT),
            new DocumentationSource(
                    "https://developers.cloudflare.com/",
                    "cloudflare",
                    "Cloudflare Developer Documentation",
                    "cloudflare",
                    OFFICIAL_DOCUMENTATION_SOURCE_KIND,
                    "platform-reference",
                    "current",
                    DocumentationCitationPathStyle.EXTENSIONLESS_HTML),
            new DocumentationSource(
                    "https://docs.dokploy.com/",
                    "dokploy",
                    "Dokploy Documentation",
                    "dokploy",
                    OFFICIAL_DOCUMENTATION_SOURCE_KIND,
                    "platform-reference",
                    "current",
                    DocumentationCitationPathStyle.EXTENSIONLESS_HTML),
            new DocumentationSource(
                    "https://infisical.com/docs/",
                    "infisical",
                    "Infisical Documentation",
                    "infisical",
                    OFFICIAL_DOCUMENTATION_SOURCE_KIND,
                    "platform-reference",
                    "current",
                    DocumentationCitationPathStyle.EXTENSIONLESS_HTML),
            new DocumentationSource(
                    "https://docs.doppler.com/docs/",
                    "doppler/docs",
                    "Doppler Guides",
                    "doppler-guides",
                    OFFICIAL_DOCUMENTATION_SOURCE_KIND,
                    "platform-guide",
                    "current",
                    DocumentationCitationPathStyle.EXTENSIONLESS_HTML),
            new DocumentationSource(
                    "https://docs.doppler.com/reference/",
                    "doppler/reference",
                    "Doppler API Reference",
                    "doppler-reference",
                    OFFICIAL_DOCUMENTATION_SOURCE_KIND,
                    "api-docs",
                    "current",
                    DocumentationCitationPathStyle.EXTENSIONLESS_HTML),
            new DocumentationSource(
                    "https://docs.doppler.com/changelog/",
                    "doppler/changelog",
                    "Doppler Changelog",
                    "doppler-changelog",
                    OFFICIAL_DOCUMENTATION_SOURCE_KIND,
                    "release-notes",
                    "current",
                    DocumentationCitationPathStyle.EXTENSIONLESS_HTML),
            new DocumentationSource(
                    "https://platform.claude.com/docs/en/",
                    "anthropic/api",
                    "Anthropic API Documentation",
                    "anthropic-api",
                    "official",
                    "api-docs",
                    "current",
                    DocumentationCitationPathStyle.EXTENSIONLESS_HTML),
            new DocumentationSource(
                    "https://code.claude.com/docs/en/",
                    "anthropic/claude-code",
                    "Claude Code Documentation",
                    "claude-code",
                    "official",
                    "tool-reference",
                    "current",
                    DocumentationCitationPathStyle.EXTENSIONLESS_HTML),
            new DocumentationSource(
                    "https://ampcode.com/",
                    "amp-code",
                    "Amp Code CLI Manual",
                    "amp-code",
                    "official",
                    "tool-reference",
                    "current",
                    DocumentationCitationPathStyle.EXTENSIONLESS_HTML),
            new DocumentationSource(
                    "https://tinker-docs.thinkingmachines.ai/",
                    "tinker",
                    "Tinker Documentation",
                    "tinker",
                    "official",
                    "api-docs",
                    "current",
                    DocumentationCitationPathStyle.EXTENSIONLESS_HTML),
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
                    "",
                    DocumentationCitationPathStyle.EXTENSIONLESS_HTML),
            new DocumentationSource(
                    SPRING_AI_BASE + "reference/1.1/",
                    "spring-ai-reference",
                    "Spring AI Stable Reference",
                    "spring-ai-reference",
                    "official",
                    "framework-reference",
                    "1.1.8"),
            new DocumentationSource(
                    SPRING_AI_API_STABLE_BASE,
                    "spring-ai-api-stable",
                    "Spring AI Stable API",
                    "spring-ai-api-stable",
                    "official",
                    "api-docs",
                    "1.1.2"),
            new DocumentationSource(
                    "https://docs.spring.io/spring-framework/reference/",
                    "spring-framework-reference",
                    "Spring Framework Reference",
                    "spring-framework-reference",
                    "official",
                    "framework-reference",
                    ""),
            new DocumentationSource(
                    SPRING_FRAMEWORK_API_BASE,
                    "spring-framework-api",
                    "Spring Framework Javadocs",
                    "spring-framework-api",
                    "official",
                    "api-docs",
                    ""),
            new DocumentationSource(
                    "https://docs.spring.io/spring-framework/docs/7.0.7/javadoc-api/",
                    "spring-framework/7.0.7/api",
                    "Spring Framework 7.0.7 API",
                    "spring-framework/7.0.7/api",
                    "official",
                    "api-docs",
                    "7.0.7"),
            new DocumentationSource(
                    ORACLE_JAVASE_BASE,
                    "oracle/javase",
                    "Java 25 Release Notes Issues",
                    "oracle/javase",
                    "official",
                    "release-notes",
                    "25"),
            new DocumentationSource(
                    IBM_ARTICLES_BASE,
                    "ibm/articles",
                    "IBM Java 25 Overview",
                    "ibm/articles",
                    "vendor",
                    "article",
                    "25"),
            new DocumentationSource(
                    JETBRAINS_IDEA_2025_09_BASE,
                    "jetbrains/idea/2025/09",
                    "JetBrains Java 25 Article",
                    "jetbrains/idea/2025/09",
                    "vendor",
                    "article",
                    "25"));

    private static final List<String> OFFICIAL_DOCUMENTATION_SOURCE_IDENTITIES = Stream.concat(
                    DOCUMENTATION_SOURCES.stream()
                            .filter(documentationSource ->
                                    OFFICIAL_DOCUMENTATION_SOURCE_KIND.equals(documentationSource.sourceKind()))
                            .map(DocumentationSource::docSet),
                    JAVA_API_DOCUMENTATION_SOURCES.stream().map(JavaApiDocumentationSource::relativeMirrorPath))
            .toList();

    private static final String[] EMBEDDED_HOST_MARKERS = {DOCS_ORACLE_HOST_MARKER, SPRING_DOCS_HOST_MARKER};
    private static final Map<String, CitationRoute> LOCAL_PREFIX_TO_CITATION_ROUTE = buildLocalPrefixLookup();

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

    /**
     * Resolves a requested Java release to exact or adjacent indexed API sources.
     *
     * <p>An exact corpus wins. A gap uses the nearest lower and higher releases; a request outside the indexed
     * range uses the nearest available side. This keeps in-scope Java questions answerable while preserving the
     * evidence releases supplied to retrieval.</p>
     *
     * @param requestedRelease numeric Java release requested by the learner
     * @return exact source, bracketing sources, or the nearest range-edge source
     */
    public static List<JavaApiDocumentationSource> javaApiDocumentationSourcesForRelease(String requestedRelease) {
        int requestedReleaseNumber = Integer.parseInt(requestedRelease);
        Optional<JavaApiDocumentationSource> exactSource = JAVA_API_DOCUMENTATION_SOURCES.stream()
                .filter(source -> Integer.parseInt(source.javaRelease()) == requestedReleaseNumber)
                .findFirst();
        if (exactSource.isPresent()) {
            return exactSource.stream().toList();
        }
        Optional<JavaApiDocumentationSource> nearestLowerSource = JAVA_API_DOCUMENTATION_SOURCES.stream()
                .filter(source -> Integer.parseInt(source.javaRelease()) < requestedReleaseNumber)
                .max(Comparator.comparingInt(source -> Integer.parseInt(source.javaRelease())));
        Optional<JavaApiDocumentationSource> nearestHigherSource = JAVA_API_DOCUMENTATION_SOURCES.stream()
                .filter(source -> Integer.parseInt(source.javaRelease()) > requestedReleaseNumber)
                .min(Comparator.comparingInt(source -> Integer.parseInt(source.javaRelease())));
        return Stream.concat(nearestLowerSource.stream(), nearestHigherSource.stream())
                .toList();
    }

    /** Resolves multiple requested releases to encounter-ordered, deduplicated indexed API sources. */
    public static List<JavaApiDocumentationSource> javaApiDocumentationSourcesForReleases(
            List<String> requestedReleases) {
        return requestedReleases.stream()
                .flatMap(requestedRelease -> javaApiDocumentationSourcesForRelease(requestedRelease).stream())
                .distinct()
                .toList();
    }

    /** Describes same-family indexed evidence for a requested dependency version. */
    public record VersionedDocumentationEvidence(
            String sourceFamily, String requestedVersion, List<DocumentationSource> sources) {
        public VersionedDocumentationEvidence {
            Objects.requireNonNull(sourceFamily, "sourceFamily");
            Objects.requireNonNull(requestedVersion, "requestedVersion");
            sources = List.copyOf(sources);
        }
    }

    /** Resolves a named versioned dependency in a query to exact or adjacent same-family sources. */
    public static Optional<VersionedDocumentationEvidence> versionedDocumentationEvidence(String query) {
        return versionedDocumentationEvidenceAll(query).stream().findFirst();
    }

    /** Resolves every named versioned dependency in a query without dropping another source family. */
    public static List<VersionedDocumentationEvidence> versionedDocumentationEvidenceAll(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String normalizedQuery =
                query.toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ');
        Map<String, List<DocumentationSource>> sourcesByFamily = DOCUMENTATION_SOURCES.stream()
                .filter(source -> source.docVersion().matches("[0-9]+(?:\\.[0-9]+)*(?:[-+][A-Za-z0-9.]+)?"))
                .collect(java.util.stream.Collectors.groupingBy(
                        source -> documentationSourceFamily(source.docSet()),
                        LinkedHashMap::new,
                        java.util.stream.Collectors.toList()));
        List<VersionedDocumentationEvidence> resolvedEvidence = new ArrayList<>();
        for (Map.Entry<String, List<DocumentationSource>> familySources : sourcesByFamily.entrySet()) {
            String familyAliases = familySources.getValue().stream()
                    .flatMap(source -> documentationSourceAliases(source).stream())
                    .filter(alias -> !alias.isBlank())
                    .distinct()
                    .sorted(Comparator.comparingInt(String::length).reversed())
                    .map(Pattern::quote)
                    .collect(java.util.stream.Collectors.joining("|"));
            Matcher requestedVersionMatcher = Pattern.compile(
                            "\\b(?:" + familyAliases + ")\\s+(\\d+(?:\\.\\d+){0,3})\\b")
                    .matcher(normalizedQuery);
            while (requestedVersionMatcher.find()) {
                String requestedVersion = requestedVersionMatcher.group(1);
                List<DocumentationSource> exactSources = familySources.getValue().stream()
                        .filter(source -> source.docVersion().equals(requestedVersion)
                                || source.docVersion().startsWith(requestedVersion + "-"))
                        .toList();
                if (!exactSources.isEmpty()) {
                    resolvedEvidence.add(
                            new VersionedDocumentationEvidence(familySources.getKey(), requestedVersion, exactSources));
                    continue;
                }
                Optional<String> lowerVersion = familySources.getValue().stream()
                        .map(DocumentationSource::docVersion)
                        .filter(version -> compareNumericVersions(version, requestedVersion) < 0)
                        .max(DocsSourceRegistry::compareNumericVersions);
                Optional<String> higherVersion = familySources.getValue().stream()
                        .map(DocumentationSource::docVersion)
                        .filter(version -> compareNumericVersions(version, requestedVersion) > 0)
                        .min(DocsSourceRegistry::compareNumericVersions);
                List<String> evidenceVersions = Stream.concat(lowerVersion.stream(), higherVersion.stream())
                        .toList();
                List<DocumentationSource> evidenceSources = evidenceVersions.stream()
                        .flatMap(evidenceVersion -> familySources.getValue().stream()
                                .filter(source -> evidenceVersion.equals(source.docVersion())))
                        .toList();
                resolvedEvidence.add(
                        new VersionedDocumentationEvidence(familySources.getKey(), requestedVersion, evidenceSources));
            }
        }
        return List.copyOf(resolvedEvidence);
    }

    /** Returns a stable source-family identity from a versioned or unversioned documentation set. */
    public static String documentationSourceFamily(String documentationSet) {
        if (documentationSet == null || documentationSet.isBlank()) {
            return "";
        }
        int familyDelimiter = documentationSet.indexOf('/');
        String pathFamily = familyDelimiter < 0 ? documentationSet : documentationSet.substring(0, familyDelimiter);
        return pathFamily.replaceFirst("-(?:api(?:-stable)?|reference|docs|guides)$", "");
    }

    private static List<String> documentationSourceAliases(DocumentationSource documentationSource) {
        String displayAlias = documentationSource
                .displayName()
                .toLowerCase(Locale.ROOT)
                .replaceFirst("\\s+\\d+(?:\\.\\d+)*(?:\\s+.*)?$", "")
                .replaceFirst("\\s+(?:stable\\s+)?(?:api|reference|documentation|guides)$", "")
                .trim();
        String familyAlias = documentationSourceFamily(documentationSource.docSet())
                .toLowerCase(Locale.ROOT)
                .replace('-', ' ')
                .replace('_', ' ');
        return Stream.of(familyAlias, displayAlias)
                .filter(alias -> !alias.isBlank())
                .filter(alias -> !"java".equals(alias) && !"jdk".equals(alias))
                .distinct()
                .toList();
    }

    private static int compareNumericVersions(String leftVersion, String rightVersion) {
        String[] leftComponents = numericVersionPrefix(leftVersion).split("\\.");
        String[] rightComponents = numericVersionPrefix(rightVersion).split("\\.");
        int componentCount = Math.max(leftComponents.length, rightComponents.length);
        for (int componentIndex = 0; componentIndex < componentCount; componentIndex++) {
            java.math.BigInteger leftComponent = componentIndex < leftComponents.length
                    ? new java.math.BigInteger(leftComponents[componentIndex])
                    : java.math.BigInteger.ZERO;
            java.math.BigInteger rightComponent = componentIndex < rightComponents.length
                    ? new java.math.BigInteger(rightComponents[componentIndex])
                    : java.math.BigInteger.ZERO;
            int comparison = leftComponent.compareTo(rightComponent);
            if (comparison != 0) {
                return comparison;
            }
        }
        return 0;
    }

    private static String numericVersionPrefix(String version) {
        Matcher numericVersionMatcher = NUMERIC_VERSION_PREFIX_PATTERN.matcher(version);
        if (!numericVersionMatcher.find()) {
            throw new IllegalArgumentException("Version has no numeric prefix");
        }
        return numericVersionMatcher.group();
    }

    /** Describes provenance and citation fields consumed by JVM ingestion. */
    public record DocumentationSource(
            String citationBaseUrl,
            String relativeMirrorPath,
            String displayName,
            String docSet,
            String sourceKind,
            String docType,
            String docVersion,
            DocumentationCitationPathStyle citationPathStyle) {
        /**
         * Preserves literal citation paths for existing documentation sources whose mirrors retain canonical
         * filenames.
         */
        public DocumentationSource(
                String citationBaseUrl,
                String relativeMirrorPath,
                String displayName,
                String docSet,
                String sourceKind,
                String docType,
                String docVersion) {
            this(
                    citationBaseUrl,
                    relativeMirrorPath,
                    displayName,
                    docSet,
                    sourceKind,
                    docType,
                    docVersion,
                    DocumentationCitationPathStyle.LITERAL);
        }

        public DocumentationSource {
            Objects.requireNonNull(citationBaseUrl, "citationBaseUrl");
            Objects.requireNonNull(relativeMirrorPath, "relativeMirrorPath");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(docSet, "docSet");
            Objects.requireNonNull(sourceKind, "sourceKind");
            Objects.requireNonNull(docType, "docType");
            Objects.requireNonNull(docVersion, "docVersion");
            Objects.requireNonNull(citationPathStyle, "citationPathStyle");
        }
    }

    /** Describes how a mirrored HTML filename maps back to its canonical citation route. */
    public enum DocumentationCitationPathStyle {
        /** Keeps the mirrored relative path unchanged. */
        LITERAL,
        /** Uses one canonical source URL for every fragment of a single mirrored document. */
        SINGLE_DOCUMENT,
        /** Maps an extracted Javadoc page to the matching source file in the official repository. */
        JAVA_SOURCE,
        /** Removes the HTML filename synthesized for an extensionless canonical route. */
        EXTENSIONLESS_HTML;

        String citationRelativePath(String mirroredRelativePath) {
            if (this == LITERAL || mirroredRelativePath == null) {
                return mirroredRelativePath;
            }
            if (mirroredRelativePath.endsWith(HTML_INDEX_FILE_NAME)) {
                return mirroredRelativePath.substring(0, mirroredRelativePath.length() - HTML_INDEX_FILE_NAME.length());
            }
            if (mirroredRelativePath.endsWith(HTM_INDEX_FILE_NAME)) {
                return mirroredRelativePath.substring(0, mirroredRelativePath.length() - HTM_INDEX_FILE_NAME.length());
            }
            if (mirroredRelativePath.endsWith(HTML_EXTENSION)) {
                return mirroredRelativePath.substring(0, mirroredRelativePath.length() - HTML_EXTENSION.length());
            }
            if (mirroredRelativePath.endsWith(HTM_EXTENSION)) {
                return mirroredRelativePath.substring(0, mirroredRelativePath.length() - HTM_EXTENSION.length());
            }
            return mirroredRelativePath;
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

    /** Returns the canonical citation base for an exact registered mirror root. */
    public static Optional<String> citationBaseForRelativeMirrorPath(String relativeMirrorPath) {
        Optional<String> documentationBase =
                documentationSourceForRelativeMirrorPath(relativeMirrorPath).map(DocumentationSource::citationBaseUrl);
        return documentationBase.or(() -> JAVA_API_DOCUMENTATION_SOURCES.stream()
                .filter(source -> source.relativeMirrorPath().equals(relativeMirrorPath))
                .map(JavaApiDocumentationSource::remoteBaseUrl)
                .findFirst());
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

    private static Map<String, CitationRoute> buildLocalPrefixLookup() {
        Map<String, CitationRoute> prefixLookup = new LinkedHashMap<>();
        for (JavaApiDocumentationSource javaApiDocumentationSource : JAVA_API_DOCUMENTATION_SOURCES) {
            prefixLookup.put(
                    LOCAL_DOCS_ROOT + javaApiDocumentationSource.relativeMirrorPath() + "/",
                    new CitationRoute(
                            javaApiDocumentationSource.remoteBaseUrl(), DocumentationCitationPathStyle.LITERAL));
        }
        for (DocumentationSource documentationSource : DOCUMENTATION_SOURCES) {
            prefixLookup.put(
                    LOCAL_DOCS_ROOT + documentationSource.relativeMirrorPath() + "/",
                    new CitationRoute(documentationSource.citationBaseUrl(), documentationSource.citationPathStyle()));
        }
        return Map.copyOf(prefixLookup);
    }

    private record CitationRoute(String citationBaseUrl, DocumentationCitationPathStyle citationPathStyle) {
        private CitationRoute {
            Objects.requireNonNull(citationBaseUrl, "citationBaseUrl");
            Objects.requireNonNull(citationPathStyle, "citationPathStyle");
        }

        Optional<String> resolveCitationUrl(String mirroredRelativePath) {
            if (citationPathStyle == DocumentationCitationPathStyle.SINGLE_DOCUMENT) {
                return Optional.of(citationBaseUrl);
            }
            if (citationPathStyle == DocumentationCitationPathStyle.JAVA_SOURCE) {
                return resolveJavaSourceCitation(citationBaseUrl, mirroredRelativePath);
            }
            return joinBaseAndRel(citationBaseUrl, citationPathStyle.citationRelativePath(mirroredRelativePath));
        }
    }

    private static String ingestionIdentityQuery(String mirroredRelativePath) {
        String encodedMirrorPath = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(mirroredRelativePath.getBytes(StandardCharsets.UTF_8));
        return INGESTION_IDENTITY_QUERY_PREFIX + encodedMirrorPath;
    }

    private static Optional<String> resolveJavaSourceCitation(String sourceBaseUrl, String mirroredRelativePath) {
        if (mirroredRelativePath == null || mirroredRelativePath.isBlank()) {
            return Optional.empty();
        }
        int fileNameStartIndex = mirroredRelativePath.lastIndexOf(UNIX_PATH_SEPARATOR) + 1;
        String fileName = mirroredRelativePath.substring(fileNameStartIndex);
        String sourceDirectory = mirroredRelativePath.substring(0, fileNameStartIndex);
        if (fileName.endsWith(HTML_EXTENSION) && Character.isUpperCase(fileName.charAt(0))) {
            String typeName = fileName.substring(0, fileName.length() - HTML_EXTENSION.length());
            int nestedTypeSeparatorIndex = typeName.indexOf(VERSION_SEPARATOR);
            String sourceTypeName =
                    nestedTypeSeparatorIndex < 0 ? typeName : typeName.substring(0, nestedTypeSeparatorIndex);
            return joinBaseAndRel(sourceBaseUrl, sourceDirectory + sourceTypeName + JAVA_EXTENSION);
        }
        String sourceTreeBaseUrl = sourceBaseUrl.replace(GITHUB_BLOB_PATH, GITHUB_TREE_PATH);
        return joinBaseAndRel(sourceTreeBaseUrl, sourceDirectory);
    }

    /** Resolves a file beneath a selected mirror root without assuming a literal host path. */
    public static Optional<String> resolveMirroredPath(Path mirrorRoot, Path documentFile) {
        if (mirrorRoot == null || documentFile == null) {
            return Optional.empty();
        }
        Path absoluteMirrorRoot = mirrorRoot.toAbsolutePath().normalize();
        Path absoluteDocumentFile = documentFile.toAbsolutePath().normalize();
        if (!absoluteDocumentFile.startsWith(absoluteMirrorRoot)) {
            return Optional.empty();
        }
        Path documentFileName = absoluteDocumentFile.getFileName();
        if (documentFileName != null
                && pathEndsWith(
                        absoluteMirrorRoot.toString().replace(WINDOWS_PATH_SEPARATOR, UNIX_PATH_SEPARATOR), "books")
                && AsciiTextNormalizer.toLowerAscii(documentFileName.toString()).endsWith(PDF_EXTENSION)) {
            return Optional.of(PUBLIC_PDFS_BASE + documentFileName);
        }
        Optional<String> embeddedRemoteUrl = reconstructFromEmbeddedHost(absoluteDocumentFile.toString());
        if (embeddedRemoteUrl.isPresent()) {
            return embeddedRemoteUrl;
        }
        String normalizedRoot = absoluteMirrorRoot.toString().replace(WINDOWS_PATH_SEPARATOR, UNIX_PATH_SEPARATOR);
        String relativeDocumentPath = absoluteMirrorRoot
                .relativize(absoluteDocumentFile)
                .toString()
                .replace(WINDOWS_PATH_SEPARATOR, UNIX_PATH_SEPARATOR);
        Optional<String> javaApiUrl = JAVA_API_DOCUMENTATION_SOURCES.stream()
                .filter(source -> pathEndsWith(normalizedRoot, source.relativeMirrorPath()))
                .findFirst()
                .flatMap(source -> joinBaseAndRel(source.remoteBaseUrl(), relativeDocumentPath));
        if (javaApiUrl.isPresent()) {
            return javaApiUrl;
        }
        return DOCUMENTATION_SOURCES.stream()
                .filter(source -> pathEndsWith(normalizedRoot, source.relativeMirrorPath()))
                .findFirst()
                .flatMap(source -> new CitationRoute(source.citationBaseUrl(), source.citationPathStyle())
                        .resolveCitationUrl(relativeDocumentPath))
                .map(DocsSourceRegistry::canonicalizeHttpDocUrl);
    }

    /** Resolves stable per-file storage identities independently from canonical citation projection. */
    public static Map<Path, String> resolveMirroredIngestionIdentities(Path mirrorRoot, List<Path> documentFiles) {
        Objects.requireNonNull(mirrorRoot, "mirrorRoot");
        List<Path> requiredDocumentFiles = List.copyOf(Objects.requireNonNull(documentFiles, "documentFiles"));
        Path absoluteMirrorRoot = mirrorRoot.toAbsolutePath().normalize();
        Map<Path, String> ingestionIdentities = new LinkedHashMap<>();
        for (Path documentFile : requiredDocumentFiles) {
            Path absoluteDocumentFile = documentFile.toAbsolutePath().normalize();
            String normalizedDocumentPath =
                    absoluteDocumentFile.toString().replace(WINDOWS_PATH_SEPARATOR, UNIX_PATH_SEPARATOR);
            Optional<DocumentationSource> documentationSource = DOCUMENTATION_SOURCES.stream()
                    .filter(source -> normalizedDocumentPath.contains(
                            PATH_SEPARATOR_TEXT + source.relativeMirrorPath() + PATH_SEPARATOR_TEXT))
                    .max(Comparator.comparingInt(
                            source -> source.relativeMirrorPath().length()));
            resolveMirroredPath(absoluteMirrorRoot, absoluteDocumentFile)
                    .or(() -> documentationSource.flatMap(
                            source -> new CitationRoute(source.citationBaseUrl(), source.citationPathStyle())
                                    .resolveCitationUrl(relativePathWithinSource(normalizedDocumentPath, source))
                                    .map(DocsSourceRegistry::canonicalizeHttpDocUrl)))
                    .or(() -> resolveLocalPath(normalizedDocumentPath))
                    .ifPresent(canonicalUrl -> ingestionIdentities.put(
                            absoluteDocumentFile,
                            documentationSource
                                    .filter(source ->
                                            source.citationPathStyle() == DocumentationCitationPathStyle.JAVA_SOURCE)
                                    .map(source ->
                                            stableJavaSourceIdentity(normalizedDocumentPath, source, canonicalUrl))
                                    .orElse(canonicalUrl)));
        }
        return Map.copyOf(ingestionIdentities);
    }

    private static String relativePathWithinSource(
            String normalizedDocumentPath, DocumentationSource documentationSource) {
        String mirrorMarker = PATH_SEPARATOR_TEXT + documentationSource.relativeMirrorPath() + PATH_SEPARATOR_TEXT;
        return normalizedDocumentPath.substring(normalizedDocumentPath.indexOf(mirrorMarker) + mirrorMarker.length());
    }

    private static String stableJavaSourceIdentity(
            String normalizedDocumentPath, DocumentationSource documentationSource, String canonicalUrl) {
        String mirroredRelativePath = relativePathWithinSource(normalizedDocumentPath, documentationSource);
        int fileNameStart = mirroredRelativePath.lastIndexOf(UNIX_PATH_SEPARATOR) + 1;
        String fileName = mirroredRelativePath.substring(fileNameStart);
        boolean canonicalOwner = fileName.equals("package-summary.html")
                || (fileName.endsWith(HTML_EXTENSION)
                        && Character.isUpperCase(fileName.charAt(0))
                        && !fileName.substring(0, fileName.length() - HTML_EXTENSION.length())
                                .contains("."));
        return canonicalOwner ? canonicalUrl : canonicalUrl + ingestionIdentityQuery(mirroredRelativePath);
    }

    private static boolean pathEndsWith(String normalizedRoot, String relativeMirrorPath) {
        return normalizedRoot.equals(relativeMirrorPath)
                || normalizedRoot.endsWith(PATH_SEPARATOR_TEXT + relativeMirrorPath);
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
            for (Map.Entry<String, CitationRoute> prefixEntry : LOCAL_PREFIX_TO_CITATION_ROUTE.entrySet()) {
                String localPrefix = prefixEntry.getKey();
                if (normalizedPath.contains(localPrefix)) {
                    String relativePath =
                            normalizedPath.substring(normalizedPath.indexOf(localPrefix) + localPrefix.length());
                    String adjustedPath = normalizeRelativePath(localPrefix, relativePath);
                    mappedUrl = prefixEntry.getValue().resolveCitationUrl(adjustedPath);
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

    /** Canonicalizes duplicated segments and retired Spring reference aliases in HTTP documentation URLs. */
    public static String canonicalizeHttpDocUrl(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        String canonicalUrl = url.replace("/docs/api/api/", "/docs/api/").replace("/api/api/", "/api/");
        if (canonicalUrl.contains(SPRING_DOCS_HTTPS_PREFIX)) {
            canonicalUrl = canonicalUrl.replace(
                    "/spring-framework/docs/current/javadoc-api/java/", "/spring-framework/docs/current/javadoc-api/");
            canonicalUrl = canonicalUrl
                    .replace("/spring-framework/reference/reference/", "/spring-framework/reference/")
                    .replace("/spring-framework/reference/current/", "/spring-framework/reference/")
                    .replace("/spring-boot/reference/reference/", "/spring-boot/reference/")
                    .replace("/spring-boot/reference/current/", "/spring-boot/reference/");
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
        String trimmedUrl = stripIngestionIdentity(rawUrl.trim());
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

    private static String stripIngestionIdentity(String url) {
        int identityStart = url.indexOf(INGESTION_IDENTITY_QUERY_PREFIX);
        return identityStart < 0 ? url : url.substring(0, identityStart);
    }
}
