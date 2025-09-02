package com.williamcallahan.javachat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Checks whether the configured local embeddings server is reachable quickly.
 * If not reachable, we log a short warning and let the application proceed
 * without creating the LocalEmbeddingModel bean, allowing Spring AI's default
 * EmbeddingModel (OpenAI) to take over.
 */
public class LocalEmbeddingServerAvailableCondition implements Condition {
    private static final Logger log = LoggerFactory.getLogger(LocalEmbeddingServerAvailableCondition.class);

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var env = context.getEnvironment();
        boolean enabled = Boolean.parseBoolean(env.getProperty("app.local-embedding.enabled", "false"));
        if (!enabled) {
            return false;
        }
        String baseUrl = env.getProperty("app.local-embedding.server-url", "http://127.0.0.1:8088");
        String healthUrl = baseUrl.endsWith("/") ? baseUrl + "v1/models" : baseUrl + "/v1/models";

        // Quick probe with tight timeouts so startup isn't delayed
        int connectTimeoutMs = 500;
        int readTimeoutMs = 500;
        try {
            URL url = new URL(healthUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            conn.connect();
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return true;
            }
            log.warn("[EMBEDDING] Local embeddings server not reachable (HTTP {}) at {}. Falling back to remote embeddings.", code, baseUrl);
            return false;
        } catch (Exception e) {
            log.warn("[EMBEDDING] Local embeddings server not reachable at {} ({}). Falling back to remote embeddings.", baseUrl, e.getClass().getSimpleName());
            return false;
        }
    }
}
