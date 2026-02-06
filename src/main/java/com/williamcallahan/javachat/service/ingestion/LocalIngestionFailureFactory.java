package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.domain.ingestion.IngestionLocalFailure;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Builds failure records with exception-specific diagnostic hints for local ingestion runs.
 */
@Service
public class LocalIngestionFailureFactory {

    private static final Map<Class<? extends Exception>, String> HINTS = new ConcurrentHashMap<>();

    static {
        register(java.io.FileNotFoundException.class, "file not found or inaccessible");
        register(java.nio.file.AccessDeniedException.class, "permission denied");
        register(java.nio.charset.MalformedInputException.class, "file encoding issue - not valid UTF-8");
        register(java.nio.file.NoSuchFileException.class, "file does not exist");
    }

    static void register(Class<? extends Exception> exceptionType, String hint) {
        HINTS.put(exceptionType, hint);
    }

    /**
     * Creates a failure record with exception-specific diagnostic context.
     *
     * @param file the file that failed processing
     * @param phase the processing phase where failure occurred
     * @param exception the exception that caused the failure
     * @return failure record with detailed diagnostics
     */
    public IngestionLocalFailure failure(Path file, String phase, Exception exception) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(exception, "exception");

        StringBuilder details = new StringBuilder();
        details.append(exception.getClass().getSimpleName());

        String message = exception.getMessage();
        if (message != null && !message.isBlank()) {
            details.append(": ").append(message);
        }

        String diagnosticHint = HINTS.get(exception.getClass());
        if (diagnosticHint != null) {
            details.append(" [").append(diagnosticHint).append("]");
        } else if (exception.getCause() != null) {
            details.append(" [caused by: ")
                    .append(exception.getCause().getClass().getSimpleName())
                    .append("]");
        }

        return new IngestionLocalFailure(file.toString(), phase, details.toString());
    }
}
