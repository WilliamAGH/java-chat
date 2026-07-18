package com.williamcallahan.javachat.service.ingestion;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Copies invalid or unsafe content outside the canonical documentation mirror for later inspection.
 */
@Service
public class IngestionQuarantineService {
    private static final Logger log = LoggerFactory.getLogger(IngestionQuarantineService.class);

    private static final String DEFAULT_DOCUMENTATION_ROOT = "data/docs";
    private static final String QUARANTINE_DIRECTORY_NAME = ".quarantine";
    private static final DateTimeFormatter QUARANTINE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Path documentationRoot;

    /**
     * Uses the canonical documentation root so inspection copies remain outside recursive ingestion.
     */
    public IngestionQuarantineService() {
        this(Path.of(DEFAULT_DOCUMENTATION_ROOT));
    }

    IngestionQuarantineService(Path documentationRoot) {
        this.documentationRoot = Objects.requireNonNull(documentationRoot, "documentationRoot")
                .toAbsolutePath()
                .normalize();
    }

    /**
     * Copies the supplied document to a timestamped inspection path under {@code data/.quarantine}.
     *
     * <p>The canonical document remains in place so rejected landing pages cannot shrink a source mirror or
     * disappear from a later ingestion run.
     *
     * @param canonicalDocument document to copy into quarantine
     * @return quarantine result describing the canonical document and inspection copy
     * @throws IOException if creating or copying the inspection file fails
     */
    public QuarantineResult quarantine(Path canonicalDocument) throws IOException {
        Objects.requireNonNull(canonicalDocument, "canonicalDocument");

        Path absoluteDocument = canonicalDocument.toAbsolutePath().normalize();
        Path inspectionCopy = buildQuarantineTarget(absoluteDocument);
        Path inspectionParent = inspectionCopy.getParent();
        if (inspectionParent != null) {
            Files.createDirectories(inspectionParent);
        }
        Files.copy(absoluteDocument, inspectionCopy, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        log.warn("Copied rejected document to quarantine");
        return new QuarantineResult(absoluteDocument, inspectionCopy);
    }

    private Path buildQuarantineTarget(Path absoluteDocument) {
        Path documentFileName = absoluteDocument.getFileName();
        if (documentFileName == null) {
            throw new IllegalArgumentException("Cannot quarantine path without a filename: " + absoluteDocument);
        }
        Path relativeDocumentPath = absoluteDocument.startsWith(documentationRoot)
                ? documentationRoot.relativize(absoluteDocument)
                : Path.of(documentFileName.toString());

        Path relativeDocumentFileName = relativeDocumentPath.getFileName();
        if (relativeDocumentFileName == null) {
            throw new IllegalArgumentException("Cannot quarantine path without a filename: " + relativeDocumentPath);
        }

        String timestamp = LocalDateTime.now().format(QUARANTINE_TIMESTAMP_FORMAT);
        String quarantinedName = appendTimestamp(relativeDocumentFileName.toString(), timestamp);
        Path quarantineRoot = quarantineRoot();
        Path relativeDocumentParent = relativeDocumentPath.getParent();
        Path candidateTarget = relativeDocumentParent == null
                ? quarantineRoot.resolve(quarantinedName)
                : quarantineRoot.resolve(relativeDocumentParent).resolve(quarantinedName);
        Path normalizedTarget = candidateTarget.toAbsolutePath().normalize();
        if (!normalizedTarget.startsWith(quarantineRoot)) {
            throw new IllegalArgumentException("Quarantine target escapes its root: " + normalizedTarget);
        }
        return normalizedTarget;
    }

    private Path quarantineRoot() {
        Path storageRoot = documentationRoot.getParent();
        if (storageRoot == null) {
            throw new IllegalStateException("Documentation root has no storage parent: " + documentationRoot);
        }

        Path quarantineRoot =
                storageRoot.resolve(QUARANTINE_DIRECTORY_NAME).toAbsolutePath().normalize();
        if (quarantineRoot.startsWith(documentationRoot)) {
            throw new IllegalStateException("Quarantine root must be outside documentation root: " + quarantineRoot);
        }
        return quarantineRoot;
    }

    private String appendTimestamp(String fileName, String timestamp) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex) + "." + timestamp + fileName.substring(dotIndex);
        }
        return fileName + "." + timestamp;
    }

    /**
     * Describes the canonical document and timestamped inspection copy produced by quarantine.
     *
     * @param original canonical document path retained for future ingestion
     * @param quarantined inspection copy path outside the documentation root
     */
    public record QuarantineResult(Path original, Path quarantined) {
        public QuarantineResult {
            Objects.requireNonNull(original, "original");
            Objects.requireNonNull(quarantined, "quarantined");
        }
    }
}
