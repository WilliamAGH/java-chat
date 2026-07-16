package com.williamcallahan.javachat.service.markdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

/**
 * Verifies rendered fence content remains literal after list normalization.
 */
class MarkdownFenceRenderingTest {

    @Test
    void process_preservesPythonIndentationInsideNumericHeaderFence() {
        String continuationProse = "Continue with the next instruction.";
        String markdownWithPythonFence = String.join(
                "\n",
                "1. Configure:",
                "```python",
                "if enabled:",
                "    ```",
                "    print(\"keep this indentation\")",
                "```",
                continuationProse);

        String renderedHtml =
                new UnifiedMarkdownService().process(markdownWithPythonFence).html();
        String expectedRenderedCodeBlock = String.join(
                "\n",
                "<pre><code class=\"language-python\">if enabled:",
                "    ```",
                "    print(\"keep this indentation\")",
                "</code></pre>");

        assertTrue(renderedHtml.contains(expectedRenderedCodeBlock));
        assertFalse(renderedHtml.contains("\n        print(\"keep this indentation\")"));

        Element numericHeaderListItem = Jsoup.parseBodyFragment(renderedHtml).selectFirst("ol > li");
        assertNotNull(numericHeaderListItem);
        Element preformattedBlock = numericHeaderListItem.selectFirst("pre");
        assertNotNull(preformattedBlock);
        assertEquals(numericHeaderListItem, preformattedBlock.parent());
        assertFalse(preformattedBlock.text().contains(continuationProse));
        assertTrue(numericHeaderListItem.text().contains(continuationProse));
    }

    @Test
    void process_preservesYamlIndentationInsideNumericHeaderFence() {
        String markdownWithYamlFence =
                String.join("\n", "2. Configure:", "~~~yaml", "settings:", "  indentation: preserved", "~~~");

        String renderedHtml =
                new UnifiedMarkdownService().process(markdownWithYamlFence).html();

        assertTrue(renderedHtml.contains(
                "<pre><code class=\"language-yaml\">settings:\n  indentation: preserved\n</code></pre>"));
        assertFalse(renderedHtml.contains("\n    indentation: preserved"));
    }

    @Test
    void process_preservesFlexmarkRenderingForMultilineInlineDelimiterWithFenceLikeBackticks() {
        String multilineInlineCode = String.join("\n", "`code", "```java", "more`");

        String renderedHtml =
                new UnifiedMarkdownService().process(multilineInlineCode).html();

        assertEquals("<p>`code</p>\n<pre><code class=\"language-java\">more`</code></pre>", renderedHtml);
    }

    @Test
    void process_rendersUnmatchedBacktickAsLiteralText() {
        String renderedHtml =
                new UnifiedMarkdownService().process("Use `unmatched code").html();

        assertEquals("<p>Use `unmatched code</p>", renderedHtml);
    }

    @Test
    void process_rendersUnclosedBacktickFenceAsCodeBlock() {
        String unclosedBacktickFence = String.join("\n", "```java", "int answer = 42;");

        String renderedHtml =
                new UnifiedMarkdownService().process(unclosedBacktickFence).html();

        assertEquals("<pre><code class=\"language-java\">int answer = 42;\n</code></pre>", renderedHtml);
    }
}
