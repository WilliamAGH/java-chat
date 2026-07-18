package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.util.Objects;
import java.util.Set;
import java.util.StringTokenizer;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

/**
 * Rejects empty HTML extraction or an explicitly structured access-error document.
 */
@Service
public class HtmlContentGuard {
    private static final String CLOUDFLARE_CHALLENGE_SCRIPT_SELECTOR = "script[src*=\"/cdn-cgi/challenge-platform/\"]";
    private static final String CLOUDFLARE_CHALLENGE_STRUCTURE_SELECTOR =
            "form#challenge-form, #challenge-stage, #cf-chl-widget";
    private static final String CLOUDFLARE_CHALLENGE_TITLE = "just a moment...";
    private static final String CLOUDFLARE_ERROR_STRUCTURE_SELECTOR = "#cf-error-details .cf-error-code";
    private static final String SERVER_ERROR_HEADING_SELECTOR = "body > h1, body > center > h1";
    private static final String APPLICATION_ERROR_HEADING_SELECTOR = "body > main > h1";
    private static final String ROBOTS_METADATA_SELECTOR = "head > meta[name=robots][content]";
    private static final String ROBOTS_NOINDEX_DIRECTIVE = "noindex";
    private static final Set<String> EXACT_ERROR_PAGE_TITLES =
            Set.of("access denied", "403 forbidden", "404 not found", "page not found");

    /**
     * Evaluates fetch and structural evidence before extracted text can reach chunking.
     *
     * @param guardInput extracted text with authoritative source evidence
     * @return acceptance decision with a diagnostic reason when rejected
     * @throws NullPointerException when guardInput is null
     */
    public GuardDecision evaluate(GuardInput guardInput) {
        GuardInput requiredGuardInput = Objects.requireNonNull(guardInput, "guardInput");
        HtmlSourceEvidence sourceEvidence = requiredGuardInput.sourceEvidence();
        if (sourceEvidence.cloudflareChallengeStructure()) {
            return GuardDecision.rejected("source document has Cloudflare challenge structure");
        }
        if (sourceEvidence.cloudflareErrorStructure()) {
            return GuardDecision.rejected("source document has Cloudflare error structure");
        }
        if (hasExplicitErrorPageStructure(sourceEvidence)) {
            return GuardDecision.rejected("source document has explicit error-page structure");
        }

        if (requiredGuardInput.bodyText().isBlank()) {
            return GuardDecision.rejected("empty body text");
        }
        return GuardDecision.accepted();
    }

    private static boolean hasExplicitErrorPageStructure(HtmlSourceEvidence sourceEvidence) {
        if (!EXACT_ERROR_PAGE_TITLES.contains(sourceEvidence.normalizedTitle())) {
            return false;
        }

        return sourceEvidence.normalizedTitle().equals(sourceEvidence.normalizedServerErrorHeading())
                || (sourceEvidence.noIndexDirective()
                        && sourceEvidence.normalizedTitle().equals(sourceEvidence.normalizedApplicationErrorHeading()));
    }

    private static HtmlSourceEvidence projectSourceEvidence(Document parsedDocument) {
        Document requiredDocument = Objects.requireNonNull(parsedDocument, "parsedDocument");
        String normalizedTitle =
                AsciiTextNormalizer.toLowerAscii(requiredDocument.title().trim());
        boolean cloudflareChallengeStructure = CLOUDFLARE_CHALLENGE_TITLE.equals(normalizedTitle)
                && requiredDocument.selectFirst(CLOUDFLARE_CHALLENGE_SCRIPT_SELECTOR) != null
                && requiredDocument.selectFirst(CLOUDFLARE_CHALLENGE_STRUCTURE_SELECTOR) != null;
        return new HtmlSourceEvidence(
                normalizedTitle,
                cloudflareChallengeStructure,
                requiredDocument.selectFirst(CLOUDFLARE_ERROR_STRUCTURE_SELECTOR) != null,
                normalizedElementText(requiredDocument.selectFirst(SERVER_ERROR_HEADING_SELECTOR)),
                normalizedElementText(requiredDocument.selectFirst(APPLICATION_ERROR_HEADING_SELECTOR)),
                hasNoIndexDirective(requiredDocument));
    }

    private static boolean hasNoIndexDirective(Document parsedDocument) {
        for (Element robotsMetadata : parsedDocument.select(ROBOTS_METADATA_SELECTOR)) {
            StringTokenizer robotsDirectives = new StringTokenizer(robotsMetadata.attr("content"), ",");
            while (robotsDirectives.hasMoreTokens()) {
                String normalizedDirective = AsciiTextNormalizer.toLowerAscii(
                        robotsDirectives.nextToken().trim());
                if (ROBOTS_NOINDEX_DIRECTIVE.equals(normalizedDirective)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String normalizedElementText(Element sourceElement) {
        return sourceElement == null
                ? ""
                : AsciiTextNormalizer.toLowerAscii(sourceElement.text().trim());
    }

    /**
     * Carries the evidence required to decide whether fetched HTML is safe to index.
     *
     * <p>The constructor projects mutable parser state into immutable structural facts. Body phrases
     * never select an error classification.</p>
     */
    public static final class GuardInput {
        private final String bodyText;
        private final HtmlSourceEvidence sourceEvidence;

        /**
         * Projects the parsed document into immutable evidence before evaluation.
         *
         * @param bodyText extracted page text; null is treated as empty
         * @param parsedDocument non-null HTML document parsed at the ingestion boundary
         * @throws NullPointerException when parsedDocument is null
         */
        public GuardInput(String bodyText, Document parsedDocument) {
            this.bodyText = bodyText == null ? "" : bodyText;
            this.sourceEvidence = projectSourceEvidence(parsedDocument);
        }

        private String bodyText() {
            return bodyText;
        }

        private HtmlSourceEvidence sourceEvidence() {
            return sourceEvidence;
        }
    }

    private record HtmlSourceEvidence(
            String normalizedTitle,
            boolean cloudflareChallengeStructure,
            boolean cloudflareErrorStructure,
            String normalizedServerErrorHeading,
            String normalizedApplicationErrorHeading,
            boolean noIndexDirective) {}

    /**
     * Decision describing whether a page is safe to ingest.
     *
     * @param acceptable true when content is acceptable for ingestion
     * @param rejectionReason diagnostic reason when rejected
     */
    public record GuardDecision(boolean acceptable, String rejectionReason) {
        public GuardDecision {
            rejectionReason = rejectionReason == null ? "" : rejectionReason;
        }

        static GuardDecision accepted() {
            return new GuardDecision(true, "");
        }

        static GuardDecision rejected(String rejectionReason) {
            return new GuardDecision(false, rejectionReason == null ? "invalid content" : rejectionReason);
        }
    }
}
