package com.williamcallahan.javachat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rag")
public class RagProperties {
    private int chunkMaxTokens;
    private int chunkOverlapTokens;
    private int searchTopK;
    private int searchReturnK;
    private int searchCitations;
    private double searchMmrLambda;

    public int getChunkMaxTokens() { return chunkMaxTokens; }
    public void setChunkMaxTokens(int chunkMaxTokens) { this.chunkMaxTokens = chunkMaxTokens; }
    public int getChunkOverlapTokens() { return chunkOverlapTokens; }
    public void setChunkOverlapTokens(int chunkOverlapTokens) { this.chunkOverlapTokens = chunkOverlapTokens; }
    public int getSearchTopK() { return searchTopK; }
    public void setSearchTopK(int searchTopK) { this.searchTopK = searchTopK; }
    public int getSearchReturnK() { return searchReturnK; }
    public void setSearchReturnK(int searchReturnK) { this.searchReturnK = searchReturnK; }
    public int getSearchCitations() { return searchCitations; }
    public void setSearchCitations(int searchCitations) { this.searchCitations = searchCitations; }
    public double getSearchMmrLambda() { return searchMmrLambda; }
    public void setSearchMmrLambda(double searchMmrLambda) { this.searchMmrLambda = searchMmrLambda; }
}


