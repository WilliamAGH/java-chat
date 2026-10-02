package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.domain.ingestion.IngestionLocalFailure;
import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of processing a single local docs file.
 */
public sealed interface LocalDocsFileOutcome
        permits LocalDocsFileOutcome.Processed,
                LocalDocsFileOutcome.Skipped,
                LocalDocsFileOutcome.Excluded,
                LocalDocsFileOutcome.Failed {

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
     *
     * <p>Skipped files retain their existing Qdrant points and are genuine duplicates of content
     * already stored in the vector index. Intentionally excluded pages that hold zero Qdrant points
     * use {@link #excludedFile()} instead.</p>
     */
    static LocalDocsFileOutcome skippedFile() {
        return Skipped.INSTANCE;
    }

    /**
     * Returns an excluded outcome for a file that was intentionally not indexed.
     *
     * <p>Excluded pages (for example Javadoc class-use index pages or frameset/navigation shells) are
     * not upserted into Qdrant and may have their existing points deleted, so the URL ends up with
     * zero Qdrant points — the opposite of an already-indexed duplicate.</p>
     */
    static LocalDocsFileOutcome excludedFile() {
        return Excluded.INSTANCE;
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

    record Excluded() implements LocalDocsFileOutcome {
        private static final Excluded INSTANCE = new Excluded();

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
