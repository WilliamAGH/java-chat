package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.williamcallahan.javachat.config.DocsSourceRegistry.DocumentationCitationPathStyle;
import com.williamcallahan.javachat.config.DocsSourceRegistry.DocumentationSource;
import com.williamcallahan.javachat.config.DocsSourceRegistry.JavaApiDocumentationSource;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies JVM citation and provenance source behavior.
 */
class DocsSourceRegistryTest {
    private static final String ORACLE_JAVASE_BASE_SETTING = "ORACLE_JAVASE_BASE";
    private static final String TEST_ORACLE_JAVASE_BASE = "https://citations.example.test/java/";
    private static final String DEFAULT_TEST_BASE_URL = "https://fallback.example.test/";
    private static final String EMBEDDED_SPRING_DOCS_LOCAL_URL_PREFIX = "file:///var/cache/docs.spring.io/";
    private static final String SPRING_DOCS_URL_PREFIX = "https://docs.spring.io/";

    @Test
    void resolvesSystemPropertyBeforeEnvironmentForRuntimeBaseUrl() {
        withSystemProperty(
                ORACLE_JAVASE_BASE_SETTING,
                TEST_ORACLE_JAVASE_BASE,
                () -> assertEquals(
                        TEST_ORACLE_JAVASE_BASE,
                        DocsSourceRegistry.resolveRuntimeBaseUrl(ORACLE_JAVASE_BASE_SETTING, DEFAULT_TEST_BASE_URL)));
    }

    @Test
    void resolvesEnvironmentOrDefaultWhenSystemPropertyIsAbsent() {
        String environmentBaseUrl = System.getenv(ORACLE_JAVASE_BASE_SETTING);
        String expectedBaseUrl = environmentBaseUrl == null ? DEFAULT_TEST_BASE_URL : environmentBaseUrl;

        withoutSystemProperty(
                ORACLE_JAVASE_BASE_SETTING,
                () -> assertEquals(
                        expectedBaseUrl,
                        DocsSourceRegistry.resolveRuntimeBaseUrl(ORACLE_JAVASE_BASE_SETTING, DEFAULT_TEST_BASE_URL)));
    }

    @Test
    void returnsImmutableJavaApiDocumentationSourceSnapshot() {
        List<JavaApiDocumentationSource> javaApiDocumentationSources = DocsSourceRegistry.javaApiDocumentationSources();

        assertThrows(UnsupportedOperationException.class, javaApiDocumentationSources::removeFirst);
    }

    @Test
    void returnsImmutableDocumentationSourceSnapshot() {
        List<DocumentationSource> documentationSources = DocsSourceRegistry.documentationSources();

        assertThrows(UnsupportedOperationException.class, documentationSources::removeFirst);
    }

    @Test
    void returnsImmutableOfficialSourceIdentities() {
        List<String> expectedSourceIdentities = Stream.concat(
                        DocsSourceRegistry.documentationSources().stream()
                                .filter(documentationSource -> "official".equals(documentationSource.sourceKind()))
                                .map(DocumentationSource::docSet),
                        DocsSourceRegistry.javaApiDocumentationSources().stream()
                                .map(JavaApiDocumentationSource::relativeMirrorPath))
                .toList();

        List<String> officialSourceIdentities = DocsSourceRegistry.officialDocumentationSourceIdentities();

        assertEquals(expectedSourceIdentities, officialSourceIdentities);
        assertThrows(UnsupportedOperationException.class, officialSourceIdentities::removeFirst);
    }

    @Test
    void mapsEveryCanonicalJavaApiMirrorToItsRemoteBaseUrl() {
        List<JavaApiDocumentationSource> javaApiDocumentationSources = DocsSourceRegistry.javaApiDocumentationSources();
        javaApiDocumentationSources.forEach(javaApiDocumentationSource -> {
            String localJavadocFileUrl = "file:///data/docs/" + javaApiDocumentationSource.relativeMirrorPath()
                    + "/api/java.base/java/lang/String.html";
            String expectedOfficialJavadocUrl =
                    javaApiDocumentationSource.remoteBaseUrl() + "java.base/java/lang/String.html";
            assertEquals(expectedOfficialJavadocUrl, DocsSourceRegistry.normalizeDocUrl(localJavadocFileUrl));
        });
        assertEquals(
                javaApiDocumentationSources.size(),
                javaApiDocumentationSources.stream()
                        .map(JavaApiDocumentationSource::javaRelease)
                        .distinct()
                        .count());
        assertEquals(
                javaApiDocumentationSources.size(),
                javaApiDocumentationSources.stream()
                        .map(JavaApiDocumentationSource::relativeMirrorPath)
                        .distinct()
                        .count());
    }

