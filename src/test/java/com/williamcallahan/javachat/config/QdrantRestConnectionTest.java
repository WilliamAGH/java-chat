package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Verifies Qdrant REST URL construction and port mapping for all known port configurations.
 */
class QdrantRestConnectionTest {

    @Test
    void restBaseUrl_withTlsAndDefaultGrpcPort_producesHttpsWithRestPort() {
        QdrantRestConnection connection = newConnection("cloud.qdrant.io", 6334, true);
        assertEquals("https://cloud.qdrant.io:6333", connection.restBaseUrl());
    }

    @Test
    void restBaseUrl_withoutTlsAndDefaultGrpcPort_producesHttpWithRestPort() {
        QdrantRestConnection connection = newConnection("localhost", 6334, false);
        assertEquals("http://localhost:6333", connection.restBaseUrl());
    }

    @Test
    void restBaseUrl_withDockerGrpcPort_mapsToDockerRestPort() {
        QdrantRestConnection connection = newConnection("localhost", 8086, false);
        assertEquals("http://localhost:8087", connection.restBaseUrl());
    }

    @Test
    void restBaseUrl_withTlsAndDockerGrpcPort_mapsToDockerRestPort() {
        QdrantRestConnection connection = newConnection("cloud.qdrant.io", 8086, true);
        assertEquals("https://cloud.qdrant.io:8087", connection.restBaseUrl());
    }

    @Test
    void restBaseUrl_withUnknownPort_passesPortThrough() {
        QdrantRestConnection connection = newConnection("custom-host", 9999, false);
        assertEquals("http://custom-host:9999", connection.restBaseUrl());
    }

    @Test
    void mapGrpcPortToRestPort_mapsKnownPorts() {
        assertEquals(6333, QdrantRestConnection.mapGrpcPortToRestPort(6334));
        assertEquals(8087, QdrantRestConnection.mapGrpcPortToRestPort(8086));
    }

    @Test
    void mapGrpcPortToRestPort_passesUnknownPortThrough() {
        assertEquals(9999, QdrantRestConnection.mapGrpcPortToRestPort(9999));
    }

    private QdrantRestConnection newConnection(String host, int configuredPort, boolean useTls) {
        QdrantConnectionProperties connectionProperties =
                new QdrantConnectionProperties(host, configuredPort, useTls, "");
        return new QdrantRestConnection(connectionProperties);
    }
}
