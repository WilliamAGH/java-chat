package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.williamcallahan.javachat.domain.markdown.EnrichmentKindCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies system prompt content and its canonical documentation-version binding.
 */
class SystemPromptConfigTest {

    private static final int TEST_DOCUMENTATION_JDK_VERSION = 24;

    private SystemPromptConfig systemPromptConfig;

    @BeforeEach
    void createSystemPromptConfiguration() {
        AppProperties appProperties = new AppProperties();
        appProperties.getDocs().setJdkVersion(TEST_DOCUMENTATION_JDK_VERSION);
        systemPromptConfig = new SystemPromptConfig(appProperties);
    }

    @Test
    void shouldRequireSafeMarkerPlacementAndValidJavaFences() {
        String corePrompt = systemPromptConfig.getCoreSystemPrompt();

        assertAll(
                () -> assertTrue(corePrompt.contains(SystemPromptConfig.MARKER_PROSE_LINE_CLAUSE)),
                () -> assertTrue(corePrompt.contains(SystemPromptConfig.MARKER_CODE_BOUNDARY_CLAUSE)),
                () -> assertTrue(corePrompt.contains(SystemPromptConfig.JAVA_FENCE_VALIDITY_CLAUSE)));
    }

    @Test
    void shouldUseCanonicalDocumentationJdkVersion() {
        String corePrompt = systemPromptConfig.getCoreSystemPrompt();

        assertTrue(corePrompt.contains("Java " + TEST_DOCUMENTATION_JDK_VERSION));
    }

    @Test
    void shouldProjectEveryCanonicalMarkerIntoPrompts() {
        EnrichmentKindCatalog enrichmentKindCatalog = EnrichmentKindCatalog.load();
        String markerUsagePrompt = systemPromptConfig.getMarkerUsagePrompt();

        assertEquals(
                enrichmentKindCatalog.all().size(), markerUsagePrompt.lines().count());
        enrichmentKindCatalog
                .all()
                .forEach(presentation ->
                        assertTrue(markerUsagePrompt.contains("{{" + presentation.token() + ":Text here}}")));
    }
}
