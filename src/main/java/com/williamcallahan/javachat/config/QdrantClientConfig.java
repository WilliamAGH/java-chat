package com.williamcallahan.javachat.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Custom Qdrant client configuration with gRPC keepalive settings.
 *
 * <p>The default Spring AI autoconfiguration creates a QdrantClient without gRPC keepalive,
 * which can cause connection drops behind load balancers (especially Qdrant Cloud). This
 * configuration overrides the default with proper keepalive settings.
 */
@Configuration
public class QdrantClientConfig {

    private static final Logger log = LoggerFactory.getLogger(QdrantClientConfig.class);

    /** Keepalive ping interval in seconds. */
    private static final long KEEPALIVE_TIME_SECONDS = 30;
    /** Keepalive timeout before connection is considered dead. */
    private static final long KEEPALIVE_TIMEOUT_SECONDS = 10;
    /**
     * Idle timeout large enough to disable gRPC idle mode.
     *
     * <p>Per gRPC Java 1.82.2 {@code ManagedChannelImplBuilder#idleTimeout}, any timeout at or above
     * {@code IDLE_MODE_MAX_TIMEOUT_DAYS} (30 days) disables idle mode entirely. Idle mode shuts down
     * the transport, the NameResolver, and the LoadBalancer after a period without RPCs, and
     * keepalive pings do not run once a channel is IDLE — so keepalive alone cannot hold the
     * connection open. Because every retrieval shares one wall-clock query budget, a reconnect
     * handshake inside that budget times out the whole fan-out and fails the request, which is
     * exactly what a short idle timeout caused here.
     */
    private static final long IDLE_MODE_DISABLE_TIMEOUT_DAYS = 30;

    private final QdrantConnectionProperties connectionProperties;

    /**
     * Creates the gRPC client configuration from the shared Qdrant connection settings.
     *
     * @param connectionProperties canonical Qdrant connection settings
     */
    public QdrantClientConfig(QdrantConnectionProperties connectionProperties) {
        this.connectionProperties = Objects.requireNonNull(connectionProperties, "connectionProperties");
    }

    /**
     * Creates a QdrantClient with gRPC keepalive configured for cloud deployments.
     *
     * <p>Marked as {@code @Primary} to override the Spring AI autoconfigured bean.
     *
     * @return configured Qdrant client with keepalive
     */
    @Bean
    @Primary
    public QdrantClient qdrantClient() {
        log.info("Creating QdrantClient with gRPC keepalive");

        ManagedChannelBuilder<?> channelBuilder =
                ManagedChannelBuilder.forAddress(connectionProperties.host(), connectionProperties.grpcPort());
        if (connectionProperties.useTls()) {
            channelBuilder.useTransportSecurity();
        } else {
            channelBuilder.usePlaintext();
        }

        channelBuilder
                .keepAliveTime(KEEPALIVE_TIME_SECONDS, TimeUnit.SECONDS)
                .keepAliveTimeout(KEEPALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .idleTimeout(IDLE_MODE_DISABLE_TIMEOUT_DAYS, TimeUnit.DAYS);
        log.debug(
                "gRPC keepalive configured: time={}s, timeout={}s, idleMode=disabled",
                KEEPALIVE_TIME_SECONDS,
                KEEPALIVE_TIMEOUT_SECONDS);

        ManagedChannel channel = Objects.requireNonNull(channelBuilder.build(), "ManagedChannel");
        QdrantGrpcClient.Builder grpcClientBuilder = QdrantGrpcClient.newBuilder(channel, true);

        String configuredApiKey = connectionProperties.apiKey();
        if (!configuredApiKey.isBlank()) {
            grpcClientBuilder.withApiKey(configuredApiKey);
        }

        return new QdrantClient(Objects.requireNonNull(grpcClientBuilder.build(), "QdrantGrpcClient"));
    }
}
