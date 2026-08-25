package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.QdrantCollectionNames;
import com.williamcallahan.javachat.config.QdrantRestConnection;
import com.williamcallahan.javachat.model.AuditReport;
import com.williamcallahan.javachat.service.ingestion.IngestionStorageServices;
import java.io.IOException;
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
    private static final int LEGACY_CHUNK_HASH_LENGTH = 12;
    private static final int SHA_256_HEX_LENGTH = 64;

    private final QdrantRestConnection qdrantRestConnection;
    private final Function<String, String> safeNameResolver;
    private final FileIngestionMarkerStore fileIngestionMarkerStore;
    private final Supplier<Path> parsedDirSupplier;
    private final RestTemplate restTemplate;
    private final List<String> collectionNames;

    /**
     * Creates an audit service that compares locally parsed chunks against the vector store state.
     *
     * @param ingestionStorage local marker and chunk storage
     * @param restTemplateBuilder Spring-managed builder for creating RestTemplate instances
     * @param qdrantRestConnection shared Qdrant REST connection details
     * @param appProperties application configuration for collection names
     */
    public AuditService(
            IngestionStorageServices ingestionStorage,
            RestTemplateBuilder restTemplateBuilder,
            QdrantRestConnection qdrantRestConnection,
            AppProperties appProperties) {
        this(ingestionStorage, restTemplateBuilder.build(), qdrantRestConnection, appProperties);
    }

    AuditService(
            IngestionStorageServices ingestionStorage,
            RestTemplate restTemplate,
            QdrantRestConnection qdrantRestConnection,
            AppProperties appProperties) {
        this.qdrantRestConnection = Objects.requireNonNull(qdrantRestConnection, "qdrantRestConnection");
        IngestionStorageServices requiredStorage = Objects.requireNonNull(ingestionStorage, "ingestionStorage");
        LocalStoreService requiredLocalStore = requiredStorage.localStore();
        this.fileIngestionMarkerStore = requiredStorage.fileMarkers();
        AppProperties requiredAppProperties = Objects.requireNonNull(appProperties, "appProperties");
        QdrantCollectionNames configuredCollections =
                requiredAppProperties.getQdrant().getCollections();
        this.safeNameResolver = requiredLocalStore::toSafeName;
        this.parsedDirSupplier = requiredLocalStore::getParsedDir;
        this.restTemplate = Objects.requireNonNull(restTemplate, "restTemplate");
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
        List<QdrantAuditPoint> qdrantPoints = fetchQdrantPoints(url);
        Set<String> storageUrls = qdrantPoints.stream()
                .map(QdrantAuditPoint::storageUrl)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        storageUrls.addAll(fileIngestionMarkerStore.storageUrlsForCanonicalCitation(url));
        if (storageUrls.isEmpty()) {
            storageUrls.add(url);
        }
        Set<String> expectedHashes = new LinkedHashSet<>();
        for (String storageUrl : storageUrls) {
            expectedHashes.addAll(getExpectedHashes(storageUrl));
        }
        List<String> qdrantHashes =
                qdrantPoints.stream().map(QdrantAuditPoint::hash).toList();

        return compareAndReport(url, expectedHashes, qdrantHashes);
    }

    Set<String> getExpectedHashes(String url) throws IOException {
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

        Pattern chunkPattern = parsedChunkPattern(safeName);

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
            String persistedHash = matcher.group(2);
            if (persistedHash.length() != SHA_256_HEX_LENGTH) {
                throw new IllegalStateException(
                        "Legacy parsed chunk identity cannot be audited; re-ingest the source to write full hashes: "
                                + fileName);
            }
            expectedHashes.add(persistedHash);
        }
        return expectedHashes;
    }

    static Pattern parsedChunkPattern(String safeName) {
        return Pattern.compile(Pattern.quote(safeName)
                + "_(\\d+)_([0-9a-f]{"
                + LEGACY_CHUNK_HASH_LENGTH
                + "}|[0-9a-f]{"
                + SHA_256_HEX_LENGTH
                + "})\\.txt");
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

        boolean auditOk = (!expectedHashes.isEmpty() || !qdrantHashes.isEmpty())
                && missingHashes.isEmpty()
                && extraHashes.isEmpty()
                && duplicateHashes.isEmpty();
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

    private List<QdrantAuditPoint> fetchQdrantPoints(String url) {
        List<QdrantAuditPoint> auditPoints = new ArrayList<>();
        for (String collectionName : collectionNames) {
            auditPoints.addAll(fetchQdrantPointsFromCollection(url, collectionName));
        }
        return auditPoints;
    }

    private List<QdrantAuditPoint> fetchQdrantPointsFromCollection(String url, String collectionName) {
        List<QdrantAuditPoint> auditPoints = new ArrayList<>();
        String canonicalAuditUrl = DocsSourceRegistry.normalizeDocUrl(url);

        String base = qdrantRestConnection.restBaseUrl();
        String endpoint = base + "/collections/" + collectionName + "/points/scroll";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String qdrantApiKey = qdrantRestConnection.apiKey();
        if (qdrantApiKey != null && !qdrantApiKey.isBlank()) {
            headers.set(QdrantRestConnection.API_KEY_HEADER, qdrantApiKey);
        }

        // Paginate through all results using next_page_offset
        JsonNode nextOffset = null;
        int pageCount = 0;
        int maxPages = 100; // Safety limit to prevent infinite loops
        int pageSize = 1000; // Reduced from 2048 for more reliable pagination

        do {
            QdrantScrollFilter scrollFilter = new QdrantScrollFilter(List.of(
                    new QdrantScrollCondition(QdrantPayloadFieldSchema.URL_FIELD, new QdrantScrollMatch(url)),
                    new QdrantScrollCondition(
                            QdrantPayloadFieldSchema.CITATION_URL_FIELD, new QdrantScrollMatch(canonicalAuditUrl))));
            QdrantScrollRequest requestBody = new QdrantScrollRequest(scrollFilter, true, pageSize, nextOffset);

            try {
                var response = restTemplate.exchange(
                        endpoint,
                        org.springframework.http.HttpMethod.POST,
                        new HttpEntity<>(requestBody, headers),
                        QdrantScrollResponse.class);

                QdrantScrollResponse body = response.getBody();
                if (body != null && body.scrollResult() != null) {
                    auditPoints.addAll(body.scrollResult().auditPoints());
                    nextOffset = body.scrollResult().nextPageOffset();
                    if (nextOffset != null && nextOffset.isNull()) {
                        nextOffset = null;
                    }
                } else {
                    nextOffset = null;
                }
                pageCount++;

                if (pageCount > 1) {
                    log.debug("Scroll page {} fetched, total audit points so far: {}", pageCount, auditPoints.size());
                }

            } catch (Exception requestFailure) {
                // Propagate failure so caller knows audit could not complete
                throw new IllegalStateException(
                        "Qdrant scroll failed for URL audit (endpoint: " + endpoint + ")", requestFailure);
            }
        } while (nextOffset != null && pageCount < maxPages);

        if (nextOffset != null) {
            throw new IllegalStateException("Qdrant URL audit exceeded the pagination safety limit");
        }

        return auditPoints;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record QdrantScrollRequest(
            @JsonProperty("filter") QdrantScrollFilter filter,
            @JsonProperty("with_payload") boolean withPayload,
            @JsonProperty("limit") int limit,
            @JsonProperty("offset") JsonNode offset) {}

    private record QdrantScrollFilter(
            @JsonProperty("should") List<QdrantScrollCondition> should) {}

    private record QdrantScrollCondition(
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
        List<QdrantAuditPoint> auditPoints() {
            if (points == null || points.isEmpty()) {
                return List.of();
            }
            List<QdrantAuditPoint> auditPoints = new ArrayList<>(points.size());
            for (QdrantScrollPoint point : points) {
                if (point == null || point.payload() == null) {
                    continue;
                }
                String hash = point.payload().hash();
                String storageUrl = point.payload().url();
                if (hash != null && !hash.isBlank() && storageUrl != null && !storageUrl.isBlank()) {
                    auditPoints.add(new QdrantAuditPoint(storageUrl, hash));
                }
            }
            return auditPoints;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QdrantScrollPoint(
            @JsonProperty("payload") QdrantScrollPayload payload) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record QdrantScrollPayload(
            @JsonProperty(QdrantPayloadFieldSchema.URL_FIELD)
            String url,

            @JsonProperty(QdrantPayloadFieldSchema.HASH_FIELD)
            String hash) {}

    private record QdrantAuditPoint(String storageUrl, String hash) {}
}
