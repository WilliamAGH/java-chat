package com.williamcallahan.javachat.service.ingestion;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.williamcallahan.javachat.service.ingestion.HtmlContentGuard.GuardDecision;
import com.williamcallahan.javachat.service.ingestion.HtmlContentGuard.GuardInput;
import java.util.Arrays;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

/** Verifies the content guard rejects explicit error structures without text-quality heuristics. */
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
    private static final String LEGITIMATE_ERROR_TERMINOLOGY =
            "Spring Security documentation explains access denied handlers, authentication challenges, and HTTP 404.";
    private static final String NONBLANK_ERROR_PAGE_TEXT =
            "The requested documentation page cannot be displayed, but this response contains substantial prose.";
    private static final String INDEXABLE_DOCUMENT_HTML = """
            <html>
              <head><title>Java Documentation</title></head>
              <body><main><h1>Java Documentation</h1><p>Reference material.</p></main></body>
            </html>
            """;

    private final HtmlContentGuard htmlContentGuard = new HtmlContentGuard();

    @Test
    void acceptsShortLegitimateDocumentationProse() {
        GuardDecision guardDecision = htmlContentGuard.evaluate(indexableGuardInput(SHORT_DOCUMENTATION_PROSE));

        assertTrue(guardDecision.acceptable());
    }

    @Test
    void acceptsCodeHeavyDocumentationText() {
        GuardDecision guardDecision = htmlContentGuard.evaluate(indexableGuardInput(CODE_HEAVY_DOCUMENTATION));

        assertTrue(guardDecision.acceptable());
    }

    @Test
    void acceptsDocumentationContainingErrorTerminologyWhenSourceEvidenceIsIndexable() {
        GuardDecision guardDecision = htmlContentGuard.evaluate(indexableGuardInput(LEGITIMATE_ERROR_TERMINOLOGY));

        assertTrue(guardDecision.acceptable());
    }

    @Test
    void rejectsEmptyBodyText() {
        GuardDecision guardDecision = htmlContentGuard.evaluate(indexableGuardInput(""));

        assertFalse(guardDecision.acceptable());
    }

    @Test
    void rejectsNonblankAccessDeniedDocumentFromExactStructure() {
        Document accessDeniedDocument = Jsoup.parse("""
                <html>
                  <head><title>Access Denied</title></head>
                  <body><h1>Access Denied</h1><p>Reference identifier.</p></body>
                </html>
                """);

        GuardDecision guardDecision =
                htmlContentGuard.evaluate(new GuardInput(NONBLANK_ERROR_PAGE_TEXT, accessDeniedDocument));

        assertFalse(guardDecision.acceptable());
    }

    @Test
    void rejectsNonblankServerNotFoundDocumentFromExactStructure() {
        Document notFoundDocument = Jsoup.parse("""
                <html>
                  <head><title>404 Not Found</title></head>
                  <body><center><h1>404 Not Found</h1></center><hr><center>nginx</center></body>
                </html>
                """);

        GuardDecision guardDecision =
                htmlContentGuard.evaluate(new GuardInput(NONBLANK_ERROR_PAGE_TEXT, notFoundDocument));

        assertFalse(guardDecision.acceptable());
    }

    @Test
    void rejectsCloudflareChallengeDocumentFromExactStructure() {
        Document challengeDocument = Jsoup.parse("""
                <html>
                  <head><title>Just a moment...</title></head>
                  <body>
                    <form id="challenge-form"><div id="challenge-stage"></div></form>
                    <script src="/cdn-cgi/challenge-platform/h/g/orchestrate/chl_page/v1"></script>
                  </body>
                </html>
                """);

        GuardDecision guardDecision =
                htmlContentGuard.evaluate(new GuardInput(NONBLANK_ERROR_PAGE_TEXT, challengeDocument));

        assertFalse(guardDecision.acceptable());
    }

    @Test
    void rejectsApplicationNotFoundDocumentFromExactStructure() {
        Document notFoundDocument = Jsoup.parse("""
                <html>
                  <head><title>Page Not Found</title><meta name="robots" content="noindex"></head>
                  <body><main><h1>Page Not Found</h1><p>Return to the documentation home page.</p></main></body>
                </html>
                """);

        GuardDecision guardDecision =
                htmlContentGuard.evaluate(new GuardInput(NONBLANK_ERROR_PAGE_TEXT, notFoundDocument));

        assertFalse(guardDecision.acceptable());
    }

    @Test
    void rejectsApplicationNotFoundDocumentWhenNoindexAppearsInDirectiveList() {
        Document notFoundDocument = Jsoup.parse("""
                <html>
                  <head><title>Page Not Found</title><meta name="robots" content="NOINDEX, nofollow"></head>
                  <body><main><h1>Page Not Found</h1><p>Return to the documentation home page.</p></main></body>
                </html>
                """);

        GuardDecision guardDecision =
                htmlContentGuard.evaluate(new GuardInput(NONBLANK_ERROR_PAGE_TEXT, notFoundDocument));

        assertFalse(guardDecision.acceptable());
    }

    @Test
    void rejectsMissingParsedDocumentEvidence() {
        assertThrows(NullPointerException.class, () -> new GuardInput(SHORT_DOCUMENTATION_PROSE, null));
    }

    @Test
    void guardInputStoresOnlyImmutableProjectedEvidence() {
        assertFalse(Arrays.stream(GuardInput.class.getDeclaredFields())
                .anyMatch(guardInputField -> Document.class.isAssignableFrom(guardInputField.getType())));
    }

    private static GuardInput indexableGuardInput(String bodyText) {
        return new GuardInput(bodyText, Jsoup.parse(INDEXABLE_DOCUMENT_HTML));
    }
}
