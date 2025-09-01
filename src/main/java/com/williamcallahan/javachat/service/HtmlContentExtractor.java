package com.williamcallahan.javachat.service;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.util.HashSet;
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
        Elements lists = element.select("ul, ol");
        Elements code = element.select("pre, code");
        Elements tables = element.select("table");
        
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
                        sb.append("\n```\n").append(text).append("\n```\n");
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
        String className = element.className().toLowerCase();
        String id = element.id().toLowerCase();
        String text = element.text().toLowerCase();
        
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
        String lowerText = text.toLowerCase();
        
        for (String noise : NOISE_PATTERNS) {
            if (lowerText.contains(noise.toLowerCase())) {
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
        
        for (String line : lines) {
            String trimmed = line.trim();
            
            // Skip empty lines
            if (trimmed.isEmpty()) {
                cleaned.append("\n");
                continue;
            }
            
            // Skip lines that are pure noise
            boolean isNoise = NOISE_PATTERNS.stream()
                .anyMatch(noise -> trimmed.equalsIgnoreCase(noise));
            
            if (!isNoise) {
                // Also skip very short lines that are likely navigation
                if (trimmed.length() > 3 || trimmed.matches("^[A-Z].*")) {
                    cleaned.append(line).append("\n");
                }
            }
        }
        
        // Clean up excessive whitespace
        return cleaned.toString()
            .replaceAll("\n{3,}", "\n\n")  // Max 2 consecutive newlines
            .replaceAll(" {2,}", " ")       // Collapse multiple spaces
            .trim();
    }
    
    /**
     * Extract Java API-specific content with focus on class/method documentation.
     */
    public String extractJavaApiContent(Document doc) {
        // For Java API docs, focus on specific content areas
        StringBuilder content = new StringBuilder();
        
        // Class/Interface name and description
        Elements classHeaders = doc.select(".header h1, .header h2, .title");
        classHeaders.forEach(h -> content.append(h.text()).append("\n\n"));
        
        // Package info
        Elements packageInfo = doc.select(".subTitle, .package");
        packageInfo.forEach(p -> content.append("Package: ").append(p.text()).append("\n"));
        
        // Main description
        Elements descriptions = doc.select(".description, .block");
        descriptions.forEach(d -> {
            String text = d.text();
            if (!containsExcessiveNoise(text)) {
                content.append("\n").append(text).append("\n");
            }
        });
        
        // Method summaries
        Elements methodSummaries = doc.select(".summary .memberSummary");
        if (!methodSummaries.isEmpty()) {
            content.append("\n\nMethod Summary:\n");
            methodSummaries.forEach(m -> content.append("• ").append(m.text()).append("\n"));
        }
        
        // Method details
        Elements methodDetails = doc.select(".details .memberDetails");
        if (!methodDetails.isEmpty()) {
            content.append("\n\nMethod Details:\n");
            methodDetails.forEach(m -> {
                String text = m.text();
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