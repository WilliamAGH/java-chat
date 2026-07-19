package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds application configuration under the "app" prefix.
 */
@Component
@ConfigurationProperties(prefix = AppProperties.CONFIG_PREFIX)
public class AppProperties {

    private static final Logger log = LoggerFactory.getLogger(AppProperties.class);

    public static final String CONFIG_PREFIX = "app";

    private static final String NULL_SECT_FMT = "Configuration section %s must not be null.";
    private static final String RAG_KEY = "app.rag";
    private static final String LOCAL_EMBED_KEY = "app.local-embedding";
    private static final String DOCS_KEY = "app.docs";
    private static final String DIAG_KEY = "app.diagnostics";
    private static final String QDRANT_KEY = "app.qdrant";
    private static final String CORS_KEY = "app.cors";
    private static final String PUBLIC_BASE_URL_KEY = "app.public-base-url";
    private static final String CLICKY_KEY = "app.clicky";
    private static final String EMBEDDINGS_KEY = "app.embeddings";
    private static final String LLM_KEY = "app.llm";

    /**
     * Default base URL for SEO endpoints when no explicit public base URL is configured.
     *
     * <p>Production deployments should override this via configuration so that robots.txt and sitemap.xml
     * emit absolute URLs for the real public host.
     */
    private static final String DEFAULT_PUBLIC_BASE_URL = "http://localhost:8085";

    private RetrievalAugmentationConfig rag = new RetrievalAugmentationConfig();
    private LocalEmbedding localEmbedding = new LocalEmbedding();
    private DocumentationConfig docs = new DocumentationConfig();
    private Diagnostics diagnostics = new Diagnostics();
    private QdrantProperties qdrant = new QdrantProperties();
    private CorsConfig cors = new CorsConfig();
    private Embeddings embeddings = new Embeddings();
    private Llm llm = new Llm();
    private Clicky clicky = new Clicky();
    private String publicBaseUrl = DEFAULT_PUBLIC_BASE_URL;

    /**
     * Creates configuration sections with default values.
     *
     * <p>Empty constructor is required by Spring's @ConfigurationProperties binding mechanism.
     * All configuration values are injected via setter methods after construction.</p>
     */
    public AppProperties() {
        // Empty constructor required for Spring @ConfigurationProperties binding
    }

    @PostConstruct
    void validateConfiguration() {
        requireConfiguredSection(rag, RAG_KEY).validateConfiguration();
        requireConfiguredSection(localEmbedding, LOCAL_EMBED_KEY).validateConfiguration();
        requireConfiguredSection(docs, DOCS_KEY).validateConfiguration();
        requireConfiguredSection(diagnostics, DIAG_KEY).validateConfiguration();
        requireConfiguredSection(qdrant, QDRANT_KEY).validateConfiguration();
        requireConfiguredSection(cors, CORS_KEY).validateConfiguration();
        requireConfiguredSection(embeddings, EMBEDDINGS_KEY).validateConfiguration();
        requireConfiguredSection(llm, LLM_KEY).validateConfiguration();
        requireConfiguredSection(clicky, CLICKY_KEY).validateConfiguration();
        this.publicBaseUrl = validatePublicBaseUrl(publicBaseUrl);
    }

    /**
     * Returns the configured public base URL used for absolute URLs in SEO endpoints.
     *
     * @return base URL like {@code https://example.com}
     */
    public String getPublicBaseUrl() {
        return publicBaseUrl;
    }

    /**
     * Sets the configured public base URL used for absolute URLs in SEO endpoints.
     *
     * @param publicBaseUrl base URL like {@code https://example.com}
     */
    public void setPublicBaseUrl(final String publicBaseUrl) {
        this.publicBaseUrl = publicBaseUrl;
    }

    public Clicky getClicky() {
        return clicky;
    }

    public void setClicky(Clicky clicky) {
        this.clicky = requireConfiguredSection(clicky, CLICKY_KEY);
    }

