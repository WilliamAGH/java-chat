package com.williamcallahan.javachat.service.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
    void preNormalizeForListsAndFences_keepsEnrichmentMarkerOutsideNumericListCodeIndentation() {
        String markdownWithFollowingEnrichment = String.join(
                "\n", "1. Learn the language", "", "{{hint:Read the official documentation}}", "", "### Next topic");

        String normalizedMarkdown = MarkdownNormalizer.preNormalizeForListsAndFences(markdownWithFollowingEnrichment);

        assertEquals(markdownWithFollowingEnrichment, normalizedMarkdown);
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
