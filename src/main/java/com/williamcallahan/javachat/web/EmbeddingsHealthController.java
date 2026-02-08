package com.williamcallahan.javachat.web;

import com.williamcallahan.javachat.config.AppProperties;
import jakarta.annotation.security.PermitAll;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Reports whether the configured local embedding server is reachable when the feature is enabled.
 */
@RestController
@PermitAll
@PreAuthorize("permitAll()")
public class EmbeddingsHealthController {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingsHealthController.class);

    private final RestTemplate restTemplate;
    private final AppProperties appProperties;
    private final ExceptionResponseBuilder exceptionBuilder;

    /**
     * Creates the embeddings health controller.
     *
     * @param restTemplateBuilder builder for creating the RestTemplate
     * @param appProperties centralized application configuration
     * @param exceptionBuilder shared exception response builder
     */
    public EmbeddingsHealthController(
            RestTemplateBuilder restTemplateBuilder,
            AppProperties appProperties,
            ExceptionResponseBuilder exceptionBuilder) {
        this.restTemplate = restTemplateBuilder.build();
        this.appProperties = appProperties;
        this.exceptionBuilder = exceptionBuilder;
    }

    /**
     * Reports whether the configured local embedding server is reachable when the feature is enabled.
     */
    @GetMapping("/api/chat/health/embeddings")
    public ResponseEntity<EmbeddingsHealthResponse> checkEmbeddingsHealth() {
        String serverUrl = appProperties.getLocalEmbedding().getServerUrl();
        if (!appProperties.getLocalEmbedding().isEnabled()) {
            return ResponseEntity.ok(EmbeddingsHealthResponse.disabled(serverUrl));
        }

        try {
            // Simple health check - try to get models list
            String healthUrl = serverUrl + "/v1/models";
            restTemplate.getForEntity(healthUrl, String.class);
            return ResponseEntity.ok(EmbeddingsHealthResponse.healthy(serverUrl));
        } catch (RestClientException httpError) {
            log.debug("Embedding server health check failed", httpError);
            String details = exceptionBuilder.describeException(httpError);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(EmbeddingsHealthResponse.unhealthy(serverUrl, "UNREACHABLE: " + details));
        }
    }
}
