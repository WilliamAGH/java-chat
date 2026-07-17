package com.williamcallahan.javachat.service.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.williamcallahan.javachat.domain.markdown.EnrichmentKindCatalog;
import com.williamcallahan.javachat.domain.markdown.MarkdownEnrichment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Verifies enrichment markers are handled safely around code fences.
 */
class EnrichmentPlaceholderizerTest {

    private EnrichmentPlaceholderizer placeholderizer;

    @BeforeEach
    void setUp() {
        MutableDataSet options = new MutableDataSet();
        Parser parser = Parser.builder(options).build();
        HtmlRenderer renderer = HtmlRenderer.builder(options).build();
        placeholderizer = new EnrichmentPlaceholderizer(parser, renderer);
    }

    @Test
    void shouldIgnoreEnrichmentInTildeFence() {
        String markdown = "~~~\n{{hint: Should be ignored}}\n~~~";
        List<MarkdownEnrichment> enrichments = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();

        String processedMarkdown =
                placeholderizer.extractAndPlaceholderizeEnrichments(markdown, enrichments, placeholders);

        assertFalse(processedMarkdown.contains("ENRICHMENT_"), "Enrichment should not be processed inside tilde fence");
        assertTrue(processedMarkdown.contains("{{hint:"), "Original text should be preserved");
    }

    @Test
    void shouldProcessValidEnrichment() {
        String markdown = "{{hint: valid}}";
        List<MarkdownEnrichment> enrichments = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();

        String processedMarkdown =
                placeholderizer.extractAndPlaceholderizeEnrichments(markdown, enrichments, placeholders);

        assertTrue(processedMarkdown.contains("ENRICHMENT_"), "Enrichment should be processed");
        assertFalse(processedMarkdown.contains("{{hint:"), "Original text should be replaced");
    }

    @Test
    void shouldRejectNoncanonicalTokenCaseWithoutAliases() {
        String canonicalToken = EnrichmentKindCatalog.load().all().getFirst().token();
        String noncanonicalToken = canonicalToken.toUpperCase(Locale.ROOT);
        List<MarkdownEnrichment> enrichments = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();

        String marker = "{{" + noncanonicalToken + ": visible}}";
        String processedMarkdown =
                placeholderizer.extractAndPlaceholderizeEnrichments(marker, enrichments, placeholders);

        assertEquals(marker, processedMarkdown);
        assertTrue(enrichments.isEmpty());
        assertTrue(placeholders.isEmpty());
    }

    @Test
    void shouldDiscardUnicodeWhitespaceOnlyEnrichmentBeforeCreatingPlaceholder() {
        String markdown = "{{hint: \t\u00A0\u2003\u3000\n}}";
        List<MarkdownEnrichment> enrichments = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();

        String processedMarkdown =
                placeholderizer.extractAndPlaceholderizeEnrichments(markdown, enrichments, placeholders);

        assertTrue(processedMarkdown.isEmpty(), "Blank marker should be consumed without rendered text");
        assertTrue(enrichments.isEmpty(), "Blank marker should not create a domain enrichment");
        assertTrue(placeholders.isEmpty(), "Blank marker should not create a render placeholder");
    }

    @Test
    void shouldIgnoreEnrichmentInsideInlineCode() {
        String markdown = "Use `{{hint: inline}}` to show markers.";
        List<MarkdownEnrichment> enrichments = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();

        String processedMarkdown =
                placeholderizer.extractAndPlaceholderizeEnrichments(markdown, enrichments, placeholders);

        assertFalse(processedMarkdown.contains("ENRICHMENT_"), "Inline code should not be placeholderized");
        assertTrue(processedMarkdown.contains("`{{hint: inline}}`"), "Inline code markers should remain intact");
    }

    @Test
    void shouldProcessEnrichmentAfterMultilineInlineCodeContainingFenceLikeBackticks() {
        String markdown = String.join("\n", "`code", "```java", "more` {{hint: visible}}");
        List<MarkdownEnrichment> enrichments = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();

        String placeholderMarkdown =
                placeholderizer.extractAndPlaceholderizeEnrichments(markdown, enrichments, placeholders);

        assertTrue(placeholderMarkdown.contains("ENRICHMENT_"));
        assertEquals(1, enrichments.size());
        assertEquals(1, placeholders.size());
    }

