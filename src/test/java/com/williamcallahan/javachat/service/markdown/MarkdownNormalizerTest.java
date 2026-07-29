package com.williamcallahan.javachat.service.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeout;

import java.time.Duration;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies normalization rules that precede markdown AST parsing.
 */
class MarkdownNormalizerTest {
    private static final int AMBIGUOUS_INLINE_SEGMENT_COUNT = 3_000;
    private static final Duration MARKDOWN_LINEAR_TIME_BUDGET = Duration.ofSeconds(5);

    @Test
    void preNormalizeForListsAndFences_indentsContinuationForThreeDigitNumericHeaderOnly() {
        String normalizedThreeDigitHeader =
                MarkdownNormalizer.preNormalizeForListsAndFences("123. Numeric header\nContinuation");
        String normalizedFourDigitPrefix =
                MarkdownNormalizer.preNormalizeForListsAndFences("1234. Numeric prefix\nContinuation");

        assertEquals("123. Numeric header\n    Continuation", normalizedThreeDigitHeader);
        assertEquals("1234. Numeric prefix\nContinuation", normalizedFourDigitPrefix);
    }

    @Test
    void preNormalizeForListsAndFences_preservesBacktickFenceContentUnderNumericHeader() {
        String markdownWithPythonFence = String.join(
                "\n",
                "1. Configure:",
                "```python",
                "if enabled:",
                "    ```",
                "    print(\"keep this indentation\")",
                "```",
                "Continue with the next instruction.");
        String expectedMarkdown = String.join(
                "\n",
                "1. Configure:",
                "    ```python",
                "    if enabled:",
                "        ```",
                "        print(\"keep this indentation\")",
                "    ```",
                "    Continue with the next instruction.");

        String normalizedMarkdown = MarkdownNormalizer.preNormalizeForListsAndFences(markdownWithPythonFence);

        assertEquals(expectedMarkdown, normalizedMarkdown);
    }

    @Test
    void preNormalizeForListsAndFences_preservesTildeFenceContentUnderNumericHeader() {
        String markdownWithYamlFence = String.join(
                "\n",
                "2. Configure:",
                "~~~yaml",
                "settings:",
                "    ~~~",
                "  indentation: preserved",
                "~~~",
                "Continue with the next instruction.");
        String expectedMarkdown = String.join(
                "\n",
                "2. Configure:",
                "    ~~~yaml",
                "    settings:",
                "        ~~~",
                "      indentation: preserved",
                "    ~~~",
                "    Continue with the next instruction.");

        String normalizedMarkdown = MarkdownNormalizer.preNormalizeForListsAndFences(markdownWithYamlFence);

        assertEquals(expectedMarkdown, normalizedMarkdown);
    }

    @Test
    void preNormalizeForListsAndFences_preservesFenceLikeBackticksInsideMultilineInlineCode() {
        String multilineInlineCode = String.join("\n", "`code", "```java", "more`");

        String normalizedMarkdown = MarkdownNormalizer.preNormalizeForListsAndFences(multilineInlineCode);

        assertEquals(multilineInlineCode, normalizedMarkdown);
    }

    @Test
    void preNormalizeForListsAndFences_preservesTripleBacktickMultilineInlineCodeAfterPreamble() {
        String multilineInlineCode = String.join("\n", "Intro```code", "continued``` end");

        String normalizedMarkdown = MarkdownNormalizer.preNormalizeForListsAndFences(multilineInlineCode);

        assertEquals(multilineInlineCode, normalizedMarkdown);
    }

    @Test
    void preNormalizeForListsAndFences_preservesTripleBacktickInlineCloserAtBlockIndentation() {
        String multilineInlineCode = String.join("\n", "Intro```code", "``` end");

        String normalizedMarkdown = MarkdownNormalizer.preNormalizeForListsAndFences(multilineInlineCode);

        assertEquals(multilineInlineCode, normalizedMarkdown);
    }