    @Test
    void mapsEveryCanonicalDocumentationMirrorToItsCitationBaseUrl() {
        List<DocumentationSource> documentationSources = DocsSourceRegistry.documentationSources();
        documentationSources.forEach(documentationSource -> {
            String localDocumentationFileUrl =
                    "file:///data/docs/" + documentationSource.relativeMirrorPath() + "/index.html";
            String expectedOfficialDocumentationUrl =
                    documentationSource.citationBaseUrl().endsWith("/")
                            ? documentationSource.citationBaseUrl()
                                    + documentationSource.citationPathStyle().citationRelativePath("index.html")
                            : documentationSource.citationBaseUrl();
            assertEquals(
                    expectedOfficialDocumentationUrl, DocsSourceRegistry.normalizeDocUrl(localDocumentationFileUrl));
            assertEquals(
                    documentationSource,
                    DocsSourceRegistry.documentationSourceForRelativeMirrorPath(
                                    documentationSource.relativeMirrorPath())
                            .orElseThrow());
            assertEquals(
                    documentationSource,
                    DocsSourceRegistry.documentationSourceForRelativeDocumentPath(
                                    documentationSource.relativeMirrorPath() + "/index.html")
                            .orElseThrow());
        });
        assertEquals(
                documentationSources.size(),
                documentationSources.stream()
                        .map(DocumentationSource::relativeMirrorPath)
                        .distinct()
                        .count());
    }

    @Test
    void restoresExtensionlessCanonicalRoutesForMirroredPlatformDocumentation() {
        assertEquals(
                "https://docs.docker.com/engine/swarm/",
                DocsSourceRegistry.normalizeDocUrl("file:///data/docs/docker/engine/swarm/index.html"));
        assertEquals(
                "https://doc.traefik.io/traefik/reference/install-configuration/providers/swarm/",
                DocsSourceRegistry.normalizeDocUrl(
                        "file:///data/docs/traefik/reference/install-configuration/providers/swarm/index.html"));
        assertEquals(
                "https://docs.dokploy.com/docs/core/backups",
                DocsSourceRegistry.normalizeDocUrl("file:///data/docs/dokploy/docs/core/backups.html"));
        assertEquals(
                "https://infisical.com/docs/integrations/platforms/infisical-agent",
                DocsSourceRegistry.normalizeDocUrl(
                        "file:///data/docs/infisical/integrations/platforms/infisical-agent.html"));
        assertEquals(
                "https://docs.doppler.com/docs/mcp",
                DocsSourceRegistry.normalizeDocUrl("file:///data/docs/doppler/docs/mcp.html"));
        assertEquals(
                "https://docs.doppler.com/reference/projects-list",
                DocsSourceRegistry.normalizeDocUrl("file:///data/docs/doppler/reference/projects-list.html"));
        assertEquals(
                "https://docs.doppler.com/changelog/june-2026",
                DocsSourceRegistry.normalizeDocUrl("file:///data/docs/doppler/changelog/june-2026.html"));
        assertEquals(
                DocumentationCitationPathStyle.EXTENSIONLESS_HTML,
                DocsSourceRegistry.documentationSourceForRelativeMirrorPath("docker")
                        .orElseThrow()
                        .citationPathStyle());
    }

    @Test
    void keepsArchiveCitationsOnTheVerifiedArtifactUrl() {
        assertEquals(
                "https://repo.maven.apache.org/maven2/com/fasterxml/jackson/core/jackson-databind/2.22.2/jackson-databind-2.22.2-javadoc.jar",
                DocsSourceRegistry.normalizeDocUrl(
                        "file:///data/docs/jackson/2.22.2/api/com/fasterxml/jackson/databind/ObjectMapper.html"));
    }

