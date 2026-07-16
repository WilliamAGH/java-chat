package com.williamcallahan.javachat.domain.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies the shared text invariant for every permitted enrichment variant.
 */
class MarkdownEnrichmentTest {

    private static final List<UnicodeBlankText> UNICODE_BLANK_ENRICHMENT_TEXTS = List.of(
            new UnicodeBlankText("U+3000 IDEOGRAPHIC SPACE", "\u3000"),
            new UnicodeBlankText("U+2003 EM SPACE", "\u2003"),
            new UnicodeBlankText("U+00A0 NO-BREAK SPACE", "\u00A0"),
            new UnicodeBlankText("U+FEFF ZERO WIDTH NO-BREAK SPACE", "\uFEFF"),
            new UnicodeBlankText("mixed Unicode spaces", " \t\u00A0\u2003\u3000\n"));

    @ParameterizedTest(name = "{0} rejects {1}")
    @MethodSource("enrichmentTypesAndUnicodeBlankTexts")
    void shouldRejectUnicodeBlankTextForEveryEnrichmentType(
            Class<? extends MarkdownEnrichment> enrichmentType, String unicodeDescription, String blankEnrichmentText) {
        InvocationTargetException constructorFailure = assertThrows(
                InvocationTargetException.class,
                () -> constructEnrichment(enrichmentType, blankEnrichmentText),
                () -> enrichmentType.getSimpleName() + " accepted " + unicodeDescription);
        assertInstanceOf(IllegalArgumentException.class, constructorFailure.getCause());
    }

    @ParameterizedTest(name = "{0} accepts visible enrichment text")
    @MethodSource("enrichmentTypes")
    void shouldAcceptVisibleTextForEveryEnrichmentType(Class<? extends MarkdownEnrichment> enrichmentType)
            throws ReflectiveOperationException {
        String visibleEnrichmentText = "Visible enrichment";

        MarkdownEnrichment enrichment = constructEnrichment(enrichmentType, visibleEnrichmentText);

        assertEquals(visibleEnrichmentText, enrichment.content());
    }

    private static Stream<Arguments> enrichmentTypesAndUnicodeBlankTexts() {
        return enrichmentTypes().flatMap(enrichmentType -> UNICODE_BLANK_ENRICHMENT_TEXTS.stream()
                .map(unicodeBlankText -> Arguments.of(
                        enrichmentType, unicodeBlankText.unicodeDescription(), unicodeBlankText.enrichmentText())));
    }

    private static Stream<Class<? extends MarkdownEnrichment>> enrichmentTypes() {
        return Arrays.stream(MarkdownEnrichment.class.getPermittedSubclasses())
                .map(permittedType -> permittedType.asSubclass(MarkdownEnrichment.class));
    }

    private static MarkdownEnrichment constructEnrichment(
            Class<? extends MarkdownEnrichment> enrichmentType, String enrichmentText)
            throws ReflectiveOperationException {
        return enrichmentType
                .getConstructor(String.class, EnrichmentPriority.class, int.class)
                .newInstance(enrichmentText, EnrichmentPriority.MEDIUM, 0);
    }

    private record UnicodeBlankText(String unicodeDescription, String enrichmentText) {}
}
