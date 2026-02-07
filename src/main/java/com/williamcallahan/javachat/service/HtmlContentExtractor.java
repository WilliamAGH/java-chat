package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.util.Set;
import java.util.stream.Collectors;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

/**
 * Enhanced HTML content extraction that filters out navigation noise
 * and JavaScript warnings to produce cleaner documentation content.
 */
@Service
public class HtmlContentExtractor {

    private static final int MIN_INLINE_TEXT_LENGTH = 20;
    private static final String CODE_FENCE_MARKER = "```";
    private static final int CODE_FENCE_LENGTH = CODE_FENCE_MARKER.length();
    private static final int MAX_CONSECUTIVE_NEWLINES = 2;

    // Common noise patterns to filter out
    private static final Set<String> NOISE_PATTERNS = Set.of(
            "JavaScript is disabled",
            "Skip navigation links",
            "Hide sidebar",
            "Show sidebar",
            "Report a bug",
            "suggest an enhancement",
            "Other versions",
            "Use is subject to license terms",
            "Scripting on this page tracks",
            "but does not change the content");

    // CSS selectors for navigation and other non-content elements
    private static final String[] REMOVE_SELECTORS = {
        "nav",
        "header",
        "footer", // Navigation elements
        ".navigation",
        ".nav",
        ".navbar", // Navigation classes
        ".sidebar",
        ".toc",
        ".breadcrumb", // Navigation components
        ".skip-nav",
        ".skip-link", // Skip links
        "script",
        "style",
        "noscript", // Scripts and styles
        ".footer",
        ".header", // Headers/footers
        ".copyright",
        ".legal", // Legal notices
        "#navigation",
        "#nav" // Navigation IDs
    };

    // CSS selectors for main content areas (in priority order)
    private static final String[] CONTENT_SELECTORS = {
        "main", // HTML5 main element
        "article", // HTML5 article element
        ".content-container", // Common content class
        ".main-content", // Common content class
        ".documentation", // Documentation class
        ".doc-content", // Documentation content
        "#content", // Content ID
        ".block", // Java doc block
        ".description", // Description sections
        ".detail", // Detail sections
        ".summary" // Summary sections
    };

    /**
     * Extract clean content from HTML document, filtering out navigation
     * and JavaScript warnings.
     */
    public String extractCleanContent(Document doc) {
        // First, remove all non-content elements
        for (String selector : REMOVE_SELECTORS) {
            doc.select(selector).remove();
        }

        // Try to find main content area
        Element contentElement = findMainContent(doc);
        if (contentElement == null) {
            contentElement = doc.body();
        }

        // Extract and clean the text
        String text = extractTextFromElement(contentElement);

        // Filter out common noise patterns
        return filterNoise(text);
    }

    /**
     * Find the main content element in the document.
     */
    private Element findMainContent(Document doc) {
        for (String selector : CONTENT_SELECTORS) {
            Elements elements = doc.select(selector);
            if (!elements.isEmpty()) {
                // Return the largest content area if multiple found
                return elements.stream()
                        .max((e1, e2) ->
                                Integer.compare(e1.text().length(), e2.text().length()))
                        .orElse(elements.first());
            }
        }

        // Fallback: look for divs with significant text content
        Elements divs = doc.select("div");
        return divs.stream()
                .filter(div -> {
                    String text = div.text();
                    // Must have substantial content and not be navigation
                    return text.length() > 500 && !isNavigationElement(div) && !containsExcessiveNoise(text);
                })
                .max((e1, e2) -> Integer.compare(e1.text().length(), e2.text().length()))
                .orElse(null);
    }

    /**
     * Extract text from element with better formatting.
     */
    private String extractTextFromElement(Element element) {
        StringBuilder sb = new StringBuilder();
        boolean hasStructuredContent = !element.select("h1, h2, h3, h4, h5, h6").isEmpty()
                || !element.select("p").isEmpty();

        if (hasStructuredContent) {
            element.children().forEach(child -> appendFormattedChild(child, sb));
        } else {
            sb.append(element.text());
        }
        return sb.toString().trim();
    }

