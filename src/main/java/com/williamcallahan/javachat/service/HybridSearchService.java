package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.application.search.LexicalSparseVectorEncoder;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.QdrantGitHubCollectionDiscovery;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.grpc.Common.Filter;
import io.qdrant.client.grpc.Points.QueryPoints;
import io.qdrant.client.grpc.Points.ScoredPoint;
import io.qdrant.client.grpc.Points.ScrollPoints;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * Executes hybrid search across all Qdrant collections and official citation search.
 *
 * <p>For each collection, a Qdrant Query API request is issued with two prefetch stages
 * (dense nearest-neighbor and sparse BM25-style lexical search) fused via reciprocal rank
 * fusion (RRF). Unconstrained queries fan out to every configured collection; canonical official
 * documentation scopes query only the documentation and PDF collections that ingestion can route
 * those sources into. Results are deduplicated by point UUID before returning top-K. Exact Javadoc
 * overload citation discovery uses a filtered payload scroll against the documentation collection,
 * while ordinary citation questions use sparse retrieval across the official-document collections.</p>
 *
 * <p>Verified API contract (Step 0): this adapter uses direct {@code io.qdrant:client} 1.18.3
 * primitives rather than Spring AI VectorStore abstractions. Hybrid behavior depends on
 * {@code QueryPoints.prefetch + QueryFactory.rrf(...)}, sparse query vectors are encoded with
 * {@code VectorInputFactory.vectorInput(values, indices)}, and {@code using} names must match
 * collection schema keys configured in {@code app.qdrant.*}.</p>
 */
@Service
public class HybridSearchService {
    private static final Logger log = LoggerFactory.getLogger(HybridSearchService.class);

    private static final int MAX_FAILURE_DETAIL_LENGTH = 240;
    private static final long MINIMUM_QDRANT_QUERY_DURATION_NANOS =
            Duration.ofMillis(1).toNanos();

    private final QdrantClient qdrantClient;
    private final QueryEncodingServices queryEncoding;
    private final QdrantSearchRequestFactory queryRequestFactory;
    private final AppProperties appProperties;
    private final Optional<QdrantGitHubCollectionDiscovery> gitHubCollectionDiscovery;

    /**
     * Wires gRPC client and encoding dependencies for hybrid search.
     *
     * @param qdrantClient Qdrant gRPC client
     * @param queryEncoding grouped query-encoding collaborators
     * @param appProperties application configuration
     * @param gitHubCollectionDiscovery optional discovery of dynamically created GitHub collections
     */
    public HybridSearchService(
            QdrantClient qdrantClient,
            QueryEncodingServices queryEncoding,
            QdrantSearchRequestFactory queryRequestFactory,
            AppProperties appProperties,
            Optional<QdrantGitHubCollectionDiscovery> gitHubCollectionDiscovery) {
        this.qdrantClient = Objects.requireNonNull(qdrantClient, "qdrantClient");
        this.queryEncoding = Objects.requireNonNull(queryEncoding, "queryEncoding");
        this.queryRequestFactory = Objects.requireNonNull(queryRequestFactory, "queryRequestFactory");
        this.appProperties = Objects.requireNonNull(appProperties, "appProperties");
        this.gitHubCollectionDiscovery = Objects.requireNonNull(gitHubCollectionDiscovery, "gitHubCollectionDiscovery");
    }

    /**
     * Captures one retrieval notice generated during non-strict hybrid fan-out.
     *
     * @param summary concise summary for UI status events
     * @param details detailed retrieval context for diagnostics
     */
    public record HybridSearchNotice(String summary, String details) {
        public HybridSearchNotice {
            if (summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("summary cannot be null or blank");
            }
            details = details == null ? "" : details;
        }
    }

    /**
     * Captures hybrid search results and optional non-fatal notices.
     *
     * @param documents retrieved documents
     * @param notices retrieval notices for non-strict partial failures
     */
    public record SearchOutcome(List<Document> documents, List<HybridSearchNotice> notices) {
        public SearchOutcome {
            documents = documents == null ? List.of() : List.copyOf(documents);
            notices = notices == null ? List.of() : List.copyOf(notices);
        }
    }

