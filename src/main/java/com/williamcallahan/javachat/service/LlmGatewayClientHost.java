package com.williamcallahan.javachat.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Names the machine that sends each LLM gateway request in the {@value #CLIENT_HOST_HEADER} header.
 *
 * <p>Gateway request logs attribute a row to its sending machine only through this header. A native run
 * sends its own short host name. Inside the deployed image the container's host name is its container id,
 * which names the replica rather than the machine, so the value comes only from the node-name marker the
 * deployment bind-mounts; a missing or invalid marker omits the header and logs one error. The header is
 * diagnostic, so an unusable value never fails a request. The gateway admits one DNS label
 * ({@code llm-gateway} {@code request_attribution.py}).</p>
 */
public final class LlmGatewayClientHost {
    private static final Logger log = LoggerFactory.getLogger(LlmGatewayClientHost.class);

    /** HTTP header the gateway records as the sending machine. */
    public static final String CLIENT_HOST_HEADER = "X-LGW-Client-Host";

    /** Root-owned file holding the Swarm node's short host name, bind-mounted into deployed tasks. */
    static final Path NODE_NAME_MARKER_PATH = Path.of("/run/aventure/node-name");

    /** Written by the runtime and ingestion Dockerfile stages: its presence means this process runs in the image. */
    static final Path CONTAINER_IMAGE_MARKER_PATH = Path.of("/etc/aventure/container-image");

    private static final int NODE_NAME_MARKER_MAX_BYTES = 64;
    private static final Pattern DNS_LABEL_PATTERN = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");
    private static final String NODE_NAME_TRAILING_LINE_FEED = "\n";

    private LlmGatewayClientHost() {}

    /** Holds the process-wide reading, computed on first use and kept for the process's life. */
    private static final class ProcessClientHost {
        private static final Optional<String> CLIENT_HOST = resolve(
                LlmGatewayClientHost::localHostName, NODE_NAME_MARKER_PATH, Files.exists(CONTAINER_IMAGE_MARKER_PATH));
    }

    /**
     * Returns the gateway client headers to add to every SDK client this process builds.
     *
     * @return the {@value #CLIENT_HOST_HEADER} entry, or no entry when this process has no usable host name
     */
    public static Map<String, List<String>> clientHeaders() {
        return ProcessClientHost.CLIENT_HOST
                .map(clientHost -> Map.of(CLIENT_HOST_HEADER, List.of(clientHost)))
                .orElse(Map.of());
    }

    /**
     * Resolves the sending machine: the node-name marker when it is present, nothing when it is absent inside
     * the image or invalid, else the platform host name cut at its first dot and lowercased.
     *
     * @param platformHostName reads the operating system's host name, empty when it cannot be read; called
     *     only on a native run without a marker
     * @param nodeNameMarker path of the deployment's node-name marker
     * @param containerized whether this process runs inside the deployed image
     * @return the DNS label to send, or empty to omit the header
     */
    static Optional<String> resolve(
            Supplier<Optional<String>> platformHostName, Path nodeNameMarker, boolean containerized) {
        byte[] markerBytes;
        try {
            if (!Files.readAttributes(nodeNameMarker, BasicFileAttributes.class).isRegularFile()) {
                return omitted(nodeNameMarker, "it is not a regular file");
            }
            try (InputStream markerStream = Files.newInputStream(nodeNameMarker)) {
                markerBytes = markerStream.readNBytes(NODE_NAME_MARKER_MAX_BYTES + 1);
            }
        } catch (NoSuchFileException missingMarker) {
            if (containerized) {
                return omitted(nodeNameMarker, "it is not mounted in this container");
            }
            return platformHostName
                    .get()
                    .map(hostName -> hostName.split("\\.", 2)[0].toLowerCase(Locale.ROOT))
                    .filter(shortHostName ->
                            DNS_LABEL_PATTERN.matcher(shortHostName).matches());
        } catch (IOException unreadableMarker) {
            return omitted(
                    nodeNameMarker,
                    "it is unreadable (" + unreadableMarker.getClass().getSimpleName() + ")");
        }
        if (markerBytes.length > NODE_NAME_MARKER_MAX_BYTES) {
            return omitted(nodeNameMarker, "it is longer than " + NODE_NAME_MARKER_MAX_BYTES + " bytes");
        }
        String markerText = new String(markerBytes, StandardCharsets.ISO_8859_1);
        String nodeName = markerText.endsWith(NODE_NAME_TRAILING_LINE_FEED)
                ? markerText.substring(0, markerText.length() - 1)
                : markerText;
        if (!DNS_LABEL_PATTERN.matcher(nodeName).matches()) {
            return omitted(
                    nodeNameMarker,
                    "it must hold one lowercase DNS label (a-z, 0-9, hyphen) and at most one trailing line feed");
        }
        return Optional.of(nodeName);
    }

    private static Optional<String> localHostName() {
        try {
            return Optional.of(InetAddress.getLocalHost().getHostName());
        } catch (UnknownHostException unresolvableHost) {
            log.error(
                    "Gateway client host unknown because the local host name does not resolve; requests omit {}",
                    CLIENT_HOST_HEADER,
                    unresolvableHost);
            return Optional.empty();
        }
    }

    private static Optional<String> omitted(Path nodeNameMarker, String reason) {
        log.error(
                "Gateway client host marker {} ignored because {}; requests omit {}. Provision the file (one "
                        + "lowercase DNS label, readable by the app user) and recreate the task.",
                nodeNameMarker,
                reason,
                CLIENT_HOST_HEADER);
        return Optional.empty();
    }
}
