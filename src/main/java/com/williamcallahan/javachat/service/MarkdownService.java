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
        // Protect inline code spans so we don't mutate their contents during preprocessing
        markdown = preserveInlineCode(markdown);

        // CRITICAL: Ensure single space after sentence-ending punctuation FIRST (outside code)
        // Fixes: "operation.For example" -> "operation. For example" while avoiding decimals
        markdown = markdown.replaceAll("(?<!\\d)([.!?])([\\\"'])?([A-Za-z(])", "$1$2 $3");
        
        // Fix inline lists FIRST - if we see "text. 1. Item 2. Item" pattern, it's a list!
        // This catches malformed lists from AI that forgot newlines
        markdown = fixInlineLists(markdown);
        
        // Apply smart paragraph breaking AFTER fixing lists, but never when list markers are present
        if (!hasListMarkers(markdown)) {
            markdown = applySmartParagraphBreaksImproved(markdown);
        }
        
        // DO NOT auto-detect lists from inline text like "are:1."
        // Only treat as list if it's at the start of a line or after explicit list marker
        // This prevents "are:1. boolean" from becoming a list
        // markdown = markdown.replaceAll(":\\s+(\\d+\\.\\s+[A-Z])", ":\n\n$1");
        // COMMENTED OUT - causing false positives
        
        // Fix inline bullet lists only in safe contexts to avoid false positives like math "x - y"
        // 1) After a colon: "such as:- Item" or "such as: - Item" -> break into a proper list
        // Also handle when items are concatenated like "- Item1- Item2"
        markdown = markdown.replaceAll("(?<=:)\\s*-\\s*(?=\\S)", "\n\n- ");
        markdown = markdown.replaceAll("(?<!^)(?<!\\n)-\\s+(?=[A-Z])", "\n- ");  // Line break before subsequent items
        markdown = markdown.replaceAll("(?<=:)\\s*\\*\\s+(?=\\S)", "\n\n* ");
        markdown = markdown.replaceAll("(?<=:)\\s*\\+\\s+(?=\\S)", "\n\n+ ");

        // 2) When multiple inline hyphen items appear, likely an inline list. Only convert when preceded by punctuation/closer
        // Detect two or more occurrences of "- Word" to reduce risk of converting a single minus usage
        if (markdown.matches("(?s).* - \\p{L}+.* - \\p{L}+.*")) {
            // Add a newline before "- " when it follows punctuation or a closing bracket/paren
            markdown = markdown.replaceAll("(?<=[:;,.\\)\\]])\\s*-\\s+(?=\\p{L})", "\n- ");
        }
        
        // Debug: show before code-fence adjustment
        if (markdown.contains("```")) {
            System.out.println("[DEBUG preprocess] Before fence adjust=\n" + markdown);
        }
        // Fix code blocks without preceding line break: ensure a paragraph break before any fence
        // e.g., "text:```" -> "text:\n\n```" and "text```" -> "text\n\n```"
        markdown = markdown.replaceAll("(?<!\\n)```", "\n\n```");
        if (markdown.contains("```")) {
            System.out.println("[DEBUG preprocess] After fence adjust=\n" + markdown);
        }
        
        // CRITICAL: Protect code block content from being consumed by other patterns
        // Ensure code blocks are properly delimited and content is preserved
        markdown = protectCodeBlocks(markdown);

        // Ensure headings don't run into body text on the same logical line (common with streaming)
        // Insert a paragraph break when an ATX heading appears to be concatenated directly
        // with the next capitalized word (start of a sentence)
        // Example: "## TitleBody starts here" -> "## Title\n\nBody starts here"
        try {
            markdown = markdown.replaceAll("(?m)^(#{1,6}\\s+[^\\n]*?[a-z])([A-Z][a-z])", "$1\n\n$2");
        } catch (Exception ignored) {}
        
        // Clean up: collapse horizontal spaces (preserve newlines!)
        markdown = markdown.replaceAll("[ \\t]{2,}", " ");
        
        // Never allow leading spaces at start of lines (except in code blocks)
        markdown = markdown.replaceAll("(?m)^[ \\t]+(?!```)", "");
        
        // Restore inline code placeholders back to markdown
        markdown = restoreInlineCode(markdown);
        return markdown;
    }
    
    /**
     * Intelligently breaks long text into paragraphs.
     * Avoids breaking in inappropriate places like abbreviations or code.
     */
    private String applySmartParagraphBreaks(String markdown) {
        // Don't process code blocks
        if (markdown.contains("```")) {
            return markdown;
        }
        
        // CRITICAL: We MUST add paragraph breaks for proper rendering!
        
        // First check if there are already paragraph breaks
        if (markdown.contains("\n\n")) {
            // Already has breaks, don't add more
            return markdown;
        }
        
        // Split on sentence endings but keep the punctuation
        String[] sentences = markdown.split("(?<=\\. )");
        
        // If text is very short, don't break it
        if (sentences.length < 2) {
            return markdown;
        }
        
        StringBuilder result = new StringBuilder();
        int sentenceCount = 0;
        
        for (int i = 0; i < sentences.length; i++) {
            String sentence = sentences[i];
            result.append(sentence);
            sentenceCount++;
            
            // Add paragraph break every 2-3 sentences
            // Check various conditions for breaking
            boolean shouldBreak = false;
            
            if (sentenceCount >= 2 && i < sentences.length - 1) {
                // Don't break at abbreviations
                if (!sentence.endsWith("e.g. ") && 
                    !sentence.endsWith("i.e. ") && 
                    !sentence.endsWith("etc. ") &&
                    !sentence.endsWith("Dr. ") &&
                    !sentence.endsWith("Mr. ") &&
                    !sentence.endsWith("Mrs. ") &&
                    !sentence.endsWith("Ms. ")) {
                    shouldBreak = true;
                    sentenceCount = 0;
                }
            }
            
            if (shouldBreak) {
                // ADD THE CRITICAL DOUBLE NEWLINE FOR PARAGRAPH BREAK
                result.append("\n\n");
                
                // IMPORTANT: Check if next sentence starts with a number
                // If so, don't let it be at line start (would be treated as list)
                if (i < sentences.length - 1) {
                    String nextSentence = sentences[i + 1].trim();
                    if (nextSentence.matches("^\\d+\\..*")) {
                        // Next sentence starts with "N." - escape it to prevent list
                        // Add a zero-width space or backslash to prevent list detection
                        // Actually, just don't break here
                        result.delete(result.length() - 2, result.length()); // Remove the \n\n we just added
                        sentenceCount = 2; // Reset to 2 so we don't immediately break again
                        shouldBreak = false;
                    }
                }
                
                if (shouldBreak) {
                    logger.debug("Added paragraph break after: {}", 
                               sentence.substring(Math.max(0, sentence.length() - 30)));
                }
            }
        }
        
        String processed = result.toString().trim();
        logger.debug("Paragraph breaking: {} sentences -> {} paragraphs", 
                    sentences.length, processed.split("\n\n").length);
        return processed;
    }
    
    /**
     * Fixes inline lists that should have line breaks.
     * Detects patterns like "types are:1. boolean: desc. 2. byte: desc."
     * and converts them to proper list format.
     */
    private String fixInlineLists(String markdown) {
        // Pattern: text followed by "1." or continuing numbers
        // If we see "... 1. ... 2. ...", it's likely an inline list emitted by the model.
        if (markdown.matches("(?s).*\\b1\\.\\s+\\S.*\\b2\\.\\s+\\S.*")) {
            // This looks like an inline list - fix it!
            
            // First, ensure newline before "1."
            markdown = markdown.replaceAll("([A-Za-z:])\\s*(1\\.\\s+)", "$1\n\n$2");
            
            // Then add newlines before each subsequent number regardless of preceding char
            // Avoid matching decimals by requiring a space before the number token
            markdown = markdown.replaceAll("(\\s+)([2-9]\\d*\\.\\s+)", "\n$2");
            
            logger.debug("Fixed inline list formatting");
        }
        
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
        // Match code blocks and ensure they have proper structure
        StringBuilder result = new StringBuilder();
        // FIXED: Use [\s\S]*? to match ANY content including backticks inside code blocks
        // Also support uppercase, numbers, hyphens in language tags like client-side
        java.util.regex.Pattern codeBlockPattern = 
            java.util.regex.Pattern.compile("```([\\w-]*)\n?([\\s\\S]*?)```", 
                                           java.util.regex.Pattern.DOTALL);
        java.util.regex.Matcher matcher = codeBlockPattern.matcher(markdown);
        
        int lastEnd = 0;
        while (matcher.find()) {
            // Append text before code block
            result.append(markdown.substring(lastEnd, matcher.start()));
            
            String language = matcher.group(1);
            String code = matcher.group(2);
            
            // Ensure code content is not empty and properly formatted
            if (code != null && !code.trim().isEmpty()) {
                // Preserve the code block with proper formatting
                // CRITICAL: Ensure paragraph break BEFORE code block if not at start
                if (result.length() > 0 && !result.toString().endsWith("\n\n")) {
                    result.append("\n\n");
                }
                result.append("```").append(language).append("\n");
                result.append(code);
                if (!code.endsWith("\n")) {
                    result.append("\n");
                }
                result.append("```\n\n"); // CRITICAL: Double newline AFTER code block for separation
                logger.debug("Code block preserved with {} characters", code.length());
            } else {
                // Log warning about empty code block
                logger.warn("Empty code block detected at position {}, language: '{}'", 
                           matcher.start(), language);
                if (result.length() > 0 && !result.toString().endsWith("\n\n")) {
                    result.append("\n\n");
                }
                result.append("```").append(language).append("\n");
                result.append("// Code block content missing - check streaming\n");
                result.append("```\n\n"); // Double newline after
            }
            
            lastEnd = matcher.end();
        }
        
        // Append remaining text
        if (lastEnd < markdown.length()) {
            result.append(markdown.substring(lastEnd));
        }
        
        return result.toString();
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

    private boolean endsWithAbbreviation(String sentence) {
        String s = sentence == null ? "" : sentence.trim();
        // Common abbreviations where the trailing period doesn't end the sentence (case-insensitive)
        return s.matches("(?i).*(?:\\b(e\\.g|i\\.e|etc|mr|mrs|ms|dr|prof|vs|sr|jr|st|no))\\.\u00A0?\\s*$");
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
