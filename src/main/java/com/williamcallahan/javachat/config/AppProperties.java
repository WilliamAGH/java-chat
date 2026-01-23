package com.williamcallahan.javachat.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Rag rag = new Rag();
    private LocalEmbedding localEmbedding = new LocalEmbedding();
    private RemoteEmbedding remoteEmbedding = new RemoteEmbedding();
    private Docs docs = new Docs();
    private Diagnostics diagnostics = new Diagnostics();
    private Qdrant qdrant = new Qdrant();
    private Cors cors = new Cors();
    
    public Rag getRag() {
        return rag;
    }
    
    public void setRag(Rag rag) {
        this.rag = rag;
    }

    public LocalEmbedding getLocalEmbedding() {
        return localEmbedding;
    }

    public void setLocalEmbedding(LocalEmbedding localEmbedding) {
        this.localEmbedding = localEmbedding;
    }

    public Docs getDocs() {
        return docs;
    }

    public void setDocs(Docs docs) {
        this.docs = docs;
    }
    
    public Diagnostics getDiagnostics() { return diagnostics; }
    public void setDiagnostics(Diagnostics diagnostics) { this.diagnostics = diagnostics; }
    public Qdrant getQdrant() { return qdrant; }
    public void setQdrant(Qdrant qdrant) { this.qdrant = qdrant; }
    public RemoteEmbedding getRemoteEmbedding() { return remoteEmbedding; }
    public void setRemoteEmbedding(RemoteEmbedding remoteEmbedding) { this.remoteEmbedding = remoteEmbedding; }
    public Cors getCors() { return cors; }
    public void setCors(Cors cors) { this.cors = cors; }
    
    public static class Rag {
        private int searchTopK = 10;
        private int searchReturnK = 5;
        private int chunkMaxTokens = 900;
        private int chunkOverlapTokens = 150;
        private int searchCitations = 3;
        private double searchMmrLambda = 0.5;
        
        public int getSearchTopK() {
            return searchTopK;
        }
        
        public void setSearchTopK(int searchTopK) {
            this.searchTopK = searchTopK;
        }
        
        public int getSearchReturnK() {
            return searchReturnK;
        }
        
        public void setSearchReturnK(int searchReturnK) {
            this.searchReturnK = searchReturnK;
        }

        public int getChunkMaxTokens() {
            return chunkMaxTokens;
        }

        public void setChunkMaxTokens(int chunkMaxTokens) {
            this.chunkMaxTokens = chunkMaxTokens;
        }

        public int getChunkOverlapTokens() {
            return chunkOverlapTokens;
        }

        public void setChunkOverlapTokens(int chunkOverlapTokens) {
            this.chunkOverlapTokens = chunkOverlapTokens;
        }

        public int getSearchCitations() {
            return searchCitations;
        }

        public void setSearchCitations(int searchCitations) {
            this.searchCitations = searchCitations;
        }

        public double getSearchMmrLambda() {
            return searchMmrLambda;
        }

        public void setSearchMmrLambda(double searchMmrLambda) {
            this.searchMmrLambda = searchMmrLambda;
        }
    }

    public static class LocalEmbedding {
        private boolean enabled = false;
        private String serverUrl = "http://127.0.0.1:1234";
        private String model = "text-embedding-qwen3-embedding-8b";
        private int dimensions = 4096;
        private boolean useHashWhenDisabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getServerUrl() { return serverUrl; }
        public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }

        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }

        public int getDimensions() { return dimensions; }
        public void setDimensions(int dimensions) { this.dimensions = dimensions; }

        public boolean isUseHashWhenDisabled() { return useHashWhenDisabled; }
        public void setUseHashWhenDisabled(boolean useHashWhenDisabled) { this.useHashWhenDisabled = useHashWhenDisabled; }
    }

    public static class RemoteEmbedding {
        private String serverUrl = ""; // e.g., https://api.novita.ai/openai
        private String model = "text-embedding-3-small";
        private String apiKey = "";
        private int dimensions = 4096;

        public String getServerUrl() { return serverUrl; }
        public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public int getDimensions() { return dimensions; }
        public void setDimensions(int dimensions) { this.dimensions = dimensions; }
    }

    public static class Docs {
        private String rootUrl = "https://docs.oracle.com/en/java/javase/24/";
        private int jdkVersion = 24;
        private String snapshotDir = "data/snapshots";
        private String parsedDir = "data/parsed";
        private String indexDir = "data/index";

        public String getRootUrl() { return rootUrl; }
        public void setRootUrl(String rootUrl) { this.rootUrl = rootUrl; }

        public int getJdkVersion() { return jdkVersion; }
        public void setJdkVersion(int jdkVersion) { this.jdkVersion = jdkVersion; }

        public String getSnapshotDir() { return snapshotDir; }
        public void setSnapshotDir(String snapshotDir) { this.snapshotDir = snapshotDir; }

        public String getParsedDir() { return parsedDir; }
        public void setParsedDir(String parsedDir) { this.parsedDir = parsedDir; }

        public String getIndexDir() { return indexDir; }
        public void setIndexDir(String indexDir) { this.indexDir = indexDir; }
    }
    
    public static class Diagnostics {
        // Whether to log each raw streaming chunk (DEBUG). Default false to avoid flooding logs.
        private boolean streamChunkLogging = false;
        // Sample every Nth chunk when logging is enabled. 0 => log every chunk.
        private int streamChunkSample = 0;
        
        public boolean isStreamChunkLogging() { return streamChunkLogging; }
        public void setStreamChunkLogging(boolean streamChunkLogging) { this.streamChunkLogging = streamChunkLogging; }
        public int getStreamChunkSample() { return streamChunkSample; }
        public void setStreamChunkSample(int streamChunkSample) { this.streamChunkSample = streamChunkSample; }
    }

    public static class Qdrant {
        // Mirror app.qdrant.ensure-payload-indexes
        private boolean ensurePayloadIndexes = true;

        public boolean isEnsurePayloadIndexes() { return ensurePayloadIndexes; }
        public void setEnsurePayloadIndexes(boolean ensurePayloadIndexes) { this.ensurePayloadIndexes = ensurePayloadIndexes; }
    }

    /**
     * CORS configuration for cross-origin requests from frontend dev servers.
     */
    public static class Cors {
        private List<String> allowedOrigins = List.of("http://localhost:8085", "http://127.0.0.1:8085");
        private List<String> allowedMethods = List.of("GET", "POST", "PUT", "DELETE", "OPTIONS");
        private List<String> allowedHeaders = List.of("*");
        private boolean allowCredentials = true;
        private long maxAgeSeconds = 3600;

        public List<String> getAllowedOrigins() { return allowedOrigins; }
        public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }
        public List<String> getAllowedMethods() { return allowedMethods; }
        public void setAllowedMethods(List<String> allowedMethods) { this.allowedMethods = allowedMethods; }
        public List<String> getAllowedHeaders() { return allowedHeaders; }
        public void setAllowedHeaders(List<String> allowedHeaders) { this.allowedHeaders = allowedHeaders; }
        public boolean isAllowCredentials() { return allowCredentials; }
        public void setAllowCredentials(boolean allowCredentials) { this.allowCredentials = allowCredentials; }
        public long getMaxAgeSeconds() { return maxAgeSeconds; }
        public void setMaxAgeSeconds(long maxAgeSeconds) { this.maxAgeSeconds = maxAgeSeconds; }
    }
}
