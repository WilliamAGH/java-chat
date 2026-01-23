package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.service.markdown.UnifiedMarkdownService;
import com.williamcallahan.javachat.service.markdown.ProcessedMarkdown;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Processes streaming markdown chunks with intelligent buffering to prevent word breaks
 * and ensure proper formatting of complete structures.
 *
 * <p>
 * <strong>Deprecated:</strong> Prefer server-side AST parsing via
 * {@link com.williamcallahan.javachat.service.markdown.UnifiedMarkdownService}
 * and DOM-safe rendering. This processor is retained only for legacy
 * two-lane experiments and should not be used in new code.
 * </p>
 * 
 * This addresses the critical issues:
 * - Word cutoffs mid-hyphen (e.g., "Auto- configuration" -> "Auto-configuration")
 * - Broken code blocks that don't render properly
 * - Lists that don't format as HTML structures
 * - Missing paragraph breaks
 */
@Deprecated(since = "1.0", forRemoval = true)
@Component
public class MarkdownStreamProcessor {
    
    private static final Logger logger = LoggerFactory.getLogger(MarkdownStreamProcessor.class);
    
    // Buffer limits and timeouts
    private static final int MIN_BUFFER_SIZE = 50;
    private static final int MAX_BUFFER_SIZE = 2000;
    private static final Duration MAX_BUFFER_TIME = Duration.ofMillis(800);
    
    // Patterns for detecting natural boundaries
    private static final Pattern SENTENCE_END = Pattern.compile(".*[.!?][\"'\\)]?\\s*$");
    private static final Pattern PARAGRAPH_BREAK = Pattern.compile(".*\\n\\n\\s*$");
    private static final Pattern CODE_BLOCK_END = Pattern.compile(".*```\\s*$");
	    private static final Pattern LIST_ITEM_END = Pattern.compile(".*\\n\\s*(?:\\d+[.)]|[-*+•→▸◆□▪])\\s+.*$");
	    
	    // Processing state
	    /**
	     * Tracks the current structure context inferred from the buffered markdown stream.
	     */
	    public enum StreamState {
	        PLAIN_TEXT,
	        IN_CODE_BLOCK,
	        IN_LIST
	    }
    
    private final UnifiedMarkdownService markdownService;
    private final StringBuilder buffer = new StringBuilder();
	    private final StringBuilder commitBuffer = new StringBuilder();
	    private StreamState currentState = StreamState.PLAIN_TEXT;
	    private Instant bufferStartTime = Instant.now();
	    
	    /**
	     * Creates a processor that delegates final markdown rendering to the unified markdown service.
	     */
	    public MarkdownStreamProcessor(UnifiedMarkdownService markdownService) {
	        this.markdownService = markdownService;
	    }
    
    /**
     * Processes a streaming chunk and returns formatted HTML when appropriate.
     * Uses intelligent buffering to avoid breaking words and structures.
     * 
     * @param chunk the incoming text chunk
     * @return formatted HTML if ready, empty if still buffering
     */
    public Optional<String> processChunk(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return Optional.empty();
        }
        
        buffer.append(chunk);
        updateState();
        
        // Check if we should flush the buffer
        if (shouldFlushBuffer()) {
            String content = buffer.toString();
            buffer.setLength(0);
            resetBufferTimer();
            
            // Apply markdown processing to complete content
            String formatted = formatContent(content);
            logger.debug("Flushed buffer with {} characters, state: {}", content.length(), currentState);
            
            return Optional.of(formatted);
        }
        
