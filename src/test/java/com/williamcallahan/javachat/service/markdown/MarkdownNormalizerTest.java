package com.williamcallahan.javachat.service.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Verifies normalization rules that precede markdown AST parsing.
 */
class MarkdownNormalizerTest {

    @Test
    void preNormalizeForListsAndFences_indentsContinuationForThreeDigitNumericHeaderOnly() {
        String normalizedThreeDigitHeader =
                MarkdownNormalizer.preNormalizeForListsAndFences("123. Numeric header\nContinuation");
        String normalizedFourDigitPrefix =
                MarkdownNormalizer.preNormalizeForListsAndFences("1234. Numeric prefix\nContinuation");

        assertEquals("123. Numeric header\n    Continuation", normalizedThreeDigitHeader);
        assertEquals("1234. Numeric prefix\nContinuation", normalizedFourDigitPrefix);
    }
}
