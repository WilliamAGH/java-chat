package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.QdrantRestConnection;
import com.williamcallahan.javachat.model.AuditReport;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/**
 * Service for auditing ingested documents against the vector store.
 */
@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final QdrantRestConnection qdrantRestConnection;
    private final Function<String, String> safeNameResolver;
    private final Supplier<Path> parsedDirSupplier;
    private final ContentHasher hasher;
    private final RestTemplate restTemplate;
    private final List<String> collectionNames;

    /**
     * Creates an audit service that compares locally parsed chunks against the vector store state.
     *
     * @param localStore local snapshot and chunk storage
     * @param hasher content hashing helper
     * @param restTemplateBuilder Spring-managed builder for creating RestTemplate instances
     * @param qdrantRestConnection shared Qdrant REST connection details
     * @param appProperties application configuration for collection names
     */
    public AuditService(
            LocalStoreService localStore,
            ContentHasher hasher,
            RestTemplateBuilder restTemplateBuilder,
            QdrantRestConnection qdrantRestConnection,
            AppProperties appProperties) {
        this.qdrantRestConnection = Objects.requireNonNull(qdrantRestConnection, "qdrantRestConnection");
        LocalStoreService requiredLocalStore = Objects.requireNonNull(localStore, "localStore");
        AppProperties requiredAppProperties = Objects.requireNonNull(appProperties, "appProperties");
        AppProperties.QdrantCollections configuredCollections =
                requiredAppProperties.getQdrant().getCollections();
        this.safeNameResolver = requiredLocalStore::toSafeName;
        this.parsedDirSupplier = requiredLocalStore::getParsedDir;
        this.hasher = Objects.requireNonNull(hasher, "hasher");
        this.restTemplate = restTemplateBuilder.build();
        this.collectionNames = List.of(
                configuredCollections.getBooks(),
                configuredCollections.getDocs(),
                configuredCollections.getArticles(),
                configuredCollections.getPdfs());
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
        String safeName = safeNameResolver.apply(url);
        if (safeName == null || safeName.isEmpty()) {
            throw new IllegalStateException("Cannot audit URL: invalid safe name mapping for " + url);
        }
        String safeBase = safeName + "_";
        Path parsedRoot = parsedDirSupplier.get();
        if (parsedRoot == null || !Files.exists(parsedRoot)) {
            throw new IllegalStateException("Parsed chunk directory not available: " + parsedRoot);
        }

        // pattern: <safeBase><index>_<hash12>.txt
        Pattern chunkPattern =
                Pattern.compile(Pattern.quote(safeName) + "_" + "(\\d+)" + "_" + "([0-9a-f]{12})" + "\\.txt");

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
            duplicateCounts.merge(hashValue, 1, (a, b) -> a + b);
        }
        List<String> duplicateHashes = duplicateCounts.entrySet().stream()
                .filter(countEntry -> countEntry.getValue() != null && countEntry.getValue() > 1)
                .map(Map.Entry::getKey)
                .toList();

        boolean auditOk = missingHashes.isEmpty() && extraHashes.isEmpty() && duplicateHashes.isEmpty();
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
                extraHashesSample);
    }

    private List<String> fetchQdrantHashes(String url) {
        List<String> hashes = new ArrayList<>();
        for (String collectionName : collectionNames) {
            hashes.addAll(fetchQdrantHashesFromCollection(url, collectionName));
        }
        return hashes;
    }

    private List<String> fetchQdrantHashesFromCollection(String url, String collectionName) {
        List<String> hashes = new ArrayList<>();

        String base = qdrantRestConnection.restBaseUrl();
        String endpoint = base + "/collections/" + collectionName + "/points/scroll";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String qdrantApiKey = qdrantRestConnection.apiKey();
        if (qdrantApiKey != null && !qdrantApiKey.isBlank()) {
            headers.set("api-key", qdrantApiKey);
        }

        QdrantScrollFilter scrollFilter =
                new QdrantScrollFilter(List.of(new QdrantScrollMustCondition("url", new QdrantScrollMatch(url))));

        // Paginate through all results using next_page_offset
        JsonNode nextOffset = null;
        int pageCount = 0;
        int maxPages = 100; // Safety limit to prevent infinite loops
        int pageSize = 1000; // Reduced from 2048 for more reliable pagination

        do {
            QdrantScrollRequest requestBody = new QdrantScrollRequest(scrollFilter, true, pageSize, nextOffset);

            try {
                var response = restTemplate.exchange(
                        endpoint,
                        org.springframework.http.HttpMethod.POST,
                        new HttpEntity<>(requestBody, headers),
                        QdrantScrollResponse.class);

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
            @JsonProperty("offset") JsonNode offset) {}

    private record QdrantScrollFilter(@JsonProperty("must") List<QdrantScrollMustCondition> must) {}

    private record QdrantScrollMustCondition(
            @JsonProperty("key") String key,
            @JsonProperty("match") QdrantScrollMatch match) {}

    private record QdrantScrollMatch(@JsonProperty("value") String value) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QdrantScrollResponse(
            @JsonProperty("result") QdrantScrollResult scrollResult) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QdrantScrollResult(
            @JsonProperty("points") List<QdrantScrollPoint> points,
            @JsonProperty("next_page_offset") JsonNode nextPageOffset) {
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
    private record QdrantScrollPoint(
            @JsonProperty("payload") QdrantScrollPayload payload) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QdrantScrollPayload(@JsonProperty("hash") String hash) {}
}
