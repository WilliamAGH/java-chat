package com.williamcallahan.javachat.service.markdown;

import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.ast.NodeVisitor;
import com.vladsch.flexmark.util.ast.VisitHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * AST-based citation processor that replaces regex-based citation extraction.
 * Uses Flexmark's visitor pattern for reliable parsing.
 */
public class CitationProcessor {
    
    private static final Logger logger = LoggerFactory.getLogger(CitationProcessor.class);
    
    /**
     * Extracts citations from a Flexmark AST document.
     * This replaces regex-based citation processing with structured AST traversal.
     * 
     * @param document the parsed markdown document
     * @return list of extracted citations
     */
    public List<MarkdownCitation> extractCitations(Node document) {
        if (document == null) {
            return List.of();
        }
        
        CitationVisitor visitor = new CitationVisitor();
        visitor.visit(document);
        
        List<MarkdownCitation> citations = visitor.citations();
        logger.debug("Extracted {} citations using AST processing", citations.size());
        
        return citations;
    }
    
    /**
     * Visitor implementation for extracting citations from AST nodes.
     * This is the AGENTS.md compliant approach using proper AST traversal.
     */
    private static class CitationVisitor {
        private final List<MarkdownCitation> citations = new ArrayList<>();
        private int position = 0;
        
        private final NodeVisitor visitor = new NodeVisitor(
            new VisitHandler<>(Link.class, this::visitLink),
            new VisitHandler<>(Text.class, this::visitText)
        );
        
        void visit(Node node) {
            visitor.visit(node);
        }
        
        List<MarkdownCitation> citations() {
            return List.copyOf(citations);
        }
        
        /**
         * Processes Link nodes to extract citation information.
         * @param link the link node to process
         */
        private void visitLink(Link link) {
            String url = link.getUrl().toString();
            CitationType type = CitationType.fromUrl(url);
            
            String title = extractLinkTitle(link);
            if (isValidCitation(url)) {
                MarkdownCitation citation = MarkdownCitation.create(url, title, "", type, position++);
                citations.add(citation);
                logger.debug("Found citation: {} -> {}", title, url);
            }
            
            // Continue visiting child nodes
            visitor.visitChildren(link);
        }
        
        /**
         * Processes Text nodes for inline citation markers.
         * @param text the text node to process
         */
        private void visitText(Text text) {
            // This could be extended to handle inline citation markers like [1], [2]
            // For now, we focus on explicit links
            position += text.getChars().length();
        }
        
        /**
         * Extracts title from a link node, preferring explicit title over link text.
         * @param link the link node
         * @return extracted title
         */
        private String extractLinkTitle(Link link) {
            // Check for explicit title attribute first
            if (link.getTitle().isNotNull() && !link.getTitle().isEmpty()) {
                return link.getTitle().toString();
            }
            
            // Fall back to link text content
            StringBuilder titleBuilder = new StringBuilder();
            Node child = link.getFirstChild();
            while (child != null) {
                if (child instanceof Text textNode) {
                    titleBuilder.append(textNode.getChars());
                }
                child = child.getNext();
            }
            
            String title = titleBuilder.toString().trim();
            return title.isEmpty() ? "Source" : title;
        }
        
        /**
         * Validates if a URL and title constitute a valid citation.
         * @param url the URL to validate
         * @param title the title to validate
         * @return true if valid citation
         */
        private boolean isValidCitation(String url) {
            if (url == null || url.trim().isEmpty()) {
                return false;
            }
            
            // Skip common non-citation links
            String lowerUrl = url.toLowerCase(Locale.ROOT);
            return !lowerUrl.startsWith("mailto:")
                && !lowerUrl.startsWith("tel:")
                && !lowerUrl.startsWith("javascript:");
        }
    }
}
