package com.williamcallahan.javachat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final LocalStoreService localStore;
    private final ContentHasher hasher;

    @Value("${spring.ai.vectorstore.qdrant.host}")
    private String host;
    @Value("${spring.ai.vectorstore.qdrant.port:6334}")
    private int port;
    @Value("${spring.ai.vectorstore.qdrant.use-tls:false}")
    private boolean useTls;
    @Value("${spring.ai.vectorstore.qdrant.api-key:}")
    private String apiKey;
    @Value("${spring.ai.vectorstore.qdrant.collection-name}")
    private String collection;

    public AuditService(LocalStoreService localStore, ContentHasher hasher) {
        this.localStore = localStore;
        this.hasher = hasher;
    }

    public Map<String, Object> auditByUrl(String url) throws IOException {
        // 1) Enumerate parsed chunks for this URL
        String safeBase = localStore.toSafeName(url) + "_";
        Path parsedRoot = localStore.getParsedDir();

        // pattern: <safeBase><index>_<hash12>.txt
        Pattern p = Pattern.compile(Pattern.quote(localStore.toSafeName(url)) + "_" + "(\\d+)" + "_" + "([0-9a-f]{12})" + "\\.txt");

        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(parsedRoot)) {
            stream.filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().startsWith(safeBase))
                    .forEach(files::add);
        }

        Set<String> expectedHashes = new LinkedHashSet<>();
        Set<Integer> chunkIndexes = new LinkedHashSet<>();
        for (Path f : files) {
            String name = f.getFileName().toString();
            Matcher m = p.matcher(name);
            if (!m.matches()) continue;
            int idx = Integer.parseInt(m.group(1));
            String text = Files.readString(f, StandardCharsets.UTF_8);
            String fullHash = hasher.generateChunkHash(url, idx, text);
            expectedHashes.add(fullHash);
            chunkIndexes.add(idx);
        }

        // 2) Query Qdrant for all points with payload.url == url
        Set<String> qdrantHashes = fetchQdrantHashes(url);

        // 3) Compare
        Set<String> missing = new LinkedHashSet<>(expectedHashes);
        missing.removeAll(qdrantHashes);

        Set<String> extra = new LinkedHashSet<>(qdrantHashes);
        extra.removeAll(expectedHashes);

        // Detect duplicates in Qdrant by hash (should be 0 if id=hash going forward)
        Map<String, Integer> dupCount = new HashMap<>();
        for (String h : qdrantHashes) dupCount.merge(h, 1, Integer::sum);
        List<String> duplicates = dupCount.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("url", url);
        result.put("parsedCount", expectedHashes.size());
        result.put("qdrantCount", qdrantHashes.size());
        result.put("missingCount", missing.size());
        result.put("extraCount", extra.size());
        result.put("duplicates", duplicates);
        result.put("ok", missing.isEmpty() && duplicates.isEmpty());
        if (!missing.isEmpty()) result.put("missingHashes", missing.stream().limit(20).toList());
        if (!extra.isEmpty()) result.put("extraHashes", extra.stream().limit(20).toList());
        return result;
    }

    private Set<String> fetchQdrantHashes(String url) {
        Set<String> hashes = new LinkedHashSet<>();
        RestTemplate rt = new RestTemplate();
        String base = (useTls ? "https://" + host : "http://" + host + ":" + port);
        String endpoint = base + "/collections/" + collection + "/points/scroll";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) headers.set("api-key", apiKey);

        Map<String, Object> filter = Map.of(
                "must", List.of(Map.of(
                        "key", "url",
                        "match", Map.of("value", url)
                ))
        );

        Map<String, Object> body = new HashMap<>();
        body.put("filter", filter);
        body.put("with_payload", true);
        body.put("limit", 2048);

        try {
            var response = rt.postForEntity(endpoint, new HttpEntity<>(body, headers), Map.class);
            Object result = response.getBody() != null ? response.getBody().get("result") : null;
            if (result instanceof Map<?, ?> m) {
                Object points = m.get("points");
                if (points instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> pm) {
                            Object payload = pm.get("payload");
                            if (payload instanceof Map<?, ?> pmap) {
                                Object h = pmap.get("hash");
                                if (h instanceof String s) hashes.add(s);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Qdrant scroll failed: {}", e.getMessage());
        }
        return hashes;
    }
}