    /**
     * Performs hybrid search across all configured collections.
     *
     * @param query search query text
     * @param topK maximum number of results to return
     * @return documents sorted by fused score (highest first)
     */
    public List<Document> search(String query, int topK) {
        return searchOutcome(query, topK, RetrievalConstraint.none()).documents();
    }

    /**
     * Performs hybrid search across all configured collections and returns retrieval notices.
     *
     * @param query search query text
     * @param topK maximum number of results to return
     * @param retrievalConstraint retrieval metadata constraint for server-side filtering
     * @return search outcome containing documents and optional non-fatal notices
     */
    public SearchOutcome searchOutcome(String query, int topK, RetrievalConstraint retrievalConstraint) {
        return searchOutcomes(query, topK, List.of(retrievalConstraint)).getFirst();
    }

    /**
     * Performs one query encoding and parallel Qdrant fan-out for each required metadata scope.
     *
     * <p>Each scope receives its own top-K result budget, so one Java release cannot crowd another
     * out. Every Qdrant request shares one operation deadline.</p>
     *
     * @param query search query text
     * @param topK maximum number of results returned for each scope
     * @param retrievalConstraints server-side metadata scopes searched with the shared encoding
     * @return search outcomes in the same order as the supplied scopes
     */
    public List<SearchOutcome> searchOutcomes(String query, int topK, List<RetrievalConstraint> retrievalConstraints) {
        Objects.requireNonNull(query, "query");
        List<RetrievalConstraint> requiredRetrievalConstraints = requireRetrievalConstraints(retrievalConstraints);
        if (query.isBlank() || topK <= 0) {
            return requiredRetrievalConstraints.stream()
                    .map(ignoredConstraint -> new SearchOutcome(List.of(), List.of()))
                    .toList();
        }

        float[] denseVector = queryEncoding.embeddingClient().embed(query, LlmGatewayTier.LIVE);
        LexicalSparseVectorEncoder.SparseVector sparseVector =
                queryEncoding.sparseVectorEncoder().encode(query);
        HybridQueryConfig queryConfig = HybridQueryConfig.fromProperties(appProperties);
        long queryDeadlineNanos = queryDeadlineNanos(queryConfig.queryTimeout());
        List<CollectionQueryDispatch> queryDispatches = new ArrayList<>(requiredRetrievalConstraints.size());
        for (RetrievalConstraint requiredRetrievalConstraint : requiredRetrievalConstraints) {
            List<String> collectionNames = collectionNamesFor(requiredRetrievalConstraint);
            Optional<Filter> retrievalFilter =
                    queryEncoding.constraintBuilder().buildFilter(requiredRetrievalConstraint);
            EncodedQuery encodedQuery = new EncodedQuery(denseVector, sparseVector, retrievalFilter);
            Map<String, CompletableFuture<List<ScoredPoint>>> futuresByCollection =
                    LinkedHashMap.newLinkedHashMap(collectionNames.size());
            for (String collectionName : collectionNames) {
                QueryPoints queryRequest = Objects.requireNonNull(
                        buildHybridQueryRequest(collectionName, encodedQuery, queryConfig, topK), "QueryPoints");
                CompletableFuture<List<ScoredPoint>> collectionQueryFuture =
                        dispatchQueryBeforeDeadline(queryRequest, queryDeadlineNanos);
                futuresByCollection.put(collectionName, collectionQueryFuture);
            }
            queryDispatches.add(
                    new CollectionQueryDispatch(futuresByCollection, queryConfig.queryTimeout(), queryDeadlineNanos));
        }
        return collectSearchOutcomes(queryDispatches, topK);
    }

    /**
     * Searches the canonical documentation collection for citation candidates.
     *
     * <p>An unambiguous Javadoc overload signature uses its exact persisted payload filter directly,
     * because signature tokens need not have a useful sparse score. Other questions use sparse
     * lexical retrieval. Neither path creates a dense embedding, performs RRF fusion, or includes
     * dynamically discovered GitHub collections.</p>
     *
     * @param query citation-discovery query text
     * @param topK maximum number of citation candidates to return
     * @param retrievalConstraint official documentation metadata constraint
     * @return citation search outcome containing documents
     */
    public SearchOutcome searchDocumentationCitationsOutcome(
            String query, int topK, RetrievalConstraint retrievalConstraint) {
        return searchDocumentationCitationsOutcomes(query, topK, List.of(retrievalConstraint))
                .getFirst();
    }

