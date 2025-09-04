package com.williamcallahan.javachat.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.regex.Pattern;

/**
 * Service for rendering Markdown to HTML with optimal formatting and caching.
 * Configured for clean output with proper spacing and code block support.
 */
@Service
public class MarkdownService {
    
    private static final Logger logger = LoggerFactory.getLogger(MarkdownService.class);
    private static final int MAX_INPUT_LENGTH = 100000; // 100KB max
    private static final int CACHE_SIZE = 500;
    private static final Duration CACHE_DURATION = Duration.ofMinutes(30);
    
    private final Parser parser;
    private final HtmlRenderer renderer;
    private final Cache<String, String> renderCache;
    
    // Pattern for custom enrichment markers
    private static final Pattern ENRICHMENT_PATTERN = Pattern.compile(
        "\\{\\{(hint|reminder|background|example|warning):([\\s\\S]*?)\\}\\}",
        Pattern.MULTILINE
    );
    
    public MarkdownService() {
        // Configure Flexmark with optimal settings
        MutableDataSet options = new MutableDataSet()
            // Core extensions for GitHub Flavored Markdown
            .set(Parser.EXTENSIONS, Arrays.asList(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListExtension.create(),
                AutolinkExtension.create()
            ))
            
            // Parser options
            .set(Parser.BLANK_LINES_IN_AST, false) // Don't preserve blank lines in AST
            .set(Parser.HTML_BLOCK_DEEP_PARSER, false) // Security: don't parse HTML deeply
            .set(Parser.INDENTED_CODE_NO_TRAILING_BLANK_LINES, true) // Clean code blocks
            
            // Renderer options for clean output
            .set(HtmlRenderer.ESCAPE_HTML, true) // Escape raw HTML input for XSS protection
            .set(HtmlRenderer.SUPPRESS_HTML, false) // Allow markdown-generated HTML output
            .set(HtmlRenderer.SOFT_BREAK, "<br />\n") // Line breaks as <br>
            .set(HtmlRenderer.HARD_BREAK, "<br />\n") // Consistent line breaks
            .set(HtmlRenderer.FENCED_CODE_LANGUAGE_CLASS_PREFIX, "language-") // For Prism.js
            .set(HtmlRenderer.INDENT_SIZE, 2) // Clean indentation
            
            // Table rendering options
            .set(TablesExtension.COLUMN_SPANS, false)
            .set(TablesExtension.APPEND_MISSING_COLUMNS, true)
            .set(TablesExtension.DISCARD_EXTRA_COLUMNS, true)
            .set(TablesExtension.HEADER_SEPARATOR_COLUMN_MATCH, true);
        
        this.parser = Parser.builder(options).build();
        this.renderer = HtmlRenderer.builder(options).build();
        
        // Initialize cache
        this.renderCache = Caffeine.newBuilder()
            .maximumSize(CACHE_SIZE)
            .expireAfterWrite(CACHE_DURATION)
            .recordStats()
            .build();
        
        logger.info("MarkdownService initialized with Flexmark and caching");
    }
    
    /**
     * Renders markdown to HTML with caching and optimal formatting.
     * 
     * @param markdown The markdown text to render
     * @return Clean HTML output with proper spacing
     */
    public String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        
        String original = markdown; // Keep original for logging
        
        if (markdown.length() > MAX_INPUT_LENGTH) {
            logger.warn("Markdown input exceeds maximum length: {} > {}", 
                       markdown.length(), MAX_INPUT_LENGTH);
            markdown = markdown.substring(0, MAX_INPUT_LENGTH);
        }
        
        // Check cache first
        String cached = renderCache.getIfPresent(markdown);
        if (cached != null) {
            logger.debug("Cache hit for markdown rendering");
            return cached;
        }
        