    @Test
    void resolvesCanonicalCitationFromArbitraryDocumentationRoot(@TempDir Path temporaryDirectory) {
        DocumentationSource documentationSource = DocsSourceRegistry.documentationSources().stream()
                .filter(source -> "spring-ai-reference".equals(source.relativeMirrorPath()))
                .findFirst()
                .orElseThrow();
        Path arbitraryMirrorRoot =
                temporaryDirectory.resolve("java-chat-corpus").resolve(documentationSource.relativeMirrorPath());
        Path arbitraryDocumentFile = arbitraryMirrorRoot.resolve("api/chat-client.html");

        assertEquals(
                documentationSource.citationBaseUrl() + "api/chat-client.html",
                DocsSourceRegistry.resolveMirroredPath(arbitraryMirrorRoot, arbitraryDocumentFile)
                        .orElseThrow());
    }

    @Test
    void resolvesExtensionlessDocumentationCitationRoutes(@TempDir Path temporaryDirectory) {
        DocumentationSource anthropicApiDocumentation = DocsSourceRegistry.documentationSources().stream()
                .filter(documentationSource -> "anthropic/api".equals(documentationSource.relativeMirrorPath()))
                .findFirst()
                .orElseThrow();
        Path anthropicApiMirrorRoot = temporaryDirectory.resolve(anthropicApiDocumentation.relativeMirrorPath());

        assertEquals(
                "https://platform.claude.com/docs/en/build-with-claude/overview",
                DocsSourceRegistry.resolveMirroredPath(
                                anthropicApiMirrorRoot,
                                anthropicApiMirrorRoot.resolve("build-with-claude/overview.html"))
                        .orElseThrow());
    }

    @Test
    void mapsSplitSpringFrameworkApiMirrorWithoutAggregateAlias() {
        assertEquals(
                DocsSourceRegistry.SPRING_FRAMEWORK_API_BASE + "org/springframework/context/ApplicationContext.html",
                DocsSourceRegistry.normalizeDocUrl(
                        "file:///data/docs/spring-framework-api/org/springframework/context/ApplicationContext.html"));
        assertEquals(
                "(local file path redacted)",
                DocsSourceRegistry.normalizeDocUrl(
                        "file:///data/docs/spring-framework-complete/org/springframework/context/ApplicationContext.html"));
    }

    @Test
    void resolvesFlatSplitSpringFrameworkMirrorPathsWithoutDuplicatingRemoteRoots(@TempDir Path temporaryDirectory) {
        Path frameworkReferenceRoot = temporaryDirectory.resolve("spring-framework-reference");
        Path frameworkApiRoot = temporaryDirectory.resolve("spring-framework-api");

        assertEquals(
                "https://docs.spring.io/spring-framework/reference/web/webflux.html",
                DocsSourceRegistry.resolveMirroredPath(
                                frameworkReferenceRoot, frameworkReferenceRoot.resolve("web/webflux.html"))
                        .orElseThrow());
        assertEquals(
                DocsSourceRegistry.SPRING_FRAMEWORK_API_BASE + "org/springframework/context/ApplicationContext.html",
                DocsSourceRegistry.resolveMirroredPath(
                                frameworkApiRoot,
                                frameworkApiRoot.resolve("org/springframework/context/ApplicationContext.html"))
                        .orElseThrow());
    }

    @Test
    void resolvesLegacySpringBootReferenceMirrorToCanonicalSqlDocumentation(@TempDir Path temporaryDirectory) {
        Path springBootReferenceRoot = temporaryDirectory.resolve("spring-boot");

        assertEquals(
                "https://docs.spring.io/spring-boot/reference/data/sql.html",
                DocsSourceRegistry.resolveMirroredPath(
                                springBootReferenceRoot, springBootReferenceRoot.resolve("reference/data/sql.html"))
                        .orElseThrow());
        assertEquals(
                "https://docs.spring.io/spring-boot/reference/data/sql.html",
                DocsSourceRegistry.normalizeDocUrl(
                        "https://docs.spring.io/spring-boot/reference/reference/data/sql.html"));
    }