    private void appendFormattedChild(Element child, StringBuilder sb) {
        String text = child.text().trim();
        if (text.isEmpty()) {
            return;
        }
        switch (child.tagName()) {
            case "h1", "h2", "h3", "h4", "h5", "h6" ->
                sb.append("\n\n").append(text).append("\n");
            case String tag
            when ("p".equals(tag) || "div".equals(tag)) && !isNavigationElement(child) ->
                sb.append("\n").append(text);
            case "pre", "code" -> sb.append("\n```\n").append(child.wholeText()).append("\n```\n");
            case "ul", "ol" -> {
                appendListItems(child, sb, 0);
                sb.append("\n");
            }
            case "table" -> appendTableContent(child, sb);
            default -> {
                if (!isNavigationElement(child) && text.length() > MIN_INLINE_TEXT_LENGTH) {
                    sb.append(" ").append(text);
                }
            }
        }
    }

    private void appendTableContent(Element table, StringBuilder sb) {
        Elements rows = table.select("tr");
        rows.forEach(row -> {
            Elements cells = row.select("td, th");
            String rowText = cells.stream().map(Element::text).collect(Collectors.joining(" | "));
            sb.append("\n").append(rowText);
        });
        sb.append("\n");
    }

    /**
     * Check if element is likely navigation.
     */
    private boolean isNavigationElement(Element element) {
        String className = AsciiTextNormalizer.toLowerAscii(element.className());
        String id = AsciiTextNormalizer.toLowerAscii(element.id());
        String text = AsciiTextNormalizer.toLowerAscii(element.text());

        return className.contains("nav")
                || className.contains("menu")
                || className.contains("sidebar")
                || className.contains("header")
                || className.contains("footer")
                || id.contains("nav")
                || id.contains("menu")
                || text.startsWith("skip")
                || text.startsWith("hide")
                || text.startsWith("show");
    }

    private void appendListItems(Element listElement, StringBuilder sb, int depth) {
        if (listElement == null) {
            return;
        }
        for (Element li : listElement.children()) {
            if (!"li".equals(li.tagName())) {
                continue;
            }
            String itemText = li.ownText().trim();
            if (!itemText.isEmpty()) {
                sb.append("\n");
                if (depth > 0) {
                    sb.append("  ".repeat(depth));
                }
                sb.append("• ").append(itemText);
            }
            Elements nestedLists = li.select("> ul, > ol");
            if (!nestedLists.isEmpty()) {
                for (Element nestedList : nestedLists) {
                    appendListItems(nestedList, sb, depth + 1);
                }
            }
        }
    }

    /**
     * Check if text contains excessive noise.
     */
    private boolean containsExcessiveNoise(String text) {
        int noiseCount = 0;
        String lowerText = AsciiTextNormalizer.toLowerAscii(text);

        for (String noise : NOISE_PATTERNS) {
            if (lowerText.contains(AsciiTextNormalizer.toLowerAscii(noise))) {
                noiseCount++;
            }
        }

        // If more than 3 noise patterns, likely navigation/footer
        return noiseCount > 3;
    }

