package com.williamcallahan.javachat.service;

/**
 * Classifies whether a Java API page supplies documentation that may be indexed.
 *
 * <p>Class-use pages list consumers of a type rather than the type's own API contract, so they
 * are rejected before chunks can enter retrieval.</p>
 */
public enum JavaApiPageDisposition {
    /** The page contains documentation that may be indexed. */
    INCLUDED,

    /** The page is a class-use index and must not be indexed as type documentation. */
    EXCLUDED_CLASS_USE_PAGE
}
