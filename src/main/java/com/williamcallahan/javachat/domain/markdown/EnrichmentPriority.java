package com.williamcallahan.javachat.domain.markdown;

/**
 * Priority levels for enrichment rendering order.
 * Higher priority enrichments are rendered first.
 */
public enum EnrichmentPriority {
    /**
     * Critical warnings that must be shown prominently.
     */
    CRITICAL(100),
    
    /**
     * High priority items like warnings and important reminders.
     */
    HIGH(75),
    
    /**
     * Medium priority items like hints and examples.
     */
    MEDIUM(50),
    
    /**
     * Low priority items like background information.
     */
    LOW(25),
    
    /**
     * Informational items with minimal visual impact.
     */
    INFO(10);
    
    private final int value;
    
    EnrichmentPriority(int value) {
        this.value = value;
    }
    
    /**
     * Gets the numeric priority value.
     * @return priority value (higher = more important)
     */
    public int getValue() {
        return value;
    }
    
    /**
     * Compares this priority with another.
     * @param other the other priority
     * @return negative if this is lower priority, positive if higher, 0 if equal
     */
    public int compareValue(EnrichmentPriority other) {
        return Integer.compare(this.value, other.value);
    }
}
