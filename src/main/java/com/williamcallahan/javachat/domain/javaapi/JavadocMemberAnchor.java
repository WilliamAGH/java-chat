package com.williamcallahan.javachat.domain.javaapi;

import java.util.Objects;

/**
 * Represents the exact DOM identifier emitted for one Javadoc member section.
 *
 * <p>The identifier excludes the URL fragment marker because storage retains it separately from
 * the fragmentless source page URL. Preserving the source spelling makes extraction, chunk
 * hashing, metadata storage, and citation construction share one member identity.</p>
 *
 * @param domIdentifier exact nonblank Javadoc member DOM identifier without a fragment marker
 */
public record JavadocMemberAnchor(String domIdentifier) {

    /**
     * Enforces the exact identifier invariant at the single domain trust boundary.
     *
     * @throws NullPointerException when the identifier is null
     * @throws IllegalArgumentException when the identifier is blank, padded, or contains {@code #}
     */
    public JavadocMemberAnchor {
        Objects.requireNonNull(domIdentifier, "domIdentifier");
        if (domIdentifier.isBlank() || !domIdentifier.equals(domIdentifier.trim()) || domIdentifier.indexOf('#') >= 0) {
            throw new IllegalArgumentException("domIdentifier must be an unpadded DOM identifier without '#'");
        }
    }
}
