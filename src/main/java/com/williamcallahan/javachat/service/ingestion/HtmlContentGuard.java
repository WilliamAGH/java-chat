package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Validates extracted HTML text before chunking to prevent indexing "loading" placeholders
 * or other non-content pages.
 */
@Service
public class HtmlContentGuard {

    private static final int MIN_TEXT_LENGTH = 1000;
    private static final int MIN_WORD_COUNT = 150;
    private static final double MIN_ALPHA_RATIO = 0.4;

    private static final String GUARD_LOADING_TOKEN = "loading";
    private static final String GUARD_PAGE_TOKEN = "page";
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

        int textLength = normalizedBody.length();
        if (textLength < MIN_TEXT_LENGTH) {
            return GuardDecision.rejected("body text too short (length=" + textLength + ")");
        }

        WordAlphaStats stats = scanWordAndAlphaStats(normalizedBody);
        if (stats.wordCount() < MIN_WORD_COUNT) {
            return GuardDecision.rejected("word count too low (words=" + stats.wordCount() + ")");
        }
        if (stats.alphaRatio() < MIN_ALPHA_RATIO) {
            return GuardDecision.rejected(
                    String.format(Locale.ROOT, "alpha ratio too low (ratio=%.2f)", stats.alphaRatio()));
        }

        String lowered = AsciiTextNormalizer.toLowerAscii(normalizedBody);
        boolean hasLoadingPage = lowered.contains(GUARD_LOADING_TOKEN) && lowered.contains(GUARD_PAGE_TOKEN);
        boolean hasEnableJavascript = lowered.contains(GUARD_ENABLE_JS_TOKEN);
        if (hasLoadingPage || hasEnableJavascript) {
            return GuardDecision.rejected("page appears to require JavaScript (loading/enable javascript)");
        }

        return GuardDecision.accepted();
    }

    private static WordAlphaStats scanWordAndAlphaStats(String bodyText) {
        Objects.requireNonNull(bodyText, "bodyText");

        int wordCount = 0;
        int alphaCount = 0;
        int nonWhitespaceCount = 0;
        boolean inWord = false;

        for (int index = 0; index < bodyText.length(); index++) {
            char ch = bodyText.charAt(index);
            if (!Character.isWhitespace(ch)) {
                nonWhitespaceCount++;
            }
            if (Character.isLetter(ch)) {
                alphaCount++;
            }
            boolean wordChar = Character.isLetterOrDigit(ch);
            if (wordChar && !inWord) {
                wordCount++;
                inWord = true;
            } else if (!wordChar) {
                inWord = false;
            }
        }

        double alphaRatio = nonWhitespaceCount == 0 ? 0.0 : (double) alphaCount / nonWhitespaceCount;
        return new WordAlphaStats(wordCount, alphaRatio);
    }

    private record WordAlphaStats(int wordCount, double alphaRatio) {}

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
