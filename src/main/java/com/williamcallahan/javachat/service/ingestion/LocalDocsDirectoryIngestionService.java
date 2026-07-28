package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.application.ingestion.FileLimit;
import com.williamcallahan.javachat.application.ingestion.LocalDocumentationIngestionUseCase;
import com.williamcallahan.javachat.domain.ingestion.IngestionBacklogStatus;
import com.williamcallahan.javachat.domain.ingestion.IngestionLocalFailure;
import com.williamcallahan.javachat.domain.ingestion.IngestionLocalOutcome;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Ingests local HTML/PDF documentation files from a selected readable mirror into Qdrant.
 */
@Service
public final class LocalDocsDirectoryIngestionService implements LocalDocumentationIngestionUseCase {
    private static final int LOCAL_INGESTION_FILE_BATCH_SIZE = 32;
    private static final int LOCAL_INGESTION_MAX_INVENTORY_FILES = 1_000_000;
    private static final int INVENTORY_DIGEST_BUFFER_SIZE = 8192;
    private static final String INVENTORY_DIGEST_ALGORITHM = "SHA-256";

    private final LocalDocsFileIngestionProcessor fileProcessor;
    private final LocalIngestionRunStore ingestionRunStore;
    private final Path configuredDocumentationRoot;

    /**
     * Creates a directory ingestor backed by a per-file ingestion processor.
     *
     * @param fileProcessor file processor for extraction, chunking, and persistence
     * @param ingestionRunStore durable progress and cross-process claim owner
     * @param documentationRoot configured boundary for every caller-selected source directory
     */
    public LocalDocsDirectoryIngestionService(
            LocalDocsFileIngestionProcessor fileProcessor,
            LocalIngestionRunStore ingestionRunStore,
            @Value("${DOCS_DIR:data/docs}") String documentationRoot) {
        this.fileProcessor = Objects.requireNonNull(fileProcessor, "fileProcessor");
        this.ingestionRunStore = Objects.requireNonNull(ingestionRunStore, "ingestionRunStore");
        if (documentationRoot == null || documentationRoot.isBlank()) {
            throw new IllegalArgumentException("Configured documentation root is required");
        }
        this.configuredDocumentationRoot =
                Path.of(documentationRoot).toAbsolutePath().normalize();
    }

    /**
     * Ingests HTML/PDF files from a local directory mirror (for example, {@code data/docs/**}).
     *
     * @param rootDir root directory to scan
     * @param fileLimit maximum number of files to process
     * @return ingestion outcome including per-file failures
     * @throws IOException if directory walking fails
     */
    @Override
    public IngestionLocalOutcome ingestLocalDirectory(String rootDirectory, FileLimit fileLimit) throws IOException {
        if (rootDirectory == null || rootDirectory.isBlank()) {
            throw new IllegalArgumentException("Local docs directory is required");
        }
        FileLimit requiredFileLimit = Objects.requireNonNull(fileLimit, "fileLimit");

        Path realSelectedRoot = requireSelectedRoot(rootDirectory);
        List<IngestionLocalFailure> failures = new ArrayList<>();
        IngestionBacklogStatus backlogStatus;

        Path realDocumentationRoot = configuredDocumentationRoot.toRealPath();
        LocalIngestionRunStore.RunClaim ingestionRunClaim = ingestionRunStore.claim(realSelectedRoot);
        try (ingestionRunClaim) {
            EligibleFileInventory eligibleFileInventory = eligibleFileInventory(realSelectedRoot);
            List<Path> eligibleFiles = eligibleFileInventory.eligibleFiles();
            String selectedDirectoryLabel = selectedDirectoryLabel(realDocumentationRoot, realSelectedRoot);
            backlogStatus = resumableBacklog(
                    realSelectedRoot,
                    selectedDirectoryLabel,
                    eligibleFiles.size(),
                    eligibleFileInventory.inventoryFingerprint());
            int firstSelectedFileIndex = backlogStatus.inspectedFiles();
            int selectedFileCount =
                    Math.min(requiredFileLimit.maximumFiles(), eligibleFiles.size() - firstSelectedFileIndex);
            ingestionRunStore.write(realSelectedRoot, backlogStatus, eligibleFileInventory.inventoryFingerprint());
            int selectedFileIndex = firstSelectedFileIndex;
            int selectedFileEndIndex = firstSelectedFileIndex + selectedFileCount;
            boolean runStopped = false;
            while (selectedFileIndex < selectedFileEndIndex && !runStopped) {
                int batchEndIndex = Math.min(selectedFileIndex + LOCAL_INGESTION_FILE_BATCH_SIZE, selectedFileEndIndex);
                List<Path> fileBatch = List.copyOf(eligibleFiles.subList(selectedFileIndex, batchEndIndex));
                backlogStatus = backlogStatus.startBatch(fileBatch.size());
                ingestionRunStore.write(realSelectedRoot, backlogStatus, eligibleFileInventory.inventoryFingerprint());

                int batchProcessedCount = 0;
                int batchSkippedCount = 0;
                int batchFailedCount = 0;
                for (LocalDocsFileOutcome fileOutcome : fileProcessor.processBatch(realSelectedRoot, fileBatch)) {
                    if (fileOutcome.processed()) {
                        batchProcessedCount++;
                    } else if (fileOutcome.failure().isPresent()) {
                        batchFailedCount++;
                    } else {
                        batchSkippedCount++;
                    }
                    fileOutcome.failure().ifPresent(failures::add);
                }
                backlogStatus = backlogStatus.completeBatch(batchProcessedCount, batchSkippedCount, batchFailedCount);
                ingestionRunStore.write(realSelectedRoot, backlogStatus, eligibleFileInventory.inventoryFingerprint());
                runStopped = batchFailedCount > 0;
                selectedFileIndex = batchEndIndex;
            }
            backlogStatus = backlogStatus.finish();
            ingestionRunStore.write(realSelectedRoot, backlogStatus, eligibleFileInventory.inventoryFingerprint());
        }

        return IngestionLocalOutcome.fromBacklog(backlogStatus, rootDirectory, failures);
    }