        return Optional.empty();
    }
    
    /**
     * Forces flush of any remaining buffered content.
     * Used when streaming is complete.
     */
    public Optional<String> flushRemaining() {
        if (buffer.length() > 0) {
            String content = buffer.toString();
            buffer.setLength(0);
            resetBufferTimer();
            
            String formatted = formatContent(content);
            logger.debug("Final flush with {} characters", content.length());
            
            return Optional.of(formatted);
        }
        return Optional.empty();
    }
    
    /**
     * Checks if a chunk completes a block and returns the block for commit.
     * This enables two-lane rendering: immediate deltas + committed blocks.
     * 
     * @param chunk the incoming text chunk
     * @return complete block content if a block boundary was detected, empty otherwise
     */
    public Optional<String> checkForCommit(String chunk) {
        if (chunk == null || chunk.isEmpty()) {
            return Optional.empty();
        }
        
        commitBuffer.append(chunk);
        String content = commitBuffer.toString();
        
        // Check if we have a complete block
        if (isCompleteBlock(content)) {
            String toCommit = content;
            commitBuffer.setLength(0);
            logger.debug("Block committed with {} characters", toCommit.length());
            return Optional.of(toCommit);
        }
        
        return Optional.empty();
    }
    
    /**
     * Forces flush of any remaining commit buffer content.
     * Used when streaming is complete.
     */
    public Optional<String> flushRemainingCommit() {
        if (commitBuffer.length() > 0) {
            String content = commitBuffer.toString();
            commitBuffer.setLength(0);
            logger.debug("Final commit flush with {} characters", content.length());
            return Optional.of(content);
        }
        return Optional.empty();
    }
    
    /**
     * Determines if content represents a complete block ready for commit.
     * Uses similar logic to shouldFlushBuffer but focuses on complete semantic units.
     */
    private boolean isCompleteBlock(String content) {
        // Paragraph break (double newline)
        if (content.endsWith("\n\n")) {
            logger.debug("Complete block detected: paragraph break");
            return true;
        }
        
        // Code block end
        if (content.endsWith("```\n") || content.endsWith("```")) {
            logger.debug("Complete block detected: code block end");
            return true;
        }
        
        // Sentence end with whitespace
        if (SENTENCE_END.matcher(content).matches()) {
            logger.debug("Complete block detected: sentence end");
            return true;
        }
        
        // List item completion (next item starts or double newline)
        if (content.matches(".*\\n\\s*(?:\\d+[.)]|[-*+•→▸◆□▪])\\s+.*\\n\\n.*") ||
            content.matches(".*\\n\\s*(?:\\d+[.)]|[-*+•→▸◆□▪])\\s+.*\\n\\s*(?:\\d+[.)]|[-*+•→▸◆□▪])\\s+.*")) {
            logger.debug("Complete block detected: list boundary");
            return true;
        }
        
        return false;
    }
    
    /**
     * Determines if the buffer should be flushed based on content and timing.
     */
    private boolean shouldFlushBuffer() {
        String content = buffer.toString();
        
        // Always flush if we hit size limits
        if (content.length() > MAX_BUFFER_SIZE) {
            logger.debug("Flushing buffer: size limit exceeded");
            return true;
        }
        
        // Don't flush if too small unless timeout
        if (content.length() < MIN_BUFFER_SIZE) {
            return hasBufferTimedOut();
        }
        
        // Don't break in the middle of code blocks
        if (currentState == StreamState.IN_CODE_BLOCK && !isCodeBlockComplete(content)) {
            return hasBufferTimedOut();
        }
        
        // Look for natural boundaries
        if (SENTENCE_END.matcher(content).matches()) {
            logger.debug("Flushing buffer: sentence boundary");
            return true;
        }
        
        if (PARAGRAPH_BREAK.matcher(content).matches()) {
            logger.debug("Flushing buffer: paragraph boundary");
            return true;
        }
        
        if (CODE_BLOCK_END.matcher(content).matches()) {
            logger.debug("Flushing buffer: code block boundary");
            return true;
        }
        
        if (LIST_ITEM_END.matcher(content).matches()) {
            logger.debug("Flushing buffer: list boundary");
            return true;
        }
        
        // Timeout-based flush
        if (hasBufferTimedOut()) {
            logger.debug("Flushing buffer: timeout");
            return true;
        }
        
        return false;
    }
    
    /**
     * Updates the current processing state based on buffer content.
     */
    private void updateState() {
        String content = buffer.toString();
        
        // Count actual ``` fence markers, not individual backticks
        int fenceCount = countCodeFences(content);
        boolean inCodeBlock = (fenceCount % 2) == 1;
        
        if (inCodeBlock) {
            currentState = StreamState.IN_CODE_BLOCK;
        } else if (content.matches(".*\\n\\s*(?:\\d+[.)]|[-*+•→▸◆□▪])\\s+.*")) {
            currentState = StreamState.IN_LIST;
        } else {
            currentState = StreamState.PLAIN_TEXT;
        }
    }
    
    /**
     * Counts actual ``` fence markers in text.
     */
    private int countCodeFences(String text) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf("```", index)) != -1) {
            count++;
            index += 3;
        }
        return count;
    }
    
    /**
     * Checks if a code block is complete (has matching opening and closing fences).
     */
    private boolean isCodeBlockComplete(String content) {
        int fenceCount = 0;
        String[] lines = content.split("\n");
        
        for (String line : lines) {
            if (line.trim().startsWith("```")) {
                fenceCount++;
            }
        }
        
        return fenceCount > 0 && fenceCount % 2 == 0;
    }
    
    /**
     * Checks if the buffer has exceeded the maximum allowed time.
     */
    private boolean hasBufferTimedOut() {
        return Instant.now().isAfter(bufferStartTime.plus(MAX_BUFFER_TIME));
    }
    
    /**
     * Resets the buffer timer.
     */
    private void resetBufferTimer() {
        bufferStartTime = Instant.now();
    }
    
    /**
     * Applies markdown formatting to content using the unified service.
     * Falls back to safe HTML escaping if processing fails.
     */
    private String formatContent(String content) {
        try {
            ProcessedMarkdown processed = markdownService.process(content);
            return processed.html();
        } catch (Exception e) {
            logger.warn("Failed to process markdown, falling back to escaped text", e);
            return escapeHtml(content).replace("\n", "<br />\n");
        }
    }
    
    /**
     * Escapes HTML characters for safe display.
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
     * Resets the processor state for a new conversation.
     */
    public void reset() {
        buffer.setLength(0);
        commitBuffer.setLength(0);
        currentState = StreamState.PLAIN_TEXT;
        resetBufferTimer();
        logger.debug("Stream processor reset");
    }
}
