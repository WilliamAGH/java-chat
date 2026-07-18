package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.service.ContentHasher;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
    private static final String TEMPORARY_INSPECTION_FILE_PREFIX = ".inspection-copy-";
    private static final String TEMPORARY_INSPECTION_FILE_SUFFIX = ".tmp";

    private final Path documentationRoot;
    private final ContentHasher contentHasher;

    /**
     * Uses the canonical documentation root so inspection copies remain outside recursive ingestion.
     */
    public IngestionQuarantineService(ContentHasher contentHasher) {
        this(Path.of(DEFAULT_DOCUMENTATION_ROOT), contentHasher);
    }

    IngestionQuarantineService(Path documentationRoot, ContentHasher contentHasher) {
        this.documentationRoot = Objects.requireNonNull(documentationRoot, "documentationRoot")
                .toAbsolutePath()
                .normalize();
        this.contentHasher = Objects.requireNonNull(contentHasher, "contentHasher");
    }

    /**
     * Copies the supplied document to a content-addressed inspection path under {@code data/.quarantine}.
     *
     * <p>The inspection name uses the SHA-256 fingerprint of the copied raw bytes, so identical rejected content
     * reuses one inspection copy without decoding the document.
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
        QuarantineDestination quarantineDestination = resolveQuarantineDestination(absoluteDocument);
        Files.createDirectories(quarantineDestination.inspectionDirectory());
        Path temporaryInspectionCopy = Files.createTempFile(
                quarantineDestination.inspectionDirectory(),
                TEMPORARY_INSPECTION_FILE_PREFIX,
                TEMPORARY_INSPECTION_FILE_SUFFIX);
        try {
            Files.copy(absoluteDocument, temporaryInspectionCopy, StandardCopyOption.REPLACE_EXISTING);
            String contentFingerprint = contentHasher.sha256(temporaryInspectionCopy);
            Path inspectionCopy = buildQuarantineTarget(quarantineDestination, contentFingerprint);
            publishInspectionCopy(temporaryInspectionCopy, inspectionCopy, contentFingerprint);

            log.warn("Copied rejected document to quarantine");
            return new QuarantineResult(absoluteDocument, inspectionCopy);
        } finally {
            Files.deleteIfExists(temporaryInspectionCopy);
        }
    }

    private QuarantineDestination resolveQuarantineDestination(Path absoluteDocument) {
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

        Path quarantineRoot = quarantineRoot();
        Path relativeDocumentParent = relativeDocumentPath.getParent();
        Path candidateInspectionDirectory =
                relativeDocumentParent == null ? quarantineRoot : quarantineRoot.resolve(relativeDocumentParent);
        Path normalizedInspectionDirectory =
                candidateInspectionDirectory.toAbsolutePath().normalize();
        if (!normalizedInspectionDirectory.startsWith(quarantineRoot)) {
            throw new IllegalArgumentException("Quarantine target escapes its root: " + normalizedInspectionDirectory);
        }
        return new QuarantineDestination(normalizedInspectionDirectory, relativeDocumentFileName.toString());
    }

    private Path buildQuarantineTarget(QuarantineDestination quarantineDestination, String contentFingerprint) {
        String quarantinedName = appendContentFingerprint(quarantineDestination.documentFileName(), contentFingerprint);
        return quarantineDestination.inspectionDirectory().resolve(quarantinedName);
    }

    private void publishInspectionCopy(Path temporaryInspectionCopy, Path inspectionCopy, String contentFingerprint)
            throws IOException {
        if (Files.exists(inspectionCopy)) {
            validateInspectionFingerprint(inspectionCopy, contentFingerprint);
            return;
        }
        try {
            Files.move(temporaryInspectionCopy, inspectionCopy, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException existingInspectionCopy) {
            try {
                validateInspectionFingerprint(inspectionCopy, contentFingerprint);
            } catch (IOException invalidInspectionCopy) {
                invalidInspectionCopy.addSuppressed(existingInspectionCopy);
                throw invalidInspectionCopy;
            }
            return;
        }
        validateInspectionFingerprint(inspectionCopy, contentFingerprint);
    }

    private void validateInspectionFingerprint(Path inspectionCopy, String expectedContentFingerprint)
            throws IOException {
        String actualContentFingerprint = contentHasher.sha256(inspectionCopy);
        if (!expectedContentFingerprint.equals(actualContentFingerprint)) {
            throw new IOException("Quarantine fingerprint collision or corrupted inspection copy: " + inspectionCopy);
        }
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

    private String appendContentFingerprint(String fileName, String contentFingerprint) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex) + "." + contentFingerprint + fileName.substring(dotIndex);
        }
        return fileName + "." + contentFingerprint;
    }

    private record QuarantineDestination(Path inspectionDirectory, String documentFileName) {
        private QuarantineDestination {
            Objects.requireNonNull(inspectionDirectory, "inspectionDirectory");
            Objects.requireNonNull(documentFileName, "documentFileName");
        }
    }

    /**
     * Describes the canonical document and content-addressed inspection copy produced by quarantine.
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