    /**
     * Searches official documentation scopes in parallel with one sparse query encoding.
     *
     * @param query citation-discovery query text
     * @param topK maximum number of citation candidates returned for each scope
     * @param retrievalConstraints official-documentation scopes searched independently
     * @return citation outcomes in the same order as the supplied scopes
     */
    public List<SearchOutcome> searchDocumentationCitationsOutcomes(
            String query, int topK, List<RetrievalConstraint> retrievalConstraints) {
        Objects.requireNonNull(query, "query");
        List<RetrievalConstraint> requiredRetrievalConstraints = requireRetrievalConstraints(retrievalConstraints);
        if (query.isBlank() || topK <= 0) {
            return requiredRetrievalConstraints.stream()
                    .map(ignoredConstraint -> new SearchOutcome(List.of(), List.of()))
                    .toList();
        }

        boolean exactOverloadSelected = queryEncoding.hasExactJavaApiOverload(query);
        HybridQueryConfig queryConfig = HybridQueryConfig.fromProperties(appProperties);
        String documentationCollectionName =
                appProperties.getQdrant().getCollections().getDocs();
        long queryDeadlineNanos = queryDeadlineNanos(queryConfig.queryTimeout());
        List<CollectionQueryDispatch> queryDispatches = new ArrayList<>(requiredRetrievalConstraints.size());
        if (exactOverloadSelected) {
            for (RetrievalConstraint requiredRetrievalConstraint : requiredRetrievalConstraints) {
                Filter exactCitationFilter = queryEncoding
                        .constraintBuilder()
                        .buildCitationFilter(requiredRetrievalConstraint, query)
                        .orElseThrow();
                CompletableFuture<List<ScoredPoint>> documentationScrollFuture = dispatchExactDocumentationCitation(
                        documentationCollectionName, exactCitationFilter, topK, queryDeadlineNanos);
                queryDispatches.add(new CollectionQueryDispatch(
                        Map.of(documentationCollectionName, documentationScrollFuture),
                        queryConfig.queryTimeout(),
                        queryDeadlineNanos));
            }
            return collectSearchOutcomes(queryDispatches, topK);
        }

        String expandedCitationQuery = queryEncoding.expandSparseCitationQuery(query);
        LexicalSparseVectorEncoder.SparseVector sparseVector =
                queryEncoding.sparseVectorEncoder().encode(expandedCitationQuery);
        if (sparseVector.indices().isEmpty()) {
            return requiredRetrievalConstraints.stream()
                    .map(ignoredConstraint -> new SearchOutcome(List.of(), List.of()))
                    .toList();
        }

        for (RetrievalConstraint requiredRetrievalConstraint : requiredRetrievalConstraints) {
            Optional<Filter> retrievalFilter =
                    queryEncoding.constraintBuilder().buildCitationFilter(requiredRetrievalConstraint, query);
            EncodedCitationQuery encodedCitationQuery = new EncodedCitationQuery(sparseVector, retrievalFilter);
            List<String> officialDocumentCollectionNames = officialDocumentCollectionNames();
            Map<String, CompletableFuture<List<ScoredPoint>>> futuresByCollection =
                    LinkedHashMap.newLinkedHashMap(officialDocumentCollectionNames.size());
            for (String officialDocumentCollectionName : officialDocumentCollectionNames) {
                QueryPoints queryRequest = buildDocumentationCitationQueryRequest(
                        officialDocumentCollectionName, encodedCitationQuery, queryConfig, topK);
                CompletableFuture<List<ScoredPoint>> documentationQueryFuture =
                        dispatchQueryBeforeDeadline(queryRequest, queryDeadlineNanos);
                futuresByCollection.put(officialDocumentCollectionName, documentationQueryFuture);
            }
            queryDispatches.add(
                    new CollectionQueryDispatch(futuresByCollection, queryConfig.queryTimeout(), queryDeadlineNanos));
        }
        return collectSearchOutcomes(queryDispatches, topK);
    }

