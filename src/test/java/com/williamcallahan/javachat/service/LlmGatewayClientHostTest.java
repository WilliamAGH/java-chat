package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies which machine name reaches the gateway's {@code X-LGW-Client-Host} header. */
class LlmGatewayClientHostTest {
    private static final String CONTAINER_ID_HOST_NAME = "3f9c2a7b81d4";

    @TempDir
    Path markerDirectory;

    @Test
    void nativeRunSendsTheLowercasedShortHostName() {
        Path absentMarker = markerDirectory.resolve("node-name");

        assertEquals(
                Optional.of("build-node-3"),
                LlmGatewayClientHost.resolve(() -> Optional.of("Build-Node-3.tail.example.net"), absentMarker, false));
        assertEquals(
                Optional.empty(),
                LlmGatewayClientHost.resolve(() -> Optional.of("-build_node.local"), absentMarker, false));
    }

    @Test
    void containerRunSendsTheMountedNodeNameInsteadOfItsContainerId() throws IOException {
        Path nodeNameMarker = Files.writeString(markerDirectory.resolve("node-name"), "swarm-worker-2\n");

        assertEquals(
                Optional.of("swarm-worker-2"),
                LlmGatewayClientHost.resolve(() -> Optional.of(CONTAINER_ID_HOST_NAME), nodeNameMarker, true));
    }

    @Test
    void containerRunWithoutTheMountOmitsTheHeader() {
        assertEquals(
                Optional.empty(),
                LlmGatewayClientHost.resolve(
                        () -> Optional.of(CONTAINER_ID_HOST_NAME), markerDirectory.resolve("node-name"), true));
    }

    @Test
    void invalidMarkerOmitsTheHeader() throws IOException {
        Path nodeNameMarker = Files.writeString(markerDirectory.resolve("node-name"), "Swarm-Worker-2\n");

        assertEquals(
                Optional.empty(),
                LlmGatewayClientHost.resolve(() -> Optional.of(CONTAINER_ID_HOST_NAME), nodeNameMarker, true));
    }
}