    private Path requireSelectedRoot(String rootDirectory) throws IOException {
        if (rootDirectory == null || rootDirectory.isBlank()) {
            throw new IllegalArgumentException("Local docs directory is required");
        }
        Path selectedRoot = Path.of(rootDirectory).toAbsolutePath().normalize();
        if (!Files.isDirectory(selectedRoot) || !Files.isReadable(selectedRoot)) {
            throw new IllegalArgumentException("Local docs directory does not exist: " + rootDirectory);
        }
        Path realDocumentationRoot = configuredDocumentationRoot.toRealPath();
        Path realSelectedRoot = selectedRoot.toRealPath();
        if (!realSelectedRoot.startsWith(realDocumentationRoot)) {
            throw new IllegalArgumentException("Local docs directory must remain beneath the configured DOCS_DIR");
        }
        return realSelectedRoot;
    }

    private List<Path> eligibleFiles(Path selectedRoot) throws IOException {
        try (Stream<Path> paths = Files.walk(selectedRoot)) {
            List<Path> eligibleFiles = paths.filter(pathCandidate -> !Files.isDirectory(pathCandidate))
                    .filter(this::isIngestableFile)
                    .sorted()
                    .limit((long) LOCAL_INGESTION_MAX_INVENTORY_FILES + 1)
                    .toList();
            if (eligibleFiles.size() > LOCAL_INGESTION_MAX_INVENTORY_FILES) {
                throw new IllegalArgumentException("Local documentation inventory exceeds the supported file limit");
            }
            return eligibleFiles;
        }
    }

    private IngestionBacklogStatus resumableBacklog(
            Path selectedRoot,
            String selectedDirectoryLabel,
            int currentEligibleFileCount,
            String inventoryFingerprint) {
        return ingestionRunStore
                .read(selectedRoot, inventoryFingerprint)
                .filter(persistedBacklog -> persistedBacklog.eligibleFiles() == currentEligibleFileCount)
                .map(IngestionBacklogStatus::resume)
                .orElseGet(() -> IngestionBacklogStatus.running(selectedDirectoryLabel, currentEligibleFileCount));
    }

    private EligibleFileInventory eligibleFileInventory(Path selectedRoot) throws IOException {
        List<Path> currentEligibleFiles = eligibleFiles(selectedRoot);
        MessageDigest inventoryDigest;
        try {
            inventoryDigest = MessageDigest.getInstance(INVENTORY_DIGEST_ALGORITHM);
        } catch (NoSuchAlgorithmException missingDigestAlgorithm) {
            throw new IllegalStateException(
                    "Required ingestion inventory digest is unavailable", missingDigestAlgorithm);
        }
        byte[] inventoryBuffer = new byte[INVENTORY_DIGEST_BUFFER_SIZE];
        for (Path eligibleFile : currentEligibleFiles) {
            updateInventoryDigest(
                    inventoryDigest, selectedRoot.relativize(eligibleFile).toString());
            try (InputStream fileInput = Files.newInputStream(eligibleFile)) {
                int readByteCount;
                while ((readByteCount = fileInput.read(inventoryBuffer)) != -1) {
                    inventoryDigest.update(inventoryBuffer, 0, readByteCount);
                }
            }
            inventoryDigest.update((byte) 0);
        }
        return new EligibleFileInventory(currentEligibleFiles, HexFormat.of().formatHex(inventoryDigest.digest()));
    }

    private static String selectedDirectoryLabel(Path documentationRoot, Path selectedRoot) {
        String relativeDirectory = documentationRoot.relativize(selectedRoot).toString();
        return relativeDirectory.isBlank() ? "." : relativeDirectory;
    }

    private void updateInventoryDigest(MessageDigest inventoryDigest, String inventoryField) {
        inventoryDigest.update(inventoryField.getBytes(StandardCharsets.UTF_8));
        inventoryDigest.update((byte) 0);
    }

    private boolean isIngestableFile(Path path) {
        Path fileNamePath = path.getFileName();
        if (fileNamePath == null) {
            return false;
        }
        String name = fileNamePath.toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".pdf");
    }

    private record EligibleFileInventory(List<Path> eligibleFiles, String inventoryFingerprint) {
        private EligibleFileInventory {
            eligibleFiles = List.copyOf(eligibleFiles);
            Objects.requireNonNull(inventoryFingerprint, "inventoryFingerprint");
        }
    }
}
