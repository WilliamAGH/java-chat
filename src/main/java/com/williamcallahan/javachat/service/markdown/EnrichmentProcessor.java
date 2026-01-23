package com.williamcallahan.javachat.service.markdown;

import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.NodeVisitor;
import com.vladsch.flexmark.util.ast.VisitHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AST-based enrichment processor that replaces regex-based enrichment extraction.
 * Uses Flexmark's visitor pattern for reliable parsing while maintaining compatibility
 * with existing enrichment markers during transition period.
 */
public class EnrichmentProcessor {
    
    private static final Logger logger = LoggerFactory.getLogger(EnrichmentProcessor.class);
    
    // Temporary pattern for transition period - will be replaced with custom AST nodes
    private static final Pattern ENRICHMENT_PATTERN = Pattern.compile(
        "(?i)\\{\\{\\s*(hint|reminder|background|example|warning)\\s*:\\s*([\\s\\S]*?)\\s*\\}\\}",
        Pattern.MULTILINE
    );
    
    /**
     * Extracts enrichments from a Flexmark AST document.
     * This replaces regex-based enrichment processing with structured AST traversal.
     *
     * @param document the parsed markdown document
     * @return list of extracted enrichments
     */
    public List<MarkdownEnrichment> extractEnrichments(Node document) {
        if (document == null) {
            return List.of();
        }

        EnrichmentVisitor visitor = new EnrichmentVisitor();
        visitor.visit(document);

        List<MarkdownEnrichment> enrichments = visitor.getEnrichments();
        logger.debug("Extracted {} enrichments using AST processing", enrichments.size());

        return enrichments;
    }
    
    /**
     * Visitor implementation for extracting enrichments from AST nodes.
     * This is the AGENTS.md compliant approach using proper AST traversal.
     */
    private static class EnrichmentVisitor {
        private final List<MarkdownEnrichment> enrichments = new ArrayList<>();
        private final List<ProcessingWarning> warnings = new ArrayList<>();
        private int position = 0;
        
        private final NodeVisitor visitor = new NodeVisitor(
            new VisitHandler<>(Text.class, this::visitText),
            new VisitHandler<>(HtmlBlock.class, this::visitHtmlBlock)
        );
        
        public void visit(Node node) {
            visitor.visit(node);
        }
        
        public List<MarkdownEnrichment> getEnrichments() {
            return List.copyOf(enrichments);
        }
        
        @SuppressWarnings("unused") // Will be used in future iterations for warning reporting
        public List<ProcessingWarning> getWarnings() {
            return List.copyOf(warnings);
        }
        
        /**
         * Processes Text nodes for enrichment markers.
         * @param text the text node to process
         */
        private void visitText(Text text) {
            String content = text.getChars().toString();
            processEnrichmentMarkers(content);
            position += content.length();
        }
        
        /**
         * Processes HTML blocks that might contain enrichment markers.
         * @param htmlBlock the HTML block to process
         */
        private void visitHtmlBlock(HtmlBlock htmlBlock) {
            String content = htmlBlock.getChars().toString();
            processEnrichmentMarkers(content);
            position += content.length();
        }
        
        /**
         * Processes enrichment markers in text content.
         * This is a transitional method that will be replaced with custom AST nodes.
         * 
         * @param content the text content to process
         */
        private void processEnrichmentMarkers(String content) {
            Matcher matcher = ENRICHMENT_PATTERN.matcher(content);
            
            while (matcher.find()) {
                String type = matcher.group(1);
                String enrichmentContent = matcher.group(2);
                
                if (enrichmentContent == null || enrichmentContent.trim().isEmpty()) {
                    warnings.add(ProcessingWarning.create(
                        "Empty enrichment content for type: " + type,
                        ProcessingWarning.WarningType.MALFORMED_ENRICHMENT,
                        position + matcher.start()
                    ));
                    continue;
                }
                
                MarkdownEnrichment enrichment = createEnrichment(type, enrichmentContent.trim(), position + matcher.start());
                if (enrichment != null) {
                    enrichments.add(enrichment);
                    logger.debug("Found {} enrichment at position {}", type, position + matcher.start());
                } else {
                    warnings.add(ProcessingWarning.create(
                        "Unknown enrichment type: " + type,
                        ProcessingWarning.WarningType.UNKNOWN_ENRICHMENT_TYPE,
                        position + matcher.start()
                    ));
                }
            }
        }
        
        /**
         * Creates typed enrichment objects from string markers.
         * This factory method ensures type safety and proper validation.
         * 
         * @param type the enrichment type string
         * @param content the enrichment content
         * @param pos the position in the document
         * @return typed Enrichment object or null if type is unknown
         */
        private MarkdownEnrichment createEnrichment(String type, String content, int pos) {
            return switch (type.toLowerCase()) {
                case "hint" -> Hint.create(content, pos);
                case "warning" -> Warning.create(content, pos);
                case "background" -> Background.create(content, pos);
                case "example" -> Example.create(content, pos);
                case "reminder" -> Reminder.create(content, pos);
                default -> null;
            };
        }
    }
}