    @Test
    void preNormalizeForListsAndFences_handlesRepeatedAmbiguousInlineFencesWithoutTailRescans() {
        StringBuilder repeatedInlineCodeBuilder = new StringBuilder();
        for (int segmentIndex = 0; segmentIndex < AMBIGUOUS_INLINE_SEGMENT_COUNT; segmentIndex++) {
            repeatedInlineCodeBuilder.append("segment").append(segmentIndex).append("```code\ncontinued``` end\n");
        }
        for (int rejectedFenceIndex = 0; rejectedFenceIndex < AMBIGUOUS_INLINE_SEGMENT_COUNT; rejectedFenceIndex++) {
            repeatedInlineCodeBuilder.append("```x\n");
        }
        String repeatedInlineCode = repeatedInlineCodeBuilder.toString();

        assertTimeout(
                MARKDOWN_LINEAR_TIME_BUDGET,
                () -> MarkdownNormalizer.preNormalizeForListsAndFences(repeatedInlineCode));
    }

    @Test
    void preNormalizeForListsAndFences_preservesClosedBacktickFence() {
        String backtickFence = String.join("\n", "```java", "int answer = 42;", "```");

        String normalizedMarkdown = MarkdownNormalizer.preNormalizeForListsAndFences(backtickFence);

        assertEquals(backtickFence, normalizedMarkdown);
    }

    @Test
    void preNormalizeForListsAndFences_preservesClosedTildeFence() {
        String tildeFence = String.join("\n", "~~~java", "int answer = 42;", "~~~");

        String normalizedMarkdown = MarkdownNormalizer.preNormalizeForListsAndFences(tildeFence);

        assertEquals(tildeFence, normalizedMarkdown);
    }

    @Test
    void preNormalizeForListsAndFences_preservesFenceMarkerWithTrailingCodeContent() {
        String fenceLikeCodeContent =
                String.join("\n", "```text", "literal content", "```ruby", "still literal content", "```");

        String normalizedMarkdown = MarkdownNormalizer.preNormalizeForListsAndFences(fenceLikeCodeContent);

        assertEquals(fenceLikeCodeContent, normalizedMarkdown);
    }

    @Test
    void preNormalizeForListsAndFences_preservesClosingBracesAfterFenceMarkerAsCode() {
        String fenceLikeCodeContent = String.join("\n", "```text", "literal content", "```}}", "still code");
        String expectedMarkdown = fenceLikeCodeContent + "\n```";

        String normalizedMarkdown = MarkdownNormalizer.preNormalizeForListsAndFences(fenceLikeCodeContent);

        assertEquals(expectedMarkdown, normalizedMarkdown);
    }

    @Test
    void preNormalizeForListsAndFences_treatsUnmatchedBacktickAsLiteralBeforeUnclosedFence() {
        String unmatchedInlineCodeAndFence = String.join("\n", "`code", "```java", "more");
        String expectedNormalizedMarkdown = unmatchedInlineCodeAndFence + "\n```";

        String normalizedMarkdown = MarkdownNormalizer.preNormalizeForListsAndFences(unmatchedInlineCodeAndFence);

        assertEquals(expectedNormalizedMarkdown, normalizedMarkdown);
    }

    @Test
    void preNormalizeForListsAndFences_repairsAttachedUnclosedFence() {
        String attachedFence = String.join("\n", "Before```java", "int answer = 42;");
        String expectedNormalizedMarkdown = String.join("\n", "Before", "```java", "int answer = 42;", "```");

        String normalizedMarkdown = MarkdownNormalizer.preNormalizeForListsAndFences(attachedFence);

        assertEquals(expectedNormalizedMarkdown, normalizedMarkdown);
    }

    @Test
    void preNormalizeForListsAndFences_repairsAttachedClosingFenceWithTrailingProse() {
        String attachedFences = String.join("\n", "Before```java", "int answer = 42;", "```The result is 42.");
        String expectedNormalizedMarkdown =
                String.join("\n", "Before", "```java", "int answer = 42;", "```", "The result is 42.");

        String normalizedMarkdown = MarkdownNormalizer.preNormalizeForListsAndFences(attachedFences);

        assertEquals(expectedNormalizedMarkdown, normalizedMarkdown);
    }

