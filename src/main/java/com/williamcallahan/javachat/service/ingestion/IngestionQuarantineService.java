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
 * Moves invalid or unsafe content into a quarantine directory for later inspection.
 */
@Service
public class IngestionQuarantineService {
    private static final Logger log = LoggerFactory.getLogger(IngestionQuarantineService.class);

    private static final String DEFAULT_DOCS_ROOT = "data/docs";
    private static final String QUARANTINE_DIR_NAME = ".quarantine";
    private static final DateTimeFormatter QUARANTINE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * Quarantines the provided file by moving it under {@code data/docs/.quarantine}.
     *
     * @param file file to quarantine
     * @return quarantine result describing the destination
     * @throws IOException if quarantine move fails
     */
    public QuarantineResult quarantine(Path file) throws IOException {
        Objects.requireNonNull(file, "file");

        Path quarantineTarget = buildQuarantineTarget(file);
        Path targetParent = quarantineTarget.getParent();
        if (targetParent != null) {
            Files.createDirectories(targetParent);
        }
        Files.move(file, quarantineTarget, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        log.warn("Quarantined file");
        return new QuarantineResult(file.toAbsolutePath().normalize(), quarantineTarget);
    }

    private Path buildQuarantineTarget(Path file) {
        Path baseDocsDir = Path.of(DEFAULT_DOCS_ROOT).toAbsolutePath().normalize();
        Path absoluteFile = file.toAbsolutePath().normalize();
        Path absoluteFileName = absoluteFile.getFileName();
        if (absoluteFileName == null) {
            throw new IllegalArgumentException("Cannot quarantine path without a filename: " + absoluteFile);
        }
        Path relativePath = absoluteFile.startsWith(baseDocsDir)
                ? baseDocsDir.relativize(absoluteFile)
                : Path.of(absoluteFileName.toString());

        Path relativeFileName = relativePath.getFileName();
        if (relativeFileName == null) {
            throw new IllegalArgumentException("Cannot quarantine path without a filename: " + relativePath);
        }

        String timestamp = LocalDateTime.now().format(QUARANTINE_TIMESTAMP_FORMAT);
        String originalName = relativeFileName.toString();
        String quarantinedName = appendTimestamp(originalName, timestamp);

        Path quarantineRoot = baseDocsDir.resolve(QUARANTINE_DIR_NAME);
        Path relativeParent = relativePath.getParent();
        if (relativeParent == null) {
            return quarantineRoot.resolve(quarantinedName);
        }
        return quarantineRoot.resolve(relativeParent).resolve(quarantinedName);
    }

    private String appendTimestamp(String fileName, String timestamp) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex) + "." + timestamp + fileName.substring(dotIndex);
        }
        return fileName + "." + timestamp;
    }

    /**
     * Describes where a file was moved during quarantine.
     *
     * @param original original file path
     * @param quarantined destination file path
     */
    public record QuarantineResult(Path original, Path quarantined) {
        public QuarantineResult {
            Objects.requireNonNull(original, "original");
            Objects.requireNonNull(quarantined, "quarantined");
        }
    }
}
