package com.williamcallahan.javachat.service.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.williamcallahan.javachat.application.ingestion.IngestionAlreadyRunningException;
import com.williamcallahan.javachat.domain.ingestion.IngestionBacklogStatus;
import com.williamcallahan.javachat.service.LocalStoreService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.function.ToIntFunction;
import org.springframework.stereotype.Service;

/**
 * Persists local ingestion run progress and enforces one cross-process owner per source directory.
 */
@Service
public final class LocalIngestionRunStore {
    private static final String RUN_FILE_PREFIX = "local-ingestion-";
    private static final String RUN_FILE_EXTENSION = ".json";
    private static final String RUN_LOCK_EXTENSION = ".lock";
    private static final String RUN_TEMPORARY_PREFIX = ".local-ingestion-";
    private static final String RUN_TEMPORARY_SUFFIX = ".tmp";
    private static final String RUN_IDENTITY_DIGEST_ALGORITHM = "SHA-256";

    private final LocalStoreService localStoreService;
    private final ObjectMapper objectMapper;

    /**
     * Creates the durable run store and publishes latest-run backlog gauges.
     */
    public LocalIngestionRunStore(
            LocalStoreService localStoreService, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
        this.localStoreService = Objects.requireNonNull(localStoreService, "localStoreService");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        MeterRegistry requiredMeterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        Gauge.builder(
                        "javachat.ingestion.backlog.pending",
                        this,
                        runStore -> runStore.aggregateCount(IngestionBacklogStatus::pendingFiles))
                .description("Files waiting across durable local documentation ingestion runs")
                .register(requiredMeterRegistry);
        Gauge.builder(
                        "javachat.ingestion.backlog.in.progress",
                        this,
                        runStore -> runStore.aggregateCount(IngestionBacklogStatus::inProgressFiles))
                .description("Files owned across active local documentation ingestion batches")
                .register(requiredMeterRegistry);
        Gauge.builder(
                        "javachat.ingestion.backlog.failed",
                        this,
                        runStore -> runStore.aggregateCount(IngestionBacklogStatus::failedFiles))
                .description("Files failed across durable local documentation ingestion runs")
                .register(requiredMeterRegistry);
    }

