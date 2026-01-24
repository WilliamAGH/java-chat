package com.williamcallahan.javachat.domain.markdown;

import java.util.Locale;

/**
 * Enumeration of citation types for structured processing.
 * This replaces string-based type identification with type-safe enums.
 */
public enum CitationType {
    /**
     * External HTTP/HTTPS link.
     */
    EXTERNAL_LINK("external"),
    
    /**
     * PDF document reference.
     */
    PDF_DOCUMENT("pdf"),
    
    /**
     * Local application link.
     */
    LOCAL_LINK("local"),
    
    /**
     * API documentation reference.
     */
    API_DOCUMENTATION("api-doc"),
    
    /**
     * Code repository reference.
     */
    CODE_REPOSITORY("repo"),
    
    /**
     * Unknown or unclassified link type.
     */
    UNKNOWN("unknown");
    
    private final String identifier;
    
    CitationType(String identifier) {
        this.identifier = identifier;
    }
    
    /**
     * Gets the string identifier for this citation type.
     * @return string identifier
     */
    public String getIdentifier() {
        return identifier;
    }
    
    /**
     * Determines citation type from URL.
     * @param url The URL to analyze
     * @return appropriate CitationType
     */
    public static CitationType fromUrl(String url) {
        if (url == null || url.isEmpty()) {
            return UNKNOWN;
        }
        
        String lowerUrl = url.toLowerCase(Locale.ROOT);
        
        if (lowerUrl.endsWith(".pdf")) {
            return PDF_DOCUMENT;
        }
        
        if (lowerUrl.startsWith("http://") || lowerUrl.startsWith("https://")) {
            if (lowerUrl.contains("docs.oracle.com") || lowerUrl.contains("javadoc") || 
                lowerUrl.contains("/api/") || lowerUrl.contains("/docs/api/")) {
                return API_DOCUMENTATION;
            }
            if (lowerUrl.contains("github.com") || lowerUrl.contains("gitlab.com") || 
                lowerUrl.contains("bitbucket.org")) {
                return CODE_REPOSITORY;
            }
            return EXTERNAL_LINK;
        }
        
        if (lowerUrl.startsWith("/")) {
            return LOCAL_LINK;
        }
        
        return UNKNOWN;
    }
}
