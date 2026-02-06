package com.williamcallahan.javachat.config;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    /** Idle timeout before keepalive pings start. */
    private static final long IDLE_TIMEOUT_MINUTES = 5;

    @Value("${spring.ai.vectorstore.qdrant.host:localhost}")
    private String host;

    @Value("${spring.ai.vectorstore.qdrant.port:6334}")
    private int port;

    @Value("${spring.ai.vectorstore.qdrant.use-tls:false}")
    private boolean useTls;

    @Value("${spring.ai.vectorstore.qdrant.api-key:}")
    private String apiKey;

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

        ManagedChannelBuilder<?> channelBuilder = ManagedChannelBuilder.forAddress(host, port);
        if (useTls) {
            channelBuilder.useTransportSecurity();
        } else {
            channelBuilder.usePlaintext();
        }

        channelBuilder
                .keepAliveTime(KEEPALIVE_TIME_SECONDS, TimeUnit.SECONDS)
                .keepAliveTimeout(KEEPALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .keepAliveWithoutCalls(true)
                .idleTimeout(IDLE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        log.debug(
                "gRPC keepalive configured: time={}s, timeout={}s, idleTimeout={}m",
                KEEPALIVE_TIME_SECONDS,
                KEEPALIVE_TIMEOUT_SECONDS,
                IDLE_TIMEOUT_MINUTES);

        ManagedChannel channel = Objects.requireNonNull(channelBuilder.build(), "ManagedChannel");
        QdrantGrpcClient.Builder grpcClientBuilder = QdrantGrpcClient.newBuilder(channel, true);

        if (apiKey != null && !apiKey.isBlank()) {
            grpcClientBuilder.withApiKey(Objects.requireNonNull(apiKey, "apiKey"));
        }

        return new QdrantClient(Objects.requireNonNull(grpcClientBuilder.build(), "QdrantGrpcClient"));
    }
}
