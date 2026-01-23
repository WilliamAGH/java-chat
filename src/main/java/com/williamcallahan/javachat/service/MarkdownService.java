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
import com.williamcallahan.javachat.service.markdown.ProcessedMarkdown;
import com.williamcallahan.javachat.service.markdown.UnifiedMarkdownService;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for rendering Markdown to HTML with optimal formatting and caching.
 * Configured for clean output with proper spacing and code block support.
 *
 * <p><strong>Migration Notice:</strong> This service is being migrated to use AST-based processing
 * instead of regex for better compliance with AGENTS.md guidelines. New code should use
 * {@link #processStructured(String)} for structured processing with type-safe citations and enrichments.</p>
 *
 * <p><strong>Recommended Usage:</strong> Use {@link #processStructured(String)} for new code.
 * Legacy methods ({@code render}, {@code renderPreview}, {@code preprocessMarkdown}) are deprecated
 * and use regex-based processing.</p>
 *
 * @see UnifiedMarkdownService for the new AST-based approach
 */
@Service
public class MarkdownService {

    private static final Logger logger = LoggerFactory.getLogger(
        MarkdownService.class
    );
    private static final int MAX_INPUT_LENGTH = 100000; // 100KB max
    private static final int CACHE_SIZE = 500;
    private static final Duration CACHE_DURATION = Duration.ofMinutes(30);

    private final Parser parser;
    private final HtmlRenderer renderer;
    private final Cache<String, String> renderCache;

    // New AST-based service for structured processing
    private final UnifiedMarkdownService unifiedService;

    // Pattern for custom enrichment markers
    private static final Pattern ENRICHMENT_PATTERN = Pattern.compile(
        "\\{\\{(hint|reminder|background|example|warning):([\\s\\S]*?)\\}\\}",
        Pattern.MULTILINE
    );

    // ThreadLocal to prevent race conditions when multiple threads process markdown concurrently.
    // Each thread gets its own map instance, avoiding the bug where one thread's clear()
    // would corrupt another thread's protected blocks.
    private final ThreadLocal<Map<String, String>> protectedBlocks =
        ThreadLocal.withInitial(HashMap::new);
    private final AtomicInteger codeBlockIdCounter = new AtomicInteger(0);

    public MarkdownService() {
        // Configure Flexmark with optimal settings
        MutableDataSet options = new MutableDataSet()
            // Core extensions for GitHub Flavored Markdown
            .set(
                Parser.EXTENSIONS,
                Arrays.asList(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    TaskListExtension.create(),
                    AutolinkExtension.create()
                )
            )
            // Parser options
            .set(Parser.BLANK_LINES_IN_AST, false) // Don't preserve blank lines in AST
            .set(Parser.HTML_BLOCK_DEEP_PARSER, false) // Security: don't parse HTML deeply
            .set(Parser.INDENTED_CODE_NO_TRAILING_BLANK_LINES, true) // Clean code blocks
            // Renderer options for clean output
            .set(HtmlRenderer.ESCAPE_HTML, true) // Escape raw HTML input for XSS protection
            .set(HtmlRenderer.SUPPRESS_HTML, false) // Allow markdown-generated HTML output
            .set(HtmlRenderer.SOFT_BREAK, "\n") // Preserve as newline (no forced <br>)
            .set(HtmlRenderer.HARD_BREAK, "<br />\n") // Only hard breaks become <br>
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

        // Initialize new AST-based service
        this.unifiedService = new UnifiedMarkdownService();

        logger.info(
            "MarkdownService initialized with Flexmark and caching (with AST-based processing available)"
        );
    }

    /**
     * Processes markdown using the new AST-based approach.
     * This method provides structured output with type-safe citations and enrichments.
     *
     * <p><strong>Recommended:</strong> This method uses the new AST-based processing
     * and is the preferred way to process markdown with structured output.</p>
     *
     * @param markdown The markdown text to process
     * @return ProcessedMarkdown with structured data
     */
    public ProcessedMarkdown processStructured(String markdown) {
        return unifiedService.process(markdown);
    }

    /**
     * Renders markdown to HTML with caching and optimal formatting.
     *
     * <p><strong>Deprecation Notice:</strong> This method uses regex-based processing which violates
     * AGENTS.md guidelines. Use {@link #processStructured(String)} for new code to get structured
     * output with type-safe citations and enrichments.</p>
     *
     * @param markdown The markdown text to render
     * @return Clean HTML output with proper spacing
     * @deprecated Use {@link #processStructured(String)} for AST-based processing
     */
    @Deprecated(since = "1.0", forRemoval = true)
    public String render(String markdown) {
        if (markdown == null || markdown.isEmpty()) {
            return "";
        }

        String original = markdown; // Keep original for logging

        if (markdown.length() > MAX_INPUT_LENGTH) {
            logger.warn(
                "Markdown input exceeds maximum length: {} > {}",
                markdown.length(),
                MAX_INPUT_LENGTH
            );
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
                logger.info(
                    "Preprocessing changed markdown: added {} paragraph breaks, {} list fixes",
                    markdown.split("\n\n").length -
                        original.split("\n\n").length,
                    markdown.contains("\n-") || markdown.contains("\n1.")
                        ? "YES"
                        : "NO"
                );
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
     *
     * @deprecated Use {@link #processStructured(String)} for AST-based processing
     */
    @Deprecated(since = "1.0", forRemoval = true)
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
     *
     * <p><strong>Deprecation Notice:</strong> This method uses extensive regex processing which
     * violates AGENTS.md guidelines. The new AST-based processing handles formatting issues
     * during parsing without regex.</p>
     *
     * @deprecated Regex-based preprocessing is replaced by AST-based processing
     */
    @Deprecated(since = "1.0", forRemoval = true)
    public String preprocessMarkdown(String markdown) {
        if (markdown == null) return "";

        // CRITICAL: Fix inline code blocks BEFORE protecting them
        // This handles cases like "text: ```java" or "text: javapublic class"
        markdown = fixInlineCodeBlocks(markdown);

        // The full, robust preprocessing pipeline.
        String protectedMd = protectCodeBlocks(markdown);

        // Protect enrichment markers so paragraph/list heuristics never split them
        java.util.Map<String, String> enrichmentPlaceholders =
            new java.util.HashMap<>();
        protectedMd = protectEnrichmentsForPreprocessing(
            protectedMd,
            enrichmentPlaceholders
        );

        String preserved = preserveInlineCode(protectedMd);
        preserved = fixInlineLists(preserved);
        preserved = normalizeInlineAndBulletLists(preserved);
        preserved = mergeMarkerOnlyLines(preserved);

        if (!hasListMarkers(preserved)) {
            preserved = applySmartParagraphBreaksImproved(preserved);
        }

        preserved = ensureFenceSeparation(preserved); // no-op while code blocks are protected; keep for parity
        preserved = restoreInlineCode(preserved);
        preserved = unprotectCodeBlocks(preserved);
        // Now that fences are visible again, enforce final separation/newline rules
        preserved = ensureFenceSeparation(preserved);
        preserved = ensureOpeningFenceNewline(preserved);

        // Normalize emphasis markers like "** text **" -> "**text**" (outside code)
        preserved = normalizeEmphasisSpacing(preserved);

        // Finally, restore enrichment markers back to their original text form
        preserved = unprotectEnrichmentsForPreprocessing(
            preserved,
            enrichmentPlaceholders
        );

        return preserved;
    }

    /**
     * CRITICAL: Fixes inline code blocks that are missing proper separation.
     * Specifically targets the pattern where code immediately follows text without proper fencing.
     * More conservative approach to avoid breaking existing content.
     *
     * @deprecated Part of regex-based preprocessing pipeline. Use AST-based processing instead.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    private String fixInlineCodeBlocks(String markdown) {
        if (markdown == null || markdown.isEmpty()) return markdown;

        // Pattern 1: Fix "text: ```java" directly attached to code
        // Look for ``` not at line start and ensure it starts on new line
        markdown = markdown.replaceAll(
            "([^\\n])(```[a-zA-Z]*)(public|private|protected|class|interface)",
            "$1\n\n$2\n$3"
        );

        // Pattern 2: Fix "class: javapublic" where language and code run together
        // Very specific pattern to avoid false positives
        markdown = markdown.replaceAll(
            "(class:|example:|Example:|code:)\\s*(java)(public\\s+class|private\\s+class|public\\s+static)",
            "$1\n\n```java\n$3"
        );

        // Pattern 3: Fix missing closing fence when code is followed by regular prose
        // Look for }} In or }} This or similar patterns
        markdown = markdown.replaceAll(
            "(\\}\\s*\\})\\s+(In\\s+this|This\\s+|The\\s+|Here|Note|Notice)",
            "$1\n```\n\n$2"
        );

        return markdown;
    }

    /**
     * Replaces code blocks with placeholders to protect them from other processing.
     * This version uses a robust line-by-line parser instead of a fragile regex.
     *
     * @deprecated Part of regex-based preprocessing pipeline. Use AST-based processing instead.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    private String protectCodeBlocks(String markdown) {
        if (markdown == null || !markdown.contains("```")) {
            return markdown;
        }

        StringBuilder result = new StringBuilder();
        String[] lines = markdown.split("\\n");
        boolean inCodeBlock = false;
        StringBuilder currentBlock = new StringBuilder();

        for (String line : lines) {
            if (line.trim().startsWith("```")) {
                if (!inCodeBlock) {
                    // Start of a new code block
                    inCodeBlock = true;
                    currentBlock.append(line).append("\n");
                } else {
                    // End of the code block
                    inCodeBlock = false;
                    currentBlock.append(line);
                    String placeholder =
                        "___CODE_BLOCK_" +
                        codeBlockIdCounter.getAndIncrement() +
                        "___";
                    protectedBlocks
                        .get()
                        .put(placeholder, currentBlock.toString());
                    result.append(placeholder).append("\n");
                    currentBlock.setLength(0); // Reset for the next block
                }
            } else {
                if (inCodeBlock) {
                    currentBlock.append(line).append("\n");
                } else {
                    result.append(line).append("\n");
                }
            }
        }

        // If a block was opened but not closed (e.g., end of file), append it as is.
        if (inCodeBlock) {
            result.append(currentBlock);
        }

        return result.toString();
    }

    /**
     * Ensures proper separation of code blocks from surrounding text.
     * CRITICAL: This prevents code from being rendered inline with paragraphs.
     * - Adds blank lines before AND after code blocks
     * - Handles both fenced (```) and indented code blocks
     * - Works with preprocessing placeholders
     */
    @Deprecated(since = "1.0", forRemoval = true)
    private String ensureFenceSeparation(String s) {
        if (s == null || !s.contains("```")) return s;

        StringBuilder out = new StringBuilder(s.length() + 32);
        boolean inCodeBlock = false;
        String[] lines = s.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmedLine = line.trim();

            // Check for code fence
            if (trimmedLine.startsWith("```")) {
                if (!inCodeBlock) {
                    // Opening fence - ensure blank line before
                    if (out.length() > 0 && !out.toString().endsWith("\n\n")) {
                        // Add enough newlines to create separation
                        if (out.toString().endsWith("\n")) {
                            out.append("\n");
                        } else {
                            out.append("\n\n");
                        }
                    }
                    inCodeBlock = true;
                } else {
                    // Closing fence - will add blank line after
                    inCodeBlock = false;
                }

                out.append(line);

                // If this is a closing fence and there's more content, ensure separation
                if (!inCodeBlock && i < lines.length - 1) {
                    String nextLine = (i + 1 < lines.length)
                        ? lines[i + 1].trim()
                        : "";
                    if (!nextLine.isEmpty() && !nextLine.startsWith("```")) {
                        out.append("\n\n");
                        continue; // Skip normal newline addition
                    }
                }
            } else {
                out.append(line);
            }

            // Add normal line break if not at end
            if (i < lines.length - 1) {
                out.append("\n");
            }
        }

        return out.toString();
    }

    /**
     * COMPREHENSIVE list formatting - handles ALL list types reliably.
     * Supports numbered lists, roman numerals, letters, bullets, and special markers.
     *
     * @deprecated Part of regex-based preprocessing pipeline. Use AST-based processing instead.
     */
    @Deprecated(since = "1.0", forRemoval = true)
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
            markdown = markdown.replaceAll("(:\\s*)(\\d+[.)]\\s+)", "$1\n$2");
            // Break subsequent numbers
            markdown = markdown.replaceAll(
                "(?<!\\n)(\\s+)(\\d+[.)]\\s+)",
                "\n$2"
            );
            logger.debug("Fixed numbered list after colon");
        }

        // Roman numerals after colon (lowercase)
        if (
            markdown.matches(
                "(?s).*:\\s*(?:i{1,3}|iv|v|vi{0,3}|ix|x)[.)]\\s+.*"
            )
        ) {
            markdown = markdown.replaceAll(
                "(:\\s*)((?:i{1,3}|iv|v|vi{0,3}|ix|x)[.)]\\s+)",
                "$1\n$2"
            );
            markdown = markdown.replaceAll(
                "(?<!\\n)(\\s+)((?:i{1,3}|iv|v|vi{0,3}|ix|x)[.)]\\s+)",
                "\n$2"
            );
            logger.debug("Fixed roman numeral list after colon");
        }

        // Letters after colon
        if (markdown.matches("(?s).*:\\s*[a-zA-Z][.)]\\s+.*")) {
            markdown = markdown.replaceAll(
                "(:\\s*)([a-zA-Z][.)]\\s+)",
                "$1\n$2"
            );
            markdown = markdown.replaceAll(
                "(?<!\\n)(\\s+)([a-zA-Z][.)]\\s+)",
                "\n$2"
            );
            logger.debug("Fixed letter list after colon");
        }

        // Bullet lists after colon (including Unicode special characters)
        String bullets = "[-*+•→▸◆□▪]";
        if (markdown.matches("(?s).*:\\s*" + bullets + "\\s+.*")) {
            markdown = markdown.replaceAll(
                "(:\\s*)(" + bullets + "\\s+)",
                "$1\n$2"
            );
            markdown = markdown.replaceAll(
                "(?<!\\n)(\\s+)(" + bullets + "\\s+)",
                "\n$2"
            );
            logger.debug("Fixed Unicode bullet list after colon");
        }

        // STEP 2: Fix multiple inline numbered items (moderate confidence)
        // Pattern: "The types are 1. boolean 2. byte 3. int"
        if (
            markdown.matches(
                "(?s).*\\b(are|include|includes|such as|follows?)\\s+\\d+[.)]\\s+.*\\d+[.)]\\s+.*"
            )
        ) {
            markdown = markdown.replaceAll(
                "\\b(are|include|includes|such as|follows?)\\s+(\\d+[.)]\\s+)",
                "$1\n$2"
            );
            markdown = markdown.replaceAll(
                "(?<!\\n)(\\s+)(\\d+[.)]\\s+)",
                "\n$2"
            );
            logger.debug("Fixed inline numbered list with intro phrase");
        }

        // STEP 2.5: Fix inline numbered lists without trigger words (NEW)
        // Pattern: "Key points 1. First 2. Second 3. Third" - detect multiple sequential numbers
        // Only apply when NO trigger words are present to avoid interfering with STEP 2
        if (
            !markdown.matches(
                "(?s).*\\b(are|include|includes|such as|follows?)\\s+\\d+[.)]\\s+.*"
            ) &&
            markdown.matches(
                "(?s).*\\b\\d+[.)]\\s+.*\\b\\d+[.)]\\s+.*\\b\\d+[.)]\\s+.*"
            )
        ) {
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
     * Normalize inline numeric/lettered/bullet markers in prose into proper line starts.
     * Parser-style scan; operates outside code blocks (blocks are protected earlier).
     */
    @Deprecated(since = "1.0", forRemoval = true)
    private String normalizeInlineAndBulletLists(String text) {
        if (text == null || text.isEmpty()) return text;
        char[] chars = text.toCharArray();
        java.util.List<Integer> positions = new java.util.ArrayList<>();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            // bullets
            if ((c == '-' || c == '*' || c == '+')) {
                char prev = (i > 0) ? chars[i - 1] : '\n';
                char next = (i + 1 < chars.length) ? chars[i + 1] : '\n';
                boolean atStart = i == 0 || prev == '\n';
                if (
                    !atStart &&
                    Character.isWhitespace(prev) &&
                    Character.isWhitespace(next)
                ) positions.add(i);
                continue;
            }
            // numbers
            if (Character.isDigit(c)) {
                int start = i;
                int j = i + 1;
                while (j < chars.length && Character.isDigit(chars[j])) j++;
                if (j < chars.length && (chars[j] == '.' || chars[j] == ')')) {
                    char prev = (start > 0) ? chars[start - 1] : '\n';
                    char next = (j + 1 < chars.length) ? chars[j + 1] : '\n';
                    boolean atStart = start == 0 || prev == '\n';
                    if (
                        !atStart &&
                        (Character.isWhitespace(prev) ||
                            prev == '(' ||
                            prev == '[') &&
                        (Character.isWhitespace(next) ||
                            Character.isLetter(next))
                    ) positions.add(start);
                    i = j; // advance
                }
                continue;
            }
            // letters with . or )
            if (
                Character.isLetter(c) &&
                i + 1 < chars.length &&
                (chars[i + 1] == '.' || chars[i + 1] == ')')
            ) {
                char prev = (i > 0) ? chars[i - 1] : '\n';
                char next = (i + 2 < chars.length) ? chars[i + 2] : '\n';
                boolean atStart = i == 0 || prev == '\n';
                if (
                    !atStart &&
                    (Character.isWhitespace(prev) ||
                        prev == '(' ||
                        prev == '[') &&
                    (Character.isWhitespace(next) || Character.isLetter(next))
                ) positions.add(i);
                i++; // skip punctuation
            }
        }
        if (positions.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length() + positions.size());
        int last = 0;
        for (int pos : positions) {
            boolean atStart = pos == 0 || text.charAt(pos - 1) == '\n';
            if (!atStart) {
                out.append(text, last, pos).append('\n');
                last = pos;
            }
        }
        out.append(text.substring(last));
        return out.toString();
    }

    /** Merge marker-only lines with the subsequent content line. */
    @Deprecated(since = "1.0", forRemoval = true)
    private String mergeMarkerOnlyLines(String text) {
        if (text == null || text.isEmpty()) return text;
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            String ln = lines[i];
            String trimmed = ln.trim();
            if (
                trimmed.matches("^(?:\\d+[\\.)]|[A-Za-z][\\.)]|[-*+])\\s*$") &&
                i + 1 < lines.length &&
                !lines[i + 1].trim().isEmpty()
            ) {
                out
                    .append(trimmed)
                    .append(' ')
                    .append(lines[i + 1].trim())
                    .append('\n');
                i++;
            } else {
                out.append(ln);
                if (i < lines.length - 1) out.append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Detect if the text contains markdown list markers at line starts.
     * Used to avoid paragraph-breaking around list structures.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    private boolean hasListMarkers(String text) {
        if (text == null || text.isEmpty()) return false;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "(?m)^(\\s*)(?:[-+*•→▸◆□▪]|\\d+\\.)\\s+"
        );
        return p.matcher(text).find();
    }

    /**
     * Replace inline code spans `code` with placeholders carrying base64 content to avoid
     * punctuation/paragraph mutations inside code. Restored before parsing markdown.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    private String preserveInlineCode(String text) {
        if (text == null || text.indexOf('`') < 0) return text;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "`([^`]+)`"
        );
        java.util.regex.Matcher m = p.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String code = m.group(1);
            String b64 = java.util.Base64.getEncoder().encodeToString(
                code.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
            m.appendReplacement(sb, "ZZINLCODESTART" + b64 + "ZZINLCODEEND");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Restore inline code placeholders back to markdown `code`.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    private String restoreInlineCode(String text) {
        if (text == null || text.indexOf('Z') < 0) return text;
        // Use a NON-GREEDY capture to avoid spanning across multiple placeholders
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
            "ZZINLCODESTART([A-Za-z0-9+/=]+?)ZZINLCODEEND"
        );
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
                code = new String(
                    java.util.Base64.getDecoder().decode(b64),
                    java.nio.charset.StandardCharsets.UTF_8
                );
            } catch (IllegalArgumentException ex) {
                // If decode still fails, do not crash the pipeline; fall back to showing raw content
                logger.warn(
                    "Failed to Base64-decode inline code placeholder; leaving as-is. length={} err={}",
                    b64.length(),
                    ex.getMessage()
                );
                code = b64; // degrade gracefully
            }
            // rewrap with backticks
            String replacement =
                "`" + java.util.regex.Matcher.quoteReplacement(code) + "`";
            m.appendReplacement(sb, replacement);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Restores protected code blocks to their original state.
     *
     * @deprecated Part of regex-based preprocessing pipeline. Use AST-based processing instead.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    private String unprotectCodeBlocks(String markdown) {
        Map<String, String> blocks = protectedBlocks.get();
        if (blocks.isEmpty()) {
            return markdown;
        }
        for (Map.Entry<String, String> entry : blocks.entrySet()) {
            markdown = markdown.replace(entry.getKey(), entry.getValue());
        }
        // Clear this thread's map for the next request (ThreadLocal ensures thread-safety)
        blocks.clear();
        return markdown;
    }

    /**
     * Post-processes HTML for optimal spacing and formatting.
     *
     * @deprecated Part of regex-based post-processing pipeline. Use AST-based processing instead.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    private String postProcessHtml(String html) {
        // NOTE: Avoid heuristic sentence spacing – rely on Flexmark output and CSS
        // (previous regex could corrupt content by injecting spaces across tags)

        // Fix escaped HTML tags that should be preserved as HTML
        html = html.replace("&lt;br /&gt;", "<br />");
        html = html.replace("&lt;br&gt;", "<br>");

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
        html = html.replace(
            "<blockquote>",
            "<blockquote class=\"markdown-quote\">"
        );

        return html.trim();
    }

    /**
     * Improved paragraph breaking that supports '.', '?', '!' and respects code blocks.
     *
     * @deprecated Part of regex-based preprocessing pipeline. Use AST-based processing instead.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    private String applySmartParagraphBreaksImproved(String markdown) {
        if (markdown == null || markdown.isEmpty()) return markdown;
        // If code blocks are present, process only non-code segments to preserve code
        if (markdown.contains("```")) {
            StringBuilder out = new StringBuilder();
            // FIXED: Consistent code block pattern with protectCodeBlocks method
            java.util.regex.Pattern codeBlockPattern =
                java.util.regex.Pattern.compile(
                    "```[\\w-]*\n?[\\s\\S]*?```",
                    java.util.regex.Pattern.DOTALL
                );
            java.util.regex.Matcher matcher = codeBlockPattern.matcher(
                markdown
            );
            int last = 0;
            while (matcher.find()) {
                String before = markdown.substring(last, matcher.start());
                out.append(applySmartParagraphBreaksNoCode(before));
                out.append(matcher.group());
                last = matcher.end();
            }
            if (last < markdown.length()) {
                out.append(
                    applySmartParagraphBreaksNoCode(markdown.substring(last))
                );
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
    @Deprecated(since = "1.0", forRemoval = true)
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
                String beforeBreak = text.substring(
                    Math.max(0, matcher.start() - 10),
                    matcher.start()
                );

                // Don't break at abbreviations
                if (
                    !beforeBreak.matches(
                        ".*\\b(e\\.g|i\\.e|etc|Dr|Mr|Mrs|Ms|Jr|Sr|St|No)$"
                    )
                ) {
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
        logger.debug(
            "Paragraph breaking: added {} breaks",
            processed.split("\n\n").length - 1
        );
        return processed;
    }

    /**
     * Preserves custom enrichment markers during markdown processing.
     * Uses unique placeholders that won't be affected by markdown parsing or HTML filtering.
     *
     * @deprecated Part of regex-based enrichment processing. Use AST-based EnrichmentProcessor instead.
     */
    @Deprecated(since = "1.0", forRemoval = true)
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
     *
     * @deprecated Part of regex-based enrichment processing. Use AST-based EnrichmentProcessor instead.
     */
    @Deprecated(since = "1.0", forRemoval = true)
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
                matcher.appendReplacement(
                    result,
                    "{{" + type + ":" + content + "}}"
                );
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
    @Deprecated(since = "1.0", forRemoval = true)
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
     *
     * @deprecated Use {@link UnifiedMarkdownService#getCacheStats()} for AST-based processing
     */
    @Deprecated(since = "1.0", forRemoval = true)
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
     *
     * @deprecated Use {@link UnifiedMarkdownService#clearCache()} for AST-based processing
     */
    @Deprecated(since = "1.0", forRemoval = true)
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

    /**
     * Ensure an opening code fence (``` or ```lang) starts code on the next line.
     * Fixes model outputs like "```javaimport ..." by inserting a newline after the info string.
     * Closing fences and already-correct fences are left untouched.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    private String ensureOpeningFenceNewline(String s) {
        if (s == null || !s.contains("```")) return s;
        String[] lines = s.split("\n", -1);
        StringBuilder out = new StringBuilder(s.length() + 16);
        boolean inCode = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("```")) {
                boolean opening = !inCode;
                inCode = !inCode;
                if (opening) {
                    String afterTicks = trimmed.substring(3);
                    String rest = afterTicks.replaceFirst(
                        "^[A-Za-z0-9_-]*\\s*",
                        ""
                    );
                    if (!rest.isEmpty()) {
                        java.util.regex.Matcher m =
                            java.util.regex.Pattern.compile(
                                "^(\\s*)```([A-Za-z0-9_-]*)\\s*(.*)$"
                            ).matcher(line);
                        if (m.find()) {
                            String indent =
                                m.group(1) == null ? "" : m.group(1);
                            String info = m.group(2) == null ? "" : m.group(2);
                            String trailing =
                                m.group(3) == null ? "" : m.group(3);
                            if (!trailing.isEmpty()) {
                                out
                                    .append(indent)
                                    .append("```")
                                    .append(info.isEmpty() ? "" : info)
                                    .append("\n");
                                out.append(trailing);
                                if (i < lines.length - 1) {
                                    out.append("\n");
                                }
                                continue;
                            }
                        }
                    }
                }
            }
            out.append(line);
            if (i < lines.length - 1) {
                out.append("\n");
            }
        }
        return out.toString();
    }

    /**
     * Temporarily replace enrichment markers with placeholders so that
     * list/paragraph normalization never splits them. Restored before returning
     * from preprocessMarkdown.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    private String protectEnrichmentsForPreprocessing(
        String s,
        java.util.Map<String, String> stash
    ) {
        if (s == null || s.indexOf("{{") < 0) return s;
        java.util.regex.Matcher m = ENRICHMENT_PATTERN.matcher(s);
        StringBuffer sb = new StringBuffer();
        int i = 0;
        while (m.find()) {
            String ph = "___ENRICH_" + (i++) + "___";
            stash.put(ph, m.group());
            m.appendReplacement(
                sb,
                java.util.regex.Matcher.quoteReplacement(ph)
            );
        }
        m.appendTail(sb);
        return sb.toString();
    }

    @Deprecated(since = "1.0", forRemoval = true)
    private String unprotectEnrichmentsForPreprocessing(
        String s,
        java.util.Map<String, String> stash
    ) {
        if (s == null || stash.isEmpty()) return s;
        for (var e : stash.entrySet()) {
            s = s.replace(e.getKey(), e.getValue());
        }
        return s;
    }

    /**
     * Normalize emphasis spacing so sequences like "** text **" and "* ital *"
     * are converted to canonical "**text**" and "*ital*". This improves bold/italic
     * rendering reliability without touching code blocks (already protected).
     */
    @Deprecated(since = "1.0", forRemoval = true)
    private String normalizeEmphasisSpacing(String s) {
        if (s == null || s.isEmpty()) return s;
        if (s.indexOf('*') < 0) return s;
        // Work line-by-line to avoid spanning across blocks
        String[] lines = s.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            // Collapse single spaces immediately inside emphasis markers
            line = line.replaceAll(
                "\\*\\*\\s+([^*][^*]*?)\\s+\\*\\*",
                "**$1**"
            );
            line = line.replaceAll(
                "(?<!\\*)\\*\\s+([^*][^*]*?)\\s+\\*(?!\\*)",
                "*$1*"
            );
            lines[i] = line;
        }
        return String.join("\n", lines);
    }
}
