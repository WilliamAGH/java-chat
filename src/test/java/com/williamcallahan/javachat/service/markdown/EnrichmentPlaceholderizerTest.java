package com.williamcallahan.javachat.service.markdown;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.data.MutableDataSet;
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
        String supportedToken = "hint";
        String noncanonicalToken = supportedToken.toUpperCase(Locale.ROOT);
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

    @Test
    void shouldRenderPresentationOwnedByServerRenderer() {
        List<MarkdownEnrichment> enrichments = new ArrayList<>();
        Map<String, String> placeholders = new HashMap<>();

        String processedMarkdown =
                placeholderizer.extractAndPlaceholderizeEnrichments("{{hint: visible}}", enrichments, placeholders);

        assertTrue(processedMarkdown.contains("ENRICHMENT_"));
        assertEquals(1, enrichments.size());
        assertEquals(1, placeholders.size());
        String renderedEnrichment = placeholders.values().iterator().next();
        assertTrue(renderedEnrichment.contains("Helpful Hints"));
        Element renderedIconPath =
                Jsoup.parseBodyFragment(renderedEnrichment).selectFirst(".inline-enrichment-header path");
        assertEquals(
                "M12 2a7 7 0 0 0-7 7c0 2.59 1.47 4.84 3.63 6.02L9 18h6l.37-2.98A7.01 7.01 0 0 0 19 9a7 7 0 0 0-7-7zm-3 19h6v1H9v-1z",
                renderedIconPath.attr("d"));
    }

    @Test
    void shouldPlaceholderizeAndRenderBackgroundEnrichment() {
        String sourceMarkdown = "{{background: Records make immutable data carriers concise.}}";
        List<MarkdownEnrichment> detectedEnrichments = new ArrayList<>();
        Map<String, String> placeholderHtmlByIdentifier = new HashMap<>();

        String placeholderMarkdown = placeholderizer.extractAndPlaceholderizeEnrichments(
                sourceMarkdown, detectedEnrichments, placeholderHtmlByIdentifier);
        String renderedHtml = placeholderizer.renderEnrichmentBlocksFromPlaceholders(
                placeholderMarkdown, placeholderHtmlByIdentifier);

        assertAll(
                () -> assertEquals(1, detectedEnrichments.size()),
                () -> assertEquals("background", detectedEnrichments.getFirst().type()),
                () -> assertEquals(
                        "Records make immutable data carriers concise.",
                        detectedEnrichments.getFirst().content()),
                () -> assertTrue(renderedHtml.contains("inline-enrichment background")),
                () -> assertTrue(renderedHtml.contains("data-enrichment-type=\"background\"")),
                () -> assertTrue(renderedHtml.contains("Background Context")),
                () -> assertTrue(renderedHtml.contains("Records make immutable data carriers concise.")),
                () -> assertFalse(renderedHtml.contains("{{background:")));
    }

    @Test
    void shouldPlaceholderizeAndRenderReminderEnrichment() {
        String sourceMarkdown = "{{reminder: Close resources with try-with-resources.}}";
        List<MarkdownEnrichment> detectedEnrichments = new ArrayList<>();
        Map<String, String> placeholderHtmlByIdentifier = new HashMap<>();

        String placeholderMarkdown = placeholderizer.extractAndPlaceholderizeEnrichments(
                sourceMarkdown, detectedEnrichments, placeholderHtmlByIdentifier);
        String renderedHtml = placeholderizer.renderEnrichmentBlocksFromPlaceholders(
                placeholderMarkdown, placeholderHtmlByIdentifier);

        assertAll(
                () -> assertEquals(1, detectedEnrichments.size()),
                () -> assertEquals("reminder", detectedEnrichments.getFirst().type()),
                () -> assertEquals(
                        "Close resources with try-with-resources.",
                        detectedEnrichments.getFirst().content()),
                () -> assertTrue(renderedHtml.contains("inline-enrichment reminder")),
                () -> assertTrue(renderedHtml.contains("data-enrichment-type=\"reminder\"")),
                () -> assertTrue(renderedHtml.contains("Important Reminders")),
                () -> assertTrue(renderedHtml.contains("Close resources with try-with-resources.")),
                () -> assertFalse(renderedHtml.contains("{{reminder:")));
    }

    @Test
    void shouldPlaceholderizeAndRenderWarningEnrichment() {
        String sourceMarkdown = "{{warning: Do not return null from public methods.}}";
        List<MarkdownEnrichment> detectedEnrichments = new ArrayList<>();
        Map<String, String> placeholderHtmlByIdentifier = new HashMap<>();

        String placeholderMarkdown = placeholderizer.extractAndPlaceholderizeEnrichments(
                sourceMarkdown, detectedEnrichments, placeholderHtmlByIdentifier);
        String renderedHtml = placeholderizer.renderEnrichmentBlocksFromPlaceholders(
                placeholderMarkdown, placeholderHtmlByIdentifier);

        assertAll(
                () -> assertEquals(1, detectedEnrichments.size()),
                () -> assertEquals("warning", detectedEnrichments.getFirst().type()),
                () -> assertEquals(
                        "Do not return null from public methods.",
                        detectedEnrichments.getFirst().content()),
                () -> assertTrue(renderedHtml.contains("inline-enrichment warning")),
                () -> assertTrue(renderedHtml.contains("data-enrichment-type=\"warning\"")),
                () -> assertTrue(renderedHtml.contains("Warning")),
                () -> assertTrue(renderedHtml.contains("Do not return null from public methods.")),
                () -> assertFalse(renderedHtml.contains("{{warning:")));
    }

    @Test
    void shouldPlaceholderizeAndRenderExampleEnrichment() {
        String sourceMarkdown = "{{example: Use try-with-resources for files.}}";
        List<MarkdownEnrichment> detectedEnrichments = new ArrayList<>();
        Map<String, String> placeholderHtmlByIdentifier = new HashMap<>();

        String placeholderMarkdown = placeholderizer.extractAndPlaceholderizeEnrichments(
                sourceMarkdown, detectedEnrichments, placeholderHtmlByIdentifier);
        String renderedHtml = placeholderizer.renderEnrichmentBlocksFromPlaceholders(
                placeholderMarkdown, placeholderHtmlByIdentifier);

        assertAll(
                () -> assertEquals(1, detectedEnrichments.size()),
                () -> assertEquals("example", detectedEnrichments.getFirst().type()),
                () -> assertEquals(
                        "Use try-with-resources for files.",
                        detectedEnrichments.getFirst().content()),
                () -> assertTrue(renderedHtml.contains("inline-enrichment example")),
                () -> assertTrue(renderedHtml.contains("data-enrichment-type=\"example\"")),
                () -> assertTrue(renderedHtml.contains("Example")),
                () -> assertTrue(renderedHtml.contains("Use try-with-resources for files.")),
                () -> assertFalse(renderedHtml.contains("{{example:")));
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
}