    private static String validatePublicBaseUrl(final String configuredPublicBaseUrl) {
        if (configuredPublicBaseUrl == null || configuredPublicBaseUrl.isBlank()) {
            log.warn("{} is not configured; defaulting to {}", PUBLIC_BASE_URL_KEY, DEFAULT_PUBLIC_BASE_URL);
            return DEFAULT_PUBLIC_BASE_URL;
        }

        final String trimmedBaseUrl = configuredPublicBaseUrl.trim();
        final URI parsed;
        try {
            parsed = new URI(trimmedBaseUrl);
        } catch (URISyntaxException syntaxError) {
            String sanitizedMessage = sanitizeLogMessage(syntaxError.getMessage());
            log.warn(
                    "{} is invalid ({}); defaulting to {}",
                    PUBLIC_BASE_URL_KEY,
                    sanitizedMessage,
                    DEFAULT_PUBLIC_BASE_URL);
            return DEFAULT_PUBLIC_BASE_URL;
        }

        final String scheme = parsed.getScheme();
        if (scheme == null || scheme.isBlank()) {
            log.warn("{} is missing a scheme; defaulting to {}", PUBLIC_BASE_URL_KEY, DEFAULT_PUBLIC_BASE_URL);
            return DEFAULT_PUBLIC_BASE_URL;
        }

        final String normalizedScheme = AsciiTextNormalizer.toLowerAscii(scheme);
        if (!"http".equals(normalizedScheme) && !"https".equals(normalizedScheme)) {
            log.warn("{} must use http/https scheme; defaulting to {}", PUBLIC_BASE_URL_KEY, DEFAULT_PUBLIC_BASE_URL);
            return DEFAULT_PUBLIC_BASE_URL;
        }

        final String host = parsed.getHost();
        if (host == null || host.isBlank()) {
            log.warn("{} is missing a host; defaulting to {}", PUBLIC_BASE_URL_KEY, DEFAULT_PUBLIC_BASE_URL);
            return DEFAULT_PUBLIC_BASE_URL;
        }

        final int port = parsed.getPort();
        try {
            // Strip any path/query/fragment; keep scheme/host/port only.
            return new URI(normalizedScheme, null, host, port, null, null, null).toString();
        } catch (URISyntaxException syntaxError) {
            String sanitizedMessage = sanitizeLogMessage(syntaxError.getMessage());
            log.warn(
                    "{} could not be normalized ({}); defaulting to {}",
                    PUBLIC_BASE_URL_KEY,
                    sanitizedMessage,
                    DEFAULT_PUBLIC_BASE_URL);
            return DEFAULT_PUBLIC_BASE_URL;
        }
    }

    private static String sanitizeLogMessage(final String message) {
        if (message == null || message.isBlank()) {
            return "";
        }
        return message.replace("\r", "").replace("\n", "");
    }

    public RetrievalAugmentationConfig getRag() {
        return rag;
    }

    public void setRag(RetrievalAugmentationConfig rag) {
        this.rag = requireConfiguredSection(rag, RAG_KEY);
    }

    public LocalEmbedding getLocalEmbedding() {
        return localEmbedding;
    }

    public void setLocalEmbedding(LocalEmbedding localEmbedding) {
        this.localEmbedding = requireConfiguredSection(localEmbedding, LOCAL_EMBED_KEY);
    }

    public DocumentationConfig getDocs() {
        return docs;
    }

    public void setDocs(DocumentationConfig docs) {
        this.docs = requireConfiguredSection(docs, DOCS_KEY);
    }

    public Diagnostics getDiagnostics() {
        return diagnostics;
    }

    public void setDiagnostics(Diagnostics diagnostics) {
        this.diagnostics = requireConfiguredSection(diagnostics, DIAG_KEY);
    }

    /**
     * Returns Qdrant vector-store configuration.
     *
     * @return Qdrant settings and collection routing names
     */
    public QdrantProperties getQdrant() {
        return qdrant;
    }

    /**
     * Replaces Qdrant vector-store configuration during property binding.
     *
     * @param qdrant Qdrant settings and collection routing names
     * @throws IllegalArgumentException when qdrant is null
     */
    public void setQdrant(QdrantProperties qdrant) {
        this.qdrant = requireConfiguredSection(qdrant, QDRANT_KEY);
    }

    public CorsConfig getCors() {
        return cors;
    }

    public void setCors(CorsConfig cors) {
        this.cors = requireConfiguredSection(cors, CORS_KEY);
    }

    public Embeddings getEmbeddings() {
        return embeddings;
    }

    public void setEmbeddings(Embeddings embeddings) {
        this.embeddings = requireConfiguredSection(embeddings, EMBEDDINGS_KEY);
    }

    public Llm getLlm() {
        return llm;
    }

    public void setLlm(Llm llm) {
        this.llm = requireConfiguredSection(llm, LLM_KEY);
    }

