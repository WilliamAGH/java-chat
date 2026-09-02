package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void shouldRequireExactlyOneTerminalOutcomePerGeneratedCandidate() {
        String corePrompt = systemPromptConfig.getCoreSystemPrompt();

        assertAll(
                () -> assertTrue(corePrompt.contains(SystemPromptConfig.GENERATED_CONTROL_FLOW_CLAUSE)),
                () -> assertTrue(corePrompt.contains("record exactly one terminal outcome for each candidate")),
                () -> assertTrue(corePrompt.contains("prevent fall-through to success")),
                () -> assertTrue(corePrompt.contains("value returned by `getOrElse`")));
    }

    @Test
    void shouldAnswerUnsupportedClaimsWithClearlyLabeledGeneralKnowledge() {
        String corePrompt = systemPromptConfig.getCoreSystemPrompt();

        assertAll(
                () -> assertTrue(corePrompt.contains(SystemPromptConfig.SOURCE_FIDELITY_CLAUSE)),
                () -> assertTrue(corePrompt.contains("at the requested major version")),
                () -> assertTrue(corePrompt.contains("explicitly labeled as adjacent same-family evidence")),
                () -> assertTrue(corePrompt.contains("nearest lower and higher records from the same source family")),
                () -> assertTrue(corePrompt.contains("Never substitute another dependency or related project")),
                () -> assertTrue(corePrompt.contains("missing evidence changes provenance, not answer availability")),
                () -> assertTrue(corePrompt.contains("Never refuse an in-scope question")),
                () -> assertTrue(
                        corePrompt.contains("answer the question as well as possible using general knowledge")),
                () -> assertTrue(corePrompt.contains("Do not write a source-availability label")),
                () -> assertTrue(corePrompt.contains("Never imply that the Sources list verifies")),
                () -> assertFalse(corePrompt.contains("Source unavailable:")));
    }

    /**
     * A same-major documentation record answers the question rather than being demoted to general
     * knowledge, so a patch-level corpus drift stops emitting a spurious unavailable-source notice.
     */
    @Test
    void shouldGroundSameMajorRecordsInsteadOfDemandingAnExactVersionMatch() {
        String corePrompt = systemPromptConfig.getCoreSystemPrompt();

        assertAll(
                () -> assertTrue(corePrompt.contains(
                        "covers the claimed library or source family at the requested major version")),
                () -> assertFalse(corePrompt.contains("the exact requested version")),
                () -> assertFalse(corePrompt.contains("Never substitute a nearby patch version")));
    }

    /**
     * Loosening the grounding test to the major version is only safe while every claim still carries
     * the version that backs it, so answers drawn from different majors can never silently merge.
     */
    @Test
    void shouldAttributeEveryGroundedClaimToItsOwnSourceVersion() {
        String corePrompt = systemPromptConfig.getCoreSystemPrompt();

        assertAll(
                () -> assertTrue(corePrompt.contains("Name that record's exact version inline with the claim")),
                () -> assertTrue(
                        corePrompt.contains("when it differs from the version the reader asked about, name both")),
                () -> assertTrue(
                        corePrompt.contains("never merge SOURCE RECORDS from different major versions into one claim")),
                () -> assertTrue(corePrompt.contains("attribute each major separately and state how they differ")),
                () -> assertTrue(corePrompt.contains("state that change and the release that introduced it")));
    }

    @Test
    void shouldUseCanonicalDocumentationJdkVersion() {
        String corePrompt = systemPromptConfig.getCoreSystemPrompt();

        assertTrue(corePrompt.contains("Java " + TEST_DOCUMENTATION_JDK_VERSION));
    }

    @Test
    void shouldAnswerRetrievedPlatformDocumentationAndDeclineNonSoftwareTopics() {
        String corePrompt = systemPromptConfig.getCoreSystemPrompt();
        String lowQualityPrompt = systemPromptConfig.getLowQualitySearchPrompt();

        assertAll(
                () -> assertTrue(corePrompt.contains("## Scope")),
                () -> assertTrue(corePrompt.contains("containers, deployment platforms, infrastructure")),
                () -> assertTrue(corePrompt.contains("authentication, databases, and developer tools")),
                () -> assertTrue(corePrompt.contains("Apply Java defaults and Java-specific guidance only")),
                () -> assertTrue(corePrompt.contains("do NOT answer its substance")),
                () -> assertTrue(corePrompt.contains("do not refuse to answer in-scope questions")),
                () -> assertTrue(lowQualityPrompt.contains("off-topic questions are still declined")));
    }
}