    /**
     * Filter out remaining noise patterns from text.
     */
    private String filterNoise(String text) {
        String[] lines = text.split("\n");
        StringBuilder cleaned = new StringBuilder();
        boolean inCodeFence = false;

        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith("```")) {
                inCodeFence = !inCodeFence;
                cleaned.append(line).append("\n");
            } else if (inCodeFence) {
                cleaned.append(line).append("\n");
            } else if (trimmed.isEmpty()) {
                cleaned.append("\n");
            } else if (!isNoiseLine(trimmed) && (trimmed.length() > 3 || startsWithUppercaseLetter(trimmed))) {
                cleaned.append(line).append("\n");
            }
        }

        return normalizeWhitespaceOutsideCodeFences(cleaned.toString()).trim();
    }

    private boolean isNoiseLine(String trimmedLine) {
        String trimmedLower = AsciiTextNormalizer.toLowerAscii(trimmedLine);
        return NOISE_PATTERNS.stream().anyMatch(noise -> trimmedLower.equals(AsciiTextNormalizer.toLowerAscii(noise)));
    }

    private boolean startsWithUppercaseLetter(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        char firstCharacter = text.charAt(0);
        return firstCharacter >= 'A' && firstCharacter <= 'Z';
    }

    private String normalizeWhitespaceOutsideCodeFences(String text) {
        StringBuilder normalized = new StringBuilder(text.length());
        boolean inFence = false;
        int newlineRun = 0;
        int index = 0;
        while (index < text.length()) {
            if (isCodeFenceAt(text, index)) {
                normalized.append(CODE_FENCE_MARKER);
                index += CODE_FENCE_LENGTH;
                inFence = !inFence;
                newlineRun = 0;
            } else if (inFence) {
                normalized.append(text.charAt(index));
                index++;
            } else {
                newlineRun = appendCollapsedWhitespace(text.charAt(index), normalized, newlineRun);
                index++;
            }
        }
        return normalized.toString();
    }

    private static boolean isCodeFenceAt(String text, int index) {
        return index + (CODE_FENCE_LENGTH - 1) < text.length()
                && text.charAt(index) == '`'
                && text.charAt(index + 1) == '`'
                && text.charAt(index + 2) == '`';
    }

    private static int appendCollapsedWhitespace(char character, StringBuilder normalized, int newlineRun) {
        if (character == '\n') {
            int updatedRun = newlineRun + 1;
            if (updatedRun <= MAX_CONSECUTIVE_NEWLINES) {
                normalized.append(character);
            }
            return updatedRun;
        }
        if (character == ' ' && shouldCollapseSpace(normalized)) {
            return 0;
        }
        normalized.append(character);
        return 0;
    }

    private static boolean shouldCollapseSpace(StringBuilder sb) {
        if (sb.isEmpty()) {
            return false;
        }
        char previous = sb.charAt(sb.length() - 1);
        return previous == ' ' || previous == '\n';
    }

    /**
     * Extract Java API-specific content with focus on class/method documentation.
     */
    public String extractJavaApiContent(Document doc) {
        // For Java API docs, focus on specific content areas
        StringBuilder content = new StringBuilder();

        // Class/Interface name and description
        Elements classHeaders = doc.select(".header h1, .header h2, .title");
        classHeaders.forEach(header -> content.append(header.text()).append("\n\n"));

        // Package info
        Elements packageInfo = doc.select(".subTitle, .package");
        packageInfo.forEach(pkgElement ->
                content.append("Package: ").append(pkgElement.text()).append("\n"));

        // Main description
        Elements descriptions = doc.select(".description, .block");
        descriptions.forEach(descElement -> {
            String text = descElement.text();
            if (!containsExcessiveNoise(text)) {
                content.append("\n").append(text).append("\n");
            }
        });

        // Method summaries
        Elements methodSummaries = doc.select(".summary .memberSummary");
        if (!methodSummaries.isEmpty()) {
            content.append("\n\nMethod Summary:\n");
            methodSummaries.forEach(methodSummary ->
                    content.append("• ").append(methodSummary.text()).append("\n"));
        }

        // Method details
        Elements methodDetails = doc.select(".details .memberDetails");
        if (!methodDetails.isEmpty()) {
            content.append("\n\nMethod Details:\n");
            methodDetails.forEach(methodDetail -> {
                String text = methodDetail.text();
                if (!containsExcessiveNoise(text)) {
                    content.append(text).append("\n\n");
                }
            });
        }

        // Code examples
        Elements codeExamples = doc.select("pre.code, pre.prettyprint");
        if (!codeExamples.isEmpty()) {
            content.append("\n\nCode Examples:\n");
            codeExamples.forEach(
                    code -> content.append("```java\n").append(code.text()).append("\n```\n\n"));
        }

        String result = content.toString().trim();

        // If we didn't get much content, fall back to general extraction
        if (result.length() < 100) {
            return extractCleanContent(doc);
        }

        return filterNoise(result);
    }
}