    private static <T> T requireConfiguredSection(T section, String sectionKey) {
        if (section == null) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, NULL_SECT_FMT, sectionKey));
        }
        return section;
    }

    /** Clicky analytics configuration. */
    public static class Clicky {
        private boolean enabled = false;
        private String siteId = "";
        private long parsedSiteId = -1L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getSiteId() {
            return siteId;
        }

        public void setSiteId(String siteId) {
            this.siteId = siteId;
        }

        public long getParsedSiteId() {
            return parsedSiteId;
        }

        Clicky validateConfiguration() {
            if (!enabled) {
                parsedSiteId = -1L;
                return this;
            }

            if (siteId == null || siteId.isBlank()) {
                throw new IllegalArgumentException("app.clicky.site-id must not be blank when app.clicky.enabled=true");
            }

            String trimmedSiteId = siteId.trim();
            boolean allDigits = trimmedSiteId.chars().allMatch(Character::isDigit);
            if (!allDigits) {
                throw new IllegalArgumentException(
                        "app.clicky.site-id must contain digits only, got: " + trimmedSiteId);
            }

            parsedSiteId = Long.parseLong(trimmedSiteId);
            return this;
        }
    }

    /** Embedding vector configuration. */
    public static class Embeddings {
        private static final String MODEL_KEY = "app.embeddings.model";

        private int dimensions = 2_560;
        private String model = "qwen/qwen3-embedding-4b";

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }

        /** Returns the embedding model owned independently from the chat model. */
        public String getModel() {
            return model;
        }

        /** Sets the embedding model owned independently from the chat model. */
        public void setModel(String model) {
            this.model = model;
        }

        Embeddings validateConfiguration() {
            if (dimensions <= 0) {
                throw new IllegalArgumentException("app.embeddings.dimensions must be positive, got: " + dimensions);
            }
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException(MODEL_KEY + " must not be blank");
            }
            return this;
        }
    }

    /** LLM generation parameters. */
    public static class Llm {
        private static final double MIN_TEMPERATURE = 0.0;
        private static final double MAX_TEMPERATURE = 2.0;
        private double temperature;
        private double rerankerTemperature;
        private String reasoningEffort;
        private int completionOutputTokenBudget;
        private int enrichmentOutputTokenBudget;
        private int rerankerOutputTokenBudget;
        private long configuredProviderBackoffSeconds;

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        /** Returns the deterministic generation temperature used only for reranking. */
        public double getRerankerTemperature() {
            return rerankerTemperature;
        }

        /** Sets the deterministic generation temperature used only for reranking. */
        public void setRerankerTemperature(double rerankerTemperature) {
            this.rerankerTemperature = rerankerTemperature;
        }

        public String getReasoningEffort() {
            return reasoningEffort;
        }

        public void setReasoningEffort(String reasoningEffort) {
            this.reasoningEffort = reasoningEffort;
        }

        public int getCompletionOutputTokenBudget() {
            return completionOutputTokenBudget;
        }

        public void setCompletionOutputTokenBudget(int completionOutputTokenBudget) {
            this.completionOutputTokenBudget = completionOutputTokenBudget;
        }

        public int getEnrichmentOutputTokenBudget() {
            return enrichmentOutputTokenBudget;
        }

        public void setEnrichmentOutputTokenBudget(int enrichmentOutputTokenBudget) {
            this.enrichmentOutputTokenBudget = enrichmentOutputTokenBudget;
        }

        /** Returns the output-token budget used only for reranker JSON responses. */
        public int getRerankerOutputTokenBudget() {
            return rerankerOutputTokenBudget;
        }

        /** Sets the output-token budget used only for reranker JSON responses. */
        public void setRerankerOutputTokenBudget(int rerankerOutputTokenBudget) {
            this.rerankerOutputTokenBudget = rerankerOutputTokenBudget;
        }

        public long getConfiguredProviderBackoffSeconds() {
            return configuredProviderBackoffSeconds;
        }

        public void setConfiguredProviderBackoffSeconds(long configuredProviderBackoffSeconds) {
            this.configuredProviderBackoffSeconds = configuredProviderBackoffSeconds;
        }

        /**
         * Returns the validated configured-provider backoff duration.
         *
         * <p>The configured-provider value type owns the operational bound so downstream failure
         * handling cannot discover an unusable duration only after a provider outage begins.</p>
         *
         * @return validated configured-provider backoff policy
         * @throws IllegalArgumentException when the configured duration is outside the supported range
         */
        public ConfiguredProviderBackoff configuredProviderBackoff() {
            return ConfiguredProviderBackoff.fromSeconds(configuredProviderBackoffSeconds);
        }

        Llm validateConfiguration() {
            if (temperature < MIN_TEMPERATURE || temperature > MAX_TEMPERATURE) {
                throw new IllegalArgumentException(String.format(
                        Locale.ROOT,
                        "app.llm.temperature must be in range [%.1f, %.1f], got: %.2f",
                        MIN_TEMPERATURE,
                        MAX_TEMPERATURE,
                        temperature));
            }
            if (rerankerTemperature < MIN_TEMPERATURE || rerankerTemperature > MAX_TEMPERATURE) {
                throw new IllegalArgumentException(String.format(
                        Locale.ROOT,
                        "app.llm.reranker-temperature must be in range [%.1f, %.1f], got: %.2f",
                        MIN_TEMPERATURE,
                        MAX_TEMPERATURE,
                        rerankerTemperature));
            }
            if (completionOutputTokenBudget <= 0) {
                throw new IllegalArgumentException(
                        "app.llm.completion-output-token-budget must be positive, got: " + completionOutputTokenBudget);
            }
            if (enrichmentOutputTokenBudget <= 0) {
                throw new IllegalArgumentException(
                        "app.llm.enrichment-output-token-budget must be positive, got: " + enrichmentOutputTokenBudget);
            }
            if (rerankerOutputTokenBudget <= 0) {
                throw new IllegalArgumentException(
                        "app.llm.reranker-output-token-budget must be positive, got: " + rerankerOutputTokenBudget);
            }
            configuredProviderBackoff();
            return this;
        }
    }
}
