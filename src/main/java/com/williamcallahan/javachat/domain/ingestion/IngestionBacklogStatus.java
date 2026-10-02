package com.williamcallahan.javachat.domain.ingestion;

import java.util.Objects;

/**
 * Describes the durable progress of one local documentation ingestion scope.
 *
 * @param lifecycle current ingestion lifecycle
 * @param eligibleFiles ingestable files discovered beneath the selected directory
 * @param inspectedFiles files that reached a terminal per-file outcome
 * @param processedFiles files that produced newly indexed chunks
 * @param skippedFiles files already indexed or intentionally excluded
 * @param failedFiles files that reached a typed failure
 * @param pendingFiles files still waiting to be inspected
 * @param inProgressFiles files currently owned by the active ingestion batch
 * @param directory selected directory relative to the configured documentation root
 */
public record IngestionBacklogStatus(
        Lifecycle lifecycle,
        int eligibleFiles,
        int inspectedFiles,
        int processedFiles,
        int skippedFiles,
        int failedFiles,
        int pendingFiles,
        int inProgressFiles,
        String directory) {

    /** Defines the lifecycle of a persisted local ingestion run. */
    public enum Lifecycle {
        /** No ingestion run has been recorded for the selected directory. */
        NOT_STARTED,
        /** One process currently owns the selected directory ingestion run. */
        RUNNING,
        /** The latest run stopped with failed or pending files. */
        PARTIAL,
        /** The latest run inspected every eligible file without failures. */
        COMPLETE
    }

    public IngestionBacklogStatus {
        Objects.requireNonNull(lifecycle, "Ingestion lifecycle is required");
        if (directory == null || directory.isBlank()) {
            throw new IllegalArgumentException("Ingestion directory is required");
        }
        requireNonNegative("eligibleFiles", eligibleFiles);
        requireNonNegative("inspectedFiles", inspectedFiles);
        requireNonNegative("processedFiles", processedFiles);
        requireNonNegative("skippedFiles", skippedFiles);
        requireNonNegative("failedFiles", failedFiles);
        requireNonNegative("pendingFiles", pendingFiles);
        requireNonNegative("inProgressFiles", inProgressFiles);
        if (inspectedFiles != processedFiles + skippedFiles + failedFiles) {
            throw new IllegalArgumentException("Inspected files must equal processed, skipped, and failed files");
        }
        if (eligibleFiles != inspectedFiles + pendingFiles + inProgressFiles) {
            throw new IllegalArgumentException("Eligible files must equal inspected, pending, and in-progress files");
        }
        if (lifecycle == Lifecycle.COMPLETE && (failedFiles != 0 || pendingFiles != 0 || inProgressFiles != 0)) {
            throw new IllegalArgumentException(
                    "Complete ingestion cannot retain failed, pending, or in-progress files");
        }
    }

    /**
     * Creates an unstarted backlog snapshot for a newly inspected directory.
     */
    public static IngestionBacklogStatus notStarted(String directory, int eligibleFiles) {
        return new IngestionBacklogStatus(
                Lifecycle.NOT_STARTED, eligibleFiles, 0, 0, 0, 0, eligibleFiles, 0, directory);
    }

    /**
     * Creates the initial durable state after a process claims the directory.
     */
    public static IngestionBacklogStatus running(String directory, int eligibleFiles) {
        return new IngestionBacklogStatus(Lifecycle.RUNNING, eligibleFiles, 0, 0, 0, 0, eligibleFiles, 0, directory);
    }

    /**
     * Moves the next bounded batch from pending to in-progress.
     */
    public IngestionBacklogStatus startBatch(int batchFileCount) {
        if (lifecycle != Lifecycle.RUNNING) {
            throw new IllegalStateException("Only a running ingestion can start a batch");
        }
        if (batchFileCount <= 0 || batchFileCount > pendingFiles) {
            throw new IllegalArgumentException("Batch file count must fit within the pending backlog");
        }
        if (inProgressFiles != 0) {
            throw new IllegalStateException("The prior ingestion batch has not completed");
        }
        return new IngestionBacklogStatus(
                lifecycle,
                eligibleFiles,
                inspectedFiles,
                processedFiles,
                skippedFiles,
                failedFiles,
                pendingFiles - batchFileCount,
                batchFileCount,
                directory);
    }

    /**
     * Records all terminal outcomes returned by the active batch.
     */
    public IngestionBacklogStatus completeBatch(int processedCount, int skippedCount, int failedCount) {
        requireNonNegative("processedCount", processedCount);
        requireNonNegative("skippedCount", skippedCount);
        requireNonNegative("failedCount", failedCount);
        int terminalOutcomeCount = processedCount + skippedCount + failedCount;
        if (terminalOutcomeCount > inProgressFiles) {
            throw new IllegalArgumentException("Batch outcomes exceed the in-progress file count");
        }
        int unattemptedCount = inProgressFiles - terminalOutcomeCount;
        return new IngestionBacklogStatus(
                lifecycle,
                eligibleFiles,
                inspectedFiles + terminalOutcomeCount,
                processedFiles + processedCount,
                skippedFiles + skippedCount,
                failedFiles + failedCount,
                pendingFiles + unattemptedCount,
                0,
                directory);
    }

    /**
     * Closes the run as complete only when no failed or pending work remains.
     */
    public IngestionBacklogStatus finish() {
        if (inProgressFiles != 0) {
            throw new IllegalStateException("Cannot finish ingestion while a batch remains in progress");
        }
        Lifecycle terminalLifecycle = failedFiles == 0 && pendingFiles == 0 ? Lifecycle.COMPLETE : Lifecycle.PARTIAL;
        return new IngestionBacklogStatus(
                terminalLifecycle,
                eligibleFiles,
                inspectedFiles,
                processedFiles,
                skippedFiles,
                failedFiles,
                pendingFiles,
                0,
                directory);
    }

    /**
     * Reclassifies work left in a running checkpoint after its process released ownership.
     *
     * <p>A recorded failure restarts the full marker-backed inventory because aggregate counts
     * cannot identify a non-prefix failed file. An interruption without a recorded failure retains
     * the terminal-success prefix and returns only interrupted files to pending.</p>
     *
     * @return partial backlog with no files reported as actively owned
     */
    public IngestionBacklogStatus abandon() {
        if (lifecycle == Lifecycle.COMPLETE || lifecycle == Lifecycle.NOT_STARTED) {
            return this;
        }
        if (failedFiles > 0) {
            return new IngestionBacklogStatus(
                    Lifecycle.PARTIAL, eligibleFiles, 0, 0, 0, 0, eligibleFiles, 0, directory);
        }
        int terminalSuccessCount = processedFiles + skippedFiles;
        return new IngestionBacklogStatus(
                Lifecycle.PARTIAL,
                eligibleFiles,
                terminalSuccessCount,
                processedFiles,
                skippedFiles,
                0,
                eligibleFiles - terminalSuccessCount,
                0,
                directory);
    }

    /**
     * Reopens unfinished work from the first position the durable state can identify safely.
     *
     * <p>Failed attempts restart the full marker-backed inventory; interruption-only attempts retain
     * their terminal-success prefix. Completed runs intentionally restart from inventory to detect
     * changed file content.</p>
     */
    public IngestionBacklogStatus resume() {
        if (lifecycle == Lifecycle.COMPLETE || lifecycle == Lifecycle.NOT_STARTED) {
            return running(directory, eligibleFiles);
        }
        IngestionBacklogStatus abandonedBacklog = abandon();
        return new IngestionBacklogStatus(
                Lifecycle.RUNNING,
                eligibleFiles,
                abandonedBacklog.inspectedFiles,
                abandonedBacklog.processedFiles,
                abandonedBacklog.skippedFiles,
                0,
                abandonedBacklog.pendingFiles,
                0,
                directory);
    }

    private static void requireNonNegative(String fieldName, int fieldCount) {
        if (fieldCount < 0) {
            throw new IllegalArgumentException(fieldName + " must be non-negative");
        }
    }
}
