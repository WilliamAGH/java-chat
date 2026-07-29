package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.application.search.LexicalSparseVectorEncoder;
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
    private static final String QDRANT_QUERY_TIMEOUT_DETAILS_TEMPLATE = "Qdrant query exceeded timeout %dms";
    private final QdrantQueryExecutor qdrantQueryExecutor;
    private final QueryEncodingServices queryEncoding;
    private final QdrantCollectionScopeResolver collectionScopeResolver;

    /**
     * Wires gRPC client and encoding dependencies for hybrid search.
     *
     * @param qdrantQueryExecutor bounded Qdrant query execution
     * @param queryEncoding grouped query-encoding collaborators
     * @param collectionScopeResolver Qdrant collection selection for retrieval constraints
     */
    public HybridSearchService(
            QdrantQueryExecutor qdrantQueryExecutor,
            QueryEncodingServices queryEncoding,
            QdrantCollectionScopeResolver collectionScopeResolver) {
        this.qdrantQueryExecutor = Objects.requireNonNull(qdrantQueryExecutor, "qdrantQueryExecutor");
        this.queryEncoding = Objects.requireNonNull(queryEncoding, "queryEncoding");
        this.collectionScopeResolver = Objects.requireNonNull(collectionScopeResolver, "collectionScopeResolver");
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
     * Performs hybrid search across all configured collections and returns retrieval notices.
     *
     * @param query search query text
     * @param topK maximum number of results to return
     * @param retrievalConstraint retrieval metadata constraint for server-side filtering
     * @param stageDeadlineNanos absolute {@link System#nanoTime()} deadline shared by every
     *     retrieval stage hop, so this search can never outlive the caller's stage budget
     * @return search outcome containing documents and optional non-fatal notices
     */
    public SearchOutcome searchOutcome(
            String query, int topK, RetrievalConstraint retrievalConstraint, long stageDeadlineNanos) {
        return searchOutcomes(query, topK, List.of(retrievalConstraint), stageDeadlineNanos)
                .getFirst();
    }

    /**
     * Performs one query encoding and parallel Qdrant fan-out for each required metadata scope.
     *
     * <p>Each scope receives its own top-K result budget, so one Java release cannot crowd another
     * out. Every Qdrant request shares one operation deadline: the earlier of the caller-owned
     * {@code stageDeadlineNanos} and the configured query timeout measured from dispatch. The
     * embedding hop is likewise bounded by the remaining stage time, so the inner budgets can
     * never sum past the stage deadline.</p>
     *
     * @param query search query text
     * @param topK maximum number of results returned for each scope
     * @param retrievalConstraints server-side metadata scopes searched with the shared encoding
     * @param stageDeadlineNanos absolute {@link System#nanoTime()} deadline shared by every
     *     retrieval stage hop
     * @return search outcomes in the same order as the supplied scopes
     */
    public List<SearchOutcome> searchOutcomes(
            String query, int topK, List<RetrievalConstraint> retrievalConstraints, long stageDeadlineNanos) {
        Objects.requireNonNull(query, "query");
        List<RetrievalConstraint> requiredRetrievalConstraints = requireRetrievalConstraints(retrievalConstraints);
        if (query.isBlank() || topK <= 0) {
            return requiredRetrievalConstraints.stream()
                    .map(ignoredConstraint -> new SearchOutcome(List.of(), List.of()))
                    .toList();
        }

        float[] denseVector = queryEncoding
                .embeddingClient()
                .embed(query, LlmGatewayTier.LIVE, remainingStageBudget(stageDeadlineNanos));
        LexicalSparseVectorEncoder.SparseVector sparseVector =
                queryEncoding.sparseVectorEncoder().encode(query);
        Duration queryTimeout = qdrantQueryExecutor.queryTimeout();
        List<Map<String, QueryPoints>> queryRequestsByConstraint = new ArrayList<>(requiredRetrievalConstraints.size());
        for (RetrievalConstraint requiredRetrievalConstraint : requiredRetrievalConstraints) {
            List<String> collectionNames = collectionScopeResolver.collectionNamesFor(requiredRetrievalConstraint);
            Optional<Filter> retrievalFilter =
                    queryEncoding.constraintBuilder().buildFilter(requiredRetrievalConstraint);
            EncodedQuery encodedQuery = new EncodedQuery(denseVector, sparseVector, retrievalFilter);
            Map<String, QueryPoints> requestsByCollection = LinkedHashMap.newLinkedHashMap(collectionNames.size());
            for (String collectionName : collectionNames) {
                QueryPoints queryRequest = Objects.requireNonNull(
                        buildHybridQueryRequest(collectionName, encodedQuery, topK), "QueryPoints");
                requestsByCollection.put(collectionName, queryRequest);
            }
            queryRequestsByConstraint.add(requestsByCollection);
        }
        long queryDeadlineNanos = effectiveQueryDeadlineNanos(queryTimeout, stageDeadlineNanos);
        List<CollectionQueryDispatch> queryDispatches = new ArrayList<>(queryRequestsByConstraint.size());
        for (Map<String, QueryPoints> requestsByCollection : queryRequestsByConstraint) {
            Map<String, CompletableFuture<List<ScoredPoint>>> futuresByCollection =
                    LinkedHashMap.newLinkedHashMap(requestsByCollection.size());
            for (Map.Entry<String, QueryPoints> queryRequestEntry : requestsByCollection.entrySet()) {
                CompletableFuture<List<ScoredPoint>> collectionQueryFuture =
                        qdrantQueryExecutor.queryBeforeDeadline(queryRequestEntry.getValue(), queryDeadlineNanos);
                futuresByCollection.put(queryRequestEntry.getKey(), collectionQueryFuture);
            }
            queryDispatches.add(new CollectionQueryDispatch(
                    futuresByCollection, remainingQueryTimeout(queryDeadlineNanos), queryDeadlineNanos));
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
     * @param stageDeadlineNanos absolute {@link System#nanoTime()} deadline shared by every
     *     retrieval stage hop, so citation discovery can never outlive the caller's stage budget
     * @return citation search outcome containing documents
     */
    public SearchOutcome searchDocumentationCitationsOutcome(
            String query, int topK, RetrievalConstraint retrievalConstraint, long stageDeadlineNanos) {
        return searchDocumentationCitationsOutcomes(query, topK, List.of(retrievalConstraint), stageDeadlineNanos)
                .getFirst();
    }

    /**
     * Searches official documentation scopes in parallel with one sparse query encoding.
     *
     * @param query citation-discovery query text
     * @param topK maximum number of citation candidates returned for each scope
     * @param retrievalConstraints official-documentation scopes searched independently
     * @param stageDeadlineNanos absolute {@link System#nanoTime()} deadline shared by every
     *     retrieval stage hop
     * @return citation outcomes in the same order as the supplied scopes
     */
    public List<SearchOutcome> searchDocumentationCitationsOutcomes(
            String query, int topK, List<RetrievalConstraint> retrievalConstraints, long stageDeadlineNanos) {
        Objects.requireNonNull(query, "query");
        List<RetrievalConstraint> requiredRetrievalConstraints = requireRetrievalConstraints(retrievalConstraints);
        if (query.isBlank() || topK <= 0) {
            return requiredRetrievalConstraints.stream()
                    .map(ignoredConstraint -> new SearchOutcome(List.of(), List.of()))
                    .toList();
        }

        boolean exactOverloadSelected = queryEncoding.hasExactJavaApiOverload(query);
        if (exactOverloadSelected) {
            Duration queryTimeout = qdrantQueryExecutor.queryTimeout();
            String documentationCollectionName = collectionScopeResolver.documentationCollectionName();
            List<ScrollPoints> documentationScrollRequests = requiredRetrievalConstraints.stream()
                    .map(requiredRetrievalConstraint -> queryEncoding
                            .constraintBuilder()
                            .buildCitationFilter(requiredRetrievalConstraint, query)
                            .orElseThrow())
                    .map(exactCitationFilter -> qdrantQueryExecutor.buildExactCitationScroll(
                            documentationCollectionName, exactCitationFilter, topK))
                    .toList();
            long queryDeadlineNanos = effectiveQueryDeadlineNanos(queryTimeout, stageDeadlineNanos);
            List<CollectionQueryDispatch> queryDispatches = new ArrayList<>(documentationScrollRequests.size());
            for (ScrollPoints documentationScrollRequest : documentationScrollRequests) {
                CompletableFuture<List<ScoredPoint>> documentationScrollFuture =
                        qdrantQueryExecutor.scrollBeforeDeadline(documentationScrollRequest, queryDeadlineNanos);
                queryDispatches.add(new CollectionQueryDispatch(
                        Map.of(documentationCollectionName, documentationScrollFuture),
                        remainingQueryTimeout(queryDeadlineNanos),
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

        Duration queryTimeout = qdrantQueryExecutor.queryTimeout();
        List<String> officialDocumentCollectionNames = collectionScopeResolver.officialDocumentCollectionNames();
        List<Map<String, QueryPoints>> queryRequestsByConstraint = new ArrayList<>(requiredRetrievalConstraints.size());
        for (RetrievalConstraint requiredRetrievalConstraint : requiredRetrievalConstraints) {
            Optional<Filter> retrievalFilter =
                    queryEncoding.constraintBuilder().buildCitationFilter(requiredRetrievalConstraint, query);
            EncodedCitationQuery encodedCitationQuery = new EncodedCitationQuery(sparseVector, retrievalFilter);
            Map<String, QueryPoints> requestsByCollection =
                    LinkedHashMap.newLinkedHashMap(officialDocumentCollectionNames.size());
            for (String officialDocumentCollectionName : officialDocumentCollectionNames) {
                QueryPoints queryRequest = buildDocumentationCitationQueryRequest(
                        officialDocumentCollectionName, encodedCitationQuery, topK);
                requestsByCollection.put(officialDocumentCollectionName, queryRequest);
            }
            queryRequestsByConstraint.add(requestsByCollection);
        }
        long queryDeadlineNanos = effectiveQueryDeadlineNanos(queryTimeout, stageDeadlineNanos);
        List<CollectionQueryDispatch> queryDispatches = new ArrayList<>(queryRequestsByConstraint.size());
        for (Map<String, QueryPoints> requestsByCollection : queryRequestsByConstraint) {
            Map<String, CompletableFuture<List<ScoredPoint>>> futuresByCollection =
                    LinkedHashMap.newLinkedHashMap(requestsByCollection.size());
            for (Map.Entry<String, QueryPoints> queryRequestEntry : requestsByCollection.entrySet()) {
                CompletableFuture<List<ScoredPoint>> documentationQueryFuture =
                        qdrantQueryExecutor.queryBeforeDeadline(queryRequestEntry.getValue(), queryDeadlineNanos);
                futuresByCollection.put(queryRequestEntry.getKey(), documentationQueryFuture);
            }
            queryDispatches.add(new CollectionQueryDispatch(
                    futuresByCollection, remainingQueryTimeout(queryDeadlineNanos), queryDeadlineNanos));
        }
        return collectSearchOutcomes(queryDispatches, topK);
    }

    /**
     * Bounds one fan-out by the earlier of the stage deadline and the configured query timeout.
     *
     * <p>The configured query timeout keeps its meaning as a per-search ceiling measured from
     * dispatch, while the caller-owned stage deadline tightens it whenever earlier hops already
     * consumed stage time, so nested hop budgets can never sum past the stage.</p>
     */
    private static long effectiveQueryDeadlineNanos(Duration queryTimeout, long stageDeadlineNanos) {
        return Math.min(stageDeadlineNanos, System.nanoTime() + queryTimeout.toNanos());
    }

    private static Duration remainingQueryTimeout(long queryDeadlineNanos) {
        return Duration.ofNanos(Math.max(0L, queryDeadlineNanos - System.nanoTime()));
    }

    private static Duration remainingStageBudget(long stageDeadlineNanos) {
        return Duration.ofNanos(Math.max(0L, stageDeadlineNanos - System.nanoTime()));
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
            Duration effectiveQueryTimeout,
            long queryDeadlineNanos) {
        CollectionQueryDispatch {
            Objects.requireNonNull(futuresByCollection, "futuresByCollection");
            Objects.requireNonNull(effectiveQueryTimeout, "effectiveQueryTimeout");
        }
    }

    private QueryPoints buildHybridQueryRequest(String collectionName, EncodedQuery encodedQuery, int limit) {
        return qdrantQueryExecutor.buildHybridQuery(
                collectionName,
                encodedQuery.denseVector(),
                encodedQuery.sparseVector(),
                encodedQuery.retrievalFilter(),
                limit);
    }

    private QueryPoints buildDocumentationCitationQueryRequest(
            String documentationCollectionName, EncodedCitationQuery encodedCitationQuery, int citationCandidateLimit) {
        return qdrantQueryExecutor.buildDocumentationCitationQuery(
                documentationCollectionName,
                encodedCitationQuery.sparseVector(),
                encodedCitationQuery.retrievalFilter(),
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
                            QDRANT_QUERY_TIMEOUT_DETAILS_TEMPLATE.formatted(
                                    queryDispatch.effectiveQueryTimeout().toMillis()),
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