    private CompletableFuture<List<ScoredPoint>> dispatchExactDocumentationCitation(
            String documentationCollectionName,
            Filter exactCitationFilter,
            int citationCandidateLimit,
            long queryDeadlineNanos) {
        ScrollPoints scrollRequest = queryRequestFactory.buildExactCitationScroll(
                documentationCollectionName, exactCitationFilter, citationCandidateLimit);
        return dispatchScrollBeforeDeadline(scrollRequest, queryDeadlineNanos);
    }

    private List<SearchOutcome> collectSearchOutcomes(List<CollectionQueryDispatch> queryDispatches, int topK) {
        try {
            return queryDispatches.stream()
                    .map(queryDispatch -> collectSearchOutcome(queryDispatch, topK))
                    .toList();
        } catch (HybridSearchPartialFailureException searchFailure) {
            queryDispatches.forEach(HybridSearchService::cancelPendingQueries);
            throw searchFailure;
        }
    }

    private static List<RetrievalConstraint> requireRetrievalConstraints(
            List<RetrievalConstraint> retrievalConstraints) {
        Objects.requireNonNull(retrievalConstraints, "retrievalConstraints");
        if (retrievalConstraints.isEmpty()) {
            throw new IllegalArgumentException("retrievalConstraints cannot be empty");
        }
        return retrievalConstraints.stream()
                .map(retrievalConstraint -> Objects.requireNonNull(retrievalConstraint, "retrievalConstraint"))
                .toList();
    }

    private SearchOutcome collectSearchOutcome(CollectionQueryDispatch queryDispatch, int topK) {
        Map<String, ScoredPointMatch> scoredPointsByUuid = new LinkedHashMap<>();
        List<HybridSearchPartialFailureException.CollectionSearchFailure> collectionFailures = new ArrayList<>();
        List<Throwable> dependencyFailures = new ArrayList<>();
        collectFanOutResults(queryDispatch, scoredPointsByUuid, collectionFailures, dependencyFailures);

        if (!collectionFailures.isEmpty()) {
            throw new HybridSearchPartialFailureException(
                    "Qdrant retrieval failed for " + collectionFailures.size() + " collection(s)",
                    collectionFailures,
                    dependencyFailures);
        }

        List<Document> rankedDocuments = scoredPointsByUuid.values().stream()
                .sorted(Comparator.comparingDouble(ScoredPointMatch::score).reversed())
                .limit(topK)
                .map(scoredPointMatch -> QdrantScoredPointDocumentMapper.toDocument(
                        scoredPointMatch.point(),
                        scoredPointMatch.id(),
                        scoredPointMatch.score(),
                        scoredPointMatch.collectionName()))
                .toList();
        List<HybridSearchNotice> retrievalNotices =
                collectionFailures.stream().map(HybridSearchService::toNotice).toList();
        return new SearchOutcome(rankedDocuments, retrievalNotices);
    }

    private static long queryDeadlineNanos(Duration queryTimeout) {
        return System.nanoTime() + queryTimeout.toNanos();
    }

    private CompletableFuture<List<ScoredPoint>> dispatchQueryBeforeDeadline(
            QueryPoints queryRequest, long queryDeadlineNanos) {
        long remainingQueryDurationNanos = queryDeadlineNanos - System.nanoTime();
        if (remainingQueryDurationNanos < MINIMUM_QDRANT_QUERY_DURATION_NANOS) {
            return QdrantFutureAwaiter.exhaustedQueryBudgetFuture();
        }
        Duration remainingQueryDuration = Duration.ofNanos(remainingQueryDurationNanos);
        try {
            return QdrantListenableFutureBridge.toCompletableFuture(
                    qdrantClient.queryAsync(queryRequest, remainingQueryDuration));
        } catch (RuntimeException queryDispatchFailure) {
            return CompletableFuture.failedFuture(queryDispatchFailure);
        }
    }

