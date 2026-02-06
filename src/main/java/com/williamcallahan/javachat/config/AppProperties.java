package com.williamcallahan.javachat.config;

import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    private static final String REMOTE_EMB_KEY = "app.remote-embedding";
    private static final String DOCS_KEY = "app.docs";
    private static final String DIAG_KEY = "app.diagnostics";
    private static final String QDRANT_KEY = "app.qdrant";
    private static final String CORS_KEY = "app.cors";
    private static final String PUBLIC_BASE_URL_KEY = "app.public-base-url";
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
    private RemoteEmbedding remoteEmbedding = new RemoteEmbedding();
    private DocumentationConfig docs = new DocumentationConfig();
    private Diagnostics diagnostics = new Diagnostics();
    private Qdrant qdrant = new Qdrant();
    private CorsConfig cors = new CorsConfig();
    private Embeddings embeddings = new Embeddings();
    private Llm llm = new Llm();
    private String publicBaseUrl = DEFAULT_PUBLIC_BASE_URL;

    /**
     * Creates configuration sections with default values.
     */
    public AppProperties() {}

    @PostConstruct
    void validateConfiguration() {
        requireConfiguredSection(rag, RAG_KEY).validateConfiguration();
        requireConfiguredSection(localEmbedding, LOCAL_EMBED_KEY).validateConfiguration();
        requireConfiguredSection(remoteEmbedding, REMOTE_EMB_KEY).validateConfiguration();
        requireConfiguredSection(docs, DOCS_KEY).validateConfiguration();
        requireConfiguredSection(diagnostics, DIAG_KEY).validateConfiguration();
        requireConfiguredSection(qdrant, QDRANT_KEY).validateConfiguration();
        requireConfiguredSection(cors, CORS_KEY).validateConfiguration();
        requireConfiguredSection(embeddings, EMBEDDINGS_KEY).validateConfiguration();
        requireConfiguredSection(llm, LLM_KEY).validateConfiguration();
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

    public RemoteEmbedding getRemoteEmbedding() {
        return remoteEmbedding;
    }

    public void setRemoteEmbedding(RemoteEmbedding remoteEmbedding) {
        this.remoteEmbedding = requireConfiguredSection(remoteEmbedding, REMOTE_EMB_KEY);
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

    public Qdrant getQdrant() {
        return qdrant;
    }

    public void setQdrant(Qdrant qdrant) {
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

    /** Qdrant vector store settings. */
    public static class Qdrant {
        private boolean ensurePayloadIndexes = true;
        private QdrantCollections collections = new QdrantCollections();
        private String denseVectorName = "dense";
        private String sparseVectorName = "bm25";
        private boolean ensureCollections = true;
        private int prefetchLimit = 20;
        private Duration queryTimeout = Duration.ofSeconds(5);

        public boolean isEnsurePayloadIndexes() {
            return ensurePayloadIndexes;
        }

        public void setEnsurePayloadIndexes(boolean ensurePayloadIndexes) {
            this.ensurePayloadIndexes = ensurePayloadIndexes;
        }

        /**
         * Returns the configured collection names used for routing ingested content.
         *
         * <p>Collections are used to split content by source type (books, docs, articles, PDFs)
         * while still allowing cross-collection retrieval.
         */
        public QdrantCollections getCollections() {
            return collections.copy();
        }

        public void setCollections(QdrantCollections collections) {
            this.collections = requireConfiguredSection(collections, QDRANT_KEY + ".collections")
                    .copy();
        }

        /**
         * Returns the configured dense vector name used for embeddings.
         *
         * <p>This name must match the collection schema's {@code vectors} key when using
         * named-vector mode (required for storing dense + sparse vectors in a single point).</p>
         */
        public String getDenseVectorName() {
            return denseVectorName;
        }

        public void setDenseVectorName(String denseVectorName) {
            this.denseVectorName = denseVectorName;
        }

        /**
         * Returns the configured sparse vector name used for lexical (hybrid) retrieval.
         *
         * <p>This name must match the collection schema's {@code sparse_vectors} key.
         */
        public String getSparseVectorName() {
            return sparseVectorName;
        }

        public void setSparseVectorName(String sparseVectorName) {
            this.sparseVectorName = sparseVectorName;
        }

        /**
         * Returns the timeout budget for hybrid query fan-out.
         */
        public Duration getQueryTimeout() {
            return queryTimeout;
        }

        public void setQueryTimeout(Duration queryTimeout) {
            this.queryTimeout = queryTimeout;
        }

        /**
         * Returns whether the application should ensure hybrid-capable collections exist at startup.
         */
        public boolean isEnsureCollections() {
            return ensureCollections;
        }

        public void setEnsureCollections(boolean ensureCollections) {
            this.ensureCollections = ensureCollections;
        }

        /**
         * Returns the per-collection prefetch limit for hybrid queries.
         *
         * <p>Controls how many candidates each dense/sparse prefetch stage retrieves
         * before RRF fusion selects the final results.</p>
         */
        public int getPrefetchLimit() {
            return prefetchLimit;
        }

        public void setPrefetchLimit(int prefetchLimit) {
            this.prefetchLimit = prefetchLimit;
        }

        Qdrant validateConfiguration() {
            if (collections == null) {
                throw new IllegalArgumentException("app.qdrant.collections must not be null");
            }
            collections.validateConfiguration();

            if (denseVectorName == null || denseVectorName.isBlank()) {
                throw new IllegalArgumentException("app.qdrant.dense-vector-name must not be blank");
            }
            if (sparseVectorName == null || sparseVectorName.isBlank()) {
                throw new IllegalArgumentException("app.qdrant.sparse-vector-name must not be blank");
            }
            if (prefetchLimit <= 0) {
                throw new IllegalArgumentException("app.qdrant.prefetch-limit must be positive, got: " + prefetchLimit);
            }
            if (queryTimeout == null || queryTimeout.isNegative() || queryTimeout.isZero()) {
                throw new IllegalArgumentException("app.qdrant.query-timeout must be positive");
            }
            return this;
        }
    }

    /**
     * Qdrant collection names used by the ingestion and retrieval pipelines.
     */
    public static class QdrantCollections {
        private String books = "java-chat-books";
        private String docs = "java-docs";
        private String articles = "java-articles";
        private String pdfs = "java-pdfs";

        /**
         * Creates collection-name defaults used when no explicit configuration is provided.
         */
        public QdrantCollections() {}

        private QdrantCollections(String books, String docs, String articles, String pdfs) {
            this.books = books;
            this.docs = docs;
            this.articles = articles;
            this.pdfs = pdfs;
        }

        public String getBooks() {
            return books;
        }

        public void setBooks(String books) {
            this.books = books;
        }

        public String getDocs() {
            return docs;
        }

        public void setDocs(String docs) {
            this.docs = docs;
        }

        public String getArticles() {
            return articles;
        }

        public void setArticles(String articles) {
            this.articles = articles;
        }

        public String getPdfs() {
            return pdfs;
        }

        public void setPdfs(String pdfs) {
            this.pdfs = pdfs;
        }

        /**
         * Returns all configured collection names in deterministic order.
         */
        public List<String> all() {
            return List.of(books, docs, articles, pdfs);
        }

        QdrantCollections copy() {
            return new QdrantCollections(books, docs, articles, pdfs);
        }

        QdrantCollections validateConfiguration() {
            List<String> all = all();
            Set<String> unique = new LinkedHashSet<>();
            for (String name : all) {
                if (name == null || name.isBlank()) {
                    throw new IllegalArgumentException("app.qdrant.collections.* must not be blank");
                }
                unique.add(name);
            }
            if (unique.size() != all.size()) {
                throw new IllegalArgumentException("app.qdrant.collections.* must be distinct");
            }
            return this;
        }
    }

    /** Embedding vector configuration. */
    public static class Embeddings {
        private int dimensions = 1536;

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }

        Embeddings validateConfiguration() {
            if (dimensions <= 0) {
                throw new IllegalArgumentException("app.embeddings.dimensions must be positive, got: " + dimensions);
            }
            return this;
        }
    }

    /** LLM generation parameters. */
    public static class Llm {
        private static final double MIN_TEMPERATURE = 0.0;
        private static final double MAX_TEMPERATURE = 2.0;

        private double temperature = 0.7;

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
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
            return this;
        }
    }
}
