package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.williamcallahan.javachat.service.ingestion.HtmlContentGuard.GuardDecision;
import org.junit.jupiter.api.Test;

/** Verifies the content guard rejects known non-content placeholders without heuristic quality gates. */
class HtmlContentGuardTest {

    private static final int CODE_EXAMPLE_REPETITIONS = 16;
    private static final String SHORT_DOCUMENTATION_PROSE =
            "Java I/O lets applications read and write bytes, characters, and files. "
                    + "Choose buffered streams when repeated small reads would otherwise reach the filesystem.";
    private static final String CODE_HEAVY_DOCUMENTATION = """
            ```java
            long[] fibonacci = {0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89, 144};
            ```
            """.repeat(CODE_EXAMPLE_REPETITIONS);
    private static final String LOADING_PAGE_PLACEHOLDER = "Loading page…";
    private static final String LEGITIMATE_CLASS_LOADING_PROSE =
            "Class loading is covered on this reference page alongside initialization and linkage.";
    private static final String ENABLE_JAVASCRIPT_PLACEHOLDER = "Please enable JavaScript to continue.";

    private final HtmlContentGuard htmlContentGuard = new HtmlContentGuard();

    @Test
    void acceptsShortLegitimateDocumentationProse() {
        GuardDecision guardDecision = htmlContentGuard.evaluate(SHORT_DOCUMENTATION_PROSE);

        assertTrue(guardDecision.acceptable());
    }

    @Test
    void acceptsCodeHeavyDocumentationText() {
        GuardDecision guardDecision = htmlContentGuard.evaluate(CODE_HEAVY_DOCUMENTATION);

        assertTrue(guardDecision.acceptable());
    }

    @Test
    void acceptsUnrelatedLoadingAndPageTerms() {
        GuardDecision guardDecision = htmlContentGuard.evaluate(LEGITIMATE_CLASS_LOADING_PROSE);

        assertTrue(guardDecision.acceptable());
    }

    @Test
    void rejectsEmptyBodyText() {
        GuardDecision guardDecision = htmlContentGuard.evaluate("");

        assertFalse(guardDecision.acceptable());
    }

    @Test
    void rejectsLoadingPagePlaceholder() {
        GuardDecision guardDecision = htmlContentGuard.evaluate(LOADING_PAGE_PLACEHOLDER);

        assertFalse(guardDecision.acceptable());
    }

    @Test
    void rejectsEnableJavaScriptPlaceholder() {
        GuardDecision guardDecision = htmlContentGuard.evaluate(ENABLE_JAVASCRIPT_PLACEHOLDER);

        assertFalse(guardDecision.acceptable());
    }
}
