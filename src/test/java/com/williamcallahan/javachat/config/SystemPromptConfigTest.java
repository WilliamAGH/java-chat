package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void shouldTeachMarkerSyntaxAndPresentationPurpose() {
        String markerUsagePrompt = systemPromptConfig.getMarkerUsagePrompt();

        assertAll(
                () -> assertTrue(markerUsagePrompt.contains("{{hint:Text here}}")),
                () -> assertTrue(markerUsagePrompt.contains("{{background:Text here}}")),
                () -> assertTrue(markerUsagePrompt.contains("{{reminder:Text here}}")),
                () -> assertTrue(markerUsagePrompt.contains("{{warning:Text here}}")),
                () -> assertTrue(markerUsagePrompt.contains("{{example:Text here}}")),
                () -> assertTrue(markerUsagePrompt.contains("Helpful Hints")),
                () -> assertTrue(markerUsagePrompt.contains("Background Context")),
                () -> assertTrue(markerUsagePrompt.contains("Important Reminders")),
                () -> assertTrue(markerUsagePrompt.contains("Warning")),
                () -> assertTrue(markerUsagePrompt.contains("Example")));
    }
}
