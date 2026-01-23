package com.williamcallahan.javachat.config;

import jakarta.annotation.PostConstruct;
import java.util.Locale;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds application configuration under the "app" prefix.
 */
@Component
@ConfigurationProperties(prefix = AppProperties.CONFIG_PREFIX)
public class AppProperties {

    public static final String CONFIG_PREFIX = "app";

    private static final String NULL_SECT_FMT = "Configuration section %s must not be null.";
    private static final String RAG_KEY = "app.rag";
    private static final String LOCAL_EMBED_KEY = "app.local-embedding";
    private static final String REMOTE_EMB_KEY = "app.remote-embedding";
    private static final String DOCS_KEY = "app.docs";
    private static final String DIAG_KEY = "app.diagnostics";
    private static final String QDRANT_KEY = "app.qdrant";
    private static final String CORS_KEY = "app.cors";

    private RetrievalAugmentationConfig rag = new RetrievalAugmentationConfig();
    private LocalEmbedding localEmbedding = new LocalEmbedding();
    private RemoteEmbedding remoteEmbedding = new RemoteEmbedding();
    private DocumentationConfig docs = new DocumentationConfig();
    private Diagnostics diagnostics = new Diagnostics();
    private Qdrant qdrant = new Qdrant();
    private CorsConfig cors = new CorsConfig();

    /**
     * Creates configuration sections with default values.
     */
    public AppProperties() {
    }

    @PostConstruct
    void validateConfiguration() {
        requireConfiguredSection(rag, RAG_KEY).validateConfiguration();
        requireConfiguredSection(localEmbedding, LOCAL_EMBED_KEY).validateConfiguration();
        requireConfiguredSection(remoteEmbedding, REMOTE_EMB_KEY).validateConfiguration();
        requireConfiguredSection(docs, DOCS_KEY).validateConfiguration();
        requireConfiguredSection(diagnostics, DIAG_KEY).validateConfiguration();
        requireConfiguredSection(qdrant, QDRANT_KEY);
        requireConfiguredSection(cors, CORS_KEY).validateConfiguration();
    }

    /**
     * Returns retrieval augmentation configuration.
     *
     * @return retrieval augmentation configuration
     */
    public RetrievalAugmentationConfig getRag() {
        return copyRagConfig(rag);
    }

    /**
     * Sets retrieval augmentation configuration.
     *
     * @param rag retrieval augmentation configuration
     */
    public void setRag(final RetrievalAugmentationConfig rag) {
        this.rag = requireConfiguredSection(rag, RAG_KEY);
    }

    /**
     * Returns local embedding configuration.
     *
     * @return local embedding configuration
     */
    public LocalEmbedding getLocalEmbedding() {
        return copyLocalEmbedding(localEmbedding);
    }

    /**
     * Sets local embedding configuration.
     *
     * @param localEmbedding local embedding configuration
     */
    public void setLocalEmbedding(final LocalEmbedding localEmbedding) {
        this.localEmbedding = requireConfiguredSection(localEmbedding, LOCAL_EMBED_KEY);
    }

    /**
     * Returns remote embedding configuration.
     *
     * @return remote embedding configuration
     */
    public RemoteEmbedding getRemoteEmbedding() {
        return copyRemoteEmbedding(remoteEmbedding);
    }

    /**
     * Sets remote embedding configuration.
     *
     * @param remoteEmbedding remote embedding configuration
     */
    public void setRemoteEmbedding(final RemoteEmbedding remoteEmbedding) {
        this.remoteEmbedding = requireConfiguredSection(remoteEmbedding, REMOTE_EMB_KEY);
    }

    /**
     * Returns documentation configuration.
     *
     * @return documentation configuration
     */
    public DocumentationConfig getDocs() {
        return copyDocumentationConfig(docs);
    }

    /**
     * Sets documentation configuration.
     *
     * @param docs documentation configuration
     */
    public void setDocs(final DocumentationConfig docs) {
        this.docs = requireConfiguredSection(docs, DOCS_KEY);
    }

    /**
     * Returns diagnostics configuration.
     *
     * @return diagnostics configuration
     */
    public Diagnostics getDiagnostics() {
        return copyDiagnostics(diagnostics);
    }

    /**
     * Sets diagnostics configuration.
     *
     * @param diagnostics diagnostics configuration
     */
    public void setDiagnostics(final Diagnostics diagnostics) {
        this.diagnostics = requireConfiguredSection(diagnostics, DIAG_KEY);
    }

    /**
     * Returns Qdrant configuration.
     *
     * @return Qdrant configuration
     */
    public Qdrant getQdrant() {
        return copyQdrant(qdrant);
    }

