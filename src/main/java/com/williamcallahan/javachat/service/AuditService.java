package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.williamcallahan.javachat.model.AuditReport;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for auditing ingested documents against the vector store.
 */
@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /**
     * Maps Qdrant gRPC ports to their corresponding REST API ports.
     *
     * <p>Spring AI configures the gRPC port (6334 default, 8086 in docker-compose),
     * but scroll/REST operations require the REST port (6333 default, 8087 in docker).
     */
    private static final Map<Integer, Integer> GRPC_TO_REST_PORT = Map.of(
        6334, 6333,  // Qdrant default: gRPC -> REST
        8086, 8087   // Docker compose mapping: gRPC -> REST
    );

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

    /**
     * Creates an audit service that compares locally parsed chunks against the vector store state.
     *
     * @param localStore local snapshot and chunk storage
     * @param hasher content hashing helper
     */
    public AuditService(LocalStoreService localStore, ContentHasher hasher) {
        this.localStore = localStore;
        this.hasher = hasher;
    }

    /**
     * Audits all parsed chunks for a URL against Qdrant and returns a summary report.
     *
     * @param url URL to audit
     * @return audit report with counts and discrepancies
     * @throws IOException if local chunk files cannot be read
     */
    public AuditReport auditByUrl(String url) throws IOException {
        Set<String> expectedHashes = getExpectedHashes(url);
        List<String> qdrantHashes = fetchQdrantHashes(url);
        
        return compareAndReport(url, expectedHashes, qdrantHashes);
    }

    private Set<String> getExpectedHashes(String url) throws IOException {
        // 1) Enumerate parsed chunks for this URL
        String safeName = localStore.toSafeName(url);
        if (safeName == null || safeName.isEmpty()) {
            log.warn("Cannot audit URL with invalid safe name mapping");
            return Set.of();
        }
        String safeBase = safeName + "_";
        Path parsedRoot = localStore.getParsedDir();
        if (parsedRoot == null || !Files.exists(parsedRoot)) {
            return Set.of();
        }

        // pattern: <safeBase><index>_<hash12>.txt
        Pattern chunkPattern = Pattern.compile(
            Pattern.quote(safeName) + "_" + "(\\d+)" + "_" + "([0-9a-f]{12})" + "\\.txt"
        );

        List<Path> parsedFiles = new ArrayList<>();
        try (var stream = Files.walk(parsedRoot)) {
            stream.filter(Files::isRegularFile)
                .filter(filePath -> {
                    Path fileName = filePath.getFileName();
                    return fileName != null && fileName.toString().startsWith(safeBase);
                })
                .forEach(parsedFiles::add);
        }

        Set<String> expectedHashes = new LinkedHashSet<>();
        for (Path parsedFile : parsedFiles) {
            Path fileNamePath = parsedFile.getFileName();
            if (fileNamePath == null) {
                continue;
            }
            String fileName = fileNamePath.toString();
            Matcher matcher = chunkPattern.matcher(fileName);
            if (!matcher.matches()) {
                continue;
            }
            int chunkIndex = Integer.parseInt(matcher.group(1));
            String chunkText = Files.readString(parsedFile, StandardCharsets.UTF_8);
            String fullHash = hasher.generateChunkHash(url, chunkIndex, chunkText);
            expectedHashes.add(fullHash);
        }
        return expectedHashes;
    }

    private AuditReport compareAndReport(String url, Set<String> expectedHashes, List<String> qdrantHashList) {
        Set<String> qdrantHashes = new LinkedHashSet<>(qdrantHashList);
        // 3) Compare
        Set<String> missingHashes = new LinkedHashSet<>(expectedHashes);
        missingHashes.removeAll(qdrantHashes);

        Set<String> extraHashes = new LinkedHashSet<>(qdrantHashes);
        extraHashes.removeAll(expectedHashes);

        // Detect duplicates in Qdrant by hash (should be 0 if id=hash going forward)
        Map<String, Integer> duplicateCounts = new HashMap<>();
        for (String hashValue : qdrantHashList) {
            duplicateCounts.merge(hashValue, 1, Integer::sum);
        }
        List<String> duplicateHashes = duplicateCounts.entrySet().stream()
            .filter(countEntry -> countEntry.getValue() != null && countEntry.getValue() > 1)
            .map(Map.Entry::getKey)
            .toList();

        boolean auditOk = missingHashes.isEmpty() && duplicateHashes.isEmpty();
        List<String> missingHashesSample = missingHashes.isEmpty()
            ? List.of()
            : missingHashes.stream().limit(20).toList();
        List<String> extraHashesSample = extraHashes.isEmpty()
            ? List.of()
            : extraHashes.stream().limit(20).toList();

        return new AuditReport(
            url,
            expectedHashes.size(),
            qdrantHashes.size(),
            missingHashes.size(),
            extraHashes.size(),
            duplicateHashes,
            auditOk,
            missingHashesSample,
            extraHashesSample
        );
    }

    private List<String> fetchQdrantHashes(String url) {
        List<String> hashes = new ArrayList<>();
        RestTemplate restTemplate = new RestTemplate();
        
        // Build REST base URL with correct port mapping
        // Note: spring.ai.vectorstore.qdrant.port is typically gRPC (6334); REST runs on 6333
        String base = buildQdrantRestBaseUrl();
        String endpoint = base + "/collections/" + collection + "/points/scroll";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiKey != null && !apiKey.isBlank()) {
            headers.set("api-key", apiKey);
        }

        QdrantScrollFilter scrollFilter = new QdrantScrollFilter(
            List.of(new QdrantScrollMustCondition("url", new QdrantScrollMatch(url)))
        );

        // Paginate through all results using next_page_offset
        JsonNode nextOffset = null;
        int pageCount = 0;
        int maxPages = 100; // Safety limit to prevent infinite loops
        int pageSize = 1000; // Reduced from 2048 for more reliable pagination
        
        do {
            QdrantScrollRequest requestBody = new QdrantScrollRequest(
                scrollFilter,
                true,
                pageSize,
                nextOffset
            );

            try {
                var response = restTemplate.exchange(
                    endpoint,
                    org.springframework.http.HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers),
                    QdrantScrollResponse.class
                );
                
                QdrantScrollResponse body = response.getBody();
                if (body != null && body.scrollResult() != null) {
                    hashes.addAll(body.scrollResult().hashes());
                    nextOffset = body.scrollResult().nextPageOffset();
                    if (nextOffset != null && nextOffset.isNull()) {
                        nextOffset = null;
                    }
                } else {
                    nextOffset = null;
                }
                pageCount++;
                
                if (pageCount > 1) {
                    log.debug("Scroll page {} fetched, total hashes so far: {}", pageCount, hashes.size());
                }
                
            } catch (Exception requestFailure) {
                // Propagate failure so caller knows audit could not complete
                throw new IllegalStateException(
                    "Qdrant scroll failed for URL audit (endpoint: " + endpoint + ")", requestFailure);
            }
        } while (nextOffset != null && pageCount < maxPages);
        
        if (pageCount >= maxPages) {
            log.warn("Scroll pagination reached safety limit of {} pages; results may be incomplete", maxPages);
        }
        
        return hashes;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record QdrantScrollRequest(
        @JsonProperty("filter") QdrantScrollFilter filter,
        @JsonProperty("with_payload") boolean withPayload,
        @JsonProperty("limit") int limit,
        @JsonProperty("offset") JsonNode offset
    ) {}

    private record QdrantScrollFilter(
        @JsonProperty("must") List<QdrantScrollMustCondition> must
    ) {}

    private record QdrantScrollMustCondition(
        @JsonProperty("key") String key,
        @JsonProperty("match") QdrantScrollMatch match
    ) {}

    private record QdrantScrollMatch(@JsonProperty("value") String value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QdrantScrollResponse(
        @JsonProperty("result") QdrantScrollResult scrollResult
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QdrantScrollResult(
        @JsonProperty("points") List<QdrantScrollPoint> points,
        @JsonProperty("next_page_offset") JsonNode nextPageOffset
    ) {
        List<String> hashes() {
            if (points == null || points.isEmpty()) {
                return List.of();
            }
            List<String> hashes = new ArrayList<>(points.size());
            for (QdrantScrollPoint point : points) {
                if (point == null || point.payload() == null) {
                    continue;
                }
                String hash = point.payload().hash();
                if (hash != null && !hash.isBlank()) {
                    hashes.add(hash);
                }
            }
            return hashes;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QdrantScrollPoint(@JsonProperty("payload") QdrantScrollPayload payload) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QdrantScrollPayload(@JsonProperty("hash") String hash) {}

    /**
     * Builds the Qdrant REST API base URL with correct port mapping.
     *
     * <p>The configured port is typically the gRPC port (6334 default, 8086 docker).
     * REST API runs on a different port (6333 default, 8087 docker, or 443 for cloud TLS).
     *
     * @return base URL for Qdrant REST API calls
     */
    private String buildQdrantRestBaseUrl() {
        if (useTls) {
            // Cloud deployment: REST via HTTPS on port 443 (gateway handles routing)
            return "https://" + host;
        }
        // Local deployment: map gRPC port to REST port, or use as-is if not a known gRPC port
        int restPort = GRPC_TO_REST_PORT.getOrDefault(port, port);
        return "http://" + host + ":" + restPort;
    }
}
