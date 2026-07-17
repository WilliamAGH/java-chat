package com.williamcallahan.javachat.domain.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** Verifies the shared text invariant for manifest-backed enrichments. */
class MarkdownEnrichmentTest {

    private static final List<UnicodeBlankText> UNICODE_BLANK_ENRICHMENT_TEXTS = List.of(
            new UnicodeBlankText("U+3000 IDEOGRAPHIC SPACE", "\u3000"),
            new UnicodeBlankText("U+2003 EM SPACE", "\u2003"),
            new UnicodeBlankText("U+00A0 NO-BREAK SPACE", "\u00A0"),
            new UnicodeBlankText("U+202F NARROW NO-BREAK SPACE", "\u202F"),
            new UnicodeBlankText("U+FEFF ZERO WIDTH NO-BREAK SPACE", "\uFEFF"),
            new UnicodeBlankText("U+200B ZERO WIDTH SPACE", "\u200B"),
            new UnicodeBlankText("U+2060 WORD JOINER", "\u2060"),
            new UnicodeBlankText("mixed Unicode spaces", " \t\u00A0\u202F\u2003\u3000\uFEFF\u200B\u2060\n"));

    @ParameterizedTest(name = "rejects {0}")
    @MethodSource("unicodeBlankTexts")
    void rejectsUnicodeBlankText(String unicodeDescription, String blankEnrichmentText) {
        assertThrows(
                IllegalArgumentException.class,
                () -> new MarkdownEnrichment("manifest-token", blankEnrichmentText, 0),
                unicodeDescription);
    }

    @ParameterizedTest(name = "accepts manifest token {0}")
    @MethodSource("canonicalTokens")
    void acceptsEveryManifestToken(String canonicalToken) {
        MarkdownEnrichment enrichment = new MarkdownEnrichment(canonicalToken, "Visible enrichment", 0);

        assertEquals(canonicalToken, enrichment.type());
        assertEquals("Visible enrichment", enrichment.content());
    }

    private static Stream<Arguments> unicodeBlankTexts() {
        return UNICODE_BLANK_ENRICHMENT_TEXTS.stream()
                .map(unicodeBlankText ->
                        Arguments.of(unicodeBlankText.unicodeDescription(), unicodeBlankText.enrichmentText()));
    }

    private static Stream<String> canonicalTokens() {
        return EnrichmentKindCatalog.load().all().stream().map(EnrichmentKindCatalog.EnrichmentPresentation::token);
    }

    private record UnicodeBlankText(String unicodeDescription, String enrichmentText) {}
}
