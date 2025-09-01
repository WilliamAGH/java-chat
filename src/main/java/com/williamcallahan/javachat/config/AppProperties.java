package com.williamcallahan.javachat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {
    
    private Rag rag = new Rag();
    private LocalEmbedding localEmbedding = new LocalEmbedding();
    private Docs docs = new Docs();
    
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
}