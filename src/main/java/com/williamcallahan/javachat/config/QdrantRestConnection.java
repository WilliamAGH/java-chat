package com.williamcallahan.javachat.config;

import java.util.LinkedHashSet;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Centralizes Qdrant REST connection knowledge: host, port mapping, TLS, and API key.
 *
 * <p>Spring AI configures the gRPC port (6334 default, 8086 in docker-compose),
 * but health checks, auditing, and index management require the REST port
 * (6333 default, 8087 in docker). This component owns the gRPC-to-REST port
 * mapping so that consumers do not duplicate the translation logic.
 */
@Component
public class QdrantRestConnection {

    /** Qdrant default gRPC port. */
    private static final int QDRANT_GRPC_PORT = 6334;

    /** Qdrant default REST port. */
    private static final int QDRANT_REST_PORT = 6333;

    /** Docker compose gRPC port mapping used in this repository. */
    private static final int DOCKER_GRPC_PORT = 8086;

    /** Docker compose REST port mapping used in this repository. */
    private static final int DOCKER_REST_PORT = 8087;

    @Value("${spring.ai.vectorstore.qdrant.host:localhost}")
    private String host;

    @Value("${spring.ai.vectorstore.qdrant.port:6334}")
    private int configuredPort;

    @Value("${spring.ai.vectorstore.qdrant.use-tls:false}")
    private boolean useTls;

    @Value("${spring.ai.vectorstore.qdrant.api-key:}")
    private String apiKey;

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
        String scheme = useTls ? "https" : "http";
        int restPort = mapGrpcPortToRestPort(configuredPort);
        return scheme + "://" + host + ":" + restPort;
    }

    /**
     * Returns candidate REST base URLs ordered by likelihood, for discovery scenarios.
     *
     * <p>Under TLS the canonical URL is sufficient. Without TLS the list includes the
     * default REST port, the mapped port, and the raw configured port as fallback so
     * that the caller can probe multiple endpoints when the exact port is uncertain.
     *
     * @return ordered, deduplicated list of candidate base URLs
     */
    public List<String> candidateRestBaseUrls() {
        if (useTls) {
            return List.of(restBaseUrl());
        }
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add("http://" + host + ":" + QDRANT_REST_PORT);
        candidates.add("http://" + host + ":" + mapGrpcPortToRestPort(configuredPort));
        candidates.add("http://" + host + ":" + configuredPort);
        return List.copyOf(candidates);
    }

    /**
     * Returns the configured API key, which may be blank when authentication is not required.
     *
     * @return API key string (never null)
     */
    public String apiKey() {
        return apiKey;
    }

    /**
     * Indicates whether TLS is enabled for the Qdrant connection.
     *
     * @return true when the connection uses HTTPS
     */
    public boolean useTls() {
        return useTls;
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
    static int mapGrpcPortToRestPort(int grpcPort) {
        if (grpcPort == QDRANT_GRPC_PORT) {
            return QDRANT_REST_PORT;
        }
        if (grpcPort == DOCKER_GRPC_PORT) {
            return DOCKER_REST_PORT;
        }
        return grpcPort;
    }
}
