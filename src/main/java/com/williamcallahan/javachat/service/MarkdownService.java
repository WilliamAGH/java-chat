package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.service.markdown.ProcessedMarkdown;
import com.williamcallahan.javachat.service.markdown.UnifiedMarkdownService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Service for markdown processing that delegates to the AST-based unified service.
 *
 * @see UnifiedMarkdownService for the structured processing implementation
 */
@Service
public class MarkdownService {

    private static final Logger logger = LoggerFactory.getLogger(MarkdownService.class);
    private final UnifiedMarkdownService unifiedService;

    /**
     * Creates a MarkdownService backed by the unified AST-based processor.
     *
     * @param unifiedService the unified markdown processor
     */
    public MarkdownService(UnifiedMarkdownService unifiedService) {
        this.unifiedService = unifiedService;
        logger.info("MarkdownService initialized with UnifiedMarkdownService");
    }

    /**
     * Processes markdown into structured output using AST-based parsing.
     *
     * @param markdown the markdown input
     * @return processed markdown with HTML, citations, and enrichments
     */
    public ProcessedMarkdown processStructured(String markdown) {
        return unifiedService.process(markdown);
    }
}
