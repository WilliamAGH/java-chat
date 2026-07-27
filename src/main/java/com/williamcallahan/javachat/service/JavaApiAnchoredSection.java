package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.domain.javaapi.JavadocMemberAnchor;
import java.util.Objects;

/**
 * Preserves one authoritative Javadoc member anchor with its documentation text.
 *
 * <p>The anchor is the exact {@code id} emitted by the Javadoc DOM and is retained separately
 * from the page URL so source-file pruning continues to use the canonical base URL.</p>
 *
 * @param anchor exact Javadoc {@code section.detail} identifier
 * @param text member signature and descriptive documentation
 */
public record JavaApiAnchoredSection(JavadocMemberAnchor anchor, String text) {

    /**
     * Enforces a nonblank exact anchor and nonblank member documentation.
     *
     * @throws IllegalArgumentException when either semantic component is blank
     */
    public JavaApiAnchoredSection {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }
}
