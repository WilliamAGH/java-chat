package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.domain.markdown.ProcessedMarkdown;
import com.williamcallahan.javachat.service.markdown.UnifiedMarkdownService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for markdown processing with AST-based rendering and caching.
 * Prefer {@link #processStructured(String)} for structured output.
 */
@Service
public class MarkdownService {

    private static final Logger logger = LoggerFactory.getLogger(MarkdownService.class);

    private final UnifiedMarkdownService unifiedService;

    /**
     * Creates a MarkdownService backed by the unified AST processor.
     *
     * @param unifiedService unified markdown processor
     */
    public MarkdownService(UnifiedMarkdownService unifiedService) {
        this.unifiedService = unifiedService;
        logger.info("MarkdownService initialized with UnifiedMarkdownService");
    }

    /**
     * Processes markdown using the AST-based approach with structured output.
     *
     * @param markdownText the markdown text to process
     * @return structured markdown result
     */
    public ProcessedMarkdown processStructured(String markdownText) {
        return unifiedService.process(markdownText);
    }

}
