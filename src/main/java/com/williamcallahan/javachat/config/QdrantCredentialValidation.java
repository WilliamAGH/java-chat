package com.williamcallahan.javachat.config;

import jakarta.annotation.PostConstruct;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Enforces the credential invariant required by every TLS-secured Qdrant connection.
 *
 * <p>Qdrant backs both web requests and non-web ingestion jobs, so this validation remains
 * unconditional across Spring application types and fails startup before either workload can
 * attempt an unauthenticated TLS connection.</p>
 */
@Configuration
@Lazy(false)
public class QdrantCredentialValidation {
    private static final Logger log = LoggerFactory.getLogger(QdrantCredentialValidation.class);
    private static final String MISSING_QDRANT_API_KEY_MESSAGE =
            "Qdrant TLS is enabled but QDRANT_API_KEY is not set. Set QDRANT_API_KEY for authenticated access.";
    private static final String QDRANT_CREDENTIAL_VALIDATION_PASSED_MESSAGE =
            "Required Qdrant credential validation passed";

    private final QdrantConnectionProperties connectionProperties;

    QdrantCredentialValidation(QdrantConnectionProperties connectionProperties) {
        this.connectionProperties = Objects.requireNonNull(connectionProperties, "connectionProperties");
    }

    /**
     * Validates the Qdrant TLS credential before any web or CLI workload begins.
     *
     * @throws IllegalStateException if TLS is enabled without a Qdrant API key
     */
    @PostConstruct
    public void validateRequiredQdrantCredential() {
        if (connectionProperties.useTls() && connectionProperties.apiKey().isBlank()) {
            throw new IllegalStateException(MISSING_QDRANT_API_KEY_MESSAGE);
        }

        log.info(QDRANT_CREDENTIAL_VALIDATION_PASSED_MESSAGE);
    }
}
