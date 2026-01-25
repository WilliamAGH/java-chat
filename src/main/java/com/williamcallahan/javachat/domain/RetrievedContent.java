package com.williamcallahan.javachat.domain;

import java.util.Map;

/**
 * Domain abstraction for retrieved document content used in search quality evaluation.
 *
 * <p>Decouples domain logic from Spring AI's Document class, allowing the domain layer
 * to remain framework-free per clean architecture principles (AR4).</p>
 */
public interface RetrievedContent {

    /**
     * Returns the text content of this retrieved item.
     *
     * @return the content text, may be null if no content is available
     */
    String getText();

    /**
     * Returns metadata associated with this retrieved item.
     *
     * @return unmodifiable map of metadata key-value pairs, never null
     */
    Map<String, Object> getMetadata();
}
