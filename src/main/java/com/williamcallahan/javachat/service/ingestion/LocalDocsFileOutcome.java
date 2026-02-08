package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.domain.ingestion.IngestionLocalFailure;
import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of processing a single local docs file.
 */
public sealed interface LocalDocsFileOutcome
        permits LocalDocsFileOutcome.Processed, LocalDocsFileOutcome.Skipped, LocalDocsFileOutcome.Failed {

    /**
     * Returns true when the file contributed new chunks to the destination.
     */
    boolean processed();

    /**
     * Returns a typed failure when processing failed.
     */
    Optional<IngestionLocalFailure> failure();

    /**
     * Returns a processed outcome for files that produced indexed chunks.
     */
    static LocalDocsFileOutcome processedFile() {
        return Processed.INSTANCE;
    }

    /**
     * Returns a skipped outcome for files that were unchanged or already indexed.
     */
    static LocalDocsFileOutcome skippedFile() {
        return Skipped.INSTANCE;
    }

    /**
     * Returns a failed outcome carrying the typed local ingestion failure.
     */
    static LocalDocsFileOutcome failedFile(IngestionLocalFailure failure) {
        Objects.requireNonNull(failure, "failure");
        return new Failed(failure);
    }

    record Processed() implements LocalDocsFileOutcome {
        private static final Processed INSTANCE = new Processed();

        @Override
        public boolean processed() {
            return true;
        }

        @Override
        public Optional<IngestionLocalFailure> failure() {
            return Optional.empty();
        }
    }

    record Skipped() implements LocalDocsFileOutcome {
        private static final Skipped INSTANCE = new Skipped();

        @Override
        public boolean processed() {
            return false;
        }

        @Override
        public Optional<IngestionLocalFailure> failure() {
            return Optional.empty();
        }
    }

    record Failed(IngestionLocalFailure detail) implements LocalDocsFileOutcome {
        public Failed {
            Objects.requireNonNull(detail, "detail");
        }

        @Override
        public boolean processed() {
            return false;
        }

        @Override
        public Optional<IngestionLocalFailure> failure() {
            return Optional.of(detail());
        }
    }
}