        try {
            // Pre-process to fix common markdown formatting issues
            markdown = preprocessMarkdown(markdown);
            
            // LOG to see if preprocessing is working
            if (!markdown.equals(original)) {
                logger.info("Preprocessing changed markdown: added {} paragraph breaks, {} list fixes", 
                           markdown.split("\n\n").length - original.split("\n\n").length,
                           markdown.contains("\n-") || markdown.contains("\n1.") ? "YES" : "NO");
            }
            
            // Pre-process custom enrichments (preserve them)
            String preprocessed = preserveEnrichments(markdown);
            
            // Parse and render markdown
            Node document = parser.parse(preprocessed);
            String html = renderer.render(document);
            
            // Post-process for clean output
            html = postProcessHtml(html);
            
            // Restore custom enrichments
            html = restoreEnrichments(html);
            
            // Cache the result
            renderCache.put(markdown, html);
            
            return html;
        } catch (Exception e) {
            logger.error("Error rendering markdown", e);
            // Fallback to escaped plain text
            return escapeHtml(markdown).replace("\n", "<br />\n");
        }
    }
    
    /**
     * Renders markdown without caching (for preview/draft content).
     */
    public String renderPreview(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }
        
        try {
            // Pre-process to fix common markdown formatting issues
            markdown = preprocessMarkdown(markdown);
            String preprocessed = preserveEnrichments(markdown);
            Node document = parser.parse(preprocessed);
            String html = renderer.render(document);
            html = postProcessHtml(html);
            html = restoreEnrichments(html);
            return html;
        } catch (Exception e) {
            logger.error("Error rendering preview markdown", e);
            return escapeHtml(markdown).replace("\n", "<br />\n");
        }
    }
    
    /**
     * Pre-processes markdown to fix common formatting issues.
     * Ensures lists and code blocks are properly separated from preceding text.
     */
    public String preprocessMarkdown(String markdown) {
        if (markdown == null) return "";

        // Preserve inline code spans so we don't mutate their contents during preprocessing
        String preserved = preserveInlineCode(markdown);

        // Fix inline lists that run together or are attached to punctuation.
        preserved = fixInlineLists(preserved);

        // Apply smart paragraph breaking, but never when list markers are present.
        if (!hasListMarkers(preserved)) {
            preserved = applySmartParagraphBreaksImproved(preserved);
        }

        // Ensure proper separation for code fences. This is the most reliable way to handle fences.
        preserved = ensureFenceSeparation(preserved);

        // Restore inline code placeholders back to their original `code` markdown.
        preserved = restoreInlineCode(preserved);

        return preserved;
    }

    /**
     * Ensures a blank paragraph (\n\n) exists before every opening code fence (```),
     * while not altering closing fences inside code blocks.
     * ENHANCED: Simplified and more robust fence detection that works with preprocessing artifacts.
     */
    private String ensureFenceSeparation(String s) {
        if (s == null || !s.contains("```")) return s;

        StringBuilder out = new StringBuilder(s.length() + 16);
        boolean inCodeBlock = false;

        String[] lines = s.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("```")) {
                if (!inCodeBlock) {
                    // This is an opening fence. Ensure it's preceded by a blank line.
                    if (out.length() > 0 && !out.toString().endsWith("\n\n")) {
                        if (out.toString().endsWith("\n")) {
                            out.append("\n");
                        } else {
                            out.append("\n\n");
                        }
                    }
                }
                inCodeBlock = !inCodeBlock;
            }
            out.append(line);
            if (i < lines.length - 1) {
                out.append("\n");
            }
        }
        return out.toString();
    }
    

    
    /**
     * COMPREHENSIVE list formatting - handles ALL list types reliably.
     * Supports numbered lists, roman numerals, letters, bullets, and special markers.
     */
    private String fixInlineLists(String markdown) {
        // Support ALL list types:
        // - Arabic numerals: 1. 2. 3. or 1) 2) 3)
        // - Roman numerals: i. ii. iii. or I. II. III.
        // - Letters: a. b. c. or A. B. C. or a) b) c)
        // - Bullets: - * + • → ▸ ◆ □ ▪
        
        // STEP 1: Fix lists after colons (highest confidence)
        // Pattern: "text:1. item" or "text:- item" etc
        
        // Numbered lists after colon
        if (markdown.matches("(?s).*:\\s*\\d+[.)]\\s+.*")) {
            markdown = markdown.replaceAll("(:\\s*)(\\d+[.)]\\s+)", "$1\n\n$2");
            // Break subsequent numbers
            markdown = markdown.replaceAll("(?<!\\n)(\\s+)(\\d+[.)]\\s+)", "\n$2");
            logger.debug("Fixed numbered list after colon");
        }
        
        // Roman numerals after colon (lowercase)
        if (markdown.matches("(?s).*:\\s*(?:i{1,3}|iv|v|vi{0,3}|ix|x)[.)]\\s+.*")) {
            markdown = markdown.replaceAll("(:\\s*)((?:i{1,3}|iv|v|vi{0,3}|ix|x)[.)]\\s+)", "$1\n\n$2");
            markdown = markdown.replaceAll("(?<!\\n)(\\s+)((?:i{1,3}|iv|v|vi{0,3}|ix|x)[.)]\\s+)", "\n$2");
            logger.debug("Fixed roman numeral list after colon");
        }
        
        // Letters after colon  
        if (markdown.matches("(?s).*:\\s*[a-zA-Z][.)]\\s+.*")) {
            markdown = markdown.replaceAll("(:\\s*)([a-zA-Z][.)]\\s+)", "$1\n\n$2");
            markdown = markdown.replaceAll("(?<!\\n)(\\s+)([a-zA-Z][.)]\\s+)", "\n$2");
            logger.debug("Fixed letter list after colon");
        }
        
        // Bullet lists after colon (including special characters)
        String bullets = "[-*+•→▸◆□▪]";
        if (markdown.matches("(?s).*:\\s*" + bullets + "\\s+.*")) {
            markdown = markdown.replaceAll("(:\\s*)(" + bullets + "\\s+)", "$1\n\n$2");
            markdown = markdown.replaceAll("(?<!\\n)(\\s+)(" + bullets + "\\s+)", "\n$2");
            logger.debug("Fixed bullet list after colon");
        }
        
        // STEP 2: Fix multiple inline numbered items (moderate confidence)
        // Pattern: "The types are 1. boolean 2. byte 3. int"
        if (markdown.matches("(?s).*\\b(are|include|includes|such as|follows?)\\s+\\d+[.)]\\s+.*\\d+[.)]\\s+.*")) {
            markdown = markdown.replaceAll(
                "\\b(are|include|includes|such as|follows?)\\s+(\\d+[.)]\\s+)",
                "$1\n\n$2"
            );
            markdown = markdown.replaceAll("(?<!\\n)(\\s+)(\\d+[.)]\\s+)", "\n$2");
            logger.debug("Fixed inline numbered list with intro phrase");
        }

        // STEP 2.5: Fix inline numbered lists without trigger words (NEW)
        // Pattern: "Key points 1. First 2. Second 3. Third" - detect multiple sequential numbers
        // Only apply when NO trigger words are present to avoid interfering with STEP 2
        if (!markdown.matches("(?s).*\\b(are|include|includes|such as|follows?)\\s+\\d+[.)]\\s+.*") &&
            markdown.matches("(?s).*\\b\\d+[.)]\\s+.*\\b\\d+[.)]\\s+.*\\b\\d+[.)]\\s+.*")) {
            // Find the first numbered item and ensure it's on a new line
            markdown = markdown.replaceAll("(?<!\\n|^)(\\d+[.)]\\s+)", "\n$1");
            logger.debug("Fixed inline numbered list without trigger words");
        }
        
        // STEP 3: Direct punctuation attachment (no space)
        // Pattern: "text:1." or "text:-" etc
        markdown = markdown.replaceAll(
            "([:.!?;,])(?=\\d+[.)]\\s+|" + bullets + "\\s+|[a-zA-Z][.)]\\s+)",
            "$1\n\n"
        );
        
        return markdown;
    }

    /**
     * Detect if the text contains markdown list markers at line starts.
     * Used to avoid paragraph-breaking around list structures.
     */
    private boolean hasListMarkers(String text) {
        if (text == null || text.isEmpty()) return false;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?m)^(\\s*)(?:[-+*]|\\d+\\.)\\s+");
        return p.matcher(text).find();
    }

    /**
     * Replace inline code spans `code` with placeholders carrying base64 content to avoid
     * punctuation/paragraph mutations inside code. Restored before parsing markdown.
     */
    private String preserveInlineCode(String text) {
        if (text == null || text.indexOf('`') < 0) return text;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("`([^`]+)`");
        java.util.regex.Matcher m = p.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String code = m.group(1);
            String b64 = java.util.Base64.getEncoder().encodeToString(code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            m.appendReplacement(sb, "ZZINLCODESTART" + b64 + "ZZINLCODEEND");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Restore inline code placeholders back to markdown `code`.
     */
    private String restoreInlineCode(String text) {
        if (text == null || text.indexOf('Z') < 0) return text;
        // Use a NON-GREEDY capture to avoid spanning across multiple placeholders
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("ZZINLCODESTART([A-Za-z0-9+/=]+?)ZZINLCODEEND");
        java.util.regex.Matcher m = p.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String b64 = m.group(1);
            // Normalize any accidental whitespace and fix padding
            b64 = b64.replaceAll("\\s+", "");
            int mod = b64.length() % 4;
            if (mod != 0) {
                // Pad with '=' to nearest multiple of 4
                b64 = b64 + "===".substring(0, 4 - mod);
            }
            String code;
            try {
                code = new String(java.util.Base64.getDecoder().decode(b64), java.nio.charset.StandardCharsets.UTF_8);
            } catch (IllegalArgumentException ex) {
                // If decode still fails, do not crash the pipeline; fall back to showing raw content
                logger.warn("Failed to Base64-decode inline code placeholder; leaving as-is. length={} err={}", b64.length(), ex.getMessage());
                code = b64; // degrade gracefully
            }
            // rewrap with backticks
            String replacement = "`" + java.util.regex.Matcher.quoteReplacement(code) + "`";
            m.appendReplacement(sb, replacement);
        }
        m.appendTail(sb);
        return sb.toString();
    }
    
    /**
     * Protects code block content from being consumed or altered by other regex patterns.
     * Ensures code blocks maintain their content integrity.
     */
    private String protectCodeBlocks(String markdown) {
        if (markdown == null) return ""; // Guard against null input
        logger.debug("protectCodeBlocks: ENTERING with input: {}", markdown.replace("\n", "\\\\n"));
        logger.debug("protectCodeBlocks: Input contains ``` ? {}", markdown.contains("```"));
        
        // Match code blocks and ensure they have proper structure
        StringBuilder result = new StringBuilder();
        // ENHANCED: Support comprehensive language tags including java, cpp, c++, objective-c, etc.
        // Handle preprocessing artifacts and edge cases with more robust pattern
        java.util.regex.Pattern codeBlockPattern =
            java.util.regex.Pattern.compile("```([\\w\\-\\+\\.]*)\\s*\\n?([\\s\\S]*?)```",
                                           java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = codeBlockPattern.matcher(markdown);
        int blocks = 0;        
        int lastEnd = 0;
        while (matcher.find()) {
            // Append text before code block
            result.append(markdown.substring(lastEnd, matcher.start()));

            String language = matcher.group(1);
            String code = matcher.group(2);

            blocks++;
            // Ensure code content is not empty and properly formatted
            if (code != null && !code.trim().isEmpty()) {
                // Preserve the code block with proper formatting
                // CRITICAL: Ensure paragraph break BEFORE code block if not at start
                if (result.length() > 0 && !result.toString().endsWith("\n\n")) {
                    result.append("\n\n");
                }
                result.append("```").append(language).append("\n");
                result.append(code.trim());
                result.append("\n```\n\n");
                logger.debug("Code block preserved: language='{}', {} characters", language, code.length());
            } else {
                // Handle empty code blocks gracefully
                logger.debug("Empty code block detected at position {}, language: '{}'",
                           matcher.start(), language);
                if (result.length() > 0 && !result.toString().endsWith("\n\n")) {
                    result.append("\n\n");
                }
                result.append("```").append(language).append("\n");
                result.append("// Code block content missing - check streaming");
                result.append("\n```\n\n");
            }

            lastEnd = matcher.end();
        }
        
        // Append remaining text
        if (lastEnd < markdown.length()) {
            result.append(markdown.substring(lastEnd));
        }
        
        String out = result.toString();

        // ENHANCED: Fallback detection for edge cases where regex fails
        if (blocks == 0 && markdown.contains("```")) {
            // Try fallback pattern for cases with malformed language tags or preprocessing artifacts
            java.util.regex.Pattern fallbackPattern =
                java.util.regex.Pattern.compile("```([^\\n]*)\\s*([\\s\\S]*?)```",
                                               java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher fallbackMatcher = fallbackPattern.matcher(markdown);

            if (fallbackMatcher.find()) {
                logger.debug("protectCodeBlocks: using fallback pattern for edge case");
                // Re-process with fallback pattern
                result = new StringBuilder();
                lastEnd = 0;
                blocks = 0;

                while (fallbackMatcher.find()) {
                    result.append(markdown.substring(lastEnd, fallbackMatcher.start()));
                    String language = fallbackMatcher.group(1);
                    String code = fallbackMatcher.group(2);

                    blocks++;
                    if (code != null && !code.trim().isEmpty()) {
                        if (result.length() > 0 && !result.toString().endsWith("\n\n")) {
                            result.append("\n\n");
                        }
                        result.append("```").append(language).append("\n");
                        result.append(code.trim());
                        result.append("\n```\n\n");
                        logger.debug("Code block preserved via fallback: language='{}', {} characters", language, code.length());
                    }
                    lastEnd = fallbackMatcher.end();
                }

                if (lastEnd < markdown.length()) {
                    result.append(markdown.substring(lastEnd));
                }

                out = result.toString();
                logger.debug("protectCodeBlocks: fallback matched {} block(s)", blocks);
            } else {
                logger.warn("protectCodeBlocks: no blocks matched even with fallback; input contains fence. Sample={}...",
                           markdown.substring(0, Math.min(80, markdown.length())).replace("\n","\\n"));
            }
        } else {
            logger.debug("protectCodeBlocks: matched {} block(s)", blocks);
        }
        return out;
    }
    
    /**
     * Post-processes HTML for optimal spacing and formatting.
     */
    private String postProcessHtml(String html) {
        // CRITICAL: Ensure space between sentences in HTML content
        // Fixes cases where tags might have removed spaces
        html = html.replaceAll("([.!?])(<[^>]+>)?([A-Z])", "$1$2 $3");
        
        // Remove any leading spaces from paragraph starts
        html = html.replaceAll("<p>\\s+", "<p>");
        
        // IMPORTANT: Preserve line breaks - only collapse excessive ones (more than 3)
        // This maintains intentional paragraph breaks
        html = html.replaceAll("(\n\\s*){4,}", "\n\n\n");
        
        // Ensure proper paragraph spacing (maintain separation)
        html = html.replaceAll("</p>\\s*<p>", "</p>\n\n<p>");
        
        // Clean up list spacing
        html = html.replaceAll("</li>\\s*<li>", "</li>\n<li>");
        
        // Remove only truly empty paragraphs (no content at all)
        html = html.replaceAll("<p>\\s*</p>", "");
        
        // Ensure proper spacing around code blocks with ALL elements
        html = html.replaceAll("</pre>\\s*<p>", "</pre>\n\n<p>");
        html = html.replaceAll("</p>\\s*<pre>", "</p>\n\n<pre>");
        html = html.replaceAll("</ol>\\s*<pre>", "</ol>\n\n<pre>");
        html = html.replaceAll("</ul>\\s*<pre>", "</ul>\n\n<pre>");
        html = html.replaceAll("</pre>\\s*<ol>", "</pre>\n\n<ol>");
        html = html.replaceAll("</pre>\\s*<ul>", "</pre>\n\n<ul>");
        
        // Ensure proper spacing between paragraphs and lists
        html = html.replaceAll("</p>\\s*<ol>", "</p>\n\n<ol>");
        html = html.replaceAll("</p>\\s*<ul>", "</p>\n\n<ul>");
        html = html.replaceAll("</ol>\\s*<p>", "</ol>\n\n<p>");
        html = html.replaceAll("</ul>\\s*<p>", "</ul>\n\n<p>");
        
        // Ensure proper spacing around enrichment placeholders (now text placeholders)
        html = html.replaceAll("(ZZENRICHZ\\w+ZSTARTZZZ)", "\n$1");
        html = html.replaceAll("(ZZENRICHZ\\w+ZENDZZZ)", "$1\n");
        
        // Clean up table formatting
        html = html.replaceAll("</tr>\\s*<tr>", "</tr>\n<tr>");
        
        // Add classes for styling
        html = html.replace("<table>", "<table class=\"markdown-table\">");
        html = html.replace("<blockquote>", "<blockquote class=\"markdown-quote\">");
        
        return html.trim();
    }
    
    /**
     * Improved paragraph breaking that supports '.', '?', '!' and respects code blocks.
     */
    private String applySmartParagraphBreaksImproved(String markdown) {
        if (markdown == null || markdown.isEmpty()) return markdown;
        // If code blocks are present, process only non-code segments to preserve code
        if (markdown.contains("```") ) {
            StringBuilder out = new StringBuilder();
            // FIXED: Consistent code block pattern with protectCodeBlocks method
            java.util.regex.Pattern codeBlockPattern = java.util.regex.Pattern.compile("```[\\w-]*\n?[\\s\\S]*?```", java.util.regex.Pattern.DOTALL);
            java.util.regex.Matcher matcher = codeBlockPattern.matcher(markdown);
            int last = 0;
            while (matcher.find()) {
                String before = markdown.substring(last, matcher.start());
                out.append(applySmartParagraphBreaksNoCode(before));
                out.append(matcher.group());
                last = matcher.end();
            }
            if (last < markdown.length()) {
                out.append(applySmartParagraphBreaksNoCode(markdown.substring(last)));
            }
            return out.toString();
        }
        return applySmartParagraphBreaksNoCode(markdown);
    }

    /**
     * Adds paragraph breaks to text without code blocks.
     * Handles '.', '?', '!' ends and respects closing quotes/parentheses.
     * Avoids abbreviations and ordered-list false positives.
     */
    private String applySmartParagraphBreaksNoCode(String text) {
        if (text == null || text.isEmpty()) return text;
        if (text.contains("\n\n")) return text; // honor existing paragraphs

        // Improved approach: find sentence boundaries and insert breaks
        StringBuilder result = new StringBuilder();
        java.util.regex.Pattern sentenceEnd = java.util.regex.Pattern.compile(
            "([.!?])([\"'\\)\\]]*)\\s+([A-Z])"
        );
        java.util.regex.Matcher matcher = sentenceEnd.matcher(text);
        
        int lastEnd = 0;
        int sentenceCount = 0;
        
        while (matcher.find()) {
            // Append text up to and including this sentence
            result.append(text.substring(lastEnd, matcher.end()));
            sentenceCount++;
            
            // Check if we should add a paragraph break
            if (sentenceCount >= 2) {
                String beforeBreak = text.substring(Math.max(0, matcher.start() - 10), matcher.start());
                
                // Don't break at abbreviations
                if (!beforeBreak.matches(".*\\b(e\\.g|i\\.e|etc|Dr|Mr|Mrs|Ms|Jr|Sr|St|No)$")) {
                    // Check if next text starts with a number (potential list)
                    String nextChar = matcher.group(3);
                    if (!Character.isDigit(nextChar.charAt(0))) {
                        // Insert paragraph break before the capital letter
                        result.setLength(result.length() - nextChar.length());
                        result.append("\n\n").append(nextChar);
                        sentenceCount = 0;
                    }
                }
            }
            
            lastEnd = matcher.end();
        }
        
        // Append any remaining text
        if (lastEnd < text.length()) {
            result.append(text.substring(lastEnd));
        }

        String processed = result.toString().trim();
        logger.debug("Paragraph breaking: added {} breaks", processed.split("\n\n").length - 1);
        return processed;
    }



    /**
     * Preserves custom enrichment markers during markdown processing.
     * Uses unique placeholders that won't be affected by markdown parsing or HTML filtering.
     */
    private String preserveEnrichments(String markdown) {
        // Log if we're about to process enrichments
        if (markdown.contains("{{")) {
            logger.debug("Processing enrichment markers in markdown");
        }
        
        // Replace enrichment markers with unique placeholders that won't be processed by markdown
        // Using a format that avoids markdown special characters (no underscores, asterisks, etc.)
        return ENRICHMENT_PATTERN.matcher(markdown).replaceAll(
            "ZZENRICHZ$1ZSTARTZZZ$2ZZENRICHZ$1ZENDZZZ"
        );
    }
    
    /**
     * Restores custom enrichment markers after markdown processing.
     * Works with unique text placeholders that survive HTML processing.
     */
    private String restoreEnrichments(String html) {
        // Restore from unique text placeholders ONLY if they have content
        // Pattern: ZZENRICHZ(type)ZSTARTZZZ(content)ZZENRICHZ(type)ZENDZZZ
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "ZZENRICHZ(\\w+)ZSTARTZZZ([\\s\\S]*?)ZZENRICHZ\\1ZENDZZZ"
        );
        java.util.regex.Matcher matcher = pattern.matcher(html);
        
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String type = matcher.group(1);
            String content = matcher.group(2);
            
            // Only restore if content is not empty
            if (content != null && !content.trim().isEmpty()) {
                matcher.appendReplacement(result, "{{" + type + ":" + content + "}}");
            } else {
                // Remove empty enrichment completely
                matcher.appendReplacement(result, "");
                logger.debug("Removed empty {} enrichment", type);
            }
        }
        matcher.appendTail(result);
        html = result.toString();
        
        // Clean up any HTML entities that might have been escaped in the content
        html = html.replace("&quot;", "\"");
        html = html.replace("&apos;", "'");
        html = html.replace("&#39;", "'");
        
        // Final cleanup: remove any empty enrichment patterns
        html = html.replaceAll("\\{\\{\\w+:\\s*\\}\\}", "");
        
        return html;
    }
    
    /**
     * Escapes HTML for security.
     */
    private String escapeHtml(String text) {
        if (text == null) return "";
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }
    
    /**
     * Get cache statistics for monitoring.
     */
    public CacheStats getCacheStats() {
        var stats = renderCache.stats();
        return new CacheStats(
            stats.hitCount(),
            stats.missCount(),
            stats.evictionCount(),
            renderCache.estimatedSize()
        );
    }
    
    /**
     * Clear the render cache.
     */
    public void clearCache() {
        renderCache.invalidateAll();
        logger.info("Markdown render cache cleared");
    }
    
    /**
     * Cache statistics record.
     */
    public record CacheStats(
        long hitCount,
        long missCount,
        long evictionCount,
        long size
    ) {
        public double hitRate() {
            long total = hitCount + missCount;
            return total == 0 ? 0.0 : (double) hitCount / total;
        }
    }
    
}
