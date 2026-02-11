package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies Qdrant REST URL construction and port mapping for all known port configurations.
 */
class QdrantRestConnectionTest {

    @Test
    void restBaseUrl_withTlsAndDefaultGrpcPort_producesHttpsWithRestPort() throws ReflectiveOperationException {
        QdrantRestConnection connection = newConnection("cloud.qdrant.io", 6334, true);
        assertEquals("https://cloud.qdrant.io:6333", connection.restBaseUrl());
    }

    @Test
    void restBaseUrl_withoutTlsAndDefaultGrpcPort_producesHttpWithRestPort() throws ReflectiveOperationException {
        QdrantRestConnection connection = newConnection("localhost", 6334, false);
        assertEquals("http://localhost:6333", connection.restBaseUrl());
    }

    @Test
    void restBaseUrl_withDockerGrpcPort_mapsToDockerRestPort() throws ReflectiveOperationException {
        QdrantRestConnection connection = newConnection("localhost", 8086, false);
        assertEquals("http://localhost:8087", connection.restBaseUrl());
    }

    @Test
    void restBaseUrl_withTlsAndDockerGrpcPort_mapsToDockerRestPort() throws ReflectiveOperationException {
        QdrantRestConnection connection = newConnection("cloud.qdrant.io", 8086, true);
        assertEquals("https://cloud.qdrant.io:8087", connection.restBaseUrl());
    }

    @Test
    void restBaseUrl_withUnknownPort_passesPortThrough() throws ReflectiveOperationException {
        QdrantRestConnection connection = newConnection("custom-host", 9999, false);
        assertEquals("http://custom-host:9999", connection.restBaseUrl());
    }

    @Test
    void candidateRestBaseUrls_withTls_returnsSingleCanonicalUrl() throws ReflectiveOperationException {
        QdrantRestConnection connection = newConnection("cloud.qdrant.io", 6334, true);
        assertIterableEquals(List.of("https://cloud.qdrant.io:6333"), connection.candidateRestBaseUrls());
    }

    @Test
    void candidateRestBaseUrls_withoutTlsAndDefaultPort_returnsDeduplicated() throws ReflectiveOperationException {
        QdrantRestConnection connection = newConnection("localhost", 6334, false);
        assertIterableEquals(
                List.of("http://localhost:6333", "http://localhost:6334"), connection.candidateRestBaseUrls());
    }

    @Test
    void candidateRestBaseUrls_withDockerPort_includesRestAndGrpcFallback() throws ReflectiveOperationException {
        QdrantRestConnection connection = newConnection("localhost", 8086, false);
        assertIterableEquals(
                List.of("http://localhost:6333", "http://localhost:8087", "http://localhost:8086"),
                connection.candidateRestBaseUrls());
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

    private QdrantRestConnection newConnection(String host, int configuredPort, boolean useTls)
            throws ReflectiveOperationException {
        QdrantRestConnection connection = new QdrantRestConnection();
        setField(connection, "host", host);
        setField(connection, "configuredPort", configuredPort);
        setField(connection, "useTls", useTls);
        setField(connection, "apiKey", "");
        return connection;
    }

    private void setField(Object target, String fieldName, Object fieldValue) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, fieldValue);
    }
}