    @Test
    void shouldProcessEnrichmentContainingMultilineInlineCodeWithFenceLikeBackticks() {
        String markdown = String.join("\n", "{{hint: `code", "```java", "more`}}");
        List<MarkdownEnrichment> enrichments = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();

        String placeholderMarkdown =
                placeholderizer.extractAndPlaceholderizeEnrichments(markdown, enrichments, placeholders);

        assertTrue(placeholderMarkdown.contains("ENRICHMENT_"));
        assertEquals(1, enrichments.size());
        assertEquals(1, placeholders.size());
    }

    @ParameterizedTest(name = "{0} fence with {1} spaces protects enrichment markers")
    @MethodSource("markdownCodeBlocksAtEveryIndentation")
    void shouldLeaveMarkersUntouchedInsideEverySupportedCodeBlockContext(
            String fenceDescription, int indentationSpaces, String markdownCodeBlock) {
        List<MarkdownEnrichment> enrichments = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();

        String processedMarkdown =
                placeholderizer.extractAndPlaceholderizeEnrichments(markdownCodeBlock, enrichments, placeholders);

        assertEquals(markdownCodeBlock, processedMarkdown, fenceDescription + " code should remain literal");
        assertTrue(enrichments.isEmpty());
        assertTrue(placeholders.isEmpty());
    }

    @ParameterizedTest(name = "{0} is blank enrichment content")
    @MethodSource("unicodeBlankMarkerContents")
    void shouldDiscardEveryInvisibleUnicodeMarkerContent(String unicodeDescription, String invisibleContent) {
        List<MarkdownEnrichment> enrichments = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();

        String processedMarkdown = placeholderizer.extractAndPlaceholderizeEnrichments(
                "{{hint: " + invisibleContent + "}}", enrichments, placeholders);

        assertTrue(processedMarkdown.isEmpty(), unicodeDescription + " should not produce rendered text");
        assertTrue(enrichments.isEmpty(), unicodeDescription + " should not create an enrichment");
        assertTrue(placeholders.isEmpty(), unicodeDescription + " should not create a placeholder");
    }

    @ParameterizedTest(name = "{0} is mapped from the canonical catalog")
    @MethodSource("canonicalEnrichmentPresentations")
    void shouldRenderEveryCanonicalCatalogPresentation(String token, String expectedTitle, String expectedIconHtml) {
        List<MarkdownEnrichment> enrichments = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();

        String processedMarkdown = placeholderizer.extractAndPlaceholderizeEnrichments(
                "{{" + token + ": visible}}", enrichments, placeholders);

        assertTrue(processedMarkdown.contains("ENRICHMENT_"));
        assertEquals(1, enrichments.size());
        assertEquals(1, placeholders.size());
        String renderedEnrichment = placeholders.values().iterator().next();
        assertTrue(renderedEnrichment.contains(expectedTitle));
        Element expectedIconPath = Jsoup.parseBodyFragment(expectedIconHtml).selectFirst("path");
        Element renderedIconPath =
                Jsoup.parseBodyFragment(renderedEnrichment).selectFirst(".inline-enrichment-header path");
        assertEquals(expectedIconPath.attr("d"), renderedIconPath.attr("d"));
    }

    private static Stream<Arguments> markdownCodeBlocksAtEveryIndentation() {
        return Stream.of("backtick", "tilde").flatMap(fenceDescription -> {
            String fence = "backtick".equals(fenceDescription) ? "```" : "~~~";
            return IntStream.rangeClosed(0, 4).mapToObj(indentationSpaces -> {
                String indentation = " ".repeat(indentationSpaces);
                String markdownCodeBlock = String.join(
                        "\n",
                        indentation + fence,
                        indentation + "{{hint: protected code marker}}",
                        indentation + fence);
                return Arguments.of(fenceDescription, indentationSpaces, markdownCodeBlock);
            });
        });
    }

    private static Stream<Arguments> unicodeBlankMarkerContents() {
        return Stream.of(
                Arguments.of("U+00A0 NO-BREAK SPACE", "\u00A0"),
                Arguments.of("U+202F NARROW NO-BREAK SPACE", "\u202F"),
                Arguments.of("U+2003 EM SPACE", "\u2003"),
                Arguments.of("U+FEFF ZERO WIDTH NO-BREAK SPACE", "\uFEFF"),
                Arguments.of("U+200B ZERO WIDTH SPACE", "\u200B"),
                Arguments.of("U+2060 WORD JOINER", "\u2060"));
    }

    private static Stream<Arguments> canonicalEnrichmentPresentations() {
        return EnrichmentKindCatalog.load().all().stream()
                .map(presentation -> Arguments.of(presentation.token(), presentation.title(), presentation.iconHtml()));
    }
}
