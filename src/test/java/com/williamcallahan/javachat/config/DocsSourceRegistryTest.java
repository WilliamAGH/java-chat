package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.williamcallahan.javachat.config.DocsSourceRegistry.DocumentationSource;
import com.williamcallahan.javachat.config.DocsSourceRegistry.JavaApiDocumentationSource;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Verifies JVM citation and provenance source behavior.
 */
class DocsSourceRegistryTest {
    private static final String ORACLE_JAVASE_BASE_SETTING = "ORACLE_JAVASE_BASE";
    private static final String TEST_ORACLE_JAVASE_BASE = "https://citations.example.test/java/";
    private static final String DEFAULT_TEST_BASE_URL = "https://fallback.example.test/";
    private static final String LEGACY_SPRING_FRAMEWORK_LOCAL_URL_PREFIX = "file:///data/docs/spring-framework/";
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
                        DocsSourceRegistry.documentationSources().stream().map(DocumentationSource::docSet),
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
            String expectedOfficialDocumentationUrl = documentationSource.citationBaseUrl() + "index.html";
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
    void mapsLegacySpringFrameworkLocalJavadocLayouts() {
        assertEquals(
                configuredSpringFrameworkCitationUrl(
                        "docs/current/javadoc-api/org/springframework/context/ApplicationContext.html"),
                DocsSourceRegistry.normalizeDocUrl(LEGACY_SPRING_FRAMEWORK_LOCAL_URL_PREFIX
                        + "docs/current/api/current/javadoc-api/org/springframework/context/ApplicationContext.html"));
        assertEquals(
                configuredSpringFrameworkCitationUrl(
                        "docs/current/javadoc-api/org/springframework/context/ApplicationContext.html"),
                DocsSourceRegistry.normalizeDocUrl(LEGACY_SPRING_FRAMEWORK_LOCAL_URL_PREFIX
                        + "api/current/javadoc-api/org/springframework/context/ApplicationContext.html"));
        assertEquals(
                configuredSpringFrameworkCitationUrl(
                        "docs/current/javadoc-api/org/springframework/context/ApplicationContext.html"),
                DocsSourceRegistry.normalizeDocUrl(LEGACY_SPRING_FRAMEWORK_LOCAL_URL_PREFIX
                        + "docs/current/javadoc-api/java/org/springframework/context/ApplicationContext.html"));
        assertEquals(
                configuredSpringFrameworkCitationUrl("javadoc-api/org/springframework/context/ApplicationContext.html"),
                DocsSourceRegistry.normalizeDocUrl(LEGACY_SPRING_FRAMEWORK_LOCAL_URL_PREFIX
                        + "javadoc-api/java/org/springframework/context/ApplicationContext.html"));
    }

    @Test
    void normalizesEmbeddedSpringFrameworkCurrentReferenceLayout() {
        assertEquals(
                SPRING_DOCS_URL_PREFIX + "spring-framework/reference/current/web/webflux.html",
                DocsSourceRegistry.normalizeDocUrl(EMBEDDED_SPRING_DOCS_LOCAL_URL_PREFIX
                        + "spring-framework/docs/current/reference/6.2.5/web/webflux.html"));
    }

    @Test
    void normalizesEmbeddedSpringFrameworkReferenceRootLayout() {
        assertEquals(
                SPRING_DOCS_URL_PREFIX + "spring-framework/reference/current/core/beans.html",
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
                SPRING_DOCS_URL_PREFIX + "spring-boot/reference/current/web/servlet.html",
                DocsSourceRegistry.normalizeDocUrl(EMBEDDED_SPRING_DOCS_LOCAL_URL_PREFIX
                        + "spring-boot/docs/current/reference/3.5.0/web/servlet.html"));
    }

    @Test
    void normalizesEmbeddedSpringBootReferenceRootLayout() {
        assertEquals(
                SPRING_DOCS_URL_PREFIX + "spring-boot/reference/current/using/structuring-your-code.html",
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

    private static String configuredSpringFrameworkCitationUrl(String relativeCitationPath) {
        String configuredBaseUrl = DocsSourceRegistry.SPRING_FRAMEWORK_BASE;
        return configuredBaseUrl.endsWith("/")
                ? configuredBaseUrl + relativeCitationPath
                : configuredBaseUrl + "/" + relativeCitationPath;
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
