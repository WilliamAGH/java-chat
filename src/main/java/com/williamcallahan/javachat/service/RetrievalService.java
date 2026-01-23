package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.model.Citation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
// TODO: Add DJL-based BGE reranker or LLM rerank; embedding-based MMR removed for now
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

@Service
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(
        RetrievalService.class
    );
    private final VectorStore vectorStore;
    private final AppProperties props;
    private final RerankerService rerankerService;
    private final LocalSearchService localSearch;
    private final DocumentFactory documentFactory;

    public RetrievalService(
        VectorStore vectorStore,
        AppProperties props,
        RerankerService rerankerService,
        LocalSearchService localSearch,
        DocumentFactory documentFactory
    ) {
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

            log.info(
                "SearchRequest created - Query: '{}', TopK: {}",
                searchRequest.getQuery(),
                searchRequest.getTopK()
            );

            docs = vectorStore.similaritySearch(searchRequest);

            log.info("VectorStore returned {} documents", docs.size());
            if (!docs.isEmpty()) {
                log.info("First doc metadata: {}", docs.get(0).getMetadata());
                log.info(
                    "First doc content preview: {}",
                    docs
                        .get(0)
                        .getText()
                        .substring(
                            0,
                            Math.min(200, docs.get(0).getText().length())
                        )
                );
            }
        } catch (Exception e) {
            return handleVectorSearchFailure(e, query);
        }

        // MMR re-ranking using embeddings
        List<Document> uniqueByUrl = docs
            .stream()
            .collect(
                Collectors.toMap(
                    d -> String.valueOf(d.getMetadata().get("url")),
                    d -> d,
                    (first, dup) -> first
                )
            )
            .values()
            .stream()
            .collect(Collectors.toList());

        List<Document> reranked = rerankerService.rerank(
            query,
            uniqueByUrl,
            props.getRag().getSearchReturnK()
        );
        // DIAGNOSTIC: Log top reranked doc preview (truncated)
        if (!reranked.isEmpty()) {
            String txt = reranked.get(0).getText();
            String preview = txt.substring(0, Math.min(500, txt.length()));
            log.info("[DIAG] RAG top doc (post-rerank) preview=\n{}", preview);
        }
        return reranked;
    }

    /**
     * Retrieve documents with custom limits for token-constrained models.
     * Used for GPT-5 which has an 8K input token limit.
     */
    public List<Document> retrieveWithLimit(
        String query,
        int maxDocs,
        int maxTokensPerDoc
    ) {
        // Initial vector search with custom topK
        List<Document> docs;
        try {
            int topK = Math.max(
                1,
                Math.max(maxDocs, props.getRag().getSearchTopK())
            );
            log.info("=== LIMITED RETRIEVAL DEBUG ===");
            log.info(
                "Query: '{}', MaxDocs: {}, MaxTokensPerDoc: {}",
                query,
                maxDocs,
                maxTokensPerDoc
            );
            log.info("TopK requested: {}", topK);

            SearchRequest searchRequest = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build();

            docs = vectorStore.similaritySearch(searchRequest);
            log.info(
                "VectorStore returned {} documents for limited retrieval",
                docs.size()
            );
        } catch (Exception e) {
            String errorType = determineErrorType(e);
            log.warn(
                "Vector search unavailable ({}); falling back to local keyword search with limits",
                errorType
            );

            // Fallback to local search with limits
            var results = localSearch.search(query, maxDocs);
            docs = results
                .stream()
                .map(r -> documentFactory.createLocalDocument(r.text, r.url))
                .collect(Collectors.toList());
        }

        // Truncate documents to token limits and return limited count
        List<Document> truncatedDocs = docs
            .stream()
            .limit(maxDocs)
            .map(doc -> truncateDocumentToTokenLimit(doc, maxTokensPerDoc))
            .collect(Collectors.toList());

        // Apply reranking with limited return count
        List<Document> uniqueByUrl = truncatedDocs
            .stream()
            .collect(
                Collectors.toMap(
                    d -> String.valueOf(d.getMetadata().get("url")),
                    d -> d,
                    (first, dup) -> first
                )
            )
            .values()
            .stream()
            .collect(Collectors.toList());

        return rerankerService.rerank(query, uniqueByUrl, maxDocs);
    }

    /**
     * Truncate a document to a maximum token count.
     */
    private Document truncateDocumentToTokenLimit(Document doc, int maxTokens) {
        String content = doc.getText();
        if (content == null || content.isEmpty()) {
            return doc;
        }

        // Conservative estimation: ~4 chars per token
        int maxChars = maxTokens * 4;

        if (content.length() <= maxChars) {
            return doc;
        }

        // Truncate and add indicator
        String truncated = content.substring(0, maxChars);

        // Try to break at a sentence or paragraph boundary
        int lastPeriod = truncated.lastIndexOf('.');
        int lastNewline = truncated.lastIndexOf('\n');
        int breakPoint = Math.max(lastPeriod, lastNewline);

        if (breakPoint > maxChars * 0.8) {
            // Only break if we're not losing too much
            truncated = truncated.substring(0, breakPoint + 1);
        }

        truncated += "\n[...content truncated for token limits...]";

        // Create new document with truncated content
        Map<String, Object> metadata = new HashMap<>(doc.getMetadata());
        metadata.put("truncated", true);
        metadata.put("originalLength", content.length());

        return documentFactory.createLocalDocument(
            truncated,
            String.valueOf(metadata.get("url"))
        );
    }

    public List<Citation> toCitations(List<Document> docs) {
        List<Citation> citations = new ArrayList<>();
        for (Document d : docs) {
            String rawUrl = String.valueOf(
                d.getMetadata().getOrDefault("url", "")
            );
            String title = String.valueOf(
                d.getMetadata().getOrDefault("title", "")
            );
            String url = normalizeCitationUrl(rawUrl);
            // Refine Javadoc URLs to nested type pages where the chunk references them
            url =
                com.williamcallahan.javachat.util.JavadocLinkResolver.refineNestedTypeUrl(
                    url,
                    d.getText()
                );
            // Append member anchors (methods/constructors) when confidently derivable
            String pkg = String.valueOf(
                d.getMetadata().getOrDefault("package", "")
            );
            url =
                com.williamcallahan.javachat.util.JavadocLinkResolver.refineMemberAnchorUrl(
                    url,
                    d.getText(),
                    pkg
                );
            // Final canonicalization in case of any accidental duplications
            if (url.startsWith("http://") || url.startsWith("https://")) {
                url = canonicalizeHttpDocUrl(url);
            }
            String snippet = d.getText();

            // For book sources, we now link to public /pdfs path (handled by normalizeCitationUrl)

            citations.add(
                new Citation(
                    url,
                    title,
                    "",
                    snippet.length() > 500
                        ? snippet.substring(0, 500) + "…"
                        : snippet
                )
            );
        }
        return citations;
    }

    // TODO: Implement MMR and reranker integration

    /**
     * Handle vector search failure by logging context and falling back to local keyword search.
     */
    private List<Document> handleVectorSearchFailure(
        Exception e,
        String query
    ) {
        String errorType = determineErrorType(e);
        log.warn(
            "Vector search unavailable ({}); falling back to local keyword search",
            errorType,
            e
        );

        logUserFriendlyErrorContext(e, errorType);

        // Use searchTopK (not searchReturnK) to match the primary path's candidate pool size
        var results = localSearch.search(query, props.getRag().getSearchTopK());
        return results
            .stream()
            .map(r -> documentFactory.createLocalDocument(r.text, r.url))
            .limit(props.getRag().getSearchReturnK())
            .collect(Collectors.toList());
    }

    /**
     * Log user-friendly context about why vector search failed.
     */
    private void logUserFriendlyErrorContext(Exception e, String errorType) {
        if (
            e.getCause() instanceof
                GracefulEmbeddingModel.EmbeddingServiceUnavailableException
        ) {
            log.info(
                "Embedding services are unavailable. Using keyword-based search with limited semantic understanding."
            );
        } else if (errorType.contains("404")) {
            log.info(
                "Embedding API endpoint not found. Check configuration for spring.ai.openai.embedding.base-url"
            );
        } else if (errorType.contains("401") || errorType.contains("403")) {
            log.info(
                "Embedding API authentication failed. Check OPENAI_API_KEY or GITHUB_TOKEN configuration"
            );
        } else if (errorType.contains("429")) {
            log.info(
                "Embedding API rate limit exceeded. Consider using local embeddings or upgrading API tier"
            );
        }
    }

    /**
     * Normalize URLs from locally mirrored files to their authoritative online sources.
     * Handles Oracle Java SE 24 docs, JDK 24 GA, and Java 25 Early Access docs.
     */
    private String normalizeCitationUrl(String url) {
        if (url == null || url.isBlank()) return url;
        String u = url.trim();
        if (u.startsWith("http://") || u.startsWith("https://")) {
            return canonicalizeHttpDocUrl(u);
        }

        // Map book PDFs to public PDFs even if not file:// (defensive)
        String publicPdf =
            com.williamcallahan.javachat.config.DocsSourceRegistry.mapBookLocalToPublic(
                u.startsWith("file://") ? u.substring("file://".length()) : u
            );
        if (publicPdf != null) return publicPdf;

        // Only handle file:// mirrors beyond this point
        if (!u.startsWith("file://")) return u;

        String p = u.substring("file://".length());
        // Try embedded host reconstruction first
        String embedded =
            com.williamcallahan.javachat.config.DocsSourceRegistry.reconstructFromEmbeddedHost(
                p
            );
        if (embedded != null) return embedded;
        // Try local prefix mapping
        String mapped =
            com.williamcallahan.javachat.config.DocsSourceRegistry.mapLocalPrefixToRemote(
                p
            );
        return mapped != null ? mapped : url;
    }

    /**
     * Fix common path duplications in already-HTTP doc URLs (e.g., '/docs/api/api/').
     */
    private String canonicalizeHttpDocUrl(String url) {
        String out = url;
        // Collapse duplicated segments for Oracle and EA docs
        out = out.replace("/docs/api/api/", "/docs/api/");
        out = out.replace("/api/api/", "/api/");
        // Fix malformed Spring docs paths that accidentally include '/java/' segment
        if (out.contains("https://docs.spring.io/")) {
            // Spring Boot Javadoc
            out = out.replace(
                "/spring-boot/docs/current/api/java/",
                "/spring-boot/docs/current/api/"
            );
            // Spring Framework Javadoc
            out = out.replace(
                "/spring-framework/docs/current/javadoc-api/java/",
                "/spring-framework/docs/current/javadoc-api/"
            );
        }
        // Remove accidental double slashes (but keep protocol)
        int protoIdx = out.indexOf("://");
        String prefix = protoIdx >= 0 ? out.substring(0, protoIdx + 3) : "";
        String rest = protoIdx >= 0 ? out.substring(protoIdx + 3) : out;
        rest = rest.replaceAll("/+", "/");
        return prefix + rest;
    }

    /**
     * Determine the type of error for better user feedback
     */
    private String determineErrorType(Exception e) {
        String message = e.getMessage();
        if (message == null) {
            message = "";
        }

        // Check the entire exception chain
        Throwable current = e;
        while (current != null) {
            String currentMessage = current.getMessage();
            if (currentMessage != null) {
                message += " " + currentMessage;
            }
            current = current.getCause();
        }

        message = message.toLowerCase();

        if (message.contains("404") || message.contains("not found")) {
            return "404 Not Found";
        } else if (
            message.contains("401") || message.contains("unauthorized")
        ) {
            return "401 Unauthorized";
        } else if (message.contains("403") || message.contains("forbidden")) {
            return "403 Forbidden";
        } else if (
            message.contains("429") || message.contains("too many requests")
        ) {
            return "429 Rate Limited";
        } else if (
            message.contains("connection") || message.contains("timeout")
        ) {
            return "Connection Error";
        } else if (
            message.contains("embedding") && message.contains("unavailable")
        ) {
            return "Embedding Service Unavailable";
        } else {
            return "Unknown Error";
        }
    }
}
