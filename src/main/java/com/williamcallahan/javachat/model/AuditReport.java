package com.williamcallahan.javachat.model;

import java.util.List;

/**
 * Summary of audit results comparing parsed chunks to stored vector entries.
 */
public record AuditReport(
        String url,
        int parsedCount,
        int qdrantCount,
        int missingCount,
        int extraCount,
        List<String> duplicateHashes,
        boolean ok,
        List<String> missingHashes,
        List<String> extraHashes) {
    public AuditReport {
        duplicateHashes = duplicateHashes == null ? List.of() : List.copyOf(duplicateHashes);
        missingHashes = missingHashes == null ? List.of() : List.copyOf(missingHashes);
        extraHashes = extraHashes == null ? List.of() : List.copyOf(extraHashes);
    }
}
