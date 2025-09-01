package com.williamcallahan.javachat.service;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
//
// Use fully qualified name to avoid clash with org.jsoup.nodes.Document
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
//
import java.time.Duration;
import java.util.*;

@Service
public class DocsIngestionService {
    private final String rootUrl;
    private final Chunker chunker;
    private final VectorStore vectorStore;
    private final ContentHasher hasher;
    private final LocalStoreService localStore;

    public DocsIngestionService(@Value("${app.docs.root-url}") String rootUrl,
                                Chunker chunker,
                                VectorStore vectorStore,
                                ContentHasher hasher,
                                LocalStoreService localStore) {
        this.rootUrl = rootUrl;
        this.chunker = chunker;
        this.vectorStore = vectorStore;
        this.hasher = hasher;
        this.localStore = localStore;
    }

    public void crawlAndIngest(int maxPages) throws IOException {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(rootUrl);
        while (!queue.isEmpty() && visited.size() < maxPages) {
            String url = queue.poll();
            if (!visited.add(url)) continue;
            if (!url.startsWith(rootUrl)) continue;
            Document doc = Jsoup.connect(url).timeout((int) Duration.ofSeconds(30).toMillis()).get();
            String title = Optional.ofNullable(doc.title()).orElse("");
            String bodyText = doc.body() != null ? doc.body().text() : "";
            String packageName = extractPackage(url, bodyText);

            // Persist raw HTML snapshot
            localStore.saveHtml(url, doc.outerHtml());

            for (Element a : doc.select("a[href]")) {
                String href = a.attr("abs:href");
                if (href.startsWith(rootUrl) && !visited.contains(href)) {
                    queue.add(href);
                }
            }

            List<String> chunks = chunker.chunkByTokens(bodyText, 900, 150);
            for (int i = 0; i < chunks.size(); i++) {
                String text = chunks.get(i);
                Map<String, Object> meta = new HashMap<>();
                meta.put("url", url);
                meta.put("title", title);
                meta.put("chunkIndex", i);
                meta.put("package", packageName);
                String hash = hasher.sha256(url + "#" + i + ":" + text);
                meta.put("hash", hash);
                org.springframework.ai.document.Document sd = new org.springframework.ai.document.Document(text);
                sd.getMetadata().putAll(meta);
                if (!localStore.isHashIngested(hash)) {
                    // TODO: When Qdrant adapter exposes upsert by id, use meta.hash as point id for true dedup
                    vectorStore.add(List.of(sd));
                    localStore.saveChunkText(url, i, text, hash);
                    localStore.markHashIngested(hash);
                }
            }
        }
    }

    private String extractPackage(String url, String bodyText) {
        // Heuristics: if URL contains /api/ or /package-summary.html, try to extract
        if (url.contains("/api/")) {
            int idx = url.indexOf("/api/") + 5;
            String tail = url.substring(idx);
            String[] parts = tail.split("/");
            StringBuilder sb = new StringBuilder();
            for (String p : parts) {
                if (p.endsWith(".html")) break;
                if (sb.length() > 0) sb.append('.');
                sb.append(p);
            }
            String pkg = sb.toString();
            if (pkg.contains(".")) return pkg;
        }
        // Fallback: scan text for "Package java." pattern
        int p = bodyText.indexOf("Package ");
        if (p >= 0) {
            int end = Math.min(bodyText.length(), p + 100);
            String snippet = bodyText.substring(p, end);
            for (String token : snippet.split("\\s+")) {
                if (token.startsWith("java.")) return token.replaceAll("[,.;]$", "");
            }
        }
        return "";
    }
}


