package com.williamcallahan.javachat.domain.markdown;

/**
 * Represents a non-fatal warning encountered during markdown processing.
 * Used for structured error reporting instead of silent failures.
 */
public record ProcessingWarning(
    String message,
    WarningType type,
    int position,
    String context
) {
    
    public ProcessingWarning {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Warning message cannot be null or empty");
        }
        if (type == null) {
            throw new IllegalArgumentException("Warning type cannot be null");
        }
        if (position < 0) {
            throw new IllegalArgumentException("Warning position must be non-negative");
        }
    }
    
    /**
     * Creates a processing warning with minimal context.
     * @param message the warning message
     * @param type the warning type
     * @param position position in document
     * @return new ProcessingWarning instance
     */
    public static ProcessingWarning create(String message, WarningType type, int position) {
        return new ProcessingWarning(message, type, position, "");
    }
    
    /**
     * Warning types for categorization.
     */
    public enum WarningType {
        /**
         * Malformed enrichment marker.
         */
        MALFORMED_ENRICHMENT,
        
        /**
         * Invalid citation format.
         */
        INVALID_CITATION,
        
        /**
         * Unclosed code block.
         */
        UNCLOSED_CODE_BLOCK,
        
        /**
         * Nested structure issue.
         */
        NESTED_STRUCTURE,
        
        /**
         * Unknown enrichment type.
         */
        UNKNOWN_ENRICHMENT_TYPE,
        
        /**
         * General parsing issue.
         */
        PARSING_ISSUE
    }
}
