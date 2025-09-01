package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.config.RagProperties;
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
    private final RagProperties props;
    private final RerankerService rerankerService;
    private final LocalSearchService localSearch;

    public RetrievalService(VectorStore vectorStore, RagProperties props, RerankerService rerankerService, LocalSearchService localSearch) {
        this.vectorStore = vectorStore;
        this.props = props;
        this.rerankerService = rerankerService;
        this.localSearch = localSearch;
    }

    public List<Document> retrieve(String query) {
        // Initial vector search
        List<Document> docs;
        try {
            int topK = Math.max(1, props.getSearchTopK());
            docs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(topK)
                    .build());
        } catch (Exception e) {
            log.warn("Vector search unavailable; falling back to local keyword search ({}).", e.getMessage());
            var results = localSearch.search(query, props.getSearchReturnK());
            return results.stream().map(r -> {
                org.springframework.ai.document.Document d = new org.springframework.ai.document.Document(r.text);
                d.getMetadata().put("url", r.url);
                d.getMetadata().put("title", "Local Doc");
                return d;
            }).collect(Collectors.toList());
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

        List<Document> reranked = rerankerService.rerank(query, uniqueByUrl, props.getSearchReturnK());
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


