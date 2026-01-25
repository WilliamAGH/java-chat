package com.williamcallahan.javachat.support;

import java.util.Locale;
import org.slf4j.Logger;

/**
 * Classifies retrieval errors and logs user-facing context without leaking raw error payloads.
 */
public final class RetrievalErrorClassifier {
    private RetrievalErrorClassifier() {}

    /**
     * Determine a stable error category based on exception messages and causes.
     *
     * @param error failure encountered during retrieval
     * @return normalized error category label
     */
    public static String determineErrorType(Throwable error) {
        StringBuilder messageBuilder = new StringBuilder();
        Throwable current = error;
        while (current != null) {
            String currentMessage = current.getMessage();
            if (currentMessage != null && !currentMessage.isBlank()) {
                if (messageBuilder.length() > 0) {
                    messageBuilder.append(' ');
                }
                messageBuilder.append(currentMessage);
            }
            current = current.getCause();
        }

        String message = messageBuilder.toString().toLowerCase(Locale.ROOT);

        if (message.contains("404") || message.contains("not found")) {
            return "404 Not Found";
        } else if (message.contains("401") || message.contains("unauthorized")) {
            return "401 Unauthorized";
        } else if (message.contains("403") || message.contains("forbidden")) {
            return "403 Forbidden";
        } else if (message.contains("429") || message.contains("too many requests")) {
            return "429 Rate Limited";
        } else if (message.contains("connection") || message.contains("timeout")) {
            return "Connection Error";
        } else if (message.contains("embedding") && message.contains("unavailable")) {
            return "Embedding Service Unavailable";
        }
        return "Unknown Error";
    }

    /**
     * Determines whether the exception is a transient Qdrant/vector store error that should trigger fallback.
     *
     * <p>Transient errors include connection issues, timeouts, and service unavailability (503).
     * Non-transient errors like invalid UUID format or programming errors should NOT trigger fallback
     * as they indicate bugs that need fixing.
     *
     * @param error the exception to classify
     * @return true if the error is transient and fallback is appropriate
     */
    public static boolean isTransientVectorStoreError(Throwable error) {
        String errorType = determineErrorType(error);

        // Connection errors and rate limits are transient
        if ("Connection Error".equals(errorType) || "429 Rate Limited".equals(errorType)) {
            return true;
        }

        // Check for specific Qdrant/gRPC error patterns in exception chain
        Throwable current = error;
        while (current != null) {
            String exceptionName = current.getClass().getName().toLowerCase(Locale.ROOT);
            String message = current.getMessage();
            String lowerMessage = message != null ? message.toLowerCase(Locale.ROOT) : "";

            // gRPC errors from Qdrant client
            if (exceptionName.contains("grpc") || exceptionName.contains("qdrant")) {
                // Service unavailable, deadline exceeded are transient
                if (lowerMessage.contains("unavailable")
                        || lowerMessage.contains("deadline exceeded")
                        || lowerMessage.contains("resource exhausted")) {
                    return true;
                }
            }

            // ExecutionException wrapping gRPC failures
            if (current instanceof java.util.concurrent.ExecutionException) {
                // Check the cause for gRPC issues
                Throwable cause = current.getCause();
                if (cause != null) {
                    String causeName = cause.getClass().getName().toLowerCase(Locale.ROOT);
                    if (causeName.contains("statusruntimeexception")) {
                        return true; // gRPC status errors are typically transient
                    }
                }
            }

            // IllegalArgumentException for UUID parsing is NOT transient (programming error)
            if (current instanceof IllegalArgumentException && lowerMessage.contains("uuid")) {
                return false;
            }

            current = current.getCause();
        }

        // Default: unknown errors are not assumed to be transient
        return false;
    }

    /**
     * Log user-friendly context about why vector search failed.
     *
     * @param log logger to emit messages
     * @param errorType classified error category
     * @param error original exception
     */
    public static void logUserFriendlyErrorContext(Logger log, String errorType, Throwable error) {
        if (error.getCause()
                instanceof
                com.williamcallahan.javachat.service.GracefulEmbeddingModel.EmbeddingServiceUnavailableException) {
            log.info(
                    "Embedding services are unavailable. Using keyword-based search with limited semantic understanding.");
        } else if (errorType.contains("404")) {
            log.info("Embedding API endpoint not found. Check configuration for spring.ai.openai.embedding.base-url");
        } else if (errorType.contains("401") || errorType.contains("403")) {
            log.info("Embedding API authentication failed. Check OPENAI_API_KEY or GITHUB_TOKEN configuration");
        } else if (errorType.contains("429")) {
            log.info("Embedding API rate limit exceeded. Consider using local embeddings or upgrading API tier");
        }
    }
}