    @Test
    void preNormalizeForListsAndFences_repairsAttachedClosingFenceWithCompactTitleCaseProse() {
        String attachedFences = String.join("\n", "Before```java", "int answer = 42;", "```Done");
        String expectedNormalizedMarkdown = String.join("\n", "Before", "```java", "int answer = 42;", "```", "Done");

        String normalizedMarkdown = MarkdownNormalizer.preNormalizeForListsAndFences(attachedFences);

        assertEquals(expectedNormalizedMarkdown, normalizedMarkdown);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Java", "C", "JavaScript", "java", "c", "javascript"})
    void preNormalizeForListsAndFences_preservesSingleWordInfoStringOnAttachedOpeningFence(String infoString) {
        String attachedFences = String.join("\n", "Before```" + infoString, "literal content", "```");
        String expectedNormalizedMarkdown = String.join("\n", "Before", "```" + infoString, "literal content", "```");

        String normalizedMarkdown = MarkdownNormalizer.preNormalizeForListsAndFences(attachedFences);

        assertEquals(expectedNormalizedMarkdown, normalizedMarkdown);
    }

    @Test
    void preNormalizeForListsAndFences_repairsAttachedClosingFenceWithParentheticalProse() {
        String attachedFences = String.join("\n", "Before```java", "int answer = 42;", "```(note)  ");
        String expectedNormalizedMarkdown =
                String.join("\n", "Before", "```java", "int answer = 42;", "```", "(note)  ");

        String normalizedMarkdown = MarkdownNormalizer.preNormalizeForListsAndFences(attachedFences);

        assertEquals(expectedNormalizedMarkdown, normalizedMarkdown);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Done", "(note)", "Java"})
    void preNormalizeForListsAndFences_preservesCompactFenceLikeCodeBeforeRealClosingFence(String fenceSuffix) {
        String fenceLikeCodeContent =
                String.join("\n", "Before```text", "literal content", "```" + fenceSuffix, "still literal", "```");
        String expectedNormalizedMarkdown = "Before\n"
                + String.join("\n", "```text", "literal content", "```" + fenceSuffix, "still literal", "```");

        String normalizedMarkdown = MarkdownNormalizer.preNormalizeForListsAndFences(fenceLikeCodeContent);

        assertEquals(expectedNormalizedMarkdown, normalizedMarkdown);
    }

    @Test
    void preNormalizeForListsAndFences_preservesFenceLikeCodeAfterAttachedOpeningFence() {
        String fenceLikeCodeContent =
                String.join("\n", "Before```text", "literal content", "```ruby", "still literal content", "```");
        String expectedNormalizedMarkdown =
                "Before\n" + String.join("\n", "```text", "literal content", "```ruby", "still literal content", "```");

        String normalizedMarkdown = MarkdownNormalizer.preNormalizeForListsAndFences(fenceLikeCodeContent);

        assertEquals(expectedNormalizedMarkdown, normalizedMarkdown);
    }

    @Test
    void preNormalizeForListsAndFences_keepsUnknownBraceMarkerInsideNumericList() {
        String markdownWithTemplate = String.join("\n", "1. Render the value", "{{name}}", "Continue rendering.");
        String expectedMarkdown = String.join("\n", "1. Render the value", "    {{name}}", "    Continue rendering.");

        String normalizedMarkdown = MarkdownNormalizer.preNormalizeForListsAndFences(markdownWithTemplate);

        assertEquals(expectedMarkdown, normalizedMarkdown);
    }

    @Test
    void preNormalizeForListsAndFences_preservesBraceMarkerInsideNumericListFence() {
        String fencedTemplate = String.join(
                "\n", "1. Render the template:", "```handlebars", "<p>{{name}}</p>", "return renderedTemplate;", "```");
        String expectedMarkdown = String.join(
                "\n",
                "1. Render the template:",
                "    ```handlebars",
                "    <p>{{name}}</p>",
                "    return renderedTemplate;",
                "    ```");

        String normalizedMarkdown = MarkdownNormalizer.preNormalizeForListsAndFences(fencedTemplate);

        assertEquals(expectedMarkdown, normalizedMarkdown);
    }

    @ParameterizedTest(name = "{0} fence with {1} spaces remains code context")
    @MethodSource("fencedCodeBlocksAtEveryIndentation")
    void preNormalizeForListsAndFencesPreservesFencedAndIndentedCodeContext(
            String fenceDescription, int indentationSpaces, String markdownCodeBlock) {
        String normalizedMarkdown = MarkdownNormalizer.preNormalizeForListsAndFences(markdownCodeBlock);

        assertEquals(markdownCodeBlock, normalizedMarkdown, fenceDescription + " should remain literal Markdown");
    }

    private static Stream<Arguments> fencedCodeBlocksAtEveryIndentation() {
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
}
