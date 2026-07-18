package com.williamcallahan.javachat.web;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Loads client-visible SSE status contracts from their shared canonical resource.
 *
 * <p>The same JSON document is imported by the frontend, so startup must fail when a required field is
 * missing instead of allowing either runtime to invent a default.</p>
 */
@Component
@Lazy(false)
public final class SseStatusContractCatalog {
    private final SseStatusContract citationPartialFailure;

    /**
     * Loads and validates every shared SSE status contract during application startup.
     *
     * @param objectMapper application-managed JSON mapper
     * @param statusContractResource canonical shared status contract resource
     * @throws IllegalStateException when the resource cannot be read or violates its typed contract
     */
    public SseStatusContractCatalog(
            ObjectMapper objectMapper, @Value("classpath:sse-status-contracts.json") Resource statusContractResource) {
        Objects.requireNonNull(objectMapper, "objectMapper");
        Objects.requireNonNull(statusContractResource, "statusContractResource");
        try (InputStream statusContractStream = statusContractResource.getInputStream()) {
            SseStatusContractDocument statusContracts = objectMapper
                    .readerFor(SseStatusContractDocument.class)
                    .with(
                            DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                            DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
                    .readValue(statusContractStream);
            if (statusContracts == null) {
                throw new IllegalStateException("SSE status contract resource must contain a JSON document");
            }
            this.citationPartialFailure = statusContracts.citationPartialFailure();
        } catch (IOException contractReadFailure) {
            throw new IllegalStateException(
                    "Failed to load SSE status contracts from " + statusContractResource.getDescription(),
                    contractReadFailure);
        }
    }

    /**
     * Returns the canonical warning contract for incomplete citation conversion.
     *
     * @return immutable citation partial-failure contract
     */
    public SseStatusContract citationPartialFailure() {
        return citationPartialFailure;
    }

    /**
     * Defines the transport fields owned by a shared SSE status contract.
     *
     * @param code stable client-visible status code
     * @param stage processing stage associated with the status
     * @param retryable whether retrying can resolve the reported condition
     */
    public record SseStatusContract(String code, String stage, boolean retryable) {
        public SseStatusContract {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("SSE status contract code is required");
            }
            if (stage == null || stage.isBlank()) {
                throw new IllegalArgumentException("SSE status contract stage is required");
            }
        }
    }

    private record SseStatusContractDocument(SseStatusContract citationPartialFailure) {
        private SseStatusContractDocument {
            Objects.requireNonNull(citationPartialFailure, "citationPartialFailure");
        }
    }
}
