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
                () -> assertTrue(corePrompt.contains("{{hint:Text here}} (Helpful Hints)")),
                () -> assertTrue(corePrompt.contains("{{background:Text here}} (Background Context)")),
                () -> assertTrue(corePrompt.contains("{{reminder:Text here}} (Important Reminders)")),
                () -> assertTrue(corePrompt.contains("{{warning:Text here}} (Warning)")),
                () -> assertTrue(corePrompt.contains("{{example:Text here}} (Example)")),
                () -> assertTrue(corePrompt.contains(SystemPromptConfig.MARKER_PROSE_LINE_CLAUSE)),
                () -> assertTrue(corePrompt.contains(SystemPromptConfig.MARKER_CODE_BOUNDARY_CLAUSE)),
                () -> assertTrue(corePrompt.contains("Marker syntax is not valid source code")),
                () -> assertTrue(corePrompt.contains("A fenced block containing marker syntax")),
                () -> assertTrue(corePrompt.contains(SystemPromptConfig.JAVA_FENCE_VALIDITY_CLAUSE)),
                () -> assertTrue(corePrompt.contains("every multi-line Java example entirely inside one fenced")),
                () -> assertTrue(corePrompt.contains("Never emit part of a declaration or method as prose")),
                () -> assertTrue(corePrompt.contains("Finish the complete example before an enrichment marker")),
                () -> assertTrue(corePrompt.contains("Never split one example across multiple fences")),
                () -> assertTrue(corePrompt.contains("never emit a raw type")),
                () -> assertTrue(corePrompt.contains("Resolve every checked exception along every code path")),
                () -> assertTrue(corePrompt.contains("restore interruption with `Thread.currentThread().interrupt()`")),
                () -> assertTrue(corePrompt.contains("public static void main(String[] args)")));
    }

    @Test
    void shouldDistinguishVirtualThreadCarrierOccupancyFromPinning() {
        String corePrompt = systemPromptConfig.getCoreSystemPrompt();

        assertAll(
                () -> assertTrue(corePrompt.contains(SystemPromptConfig.VIRTUAL_THREAD_SEMANTICS_CLAUSE)),
                () -> assertTrue(corePrompt.contains("that is not pinning")),
                () -> assertTrue(corePrompt.contains("For Java 24 and later")));
    }

    @Test
    void shouldUseCanonicalDocumentationJdkVersion() {
        String corePrompt = systemPromptConfig.getCoreSystemPrompt();

        assertTrue(corePrompt.contains("Java " + TEST_DOCUMENTATION_JDK_VERSION));
    }

    @Test
    void shouldDeclineOffTopicQuestionsWithinScopeRules() {
        String corePrompt = systemPromptConfig.getCoreSystemPrompt();
        String lowQualityPrompt = systemPromptConfig.getLowQualitySearchPrompt();

        assertAll(
                () -> assertTrue(corePrompt.contains("## Scope")),
                () -> assertTrue(corePrompt.contains("do NOT answer its substance")),
                () -> assertTrue(corePrompt.contains("do not refuse to answer in-scope questions")),
                () -> assertTrue(lowQualityPrompt.contains("off-topic questions are still declined")));
    }
}
