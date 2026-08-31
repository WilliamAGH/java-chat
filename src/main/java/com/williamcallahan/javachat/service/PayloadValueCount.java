package com.williamcallahan.javachat.service;

/**
 * One distinct payload value with the number of points carrying it.
 *
 * <p>Faceting happens server-side in Qdrant so inventory reads avoid scrolling whole
 * collections; this record is the typed shape that crosses from the vector-store owner
 * ({@link HybridVectorService}) to inventory composition.</p>
 *
 * @param payloadValue distinct string value of the faceted payload field
 * @param pointCount points carrying that value
 */
public record PayloadValueCount(String payloadValue, long pointCount) {}