    @Test
    void normalizesEmbeddedSpringFrameworkCurrentReferenceLayout() {
        assertEquals(
                SPRING_DOCS_URL_PREFIX + "spring-framework/reference/web/webflux.html",
                DocsSourceRegistry.normalizeDocUrl(EMBEDDED_SPRING_DOCS_LOCAL_URL_PREFIX
                        + "spring-framework/docs/current/reference/6.2.5/web/webflux.html"));
    }

    @Test
    void normalizesEmbeddedSpringFrameworkReferenceRootLayout() {
        assertEquals(
                SPRING_DOCS_URL_PREFIX + "spring-framework/reference/core/beans.html",
                DocsSourceRegistry.normalizeDocUrl(
                        EMBEDDED_SPRING_DOCS_LOCAL_URL_PREFIX + "spring-framework/reference/6.2.5/core/beans.html"));
    }

    @Test
    void normalizesEmbeddedSpringFrameworkApiCurrentLayout() {
        assertEquals(
                SPRING_DOCS_URL_PREFIX
                        + "spring-framework/docs/current/javadoc-api/org/springframework/context/ApplicationContext.html",
                DocsSourceRegistry.normalizeDocUrl(
                        EMBEDDED_SPRING_DOCS_LOCAL_URL_PREFIX
                                + "spring-framework/docs/current/api/current/javadoc-api/org/springframework/context/ApplicationContext.html"));
    }

    @Test
    void normalizesEmbeddedSpringFrameworkJavadocJavaLayout() {
        assertEquals(
                SPRING_DOCS_URL_PREFIX
                        + "spring-framework/docs/current/javadoc-api/org/springframework/core/ResolvableType.html",
                DocsSourceRegistry.normalizeDocUrl(
                        EMBEDDED_SPRING_DOCS_LOCAL_URL_PREFIX
                                + "spring-framework/docs/current/javadoc-api/java/org/springframework/core/ResolvableType.html"));
    }

    @Test
    void normalizesEmbeddedSpringBootCurrentReferenceLayout() {
        assertEquals(
                SPRING_DOCS_URL_PREFIX + "spring-boot/reference/web/servlet.html",
                DocsSourceRegistry.normalizeDocUrl(EMBEDDED_SPRING_DOCS_LOCAL_URL_PREFIX
                        + "spring-boot/docs/current/reference/3.5.0/web/servlet.html"));
    }

    @Test
    void normalizesEmbeddedSpringBootReferenceRootLayout() {
        assertEquals(
                SPRING_DOCS_URL_PREFIX + "spring-boot/reference/using/structuring-your-code.html",
                DocsSourceRegistry.normalizeDocUrl(EMBEDDED_SPRING_DOCS_LOCAL_URL_PREFIX
                        + "spring-boot/reference/3.5.0/using/structuring-your-code.html"));
    }

    @Test
    void normalizesEmbeddedSpringBootApiJavaLayout() {
        assertEquals(
                SPRING_DOCS_URL_PREFIX + "spring-boot/docs/current/api/org/springframework/boot/SpringApplication.html",
                DocsSourceRegistry.normalizeDocUrl(EMBEDDED_SPRING_DOCS_LOCAL_URL_PREFIX
                        + "spring-boot/docs/current/api/java/org/springframework/boot/SpringApplication.html"));
    }

    private static void withSystemProperty(String settingKey, String configuredBaseUrl, Runnable testAssertion) {
        initializeRegistryBeforePropertyMutation();
        String originalBaseUrl = System.getProperty(settingKey);
        System.setProperty(settingKey, configuredBaseUrl);
        try {
            testAssertion.run();
        } finally {
            restoreSystemProperty(settingKey, originalBaseUrl);
        }
    }

    private static void withoutSystemProperty(String settingKey, Runnable testAssertion) {
        initializeRegistryBeforePropertyMutation();
        String originalBaseUrl = System.getProperty(settingKey);
        System.clearProperty(settingKey);
        try {
            testAssertion.run();
        } finally {
            restoreSystemProperty(settingKey, originalBaseUrl);
        }
    }

    private static void restoreSystemProperty(String settingKey, String originalBaseUrl) {
        if (originalBaseUrl == null) {
            System.clearProperty(settingKey);
            return;
        }
        System.setProperty(settingKey, originalBaseUrl);
    }

    private static void initializeRegistryBeforePropertyMutation() {
        DocsSourceRegistry.javaApiDocumentationSources();
    }
}
