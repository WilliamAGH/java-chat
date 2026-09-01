package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.williamcallahan.javachat.config.DocsSourceRegistry.DocumentationCitationPathStyle;
import com.williamcallahan.javachat.config.DocsSourceRegistry.DocumentationSource;
import com.williamcallahan.javachat.config.DocsSourceRegistry.JavaApiDocumentationSource;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
        String expectedBaseUrl =
                environmentBaseUrl == null || environmentBaseUrl.isBlank() ? DEFAULT_TEST_BASE_URL : environmentBaseUrl;

        withoutSystemProperty(
                ORACLE_JAVASE_BASE_SETTING,
                () -> assertEquals(
                        expectedBaseUrl,
                        DocsSourceRegistry.resolveRuntimeBaseUrl(ORACLE_JAVASE_BASE_SETTING, DEFAULT_TEST_BASE_URL)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void ignoresBlankSystemPropertyForRuntimeBaseUrl(String blankSystemProperty) {
        String environmentBaseUrl = System.getenv(ORACLE_JAVASE_BASE_SETTING);
        String expectedBaseUrl =
                environmentBaseUrl == null || environmentBaseUrl.isBlank() ? DEFAULT_TEST_BASE_URL : environmentBaseUrl;

        withSystemProperty(
                ORACLE_JAVASE_BASE_SETTING,
                blankSystemProperty,
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
    void resolvesExactAndAdjacentJavaApiDocumentationSources() {
        assertEquals(
                List.of("25"),
                DocsSourceRegistry.javaApiDocumentationSourcesForRelease("25").stream()
                        .map(JavaApiDocumentationSource::javaRelease)
                        .toList());
        assertEquals(
                List.of("21", "25"),
                DocsSourceRegistry.javaApiDocumentationSourcesForRelease("22").stream()
                        .map(JavaApiDocumentationSource::javaRelease)
                        .toList());
        assertEquals(
                List.of("21"),
                DocsSourceRegistry.javaApiDocumentationSourcesForRelease("20").stream()
                        .map(JavaApiDocumentationSource::javaRelease)
                        .toList());
        assertEquals(
                List.of("26"),
                DocsSourceRegistry.javaApiDocumentationSourcesForRelease("27").stream()
                        .map(JavaApiDocumentationSource::javaRelease)
                        .toList());
    }

    @Test
    void resolvesVersionedDependencyGapsWithinOneSourceFamily() {
        DocsSourceRegistry.VersionedDocumentationEvidence evidence = DocsSourceRegistry.versionedDocumentationEvidence(
                        "HikariCP 7.0.5 connection pooling")
                .orElseThrow();

        assertEquals("hikaricp", evidence.sourceFamily());
        assertEquals("7.0.5", evidence.requestedVersion());
        assertEquals(
                List.of("7.0.2", "7.1.0"),
                evidence.sources().stream().map(DocumentationSource::docVersion).toList());
        assertEquals(
                List.of("7.0.2"),
                DocsSourceRegistry.versionedDocumentationEvidence("HikariCP 7.0.2 connection pooling")
                        .orElseThrow()
                        .sources()
                        .stream()
                        .map(DocumentationSource::docVersion)
                        .toList());
        assertEquals(
                List.of("7.0.2"),
                DocsSourceRegistry.versionedDocumentationEvidence("HikariCP 6.9 connection pooling")
                        .orElseThrow()
                        .sources()
                        .stream()
                        .map(DocumentationSource::docVersion)
                        .toList());
        assertEquals(
                List.of("7.1.0"),
                DocsSourceRegistry.versionedDocumentationEvidence("HikariCP 8.0 connection pooling")
                        .orElseThrow()
                        .sources()
                        .stream()
                        .map(DocumentationSource::docVersion)
                        .toList());
        assertEquals(
                List.of("7.0.2", "7.1.0"),
                DocsSourceRegistry.versionedDocumentationEvidenceAll("Compare HikariCP 7.0.2 with HikariCP 7.1.0")
                        .stream()
                        .flatMap(versionEvidence -> versionEvidence.sources().stream())
                        .map(DocumentationSource::docVersion)
                        .toList());

        assertEquals(
                List.of("0.17.1-64e8b4f4caad"),
                DocsSourceRegistry.versionedDocumentationEvidence("Porkbun MCP 0.17.1").orElseThrow().sources().stream()
                        .map(DocumentationSource::docVersion)
                        .toList());

        DocsSourceRegistry.VersionedDocumentationEvidence jacksonEvidence =
                DocsSourceRegistry.versionedDocumentationEvidence("Jackson Databind 2.22.1 serialization")
                        .orElseThrow();
        assertEquals("jackson", jacksonEvidence.sourceFamily());
        assertEquals(
                List.of("2.21.2", "2.22.2"),
                jacksonEvidence.sources().stream()
                        .map(DocumentationSource::docVersion)
                        .toList());
        assertEquals(
                List.of("2.21.2", "2.22.2"),
                DocsSourceRegistry.versionedDocumentationEvidence("Jackson 2.22.1 serialization")
                        .orElseThrow()
                        .sources()
                        .stream()
                        .map(DocumentationSource::docVersion)
                        .toList());

        DocsSourceRegistry.VersionedDocumentationEvidence springAiEvidence =
                DocsSourceRegistry.versionedDocumentationEvidence("Spring AI 1.1.5 tool calling")
                        .orElseThrow();
        assertEquals("spring-ai", springAiEvidence.sourceFamily());
        assertEquals(
                List.of("1.1.2", "1.1.8"),
                springAiEvidence.sources().stream()
                        .map(DocumentationSource::docVersion)
                        .toList());
        assertEquals(
                List.of("1.1.2", "1.1.8"),
                DocsSourceRegistry.versionedDocumentationEvidence("Spring AI 1.1.5 examples")
                        .orElseThrow()
                        .sources()
                        .stream()
                        .map(DocumentationSource::docVersion)
                        .toList());

        assertTrue(DocsSourceRegistry.versionedDocumentationEvidence("Oracle 23 database")
                .isEmpty());
        assertTrue(DocsSourceRegistry.versionedDocumentationEvidence("IBM 24 database")
                .isEmpty());
        assertTrue(DocsSourceRegistry.versionedDocumentationEvidence("Python 100 examples")
                .isEmpty());
        assertEquals(
                List.of("7.1.0"),
                DocsSourceRegistry.versionedDocumentationEvidence("HikariCP 9 connection pooling")
                        .orElseThrow()
                        .sources()
                        .stream()
                        .map(DocumentationSource::docVersion)
                        .toList());
        assertEquals(
                List.of("25"),
                DocsSourceRegistry.versionedDocumentationEvidence("Oracle Java 25 release notes")
                        .orElseThrow()
                        .sources()
                        .stream()
                        .map(DocumentationSource::docVersion)
                        .toList());
        assertEquals(
                List.of("3.16"),
                DocsSourceRegistry.versionedDocumentationEvidence("Porkbun 3.16 DNS API")
                        .orElseThrow()
                        .sources()
                        .stream()
                        .map(DocumentationSource::docVersion)
                        .toList());
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
            String expectedOfficialDocumentationUrl;
            if (documentationSource.citationPathStyle() == DocumentationCitationPathStyle.JAVA_SOURCE) {
                expectedOfficialDocumentationUrl =
                        documentationSource.citationBaseUrl().replace("/blob/", "/tree/");
            } else {
                expectedOfficialDocumentationUrl = documentationSource
                                .citationBaseUrl()
                                .endsWith("/")
                        ? documentationSource.citationBaseUrl()
                                + documentationSource.citationPathStyle().citationRelativePath("index.html")
                        : documentationSource.citationBaseUrl();
            }
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
    void resolvesCanonicalRootsByStoredDocumentSetRatherThanMirrorPath() {
        assertEquals(
                List.of("https://docs.groovy-lang.org/docs/groovy-5.0.7/html/documentation/"),
                DocsSourceRegistry.citationBasesForDocSet("groovy"));
        assertEquals(List.of(), DocsSourceRegistry.citationBasesForDocSet("groovy/5.0.7"));
    }

    @Test
    void assignsDistinctStorageUrlsToJavaPagesThatShareOneCanonicalSourceFile(@TempDir Path temporaryDirectory) {
        Path mirrorRoot = temporaryDirectory.resolve("jackson/2.22.2/api");
        Path outerTypePage = mirrorRoot.resolve("com/fasterxml/jackson/databind/ObjectMapper.html");
        Path nestedTypePage = mirrorRoot.resolve("com/fasterxml/jackson/databind/ObjectMapper.DefaultTyping.html");
        Path singletonTypePage = mirrorRoot.resolve("com/fasterxml/jackson/databind/JsonNode.html");

        Map<Path, String> ingestionIdentities = DocsSourceRegistry.resolveMirroredIngestionIdentities(
                mirrorRoot, List.of(outerTypePage, nestedTypePage, singletonTypePage));
        String outerStorageUrl =
                ingestionIdentities.get(outerTypePage.toAbsolutePath().normalize());
        String nestedStorageUrl =
                ingestionIdentities.get(nestedTypePage.toAbsolutePath().normalize());
        String singletonStorageUrl =
                ingestionIdentities.get(singletonTypePage.toAbsolutePath().normalize());

        assertNotEquals(outerStorageUrl, nestedStorageUrl);
        assertEquals(
                DocsSourceRegistry.normalizeDocUrl(outerStorageUrl),
                DocsSourceRegistry.normalizeDocUrl(nestedStorageUrl));
        assertEquals(
                DocsSourceRegistry.resolveMirroredPath(mirrorRoot, outerTypePage)
                        .orElseThrow(),
                DocsSourceRegistry.normalizeDocUrl(outerStorageUrl));
        assertEquals(
                DocsSourceRegistry.resolveMirroredPath(mirrorRoot, singletonTypePage)
                        .orElseThrow(),
                singletonStorageUrl);

        String nestedSingletonIdentity = DocsSourceRegistry.resolveMirroredIngestionIdentities(
                        mirrorRoot, List.of(nestedTypePage))
                .get(nestedTypePage.toAbsolutePath().normalize());
        String nestedSubdirectoryIdentity = DocsSourceRegistry.resolveMirroredIngestionIdentities(
                        nestedTypePage.getParent(), List.of(nestedTypePage))
                .get(nestedTypePage.toAbsolutePath().normalize());
        assertEquals(nestedStorageUrl, nestedSingletonIdentity);
        assertEquals(nestedStorageUrl, nestedSubdirectoryIdentity);
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
                "https://developers.cloudflare.com/r2/objects/workers/workers-api-usage/",
                DocsSourceRegistry.normalizeDocUrl(
                        "file:///data/docs/cloudflare/r2/objects/workers/workers-api-usage/index.html"));
        assertEquals(
                "https://quarkus.io/guides/aesh/",
                DocsSourceRegistry.normalizeDocUrl("file:///data/docs/quarkus/aesh/index.html"));
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
    void preservesCanonicalUrlsForSingleDocumentMirrors() {
        assertEquals(
                "https://www.npmjs.com/package/@wcallahan/javachat-cli",
                DocsSourceRegistry.normalizeDocUrl("file:///data/docs/javachat-cli/index.html"));
        assertEquals(
                "https://www.mintlify.com/llms.txt",
                DocsSourceRegistry.normalizeDocUrl("file:///data/docs/mintlify/index.html"));
        assertEquals(
                "https://buildwithfern.com/llms.txt",
                DocsSourceRegistry.normalizeDocUrl("file:///data/docs/fern/index.html"));
        assertEquals(
                "https://temporal.io/llms.txt",
                DocsSourceRegistry.normalizeDocUrl("file:///data/docs/temporal/index.html"));
        assertEquals(
                "https://porkbun.com/api/json/v3/documentation",
                DocsSourceRegistry.normalizeDocUrl("file:///data/docs/porkbun/index.html"));
        assertEquals(
                "https://github.com/oborseth/Porkbun-MCP/blob/64e8b4f4caad75e99333733bca5f2987afee3c75/README.md",
                DocsSourceRegistry.normalizeDocUrl("file:///data/docs/porkbun-mcp/index.html"));
        assertEquals(
                "https://clerk.com/docs/llms-full.txt",
                DocsSourceRegistry.normalizeDocUrl("file:///data/docs/clerk/index.html"));
    }

    @Test
    void mapsArchiveBackedJavadocsToReadableHostedPages() {
        assertEquals(
                "https://github.com/FasterXML/jackson-databind/blob/jackson-databind-2.22.2/src/main/java/com/fasterxml/jackson/databind/ObjectMapper.java",
                DocsSourceRegistry.normalizeDocUrl(
                        "file:///data/docs/jackson/2.22.2/api/com/fasterxml/jackson/databind/ObjectMapper.html"));
        assertEquals(
                "https://github.com/FasterXML/jackson-databind/blob/jackson-databind-2.22.2/src/main/java/com/fasterxml/jackson/databind/ObjectMapper.java",
                DocsSourceRegistry.normalizeDocUrl(
                        "file:///data/docs/jackson/2.22.2/api/com/fasterxml/jackson/databind/ObjectMapper.DefaultTyping.html"));
        assertEquals(
                "https://github.com/FasterXML/jackson-databind/tree/jackson-databind-2.22.2/src/main/java/com/fasterxml/jackson/databind/",
                DocsSourceRegistry.normalizeDocUrl(
                        "file:///data/docs/jackson/2.22.2/api/com/fasterxml/jackson/databind/package-summary.html"));
        assertEquals(
                "https://github.com/FasterXML/jackson-databind/blob/jackson-databind-3.2.2/src/main/java/tools/jackson/databind/ObjectMapper.java",
                DocsSourceRegistry.normalizeDocUrl(
                        "file:///data/docs/jackson/3.2.2/api/tools/jackson/databind/ObjectMapper.html"));
        assertEquals(
                "https://javadoc.io/doc/org.projectlombok/lombok/1.18.46/lombok/Builder.html",
                DocsSourceRegistry.normalizeDocUrl("file:///data/docs/lombok/1.18.46/api/lombok/Builder.html"));
    }

    @Test
    void restoresCanonicalDirectoryRoutesForTheJooqManual() {
        assertEquals(
                "https://www.jooq.org/doc/3.21.7/manual/sql-building/sql-statements/select-statement/",
                DocsSourceRegistry.normalizeDocUrl(
                        "file:///data/docs/jooq/3.21/manual/sql-building/sql-statements/select-statement/index.html"));
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