    private CompletableFuture<List<ScoredPoint>> dispatchScrollBeforeDeadline(
            ScrollPoints scrollRequest, long queryDeadlineNanos) {
        long remainingQueryDurationNanos = queryDeadlineNanos - System.nanoTime();
        if (remainingQueryDurationNanos < MINIMUM_QDRANT_QUERY_DURATION_NANOS) {
            return QdrantFutureAwaiter.exhaustedQueryBudgetFuture();
        }
        Duration remainingQueryDuration = Duration.ofNanos(remainingQueryDurationNanos);
        try {
            return QdrantListenableFutureBridge.transformToCompletableFuture(
                    qdrantClient.scrollAsync(scrollRequest, remainingQueryDuration),
                    scrollResponse -> scrollResponse.getResultList().stream()
                            .map(retrievedPoint -> ScoredPoint.newBuilder()
                                    .setId(retrievedPoint.getId())
                                    .setScore(1.0f)
                                    .putAllPayload(retrievedPoint.getPayloadMap())
                                    .build())
                            .toList());
        } catch (RuntimeException scrollDispatchFailure) {
            return CompletableFuture.failedFuture(scrollDispatchFailure);
        }
    }

    /**
     * Groups Qdrant hybrid query configuration that travels together across search operations.
     *
     * @param denseVectorName Qdrant named vector key for dense embeddings
     * @param sparseVectorName Qdrant named vector key for sparse tokens
     * @param prefetchLimit per-collection prefetch candidate count
     * @param rrfK reciprocal rank fusion k parameter
     * @param queryTimeout shared timeout budget for the complete collection fan-out
     */
    record HybridQueryConfig(
            String denseVectorName, String sparseVectorName, int prefetchLimit, int rrfK, Duration queryTimeout) {

        HybridQueryConfig {
            Objects.requireNonNull(denseVectorName, "denseVectorName");
            Objects.requireNonNull(sparseVectorName, "sparseVectorName");
            Objects.requireNonNull(queryTimeout, "queryTimeout");
        }

        static HybridQueryConfig fromProperties(AppProperties appProperties) {
            return new HybridQueryConfig(
                    appProperties.getQdrant().getDenseVectorName(),
                    appProperties.getQdrant().getSparseVectorName(),
                    appProperties.getQdrant().getPrefetchLimit(),
                    appProperties.getQdrant().getRrfK(),
                    appProperties.getQdrant().getQueryTimeout());
        }
    }

    /**
     * Groups the three query-encoding outputs that always travel together into a single search call.
     *
     * @param denseVector dense embedding vector for nearest-neighbor prefetch
     * @param sparseVector sparse BM25-style token vector for lexical prefetch
     * @param retrievalFilter optional Qdrant metadata filter from retrieval constraints
     */
    private record EncodedQuery(
            float[] denseVector,
            LexicalSparseVectorEncoder.SparseVector sparseVector,
            Optional<Filter> retrievalFilter) {
        EncodedQuery {
            Objects.requireNonNull(denseVector, "denseVector");
            Objects.requireNonNull(sparseVector, "sparseVector");
            Objects.requireNonNull(retrievalFilter, "retrievalFilter");
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (obj == null || getClass() != obj.getClass()) return false;
            EncodedQuery that = (EncodedQuery) obj;
            return java.util.Arrays.equals(denseVector, that.denseVector)
                    && java.util.Objects.equals(sparseVector, that.sparseVector)
                    && java.util.Objects.equals(retrievalFilter, that.retrievalFilter);
        }

        @Override
        public int hashCode() {
            int hash = java.util.Objects.hash(sparseVector, retrievalFilter);
            hash = 31 * hash + java.util.Arrays.hashCode(denseVector);
            return hash;
        }

        @Override
        public String toString() {
            return "EncodedQuery{" + "denseVector="
                    + java.util.Arrays.toString(denseVector) + ", sparseVector="
                    + sparseVector + ", retrievalFilter="
                    + retrievalFilter + '}';
        }
    }

