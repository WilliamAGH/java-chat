package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;


import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enhanced HTML content extraction that filters out navigation noise
 * and JavaScript warnings to produce cleaner documentation content.
 */
@Service
public class HtmlContentExtractor {
    
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
        "but does not change the content"
    );
    
    // CSS selectors for navigation and other non-content elements
    private static final String[] REMOVE_SELECTORS = {
        "nav", "header", "footer",           // Navigation elements
        ".navigation", ".nav", ".navbar",    // Navigation classes
        ".sidebar", ".toc", ".breadcrumb",   // Navigation components
        ".skip-nav", ".skip-link",           // Skip links
        "script", "style", "noscript",       // Scripts and styles
        ".footer", ".header",                 // Headers/footers
        ".copyright", ".legal",               // Legal notices
        "#navigation", "#nav"                 // Navigation IDs
    };
    
    // CSS selectors for main content areas (in priority order)
    private static final String[] CONTENT_SELECTORS = {
        "main",                               // HTML5 main element
        "article",                            // HTML5 article element
        ".content-container",                 // Common content class
        ".main-content",                      // Common content class
        ".documentation",                     // Documentation class
        ".doc-content",                       // Documentation content
        "#content",                           // Content ID
        ".block",                             // Java doc block
        ".description",                       // Description sections
        ".detail",                            // Detail sections
        ".summary"                            // Summary sections
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
                    .max((e1, e2) -> Integer.compare(
                        e1.text().length(), 
                        e2.text().length()))
                    .orElse(elements.first());
            }
        }
        
        // Fallback: look for divs with significant text content
        Elements divs = doc.select("div");
        return divs.stream()
            .filter(div -> {
                String text = div.text();
                // Must have substantial content and not be navigation
                return text.length() > 500 && 
                       !isNavigationElement(div) &&
                       !containsExcessiveNoise(text);
            })
            .max((e1, e2) -> Integer.compare(
                e1.text().length(), 
                e2.text().length()))
            .orElse(null);
    }
    
    /**
     * Extract text from element with better formatting.
     */
    private String extractTextFromElement(Element element) {
        // Use wholeText() for better preservation of structure
        StringBuilder sb = new StringBuilder();
        
        // Process specific elements for better formatting
        Elements headers = element.select("h1, h2, h3, h4, h5, h6");
        Elements paragraphs = element.select("p");
        
        // If we have structured content, process it
        if (!headers.isEmpty() || !paragraphs.isEmpty()) {
            element.children().forEach(child -> {
                String tagName = child.tagName();
                String text = child.text().trim();
                
                if (text.isEmpty()) return;
                
                switch (tagName) {
                    case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        sb.append("\n\n").append(text).append("\n");
                    }
                    case "p", "div" -> {
                        if (!isNavigationElement(child)) {
                            sb.append("\n").append(text);
                        }
                    }
                    case "pre", "code" -> {
                        String rawCode = child.wholeText();
                        sb.append("\n```\n").append(rawCode).append("\n```\n");
                    }
                    case "ul", "ol" -> {
                        child.select("li").forEach(li -> 
                            sb.append("\n• ").append(li.text()));
                        sb.append("\n");
                    }
                    case "table" -> {
                        // Extract table content in readable format
                        Elements rows = child.select("tr");
                        rows.forEach(row -> {
                            Elements cells = row.select("td, th");
                            String rowText = cells.stream()
                                .map(Element::text)
                                .collect(Collectors.joining(" | "));
                            sb.append("\n").append(rowText);
                        });
                        sb.append("\n");
                    }
                    default -> {
                        if (!isNavigationElement(child) && text.length() > 20) {
                            sb.append(" ").append(text);
                        }
                    }
                }
            });
        } else {
            // Fallback to simple text extraction
            sb.append(element.text());
        }
        
        return sb.toString().trim();
    }
    
    /**
     * Check if element is likely navigation.
     */
    private boolean isNavigationElement(Element element) {
        String className = AsciiTextNormalizer.toLowerAscii(element.className());
        String id = AsciiTextNormalizer.toLowerAscii(element.id());
        String text = AsciiTextNormalizer.toLowerAscii(element.text());
        
        return className.contains("nav") || 
               className.contains("menu") ||
               className.contains("sidebar") ||
               className.contains("header") ||
               className.contains("footer") ||
               id.contains("nav") ||
               id.contains("menu") ||
               text.startsWith("skip") ||
               text.startsWith("hide") ||
               text.startsWith("show");
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
                continue;
            }

            if (inCodeFence) {
                cleaned.append(line).append("\n");
                continue;
            }
            
            // Skip empty lines
            if (trimmed.isEmpty()) {
                cleaned.append("\n");
                continue;
            }
            
            // Skip lines that are pure noise
            String trimmedLower = AsciiTextNormalizer.toLowerAscii(trimmed);
            boolean isNoise = NOISE_PATTERNS.stream()
                .anyMatch(noise -> trimmedLower.equals(AsciiTextNormalizer.toLowerAscii(noise)));
            
            if (!isNoise) {
                // Also skip very short lines that are likely navigation
                if (trimmed.length() > 3 || startsWithUppercaseLetter(trimmed)) {
                    cleaned.append(line).append("\n");
                }
            }
        }
        
        // Clean up excessive whitespace without disturbing code fences
        return normalizeWhitespaceOutsideCodeFences(cleaned.toString()).trim();
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
            if (index + 2 < text.length()
                && text.charAt(index) == '`'
                && text.charAt(index + 1) == '`'
                && text.charAt(index + 2) == '`') {
                normalized.append("```");
                index += 3;
                inFence = !inFence;
                newlineRun = 0;
                continue;
            }

            char character = text.charAt(index);
            if (!inFence) {
                if (character == '\n') {
                    newlineRun++;
                    if (newlineRun <= 2) {
                        normalized.append(character);
                    }
                    index++;
                    continue;
                }
                newlineRun = 0;
                if (character == ' ') {
                    if (normalized.length() == 0) {
                        normalized.append(character);
                        index++;
                        continue;
                    }
                    char previous = normalized.charAt(normalized.length() - 1);
                    if (previous == ' ' || previous == '\n') {
                        index++;
                        continue;
                    }
                }
            }

            newlineRun = 0;
            normalized.append(character);
            index++;
        }
        return normalized.toString();
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
        packageInfo.forEach(pkgElement -> content.append("Package: ").append(pkgElement.text()).append("\n"));
        
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
            methodSummaries.forEach(methodSummary -> content.append("• ").append(methodSummary.text()).append("\n"));
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
            codeExamples.forEach(code -> 
                content.append("```java\n").append(code.text()).append("\n```\n\n"));
        }
        
        String result = content.toString().trim();
        
        // If we didn't get much content, fall back to general extraction
        if (result.length() < 100) {
            return extractCleanContent(doc);
        }
        
        return filterNoise(result);
    }
}