    /**
     * Acquires the exclusive run claim for a canonical documentation directory.
     *
     * @throws IOException when the claim file cannot be opened
     * @throws IngestionAlreadyRunningException when another process already owns the same directory
     */
    public RunClaim claim(Path canonicalDirectory) throws IOException {
        Path requiredDirectory = Objects.requireNonNull(canonicalDirectory, "canonicalDirectory");
        Path lockPath = runPath(requiredDirectory, RUN_LOCK_EXTENSION);
        Files.createDirectories(localStoreService.indexDirectory());
        FileChannel lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock fileLock = lockChannel.tryLock();
            if (fileLock == null) {
                lockChannel.close();
                throw new IngestionAlreadyRunningException();
            }
            return new RunClaim(lockChannel);
        } catch (OverlappingFileLockException lockConflict) {
            lockChannel.close();
            IngestionAlreadyRunningException ingestionConflict = new IngestionAlreadyRunningException();
            ingestionConflict.addSuppressed(lockConflict);
            throw ingestionConflict;
        } catch (IOException | RuntimeException claimFailure) {
            if (lockChannel.isOpen()) {
                lockChannel.close();
            }
            throw claimFailure;
        }
    }

    /**
     * Atomically persists the latest progress for one canonical documentation directory.
     *
     * @param canonicalDirectory canonical selected documentation directory
     * @param backlogStatus latest aggregate progress
     * @param inventoryFingerprint identity of the sorted source inventory
     */
    public void write(Path canonicalDirectory, IngestionBacklogStatus backlogStatus, String inventoryFingerprint)
            throws IOException {
        Path requiredDirectory = Objects.requireNonNull(canonicalDirectory, "canonicalDirectory");
        IngestionBacklogStatus requiredBacklog = Objects.requireNonNull(backlogStatus, "backlogStatus");
        String requiredInventoryFingerprint = requireInventoryFingerprint(inventoryFingerprint);
        String directoryIdentity = directoryIdentity(requiredDirectory);
        Path progressPath = runPath(requiredDirectory, RUN_FILE_EXTENSION);
        Path progressDirectory = progressPath.getParent();
        if (progressDirectory == null) {
            throw new IOException("Local ingestion progress path has no parent directory");
        }
        Files.createDirectories(progressDirectory);
        Path temporaryProgress = Files.createTempFile(progressDirectory, RUN_TEMPORARY_PREFIX, RUN_TEMPORARY_SUFFIX);
        try {
            persistCheckpoint(
                    temporaryProgress,
                    progressPath,
                    new DurableIngestionCheckpoint(directoryIdentity, requiredInventoryFingerprint, requiredBacklog));
        } finally {
            Files.deleteIfExists(temporaryProgress);
        }
    }

    /**
     * Reads the latest persisted progress for one canonical documentation directory.
     *
     * @param canonicalDirectory canonical selected documentation directory
     * @param inventoryFingerprint identity of the current sorted source inventory
     * @return matching checkpoint, or empty when inventory changed or no run exists
     */
    public Optional<IngestionBacklogStatus> read(Path canonicalDirectory, String inventoryFingerprint) {
        Path requiredDirectory = Objects.requireNonNull(canonicalDirectory, "canonicalDirectory");
        String requiredInventoryFingerprint = requireInventoryFingerprint(inventoryFingerprint);
        Path progressPath = runPath(requiredDirectory, RUN_FILE_EXTENSION);
        if (!Files.exists(progressPath)) {
            return Optional.empty();
        }
        try {
            DurableIngestionCheckpoint checkpoint =
                    objectMapper.readValue(progressPath.toFile(), DurableIngestionCheckpoint.class);
            String requiredDirectoryIdentity = directoryIdentity(requiredDirectory);
            if (!checkpoint.directoryIdentity().equals(requiredDirectoryIdentity)) {
                throw new IllegalStateException("Local ingestion checkpoint directory identity does not match");
            }
            if (!checkpoint.inventoryFingerprint().equals(requiredInventoryFingerprint)) {
                Files.deleteIfExists(progressPath);
                return Optional.empty();
            }
            return Optional.of(checkpoint.backlog());
        } catch (IOException progressReadFailure) {
            throw new IllegalStateException(
                    "Failed to read local ingestion progress for directory: " + requiredDirectory, progressReadFailure);
        }
    }

    private Path runPath(Path canonicalDirectory, String extension) {
        String runFileName = RUN_FILE_PREFIX + directoryIdentity(canonicalDirectory) + extension;
        return localStoreService.indexDirectory().resolve(runFileName);
    }

    private static String directoryIdentity(Path canonicalDirectory) {
        String canonicalDirectoryText =
                canonicalDirectory.toAbsolutePath().normalize().toString();
        try {
            MessageDigest directoryDigest = MessageDigest.getInstance(RUN_IDENTITY_DIGEST_ALGORITHM);
            return HexFormat.of()
                    .formatHex(directoryDigest.digest(canonicalDirectoryText.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missingDigestAlgorithm) {
            throw new IllegalStateException(
                    "Required local ingestion identity digest is unavailable", missingDigestAlgorithm);
        }
    }

    private double aggregateCount(ToIntFunction<IngestionBacklogStatus> countSelector) {
        Path indexDirectory = localStoreService.indexDirectory();
        if (!Files.isDirectory(indexDirectory)) {
            return 0;
        }
        int aggregateFileCount = 0;
        try (DirectoryStream<Path> progressPaths =
                Files.newDirectoryStream(indexDirectory, RUN_FILE_PREFIX + "*" + RUN_FILE_EXTENSION)) {
            for (Path progressPath : progressPaths) {
                DurableIngestionCheckpoint checkpoint =
                        objectMapper.readValue(progressPath.toFile(), DurableIngestionCheckpoint.class);
                DurableIngestionCheckpoint reconciledCheckpoint =
                        reconcileAbandonedCheckpoint(progressPath, checkpoint);
                aggregateFileCount += countSelector.applyAsInt(reconciledCheckpoint.backlog());
            }
            return aggregateFileCount;
        } catch (IOException progressReadFailure) {
            throw new IllegalStateException(
                    "Failed to aggregate durable local ingestion progress", progressReadFailure);
        }
    }

    private DurableIngestionCheckpoint reconcileAbandonedCheckpoint(
            Path progressPath, DurableIngestionCheckpoint checkpoint) throws IOException {
        if (checkpoint.backlog().lifecycle() != IngestionBacklogStatus.Lifecycle.RUNNING) {
            return checkpoint;
        }
        String progressFileName = Objects.requireNonNull(
                        progressPath.getFileName(), "Progress path must have a file name")
                .toString();
        String runFileStem = progressFileName.substring(0, progressFileName.length() - RUN_FILE_EXTENSION.length());
        Path lockPath = progressPath.resolveSibling(runFileStem + RUN_LOCK_EXTENSION);
        try (FileChannel lockChannel =
                FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock abandonedRunLock;
            try {
                abandonedRunLock = lockChannel.tryLock();
            } catch (OverlappingFileLockException activeLocalRun) {
                return checkpoint;
            }
            if (abandonedRunLock == null) {
                return checkpoint;
            }
            try (abandonedRunLock) {
                // Re-read under the lock: the owner may have persisted a terminal checkpoint between
                // the unlocked read and this acquisition; reconciling that stale snapshot would
                // regress durable progress (e.g. COMPLETE back to PARTIAL).
                if (!Files.exists(progressPath)) {
                    return checkpoint;
                }
                DurableIngestionCheckpoint lockedCheckpoint =
                        objectMapper.readValue(progressPath.toFile(), DurableIngestionCheckpoint.class);
                if (!lockedCheckpoint.equals(checkpoint)) {
                    return lockedCheckpoint;
                }
                DurableIngestionCheckpoint reconciledCheckpoint = new DurableIngestionCheckpoint(
                        checkpoint.directoryIdentity(),
                        checkpoint.inventoryFingerprint(),
                        checkpoint.backlog().abandon());
                Path progressDirectory = progressPath.getParent();
                if (progressDirectory == null) {
                    throw new IOException("Local ingestion progress path has no parent directory");
                }
                Path temporaryProgress =
                        Files.createTempFile(progressDirectory, RUN_TEMPORARY_PREFIX, RUN_TEMPORARY_SUFFIX);
                try {
                    persistCheckpoint(temporaryProgress, progressPath, reconciledCheckpoint);
                } finally {
                    Files.deleteIfExists(temporaryProgress);
                }
                return reconciledCheckpoint;
            }
        }
    }

    private void persistCheckpoint(Path temporaryProgress, Path progressPath, DurableIngestionCheckpoint checkpoint)
            throws IOException {
        objectMapper.writeValue(temporaryProgress.toFile(), checkpoint);
        Files.move(
                temporaryProgress, progressPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static String requireInventoryFingerprint(String inventoryFingerprint) {
        if (inventoryFingerprint == null || inventoryFingerprint.isBlank()) {
            throw new IllegalArgumentException("Ingestion inventory fingerprint is required");
        }
        return inventoryFingerprint;
    }

    private record DurableIngestionCheckpoint(
            String directoryIdentity, String inventoryFingerprint, IngestionBacklogStatus backlog) {
        private DurableIngestionCheckpoint {
            if (directoryIdentity == null || directoryIdentity.isBlank()) {
                throw new IllegalArgumentException("Ingestion directory identity is required");
            }
            inventoryFingerprint = requireInventoryFingerprint(inventoryFingerprint);
            backlog = Objects.requireNonNull(backlog, "backlog");
        }
    }

    /**
     * Owns one operating-system file lock for the duration of a local ingestion run.
     */
    public static final class RunClaim implements AutoCloseable {
        private final FileChannel lockChannel;

        private RunClaim(FileChannel lockChannel) {
            this.lockChannel = Objects.requireNonNull(lockChannel, "lockChannel");
        }

        /**
         * Releases the directory claim and closes its channel.
         */
        @Override
        public void close() throws IOException {
            lockChannel.close();
        }
    }
}