    /** Groups sparse encoding and its exact metadata filter for citation discovery. */
    private record EncodedCitationQuery(
            LexicalSparseVectorEncoder.SparseVector sparseVector, Optional<Filter> retrievalFilter) {
        EncodedCitationQuery {
            Objects.requireNonNull(sparseVector, "sparseVector");
            Objects.requireNonNull(retrievalFilter, "retrievalFilter");
        }
    }

    /** Keeps every collection wait on the timeout deadline established before fan-out dispatch. */
    private record CollectionQueryDispatch(
            Map<String, CompletableFuture<List<ScoredPoint>>> futuresByCollection,
            Duration queryTimeout,
            long queryDeadlineNanos) {
        CollectionQueryDispatch {
            Objects.requireNonNull(futuresByCollection, "futuresByCollection");
            Objects.requireNonNull(queryTimeout, "queryTimeout");
        }
    }

    private QueryPoints buildHybridQueryRequest(
            String collectionName, EncodedQuery encodedQuery, HybridQueryConfig queryConfig, int limit) {
        return queryRequestFactory.buildHybridQuery(
                collectionName,
                encodedQuery.denseVector(),
                encodedQuery.sparseVector(),
                encodedQuery.retrievalFilter(),
                queryConfig.denseVectorName(),
                queryConfig.sparseVectorName(),
                queryConfig.prefetchLimit(),
                queryConfig.rrfK(),
                limit);
    }

    private QueryPoints buildDocumentationCitationQueryRequest(
            String documentationCollectionName,
            EncodedCitationQuery encodedCitationQuery,
            HybridQueryConfig queryConfig,
            int citationCandidateLimit) {
        return queryRequestFactory.buildDocumentationCitationQuery(
                documentationCollectionName,
                encodedCitationQuery.sparseVector(),
                encodedCitationQuery.retrievalFilter(),
                queryConfig.sparseVectorName(),
                citationCandidateLimit);
    }

    private void collectFanOutResults(
            CollectionQueryDispatch queryDispatch,
            Map<String, ScoredPointMatch> scoredPointsByUuid,
            List<HybridSearchPartialFailureException.CollectionSearchFailure> collectionFailures,
            List<Throwable> dependencyFailures) {

        for (Map.Entry<String, CompletableFuture<List<ScoredPoint>>> collectionQueryEntry :
                queryDispatch.futuresByCollection().entrySet()) {
            String collectionName = collectionQueryEntry.getKey();
            CompletableFuture<List<ScoredPoint>> collectionQueryFuture = collectionQueryEntry.getValue();
            try {
                long remainingWaitNanos = Math.max(0L, queryDispatch.queryDeadlineNanos() - System.nanoTime());
                List<ScoredPoint> scoredPoints =
                        QdrantFutureAwaiter.awaitFuture(collectionQueryFuture, remainingWaitNanos);
                mergePoints(scoredPoints, collectionName, scoredPointsByUuid);
            } catch (QdrantFutureAwaiter.QdrantFutureAwaitException awaitFailure) {
                Throwable dependencyFailure = awaitFailure.getCause();
                if (awaitFailure.interrupted()) {
                    cancelPendingQueries(queryDispatch);
                    log.warn("[QDRANT] Search interrupted for collection={}", collectionName);
                    HybridSearchPartialFailureException.CollectionSearchFailure interruptionFailure =
                            new HybridSearchPartialFailureException.CollectionSearchFailure(
                                    collectionName,
                                    "Interrupted",
                                    "Qdrant query was interrupted",
                                    HybridSearchPartialFailureException.FailureDisposition.PERMANENT);
                    throw new HybridSearchPartialFailureException(
                            "Qdrant retrieval was interrupted",
                            List.of(interruptionFailure),
                            List.of(dependencyFailure));
                }
                if (awaitFailure.timedOut()) {
                    collectionQueryFuture.cancel(true);
                    log.warn("[QDRANT] Search timed out for collection={}", collectionName);
                    collectionFailures.add(new HybridSearchPartialFailureException.CollectionSearchFailure(
                            collectionName,
                            "Timeout",
                            "Qdrant query exceeded timeout "
                                    + queryDispatch.queryTimeout().toMillis() + "ms",
                            HybridSearchPartialFailureException.FailureDisposition.TRANSIENT));
                    dependencyFailures.add(dependencyFailure);
                    continue;
                }

                String exceptionType = dependencyFailure.getClass().getSimpleName();
                String failureMessage = dependencyFailure.getMessage();
                log.warn("[QDRANT] Search failed for collection={} (exceptionType={})", collectionName, exceptionType);
                collectionFailures.add(new HybridSearchPartialFailureException.CollectionSearchFailure(
                        collectionName,
                        exceptionType,
                        sanitizeFailureDetails(failureMessage),
                        HybridSearchPartialFailureException.classifyDependencyFailure(dependencyFailure)));
                dependencyFailures.add(dependencyFailure);
            }
        }
    }

