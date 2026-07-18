package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import org.springframework.stereotype.Service;

/**
 * Rejects empty and JavaScript-placeholder extracted HTML text before chunking.
 */
@Service
public class HtmlContentGuard {

    private static final String GUARD_LOADING_PAGE_TOKEN = "loading page";
    private static final String GUARD_ENABLE_JS_TOKEN = "enable javascript";

    /**
     * Evaluates the extracted page text and returns a typed decision.
     *
     * @param bodyText extracted text (null treated as empty)
     * @return acceptance decision with a diagnostic reason when rejected
     */
    public GuardDecision evaluate(String bodyText) {
        String normalizedBody = bodyText == null ? "" : bodyText;
        if (normalizedBody.isBlank()) {
            return GuardDecision.rejected("empty body text");
        }

        String lowered = AsciiTextNormalizer.toLowerAscii(normalizedBody);
        boolean hasLoadingPage = lowered.contains(GUARD_LOADING_PAGE_TOKEN);
        boolean hasEnableJavascript = lowered.contains(GUARD_ENABLE_JS_TOKEN);
        if (hasLoadingPage || hasEnableJavascript) {
            return GuardDecision.rejected("page appears to require JavaScript (loading/enable javascript)");
        }

        return GuardDecision.accepted();
    }

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

        static GuardDecision rejected(String reason) {
            return new GuardDecision(false, reason == null ? "invalid content" : reason);
        }
    }
}
