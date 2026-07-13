package com.williamcallahan.javachat.application.streaming;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Marks a terminal streaming failure that its provider boundary has already reported.
 *
 * <p>Request boundaries use this marker to avoid emitting duplicate alerts while still surfacing the
 * provider's upstream failure to retry classification and client-facing diagnostics.</p>
 */
public interface ReportedStreamingFailure {

    /**
     * Returns the upstream failure that the provider boundary reported.
     *
     * @return upstream failure preserved for generic request-boundary handling
     */
    Throwable upstreamFailure();

    /**
     * Finds the first reported streaming failure in a cause chain without revisiting identity cycles.
     *
     * @param failure exception received by an outer request boundary
     * @return reported streaming failure when a provider boundary already emitted its alert
     */
    static Optional<ReportedStreamingFailure> findInCauseChain(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        Set<Throwable> inspectedFailures = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable currentFailure = failure;
        while (currentFailure != null && inspectedFailures.add(currentFailure)) {
            if (currentFailure instanceof ReportedStreamingFailure reportedStreamingFailure) {
                return Optional.of(reportedStreamingFailure);
            }
            currentFailure = currentFailure.getCause();
        }
        return Optional.empty();
    }
}
