package com.williamcallahan.javachat.domain;

import java.util.Optional;

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
     * @return the content text, empty if no content is available
     */
    Optional<String> getText();

    /**
     * Returns the source URL of this retrieved item, if available.
     *
     * <p>Used to identify retrieval source characteristics (e.g., keyword search markers).</p>
     *
     * @return the source URL, empty if unknown
     */
    Optional<String> getSourceUrl();
}
