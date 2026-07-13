package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Verifies that the core prompt protects prose enrichment markers and Java code blocks.
 */
class SystemPromptConfigTest {

    private static final String TEST_JDK_VERSION = "25";

    @Test
    void shouldRequireSafeMarkerPlacementAndValidJavaFences() {
        SystemPromptConfig systemPromptConfig = new SystemPromptConfig();
        ReflectionTestUtils.setField(systemPromptConfig, "jdkVersion", TEST_JDK_VERSION);

        String corePrompt = systemPromptConfig.getCoreSystemPrompt();

        assertAll(
                () -> assertTrue(corePrompt.contains(SystemPromptConfig.MARKER_PROSE_LINE_CLAUSE)),
                () -> assertTrue(corePrompt.contains(SystemPromptConfig.MARKER_CODE_BOUNDARY_CLAUSE)),
                () -> assertTrue(corePrompt.contains(SystemPromptConfig.JAVA_FENCE_VALIDITY_CLAUSE)));
    }
}