    /**
     * Sets Qdrant configuration.
     *
     * @param qdrant Qdrant configuration
     */
    public void setQdrant(final Qdrant qdrant) {
        this.qdrant = requireConfiguredSection(qdrant, QDRANT_KEY);
    }

    /**
     * Returns CORS configuration.
     *
     * @return CORS configuration
     */
    public CorsConfig getCors() {
        return copyCorsConfig(cors);
    }

    /**
     * Sets CORS configuration.
     *
     * @param cors CORS configuration
     */
    public void setCors(final CorsConfig cors) {
        this.cors = requireConfiguredSection(cors, CORS_KEY);
    }

    private static <T> T requireConfiguredSection(final T section, final String sectionKey) {
        if (section == null) {
            throw new IllegalArgumentException(String.format(Locale.ROOT, NULL_SECT_FMT, sectionKey));
        }
        return section;
    }

    private static RetrievalAugmentationConfig copyRagConfig(final RetrievalAugmentationConfig sourceConfig) {
        final RetrievalAugmentationConfig copy = new RetrievalAugmentationConfig();
        copy.setSearchTopK(sourceConfig.getSearchTopK());
        copy.setSearchReturnK(sourceConfig.getSearchReturnK());
        copy.setChunkMaxTokens(sourceConfig.getChunkMaxTokens());
        copy.setChunkOverlapTokens(sourceConfig.getChunkOverlapTokens());
        copy.setSearchCitations(sourceConfig.getSearchCitations());
        copy.setSearchMmrLambda(sourceConfig.getSearchMmrLambda());
        return copy;
    }

    private static LocalEmbedding copyLocalEmbedding(final LocalEmbedding sourceConfig) {
        final LocalEmbedding copy = new LocalEmbedding();
        copy.setEnabled(sourceConfig.isEnabled());
        copy.setServerUrl(sourceConfig.getServerUrl());
        copy.setModel(sourceConfig.getModel());
        copy.setDimensions(sourceConfig.getDimensions());
        copy.setUseHashWhenDisabled(sourceConfig.isUseHashWhenDisabled());
        return copy;
    }

    private static RemoteEmbedding copyRemoteEmbedding(final RemoteEmbedding sourceConfig) {
        final RemoteEmbedding copy = new RemoteEmbedding();
        copy.setServerUrl(sourceConfig.getServerUrl());
        copy.setModel(sourceConfig.getModel());
        copy.setApiKey(sourceConfig.getApiKey());
        copy.setDimensions(sourceConfig.getDimensions());
        return copy;
    }

    private static DocumentationConfig copyDocumentationConfig(final DocumentationConfig sourceConfig) {
        final DocumentationConfig copy = new DocumentationConfig();
        copy.setRootUrl(sourceConfig.getRootUrl());
        copy.setJdkVersion(sourceConfig.getJdkVersion());
        copy.setSnapshotDir(sourceConfig.getSnapshotDir());
        copy.setParsedDir(sourceConfig.getParsedDir());
        copy.setIndexDir(sourceConfig.getIndexDir());
        return copy;
    }

    private static Diagnostics copyDiagnostics(final Diagnostics sourceConfig) {
        final Diagnostics copy = new Diagnostics();
        copy.setStreamChunkLogging(sourceConfig.isStreamChunkLogging());
        copy.setStreamChunkSample(sourceConfig.getStreamChunkSample());
        return copy;
    }

    private static Qdrant copyQdrant(final Qdrant sourceConfig) {
        final Qdrant copy = new Qdrant();
        copy.setEnsurePayloadIndexes(sourceConfig.isEnsurePayloadIndexes());
        return copy;
    }

    private static CorsConfig copyCorsConfig(final CorsConfig sourceConfig) {
        final CorsConfig copy = new CorsConfig();
        copy.setAllowedOrigins(sourceConfig.getAllowedOrigins());
        copy.setAllowedMethods(sourceConfig.getAllowedMethods());
        copy.setAllowedHeaders(sourceConfig.getAllowedHeaders());
        copy.setAllowCredentials(sourceConfig.isAllowCredentials());
        copy.setMaxAgeSeconds(sourceConfig.getMaxAgeSeconds());
        return copy;
    }

    /**
     * Qdrant configuration.
     */
    public static class Qdrant {

        private boolean payloadIndexing = true;

        /**
         * Creates Qdrant configuration.
         */
        public Qdrant() {
        }

        /**
         * Returns whether payload indexes are ensured.
         *
         * @return whether payload indexes are ensured
         */
        public boolean isEnsurePayloadIndexes() {
            return payloadIndexing;
        }

        /**
         * Sets whether payload indexes are ensured.
         *
         * @param payloadIndexing whether payload indexes are ensured
         */
        public void setEnsurePayloadIndexes(final boolean payloadIndexing) {
            this.payloadIndexing = payloadIndexing;
        }
    }
}
