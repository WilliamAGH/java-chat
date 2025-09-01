package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.model.Citation;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
// TODO: Add DJL-based BGE reranker or LLM rerank; embedding-based MMR removed for now
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RetrievalService {
    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);
    private final VectorStore vectorStore;
    private final AppProperties props;
    private final RerankerService rerankerService;
    private final LocalSearchService localSearch;
    private final DocumentFactory documentFactory;

    public RetrievalService(VectorStore vectorStore, AppProperties props, RerankerService rerankerService, LocalSearchService localSearch, DocumentFactory documentFactory) {
        this.vectorStore = vectorStore;
        this.props = props;
        this.rerankerService = rerankerService;
        this.localSearch = localSearch;
        this.documentFactory = documentFactory;
    }

    public List<Document> retrieve(String query) {
        // Initial vector search
        List<Document> docs;
        try {
            int topK = Math.max(1, props.getRag().getSearchTopK());
            log.info("=== RETRIEVAL DEBUG ===");
            log.info("Query: '{}'", query);
            log.info("TopK requested: {}", topK);
            log.info("VectorStore class: {}", vectorStore.getClass().getName());
            
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .build();
            
            log.info("SearchRequest created - Query: '{}', TopK: {}", 
                searchRequest.getQuery(), searchRequest.getTopK());
            
            docs = vectorStore.similaritySearch(searchRequest);
            
            log.info("VectorStore returned {} documents", docs.size());
            if (!docs.isEmpty()) {
                log.info("First doc metadata: {}", docs.get(0).getMetadata());
                log.info("First doc content preview: {}", 
                    docs.get(0).getText().substring(0, Math.min(200, docs.get(0).getText().length())));
            }
        } catch (Exception e) {
            log.warn("Vector search unavailable; falling back to local keyword search", e);
            var results = localSearch.search(query, props.getRag().getSearchReturnK());
            return results.stream()
                .map(r -> documentFactory.createLocalDocument(r.text, r.url))
                .collect(Collectors.toList());
        }

        // MMR re-ranking using embeddings
        List<Document> uniqueByUrl = docs.stream()
                .collect(Collectors.toMap(
                        d -> String.valueOf(d.getMetadata().get("url")),
                        d -> d,
                        (first, dup) -> first
                ))
                .values()
                .stream()
                .collect(Collectors.toList());

        List<Document> reranked = rerankerService.rerank(query, uniqueByUrl, props.getRag().getSearchReturnK());
        return reranked;
    }

    public List<Citation> toCitations(List<Document> docs) {
        List<Citation> citations = new ArrayList<>();
        for (Document d : docs) {
            String url = String.valueOf(d.getMetadata().getOrDefault("url", ""));
            String title = String.valueOf(d.getMetadata().getOrDefault("title", ""));
            String snippet = d.getText();
            citations.add(new Citation(url, title, "", snippet.length() > 500 ? snippet.substring(0, 500) + "…" : snippet));
        }
        return citations;
    }

    // TODO: Implement MMR and reranker integration
}