    private static void cancelPendingQueries(CollectionQueryDispatch queryDispatch) {
        queryDispatch.futuresByCollection().values().forEach(queryFuture -> queryFuture.cancel(true));
    }

    private static void mergePoints(
            List<ScoredPoint> points, String collectionName, Map<String, ScoredPointMatch> scoredPointsByUuid) {
        for (ScoredPoint point : points) {
            String pointId = extractPointId(point);
            ScoredPointMatch existing = scoredPointsByUuid.get(pointId);
            if (existing == null || point.getScore() > existing.score()) {
                scoredPointsByUuid.put(pointId, new ScoredPointMatch(pointId, point.getScore(), point, collectionName));
            }
        }
    }

    private List<String> allCollectionNames() {
        List<String> coreCollections =
                appProperties.getQdrant().getCollections().all();
        List<String> gitHubCollections = gitHubCollectionDiscovery
                .map(QdrantGitHubCollectionDiscovery::getDiscoveredCollections)
                .orElse(List.of());
        if (gitHubCollections.isEmpty()) {
            return coreCollections;
        }
        List<String> combined = new ArrayList<>(coreCollections.size() + gitHubCollections.size());
        combined.addAll(coreCollections);
        combined.addAll(gitHubCollections);
        return List.copyOf(combined);
    }

    private List<String> collectionNamesFor(RetrievalConstraint retrievalConstraint) {
        if (!isCanonicalOfficialDocumentationScope(retrievalConstraint)) {
            return allCollectionNames();
        }
        return officialDocumentCollectionNames();
    }

    private List<String> officialDocumentCollectionNames() {
        var collections = appProperties.getQdrant().getCollections();
        return List.of(collections.getDocs(), collections.getPdfs());
    }

    private static boolean isCanonicalOfficialDocumentationScope(RetrievalConstraint retrievalConstraint) {
        List<String> officialDocumentationSourceIdentities = DocsSourceRegistry.officialDocumentationSourceIdentities();
        return "official".equals(retrievalConstraint.sourceKind())
                && !retrievalConstraint.docSet().isEmpty()
                && officialDocumentationSourceIdentities.containsAll(retrievalConstraint.docSet());
    }

    private static String extractPointId(ScoredPoint point) {
        if (point.getId().hasUuid()) {
            return point.getId().getUuid();
        }
        return String.valueOf(point.getId().getNum());
    }

    private static HybridSearchNotice toNotice(
            HybridSearchPartialFailureException.CollectionSearchFailure collectionSearchFailure) {
        String summary = "Partial retrieval failure in collection " + collectionSearchFailure.collectionName();
        String details = collectionSearchFailure.failureType() + ": " + collectionSearchFailure.failureDetails();
        return new HybridSearchNotice(summary, details);
    }

    private static String sanitizeFailureDetails(String failureDetails) {
        if (failureDetails == null || failureDetails.isBlank()) {
            return "";
        }
        String flattenedFailure =
                failureDetails.replace('\n', ' ').replace('\r', ' ').trim();
        if (flattenedFailure.length() <= MAX_FAILURE_DETAIL_LENGTH) {
            return flattenedFailure;
        }
        return flattenedFailure.substring(0, MAX_FAILURE_DETAIL_LENGTH) + "...";
    }

    private record ScoredPointMatch(String id, double score, ScoredPoint point, String collectionName) {}
}
