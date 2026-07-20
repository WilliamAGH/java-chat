package com.williamcallahan.javachat.config;

import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Provides Qdrant REST URLs and authentication from the configured connection settings.
 *
 * <p>Spring AI configures the gRPC port (6334 default, 8086 in docker-compose),
 * but health checks, auditing, and index management require the REST port
 * (6333 default, 8087 in docker). This component owns the gRPC-to-REST port
 * mapping so that consumers do not duplicate the translation logic.
 */
@Component
public class QdrantRestConnection {
    private static final String HTTP_SCHEME = "http";
    private static final String HTTPS_SCHEME = "https";
    private static final String URL_SCHEME_SEPARATOR = "://";

    /** Qdrant API key header name for authenticated REST requests. */
    public static final String API_KEY_HEADER = "api-key";

    /** Qdrant default gRPC port. */
    private static final int QDRANT_GRPC_PORT = 6334;

    /** Qdrant default REST port. */
    private static final int QDRANT_REST_PORT = 6333;

    /** Docker compose gRPC port mapping used in this repository. */
    private static final int DOCKER_GRPC_PORT = 8086;

    /** Docker compose REST port mapping used in this repository. */
    private static final int DOCKER_REST_PORT = 8087;

    private final QdrantConnectionProperties connectionProperties;

    /**
     * Creates a REST projection from the canonical Qdrant connection settings.
     *
     * @param connectionProperties canonical Qdrant connection settings
     */
    public QdrantRestConnection(QdrantConnectionProperties connectionProperties) {
        this.connectionProperties = Objects.requireNonNull(connectionProperties, "connectionProperties");
    }

    /**
     * Builds the canonical Qdrant REST base URL with correct port mapping.
     *
     * <p>Maps the configured gRPC port to the corresponding REST port. The mapped
     * port is always included, even under TLS, because Qdrant Cloud exposes REST
     * on port 6333.
     *
     * @return base URL for Qdrant REST API calls (for example {@code https://cloud.qdrant.io:6333})
     */
    public String restBaseUrl() {
        String urlScheme = connectionProperties.useTls() ? HTTPS_SCHEME : HTTP_SCHEME;
        int restPort = mapGrpcPortToRestPort(connectionProperties.grpcPort());
        return buildBaseUrl(urlScheme, restPort);
    }

    /**
     * Returns the configured API key, which may be blank when authentication is not required.
     *
     * @return API key string (never null)
     */
    public String apiKey() {
        return connectionProperties.apiKey();
    }

    /**
     * Indicates whether TLS is enabled for the Qdrant connection.
     *
     * @return true when the connection uses HTTPS
     */
    public boolean useTls() {
        return connectionProperties.useTls();
    }

    /**
     * Maps a gRPC port to the corresponding Qdrant REST API port.
     *
     * <p>Known mappings: 6334 → 6333 (Qdrant default), 8086 → 8087 (docker compose).
     * Unrecognized ports are returned unchanged under the assumption that the caller
     * configured the REST port directly.
     *
     * @param grpcPort the configured gRPC port
     * @return the corresponding REST port
     */
    public static int mapGrpcPortToRestPort(int grpcPort) {
        if (grpcPort == QDRANT_GRPC_PORT) {
            return QDRANT_REST_PORT;
        }
        if (grpcPort == DOCKER_GRPC_PORT) {
            return DOCKER_REST_PORT;
        }
        return grpcPort;
    }

    private String buildBaseUrl(String urlScheme, int restPort) {
        return urlScheme + URL_SCHEME_SEPARATOR + connectionProperties.host() + ":" + restPort;
    }
}
