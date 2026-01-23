package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.model.Citation;
import java.util.List;

/**
 * Response for retrieval diagnostics endpoint (dev-only).
 *
 * @param docs The list of retrieved citations
 * @param error Optional error message if retrieval failed
 */
public record RetrievalDiagnosticsResponse(
    List<Citation> docs,
    String error
) {
    public RetrievalDiagnosticsResponse {
        docs = docs == null ? List.of() : List.copyOf(docs);
    }

    @Override
    public List<Citation> docs() {
        return List.copyOf(docs);
    }
    /**
     * Creates a successful response with citations.
     */
    public static RetrievalDiagnosticsResponse success(List<Citation> citations) {
        return new RetrievalDiagnosticsResponse(citations, null);
    }

    /**
     * Creates an error response.
     */
    public static RetrievalDiagnosticsResponse error(String errorMessage) {
        return new RetrievalDiagnosticsResponse(List.of(), errorMessage);
    }
}
