package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.application.search.JavaApiMethodSelector;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.ModelConfiguration;
import com.williamcallahan.javachat.config.RetrievalAugmentationConfig;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.util.QueryVersionExtractor;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Service;

/**
 * Retrieves and reranks context documents for RAG queries and converts them into citation-ready metadata.
 *
 * <p>This implementation performs hybrid retrieval per Qdrant collection (dense + sparse) and
 * fails fast on any dependency failures. It does not fall back to secondary retrieval paths.</p>
 */
@Service
public final class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    private static final int DEBUG_FIRST_DOC_PREVIEW_LENGTH = 200;
    private static final int CITATION_SNIPPET_MAX_LENGTH = 500;
    private static final double TRUNCATION_BREAK_THRESHOLD = 0.8;

    private static final String FILE_URL_PREFIX = "file://";
    private static final char URL_FRAGMENT_DELIMITER = '#';

    /** User-facing progress status emitted when the hybrid library search begins. */
    private static final String RETRIEVAL_SEARCH_STATUS_SUMMARY = "Searching the Java documentation index";

    private static final String RETRIEVAL_SEARCH_STATUS_DETAILS =
            "Embedding your question and matching it against the official JDK documentation, books, and GitHub repositories.";

    /** User-facing progress status emitted when the reranker reviews search matches. */
    private static final String RETRIEVAL_RERANK_STATUS_SUMMARY = "Reviewing the top matches";

    private static final String RETRIEVAL_RERANK_STATUS_DETAILS =
            "Ranking the most relevant passages before writing the answer.";

    /** Listener for callers that run retrieval without a user-facing progress channel. */
    private static final Consumer<RetrievalNotice> NO_PROGRESS_LISTENER = notice -> {};

    private static final Set<String> JAVA_API_DOCUMENTATION_DOC_SETS =
            Set.copyOf(DocsSourceRegistry.javaApiDocumentationSources().stream()
                    .map(DocsSourceRegistry.JavaApiDocumentationSource::relativeMirrorPath)
                    .toList());
    private final HybridSearchService hybridSearchService;
    private final AppProperties appProperties;
    private final RerankerService rerankerService;
    private final DocumentFactory documentFactory;
    private final List<String> defaultJavaApiDocumentationDocSets;

    /**
     * Creates a retrieval service backed by gRPC hybrid search with RRF fusion and a reranker.
     *
     * @param hybridSearchService gRPC-based hybrid search across all collections
     * @param appProperties application configuration
     * @param rerankerService reranker for result ordering
     * @param documentFactory document factory for metadata preservation
     */
    public RetrievalService(
            HybridSearchService hybridSearchService,
            AppProperties appProperties,
            RerankerService rerankerService,
            DocumentFactory documentFactory) {
        this.hybridSearchService = hybridSearchService;
        this.appProperties = appProperties;
        this.rerankerService = rerankerService;
        this.documentFactory = documentFactory;
        String configuredJavaRelease = Integer.toString(appProperties.getDocs().getJdkVersion());
        this.defaultJavaApiDocumentationDocSets =
                DocsSourceRegistry.javaApiDocumentationSourcesForRelease(configuredJavaRelease).stream()
                        .map(DocsSourceRegistry.JavaApiDocumentationSource::relativeMirrorPath)
                        .toList();
    }

    /**
     * Diagnostic notice describing retrieval failures.
     *
     * @param summary short human-readable summary
     * @param details detailed diagnostics suitable for UI display
     */
    public static record RetrievalNotice(String summary, String details) {
        public RetrievalNotice {
            if (summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("Summary cannot be null or blank");
            }
            details = details == null ? "" : details;
        }
    }

    /**
     * Outcome of a retrieval request, including documents and diagnostic notices.
     *
     * <p>Notices are informational only; this service does not swallow dependency failures.</p>
     *
     * @param documents retrieved documents
     * @param notices diagnostic notices for UI consumption
     */
    public static record RetrievalOutcome(List<Document> documents, List<RetrievalNotice> notices) {
        public RetrievalOutcome {
            documents = documents == null ? List.of() : List.copyOf(documents);
            notices = notices == null ? List.of() : List.copyOf(notices);
        }
    }

    /**
     * Retrieves documents for a query using hybrid retrieval and reranking.
     */
    public List<Document> retrieve(String query) {
        return retrieveOutcome(query).documents();
    }

    /**
     * Retrieves documents for a query within the caller-owned metadata constraint.
     *
     * @param query retrieval query
     * @param retrievalConstraint exact server-side constraint for the retrieval
     * @return retrieved and reranked documents
     */
    public List<Document> retrieve(String query, RetrievalConstraint retrievalConstraint) {
        return retrieveOutcome(query, retrievalConstraint).documents();
    }

    /**
     * Retrieves documents within the caller-owned constraint while reporting live progress.
     *
     * <p>The listener receives one notice when the hybrid library search begins and one when the
     * reranker starts reviewing matches, so streaming callers can show which retrieval step is
     * running instead of a single opaque preparation status.</p>
     *
     * @param query retrieval query
     * @param retrievalConstraint exact server-side constraint for the retrieval
     * @param progressListener receives live user-facing retrieval progress notices
     * @return retrieved and reranked documents
     */
    public List<Document> retrieve(
            String query, RetrievalConstraint retrievalConstraint, Consumer<RetrievalNotice> progressListener) {
        return retrieve(query, retrievalConstraint, progressListener, retrievalStageDeadlineNanos());
    }

    /**
     * Retrieves documents within the caller-owned response-preparation deadline.
     *
     * @param query retrieval query
     * @param retrievalConstraint exact server-side constraint for the retrieval
     * @param progressListener receives live user-facing retrieval progress notices
     * @param stageDeadlineNanos absolute {@link System#nanoTime()} response-preparation deadline
     * @return retrieved and reranked documents
     */
    public List<Document> retrieve(
            String query,
            RetrievalConstraint retrievalConstraint,
            Consumer<RetrievalNotice> progressListener,
            long stageDeadlineNanos) {
        return retrieveOutcome(query, retrievalConstraint, progressListener, stageDeadlineNanos)
                .documents();
    }

    /**
     * Discovers static citations from one sparse official-documentation query.
     *
     * <p>Candidate documents are converted and deduplicated by their final citation URL and anchor
     * before the configured citation limit is applied. Chat-answer context retrieval continues to
     * use {@link #retrieve(String)} and its configured hybrid and reranking pipeline.</p>
     *
     * @param query citation-discovery query
     * @param retrievalConstraint exact server-side constraint for the citation sources
     * @return limited citations plus every candidate conversion failure
     */
    public CitationOutcome discoverCitations(String query, RetrievalConstraint retrievalConstraint) {
        Objects.requireNonNull(retrievalConstraint, "retrievalConstraint");
        if (query == null || query.isBlank()) {
            return new CitationOutcome(List.of(), 0);
        }
        int citationLimit = appProperties.getRag().getSearchCitations();
        if (citationLimit <= 0) {
            return new CitationOutcome(List.of(), 0);
        }
        int citationCandidateLimit = Math.max(appProperties.getRag().getSearchTopK(), citationLimit);
        long stageDeadlineNanos = retrievalStageDeadlineNanos();
        QueryVersionEvidence queryVersionEvidence = queryVersionEvidence(query, retrievalConstraint);
        if (!queryVersionEvidence.docSets().isEmpty()) {
            List<String> evidenceVersions = queryVersionEvidence.evidenceVersions();
            if (evidenceVersions.isEmpty() && !retrievalConstraint.docVersions().isEmpty()) {
                return new CitationOutcome(List.of(), 0);
            }
            RetrievalConstraint scopedConstraint = new RetrievalConstraint(
                    evidenceVersions,
                    retrievalConstraint.sourceKind(),
                    queryVersionEvidence.docType().isBlank()
                            ? retrievalConstraint.docType()
                            : queryVersionEvidence.docType(),
                    queryVersionEvidence.sourceName().isBlank()
                            ? retrievalConstraint.sourceName()
                            : queryVersionEvidence.sourceName(),
                    queryVersionEvidence.docSets());
            List<Document> citationSearchDocuments = searchCitationCandidates(
                    query,
                    citationCandidateLimit,
                    scopedConstraint,
                    queryVersionEvidence.evidenceConstraints(),
                    stageDeadlineNanos);
            List<Document> orderedCitationCandidates = CitationCandidateRanker.selectPromptContextForCitationQuery(
                    query, CitationCandidateRanker.orderForCitationQuery(query, citationSearchDocuments));
            CitationOutcome candidateCitationOutcome = toCitations(retainEvidenceSourceCoverage(
                    orderedCitationCandidates,
                    orderedCitationCandidates,
                    queryVersionEvidence.evidenceConstraints(),
                    citationLimit));
            return new CitationOutcome(
                    candidateCitationOutcome.citations().stream()
                            .limit(citationLimit)
                            .toList(),
                    candidateCitationOutcome.failedConversionCount());
        }
        RetrievalConstraint scopedRetrievalConstraint =
                defaultJavaApiScope(query, retrievalConstraint, queryVersionEvidence.requestedJavaVersions());
        List<Document> citationSearchDocuments = searchCitationCandidates(
                query, citationCandidateLimit, scopedRetrievalConstraint, List.of(), stageDeadlineNanos);
        List<Document> orderedCitationCandidates = CitationCandidateRanker.selectPromptContextForCitationQuery(
                query, CitationCandidateRanker.orderForCitationQuery(query, citationSearchDocuments));
        List<Document> limitedCitationCandidates = retainEvidenceSourceCoverage(
                orderedCitationCandidates, orderedCitationCandidates, List.of(), citationLimit);
        CitationOutcome candidateCitationOutcome = toCitations(limitedCitationCandidates);
        List<Citation> limitedCitations = candidateCitationOutcome.citations().stream()
                .limit(citationLimit)
                .toList();
        return new CitationOutcome(limitedCitations, candidateCitationOutcome.failedConversionCount());
    }

    /**
     * Retrieves documents and diagnostic notices for a query.
     */
    public RetrievalOutcome retrieveOutcome(String query) {
        if (query == null || query.isBlank()) {
            return new RetrievalOutcome(List.of(), List.of());
        }
        RetrievalConstraint officialDocumentationConstraint =
                RetrievalConstraint.forOfficialDocSets(DocsSourceRegistry.officialDocumentationSourceIdentities());
        QueryVersionEvidence queryVersionEvidence = queryVersionEvidence(query, officialDocumentationConstraint);
        if (!queryVersionEvidence.docSets().isEmpty()) {
            List<String> evidenceVersions = queryVersionEvidence.evidenceVersions();
            RetrievalConstraint retrievalConstraint = new RetrievalConstraint(
                    evidenceVersions,
                    "official",
                    queryVersionEvidence.docType(),
                    queryVersionEvidence.sourceName(),
                    queryVersionEvidence.docSets());
            return retrieveOutcome(
                    query,
                    retrievalConstraint,
                    queryVersionEvidence.requestedJavaVersions(),
                    queryVersionEvidence.evidenceConstraints(),
                    NO_PROGRESS_LISTENER,
                    retrievalStageDeadlineNanos());
        }
        return retrieveOutcome(
                query,
                officialDocumentationConstraint,
                queryVersionEvidence.requestedJavaVersions(),
                List.of(),
                NO_PROGRESS_LISTENER,
                retrievalStageDeadlineNanos());
    }

    /**
     * Retrieves documents and notices within the caller-owned metadata constraint.
     *
     * <p>Feature-specific source scopes are retained while any query-derived Java version is added
     * to the same server-side Qdrant filter.</p>
     *
     * @param query retrieval query
     * @param retrievalConstraint exact server-side constraint for the retrieval
     * @return retrieval outcome with documents and notices
     */
    public RetrievalOutcome retrieveOutcome(String query, RetrievalConstraint retrievalConstraint) {
        return retrieveOutcome(query, retrievalConstraint, NO_PROGRESS_LISTENER);
    }

    /**
     * Retrieves documents and notices within the caller-owned constraint, reporting live progress.
     *
     * @param query retrieval query
     * @param retrievalConstraint exact server-side constraint for the retrieval
     * @param progressListener receives live user-facing retrieval progress notices
     * @return retrieval outcome with documents and notices
     */
    public RetrievalOutcome retrieveOutcome(
            String query, RetrievalConstraint retrievalConstraint, Consumer<RetrievalNotice> progressListener) {
        return retrieveOutcome(query, retrievalConstraint, progressListener, retrievalStageDeadlineNanos());
    }

    /**
     * Retrieves documents and notices within one caller-owned response-preparation deadline.
     *
     * @param query retrieval query
     * @param retrievalConstraint exact server-side constraint for the retrieval
     * @param progressListener receives live user-facing retrieval progress notices
     * @param stageDeadlineNanos absolute {@link System#nanoTime()} response-preparation deadline
     * @return retrieval outcome with documents and notices
     */
    public RetrievalOutcome retrieveOutcome(
            String query,
            RetrievalConstraint retrievalConstraint,
            Consumer<RetrievalNotice> progressListener,
            long stageDeadlineNanos) {
        Objects.requireNonNull(retrievalConstraint, "retrievalConstraint");
        Objects.requireNonNull(progressListener, "progressListener");
        if (query == null || query.isBlank()) {
            return new RetrievalOutcome(List.of(), List.of());
        }
        QueryVersionEvidence queryVersionEvidence = queryVersionEvidence(query, retrievalConstraint);
        if (!queryVersionEvidence.docSets().isEmpty()) {
            List<String> evidenceVersions = queryVersionEvidence.evidenceVersions();
            if (evidenceVersions.isEmpty() && !retrievalConstraint.docVersions().isEmpty()) {
                return new RetrievalOutcome(List.of(), List.of());
            }
            RetrievalConstraint scopedConstraint = new RetrievalConstraint(
                    evidenceVersions,
                    retrievalConstraint.sourceKind(),
                    queryVersionEvidence.docType().isBlank()
                            ? retrievalConstraint.docType()
                            : queryVersionEvidence.docType(),
                    queryVersionEvidence.sourceName().isBlank()
                            ? retrievalConstraint.sourceName()
                            : queryVersionEvidence.sourceName(),
                    queryVersionEvidence.docSets());
            return retrieveOutcome(
                    query,
                    scopedConstraint,
                    queryVersionEvidence.requestedJavaVersions(),
                    queryVersionEvidence.evidenceConstraints(),
                    progressListener,
                    stageDeadlineNanos);
        }
        List<String> requestedJavaVersions = queryVersionEvidence.requestedJavaVersions();
        RetrievalConstraint scopedRetrievalConstraint =
                defaultJavaApiScope(query, retrievalConstraint, requestedJavaVersions);
        return retrieveOutcome(
                query,
                scopedRetrievalConstraint,
                requestedJavaVersions,
                List.of(),
                progressListener,
                stageDeadlineNanos);
    }

    /**
     * Runs the retrieval stage under one deadline shared by every dependency hop.
     *
     * <p>The stage deadline is computed once here from
     * {@link RetrievalAugmentationConfig#RESPONSE_PREPARATION_TIMEOUT} — the same budget the SSE
     * preparation layer enforces — and handed to the embedding, Qdrant fan-out, and reranker hops.
     * Each hop applies the tighter of its remaining stage time and its own configured cap, so the
     * hop budgets can never sum past the outer preparation deadline.</p>
     */
    private RetrievalOutcome retrieveOutcome(
            String query,
            RetrievalConstraint retrievalConstraint,
            List<String> requestedVersions,
            List<RetrievalConstraint> evidenceConstraints,
            Consumer<RetrievalNotice> progressListener,
            long stageDeadlineNanos) {
        progressListener.accept(new RetrievalNotice(RETRIEVAL_SEARCH_STATUS_SUMMARY, RETRIEVAL_SEARCH_STATUS_DETAILS));
        CandidateRetrieval candidateRetrieval = retrieveCandidates(
                query, retrievalConstraint, requestedVersions, evidenceConstraints, stageDeadlineNanos);

        int returnDocumentLimit = Math.max(appProperties.getRag().getSearchReturnK(), evidenceConstraints.size());
        List<Document> promptDocuments;
        if (requiresJavaMemberEvidence(query, retrievalConstraint)) {
            List<Document> javaMemberDocuments =
                    CitationCandidateRanker.selectPromptContextForCitationQuery(query, candidateRetrieval.documents());
            promptDocuments = evidenceConstraints.isEmpty()
                    ? javaMemberDocuments.stream().limit(returnDocumentLimit).toList()
                    : retainEvidenceSourceCoverage(
                            javaMemberDocuments, javaMemberDocuments, evidenceConstraints, returnDocumentLimit);
        } else {
            progressListener.accept(
                    new RetrievalNotice(RETRIEVAL_RERANK_STATUS_SUMMARY, RETRIEVAL_RERANK_STATUS_DETAILS));
            List<Document> reranked = rerankerService.rerank(
                    query, candidateRetrieval.documents(), returnDocumentLimit, stageDeadlineNanos);
            requireRemainingStageBudget(stageDeadlineNanos);
            promptDocuments = retainEvidenceSourceCoverage(
                    reranked, candidateRetrieval.documents(), evidenceConstraints, returnDocumentLimit);
        }

        if (!promptDocuments.isEmpty()) {
            Map<String, ?> firstDocMetadata = promptDocuments.get(0).getMetadata();
            int metadataSize = firstDocMetadata.size();
            String firstDocumentText =
                    Optional.ofNullable(promptDocuments.get(0).getText()).orElse("");
            int previewLength = Math.min(DEBUG_FIRST_DOC_PREVIEW_LENGTH, firstDocumentText.length());
            log.debug("First doc metadata size: {}", metadataSize);
            log.debug("First doc content preview length: {}", previewLength);
        }
        return new RetrievalOutcome(promptDocuments, candidateRetrieval.notices());
    }

    private CandidateRetrieval retrieveCandidates(
            String query,
            RetrievalConstraint retrievalConstraint,
            List<String> requestedVersions,
            List<RetrievalConstraint> evidenceConstraints,
            long stageDeadlineNanos) {
        String boostedQuery = QueryVersionExtractor.boostQueryWithVersionContext(query, requestedVersions);
        int baseTopK = Math.max(1, appProperties.getRag().getSearchTopK());
        List<Document> retrievedDocuments = new ArrayList<>();
        List<RetrievalNotice> retrievalNotices = new ArrayList<>();
        if (requiresJavaMemberEvidence(query, retrievalConstraint)) {
            List<Document> citationCandidates = searchCitationCandidates(
                    query, baseTopK, retrievalConstraint, evidenceConstraints, stageDeadlineNanos);
            List<Document> javaMemberDocuments = CitationCandidateRanker.selectPromptContextForCitationQuery(
                    query, CitationCandidateRanker.orderForCitationQuery(query, citationCandidates));
            return new CandidateRetrieval(deduplicateBySourceAndContentIdentity(javaMemberDocuments), List.of());
        }
        if (evidenceConstraints.isEmpty()) {
            appendSearchOutcome(
                    hybridSearchService.searchOutcome(boostedQuery, baseTopK, retrievalConstraint, stageDeadlineNanos),
                    retrievedDocuments,
                    retrievalNotices);
        } else {
            List<HybridSearchService.SearchOutcome> evidenceSearchOutcomes =
                    hybridSearchService.searchOutcomes(boostedQuery, baseTopK, evidenceConstraints, stageDeadlineNanos);
            for (HybridSearchService.SearchOutcome evidenceSearchOutcome : evidenceSearchOutcomes) {
                appendSearchOutcome(evidenceSearchOutcome, retrievedDocuments, retrievalNotices);
            }
        }
        List<Document> deduplicatedCandidates = deduplicateBySourceAndContentIdentity(retrievedDocuments);
        return new CandidateRetrieval(deduplicatedCandidates, retrievalNotices);
    }

    private static boolean requiresExactJavaOverloadEvidence(String query, RetrievalConstraint retrievalConstraint) {
        return JavaApiMethodSelector.uniqueExplicitJavaApiMemberFromQuery(query)
                        .flatMap(JavaApiMethodSelector::exactOverloadAnchor)
                        .isPresent()
                && DocsSourceRegistry.javaApiDocumentationSources().stream()
                        .map(DocsSourceRegistry.JavaApiDocumentationSource::relativeMirrorPath)
                        .anyMatch(retrievalConstraint.docSet()::contains);
    }

    private static boolean requiresJavaMemberEvidence(String query, RetrievalConstraint retrievalConstraint) {
        return JavaApiMethodSelector.uniqueExplicitJavaApiMemberFromQuery(query).isPresent()
                && DocsSourceRegistry.javaApiDocumentationSources().stream()
                        .map(DocsSourceRegistry.JavaApiDocumentationSource::relativeMirrorPath)
                        .anyMatch(retrievalConstraint.docSet()::contains);
    }

    /**
     * Uses the configured exact or adjacent Java API sources for a generic broad official-doc request.
     *
     * <p>Historical Java API mirrors contain near-duplicate API pages and dominate the broad
     * documentation corpus. Generic requests therefore use the configured Java API evidence while
     * retaining every non-Java documentation set. Explicit release requests, caller-owned release
     * filters, and exact-overload lookups retain their full source scope for historical evidence.</p>
     */
    private RetrievalConstraint defaultJavaApiScope(
            String query, RetrievalConstraint retrievalConstraint, List<String> parsedVersions) {
        boolean hasBroadOfficialJavaApiScope = "official".equals(retrievalConstraint.sourceKind())
                && retrievalConstraint.docSet().containsAll(JAVA_API_DOCUMENTATION_DOC_SETS);
        boolean preservesHistoricalJavaApiEvidence = !parsedVersions.isEmpty()
                || !retrievalConstraint.docVersions().isEmpty()
                || requiresExactJavaOverloadEvidence(query, retrievalConstraint);
        if (!hasBroadOfficialJavaApiScope || preservesHistoricalJavaApiEvidence) {
            return retrievalConstraint;
        }
        List<String> defaultJavaApiDocSets = retrievalConstraint.docSet().stream()
                .filter(docSet -> !JAVA_API_DOCUMENTATION_DOC_SETS.contains(docSet)
                        || defaultJavaApiDocumentationDocSets.contains(docSet))
                .toList();
        return retrievalConstraint.withDocSetScope(defaultJavaApiDocSets);
    }

    /**
     * Retrieve documents with custom limits for token-constrained models.
     */
    public RetrievalOutcome retrieveWithLimitOutcome(String query, int maxDocuments, int maxTokensPerDocument) {
        QueryVersionEvidence queryVersionEvidence = queryVersionEvidence(query, RetrievalConstraint.none());
        return limitRetrievalOutcome(
                retrieveOutcome(query), maxDocuments, maxTokensPerDocument, queryVersionEvidence.evidenceConstraints());
    }

    /**
     * Retrieves constrained documents while capping document count and per-document token budget.
     *
     * @param query retrieval query
     * @param maxDocuments baseline maximum before required version evidence expands coverage
     * @param maxTokensPerDocument maximum estimated tokens retained per document
     * @param retrievalConstraint exact server-side constraint for the retrieval
     * @return constrained, truncated retrieval outcome
     */
    public RetrievalOutcome retrieveWithLimitOutcome(
            String query, int maxDocuments, int maxTokensPerDocument, RetrievalConstraint retrievalConstraint) {
        return retrieveWithLimitOutcome(
                query, maxDocuments, maxTokensPerDocument, retrievalConstraint, NO_PROGRESS_LISTENER);
    }

    /**
     * Retrieves constrained documents with limits while reporting live retrieval progress.
     *
     * @param query retrieval query
     * @param maxDocuments baseline maximum before required version evidence expands coverage
     * @param maxTokensPerDocument maximum estimated tokens retained per document
     * @param retrievalConstraint exact server-side constraint for the retrieval
     * @param progressListener receives live user-facing retrieval progress notices
     * @return constrained, truncated retrieval outcome
     */
    public RetrievalOutcome retrieveWithLimitOutcome(
            String query,
            int maxDocuments,
            int maxTokensPerDocument,
            RetrievalConstraint retrievalConstraint,
            Consumer<RetrievalNotice> progressListener) {
        return retrieveWithLimitOutcome(
                query,
                maxDocuments,
                maxTokensPerDocument,
                retrievalConstraint,
                progressListener,
                retrievalStageDeadlineNanos());
    }

    /**
     * Retrieves constrained documents under the caller-owned response-preparation deadline.
     *
     * @param query retrieval query
     * @param maxDocuments baseline maximum before required version evidence expands coverage
     * @param maxTokensPerDocument maximum estimated tokens retained per document
     * @param retrievalConstraint exact server-side constraint for the retrieval
     * @param progressListener receives live user-facing retrieval progress notices
     * @param stageDeadlineNanos absolute {@link System#nanoTime()} response-preparation deadline
     * @return constrained, truncated retrieval outcome
     */
    public RetrievalOutcome retrieveWithLimitOutcome(
            String query,
            int maxDocuments,
            int maxTokensPerDocument,
            RetrievalConstraint retrievalConstraint,
            Consumer<RetrievalNotice> progressListener,
            long stageDeadlineNanos) {
        QueryVersionEvidence queryVersionEvidence = queryVersionEvidence(query, retrievalConstraint);
        return limitRetrievalOutcome(
                retrieveOutcome(query, retrievalConstraint, progressListener, stageDeadlineNanos),
                maxDocuments,
                maxTokensPerDocument,
                queryVersionEvidence.evidenceConstraints());
    }

    private RetrievalOutcome limitRetrievalOutcome(
            RetrievalOutcome outcome,
            int maxDocuments,
            int maxTokensPerDocument,
            List<RetrievalConstraint> evidenceConstraints) {
        List<Document> documents = outcome.documents();
        if (documents.isEmpty()) {
            return outcome;
        }
        int finalDocumentLimit = Math.max(Math.max(1, maxDocuments), evidenceConstraints.size());
        List<Document> coveredDocuments = evidenceConstraints.isEmpty()
                ? documents.stream().limit(finalDocumentLimit).toList()
                : retainEvidenceSourceCoverage(documents, documents, evidenceConstraints, finalDocumentLimit);
        List<Document> truncatedDocuments = coveredDocuments.stream()
                .map(document -> truncateDocumentToTokenLimit(document, maxTokensPerDocument))
                .toList();
        return new RetrievalOutcome(truncatedDocuments, outcome.notices());
    }

    /**
     * Retrieves documents while capping document count and per-document token budget.
     */
    public List<Document> retrieveWithLimit(String query, int maxDocuments, int maxTokensPerDocument) {
        return retrieveWithLimitOutcome(query, maxDocuments, maxTokensPerDocument)
                .documents();
    }

    /** Holds hybrid-ranked candidates before the operation-specific final ordering step. */
    private record CandidateRetrieval(List<Document> documents, List<RetrievalNotice> notices) {
        private CandidateRetrieval {
            documents = documents == null ? List.of() : List.copyOf(documents);
            notices = notices == null ? List.of() : List.copyOf(notices);
        }
    }

    private List<Document> deduplicateBySourceAndContentIdentity(List<Document> documents) {
        if (documents.isEmpty()) {
            return documents;
        }
        Set<String> retainedSourceContentIdentities = new HashSet<>();
        Set<String> retainedHashlessSourceUrls = new HashSet<>();
        List<Document> deduplicatedDocuments = new ArrayList<>(documents.size());
        int unidentifiedDocumentCount = 0;
        for (Document document : documents) {
            String contentHash = stringMetadataValue(document.getMetadata(), QdrantPayloadFieldSchema.HASH_FIELD);
            if (!contentHash.isBlank()) {
                String sourceIdentity = documentDocSet(document) + "\u0000" + documentVersion(document);
                if (!retainedSourceContentIdentities.add(sourceIdentity + "\u0000" + contentHash)) {
                    continue;
                }
            } else {
                String documentUrl = stringMetadataValue(document.getMetadata(), QdrantPayloadFieldSchema.URL_FIELD)
                        .trim();
                if (!documentUrl.isBlank()) {
                    String canonicalDocumentUrl = documentUrl.startsWith(FILE_URL_PREFIX)
                            ? DocsSourceRegistry.resolveLocalPath(documentUrl.substring(FILE_URL_PREFIX.length()))
                                    .map(DocsSourceRegistry::canonicalizeHttpDocUrl)
                                    .orElse(documentUrl)
                            : DocsSourceRegistry.normalizeDocUrl(documentUrl);
                    if (!retainedHashlessSourceUrls.add(documentDocSet(document) + "\u0000" + canonicalDocumentUrl)) {
                        continue;
                    }
                } else {
                    unidentifiedDocumentCount++;
                }
            }

            deduplicatedDocuments.add(document);
        }
        if (unidentifiedDocumentCount > 0) {
            log.warn("Dedup kept {} documents with neither hash nor URL metadata", unidentifiedDocumentCount);
        }
        return List.copyOf(deduplicatedDocuments);
    }

    private static long retrievalStageDeadlineNanos() {
        return System.nanoTime() + RetrievalAugmentationConfig.RESPONSE_PREPARATION_TIMEOUT.toNanos();
    }

    private static void requireRemainingStageBudget(long stageDeadlineNanos) {
        long remainingStageNanos = stageDeadlineNanos - System.nanoTime();
        if (remainingStageNanos <= 0) {
            String failureMessage = "Retrieval stage deadline elapsed while reranking";
            throw new RerankingFailureException(failureMessage, new TimeoutException(failureMessage));
        }
    }

    private static void appendSearchOutcome(
            HybridSearchService.SearchOutcome searchOutcome,
            List<Document> retrievedDocuments,
            List<RetrievalNotice> retrievalNotices) {
        retrievedDocuments.addAll(searchOutcome.documents());
        searchOutcome.notices().stream()
                .map(searchNotice -> new RetrievalNotice(searchNotice.summary(), searchNotice.details()))
                .forEach(retrievalNotices::add);
    }

    private List<Document> searchCitationCandidates(
            String query,
            int citationCandidateLimit,
            RetrievalConstraint retrievalConstraint,
            List<RetrievalConstraint> evidenceConstraints,
            long stageDeadlineNanos) {
        if (evidenceConstraints.isEmpty()) {
            return hybridSearchService
                    .searchDocumentationCitationsOutcome(
                            query, citationCandidateLimit, retrievalConstraint, stageDeadlineNanos)
                    .documents();
        }
        List<HybridSearchService.SearchOutcome> evidenceCitationOutcomes =
                hybridSearchService.searchDocumentationCitationsOutcomes(
                        query, citationCandidateLimit, evidenceConstraints, stageDeadlineNanos);
        List<Document> citationCandidates = new ArrayList<>();
        for (HybridSearchService.SearchOutcome evidenceCitationOutcome : evidenceCitationOutcomes) {
            citationCandidates.addAll(evidenceCitationOutcome.documents());
        }
        return deduplicateBySourceAndContentIdentity(citationCandidates);
    }

    private static List<Document> retainEvidenceSourceCoverage(
            List<Document> orderedDocuments,
            List<Document> candidateDocuments,
            List<RetrievalConstraint> evidenceConstraints,
            int documentLimit) {
        if (evidenceConstraints.isEmpty()) {
            return List.copyOf(orderedDocuments);
        }
        List<Document> coveredDocuments = new ArrayList<>(
                orderedDocuments.stream().limit(Math.max(0, documentLimit)).toList());
        for (RetrievalConstraint evidenceConstraint : evidenceConstraints) {
            if (coveredDocuments.stream().anyMatch(document -> matchesEvidenceSource(document, evidenceConstraint))) {
                continue;
            }
            Document requiredEvidenceDocument = candidateDocuments.stream()
                    .filter(document -> matchesEvidenceSource(document, evidenceConstraint))
                    .findFirst()
                    .orElse(null);
            if (requiredEvidenceDocument == null) {
                continue;
            }
            if (coveredDocuments.size() < documentLimit) {
                coveredDocuments.add(requiredEvidenceDocument);
                continue;
            }
            int replacementIndex = findReplaceableDocumentIndex(coveredDocuments, evidenceConstraints);
            if (replacementIndex < 0) {
                continue;
            }
            coveredDocuments.set(replacementIndex, requiredEvidenceDocument);
        }
        return List.copyOf(coveredDocuments);
    }

    private static int findReplaceableDocumentIndex(
            List<Document> documents, List<RetrievalConstraint> evidenceConstraints) {
        for (int documentIndex = documents.size() - 1; documentIndex >= 0; documentIndex--) {
            Document candidateDocument = documents.get(documentIndex);
            Optional<RetrievalConstraint> candidateEvidenceConstraint = evidenceConstraints.stream()
                    .filter(evidenceConstraint -> matchesEvidenceSource(candidateDocument, evidenceConstraint))
                    .findFirst();
            if (candidateEvidenceConstraint.isEmpty()) {
                return documentIndex;
            }
            long representedEvidenceCount = documents.stream()
                    .filter(document -> matchesEvidenceSource(document, candidateEvidenceConstraint.orElseThrow()))
                    .count();
            if (representedEvidenceCount > 1) {
                return documentIndex;
            }
        }
        return -1;
    }

    private static boolean matchesEvidenceSource(Document document, RetrievalConstraint evidenceConstraint) {
        return evidenceConstraint.docVersions().contains(documentVersion(document))
                && evidenceConstraint.docSet().contains(documentDocSet(document));
    }

    private static String documentVersion(Document document) {
        return stringMetadataValue(document.getMetadata(), QdrantPayloadFieldSchema.DOC_VERSION_FIELD);
    }

    private static String documentDocSet(Document document) {
        return stringMetadataValue(document.getMetadata(), QdrantPayloadFieldSchema.DOC_SET_FIELD);
    }

    private static List<String> evidenceVersions(List<RetrievalConstraint> evidenceConstraints) {
        return evidenceConstraints.stream()
                .flatMap(evidenceConstraint -> evidenceConstraint.docVersions().stream())
                .distinct()
                .toList();
    }

    private static QueryVersionEvidence queryVersionEvidence(String query, RetrievalConstraint retrievalConstraint) {
        List<String> requestedJavaVersions = QueryVersionExtractor.extractVersionNumbers(query);
        List<DocsSourceRegistry.VersionedDocumentationEvidence> dependencyEvidence =
                DocsSourceRegistry.versionedDocumentationEvidenceAll(query);
        List<DocsSourceRegistry.DocumentationSource> dependencyFamilySources = dependencyEvidence.stream()
                .flatMap(evidence -> evidence.sources().stream())
                .filter(source -> retrievalConstraint.docSet().isEmpty()
                        || retrievalConstraint.docSet().contains(source.docSet()))
                .toList();
        List<DocsSourceRegistry.DocumentationSource> dependencySources = dependencyFamilySources.stream()
                .filter(source -> retrievalConstraint.docVersions().isEmpty()
                        || retrievalConstraint.docVersions().contains(source.docVersion()))
                .toList();
        List<DocsSourceRegistry.JavaApiDocumentationSource> javaFamilySources =
                DocsSourceRegistry.javaApiDocumentationSourcesForReleases(requestedJavaVersions).stream()
                        .filter(source -> retrievalConstraint.docSet().isEmpty()
                                || retrievalConstraint.docSet().contains(source.relativeMirrorPath()))
                        .toList();
        List<DocsSourceRegistry.JavaApiDocumentationSource> javaSources = javaFamilySources.stream()
                .filter(source -> retrievalConstraint.docVersions().isEmpty()
                        || retrievalConstraint.docVersions().contains(source.javaRelease()))
                .toList();
        List<RetrievalConstraint> evidenceConstraints = Stream.concat(
                        dependencySources.stream()
                                .map(source -> new RetrievalConstraint(
                                        List.of(source.docVersion()),
                                        source.sourceKind(),
                                        source.docType(),
                                        source.docSet(),
                                        List.of(source.docSet()))),
                        javaSources.stream()
                                .map(source -> new RetrievalConstraint(
                                        List.of(source.javaRelease()),
                                        retrievalConstraint.sourceKind(),
                                        DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE,
                                        "oracle",
                                        List.of(source.relativeMirrorPath()))))
                .distinct()
                .toList();
        List<String> resolvedDocSets = Stream.concat(
                        dependencyFamilySources.stream().map(DocsSourceRegistry.DocumentationSource::docSet),
                        javaFamilySources.stream()
                                .map(DocsSourceRegistry.JavaApiDocumentationSource::relativeMirrorPath))
                .distinct()
                .toList();
        boolean javaOnlyEvidence = dependencyFamilySources.isEmpty() && !javaFamilySources.isEmpty();
        List<String> docSets = resolvedDocSets;
        if (!resolvedDocSets.isEmpty() && !retrievalConstraint.docSet().isEmpty()) {
            docSets = javaOnlyEvidence
                    ? retrievalConstraint.docSet()
                    : retrievalConstraint.docSet().stream()
                            .filter(resolvedDocSets::contains)
                            .toList();
        }
        return new QueryVersionEvidence(
                requestedJavaVersions,
                evidenceConstraints,
                docSets,
                javaOnlyEvidence ? DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE : "",
                javaOnlyEvidence ? "oracle" : "");
    }

    private record QueryVersionEvidence(
            List<String> requestedJavaVersions,
            List<RetrievalConstraint> evidenceConstraints,
            List<String> docSets,
            String docType,
            String sourceName) {
        private List<String> evidenceVersions() {
            return RetrievalService.evidenceVersions(evidenceConstraints);
        }
    }

    private static String stringMetadataValue(Map<String, ?> metadata, String key) {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        Object rawMetadataEntry = metadata.get(key);
        return rawMetadataEntry == null ? "" : String.valueOf(rawMetadataEntry);
    }

    private Document truncateDocumentToTokenLimit(Document sourceDocument, int maxTokens) {
        String documentText = sourceDocument.getText();
        if (documentText == null || documentText.isEmpty()) {
            return sourceDocument;
        }
        int maxChars = Math.max(1, maxTokens) * ModelConfiguration.ESTIMATED_CHARS_PER_TOKEN;
        if (documentText.length() <= maxChars) {
            return sourceDocument;
        }
        String truncated = documentText.substring(0, maxChars);
        int lastPeriod = truncated.lastIndexOf('.');
        int lastNewline = truncated.lastIndexOf('\n');
        int breakPoint = Math.max(lastPeriod, lastNewline);
        if (breakPoint > maxChars * TRUNCATION_BREAK_THRESHOLD) {
            truncated = truncated.substring(0, breakPoint + 1);
        }
        truncated += "\n[...content truncated for token limits...]";
        Map<String, ?> truncationMetadata = Map.of("truncated", true, "originalLength", documentText.length());
        return documentFactory.createWithPreservedMetadata(truncated, sourceDocument.getMetadata(), truncationMetadata);
    }

    /**
     * Outcome of converting documents into citations, surfacing any partial conversion failures.
     *
     * <p>Callers must inspect {@code failedConversionCount} or call {@link #citationsOrThrow()} to
     * avoid silently receiving an incomplete citation list. A zero count means all documents
     * converted successfully.</p>
     *
     * @param citations successfully converted citations
     * @param failedConversionCount number of documents that failed citation conversion
     */
    public record CitationOutcome(List<Citation> citations, int failedConversionCount) {
        public CitationOutcome {
            citations = citations == null ? List.of() : List.copyOf(citations);
            if (failedConversionCount < 0) {
                throw new IllegalArgumentException("failedConversionCount cannot be negative");
            }
        }

        /**
         * Returns fully converted citations or rejects the incomplete conversion outcome.
         *
         * <p>Static citation responses cannot represent a source list truthfully when any source
         * document failed conversion, so callers must surface the typed failure instead of returning
         * only the successfully converted subset.</p>
         *
         * @return immutable citations when every source document converted successfully
         * @throws CitationConversionFailureException when one or more source documents failed conversion
         */
        public List<Citation> citationsOrThrow() {
            if (failedConversionCount > 0) {
                throw new CitationConversionFailureException(failedConversionCount);
            }
            return citations;
        }
    }

    /**
     * Builds citations from retrieved documents by normalizing source URLs and trimming snippets for UI display.
     *
     * <p>Returns a {@link CitationOutcome} that includes both the successfully converted citations and
     * a count of conversion failures, ensuring callers are aware of any partial failures.</p>
     */
    public CitationOutcome toCitations(List<Document> documents) {
        if (documents == null || documents.isEmpty()) {
            return new CitationOutcome(List.of(), 0);
        }
        List<Citation> citations = new ArrayList<>();
        Set<String> retainedCitationIdentities = new HashSet<>();
        int failedConversionCount = 0;
        for (Document sourceDocument : documents) {
            if (sourceDocument == null) {
                continue;
            }
            try {
                Map<String, ?> sourceDocMetadata = sourceDocument.getMetadata();
                String rawUrl = stringMetadataValue(sourceDocMetadata, QdrantPayloadFieldSchema.URL_FIELD);
                String title = stringMetadataValue(sourceDocMetadata, QdrantPayloadFieldSchema.TITLE_FIELD);
                String exactAnchor = stringMetadataValue(sourceDocMetadata, QdrantPayloadFieldSchema.ANCHOR_FIELD);
                String refinedCitationUrl = refineCitationUrl(rawUrl, exactAnchor);
                String citationIdentity = citationIdentityFor(rawUrl, refinedCitationUrl);
                if (!citationIdentity.isBlank() && !retainedCitationIdentities.add(citationIdentity)) {
                    continue;
                }
                citations.add(new Citation(
                        fragmentlessCitationSourceUrl(refinedCitationUrl),
                        title,
                        citationAnchor(refinedCitationUrl),
                        trimmedCitationSnippet(sourceDocument.getText())));
            } catch (RuntimeException citationConversionFailure) {
                failedConversionCount++;
                log.warn(
                        "Citation conversion failed (exceptionType={}, docUrl={}, docTitle={})",
                        citationConversionFailure.getClass().getSimpleName(),
                        safeMetadataValueForLogging(sourceDocument.getMetadata(), QdrantPayloadFieldSchema.URL_FIELD),
                        safeMetadataValueForLogging(sourceDocument.getMetadata(), QdrantPayloadFieldSchema.TITLE_FIELD),
                        citationConversionFailure);
            }
        }
        if (failedConversionCount > 0) {
            log.warn(
                    "Citation conversion completed with {} failure(s) out of {} documents",
                    failedConversionCount,
                    documents.size());
        }
        return new CitationOutcome(citations, failedConversionCount);
    }

    /**
     * Converts prompt-context documents with authoritative Java member selection when canonical metadata permits it.
     *
     * <p>One explicit Java API member retains only canonical stored anchors for that member family;
     * an exact signature narrows the family to one anchor. Non-Java, malformed, chained, and
     * multi-selector queries preserve the supplied context order. Every emitted source therefore
     * remains grounded in the model prompt without substituting a sibling Java member.</p>
     */
    public CitationOutcome toCitationsForQuery(String query, List<Document> promptDocuments) {
        if (query == null || promptDocuments == null || promptDocuments.isEmpty()) {
            return toCitations(promptDocuments);
        }
        return toCitations(CitationCandidateRanker.selectPromptContextForCitationQuery(query, promptDocuments));
    }

    /**
     * Converts the exact retained subset of query-aware prompt context into citations.
     *
     * @param userQuery current user query
     * @param promptContextDocuments context documents supplied before provider truncation
     * @param retainedDocumentIds source identities retained after provider truncation
     * @return citations and conversion failures for retained prompt context only
     */
    public CitationOutcome toCitationsForRetainedContext(
            String userQuery, List<Document> promptContextDocuments, List<String> retainedDocumentIds) {
        return toCitationsForQuery(
                userQuery, retainedPromptContextDocuments(promptContextDocuments, retainedDocumentIds));
    }

    /**
     * Converts the exact retained subset of prompt context into citations.
     *
     * @param promptContextDocuments context documents supplied before provider truncation
     * @param retainedDocumentIds source identities retained after provider truncation
     * @return citations and conversion failures for retained prompt context only
     */
    public CitationOutcome toCitationsForRetainedContext(
            List<Document> promptContextDocuments, List<String> retainedDocumentIds) {
        return toCitations(retainedPromptContextDocuments(promptContextDocuments, retainedDocumentIds));
    }

    private static List<Document> retainedPromptContextDocuments(
            List<Document> promptContextDocuments, List<String> retainedDocumentIds) {
        Objects.requireNonNull(retainedDocumentIds, "retainedDocumentIds");
        if (promptContextDocuments == null || promptContextDocuments.isEmpty() || retainedDocumentIds.isEmpty()) {
            return List.of();
        }
        Set<String> retainedDocumentIdSet = Set.copyOf(retainedDocumentIds);
        return promptContextDocuments.stream()
                .filter(promptContextDocument -> retainedDocumentIdSet.contains(promptContextDocument.getId()))
                .toList();
    }

    private static String fragmentlessCitationSourceUrl(String citationUrl) {
        int fragmentDelimiterIndex = citationUrl.indexOf(URL_FRAGMENT_DELIMITER);
        return fragmentDelimiterIndex < 0 ? citationUrl : citationUrl.substring(0, fragmentDelimiterIndex);
    }

    private static String citationAnchor(String citationUrl) {
        int fragmentDelimiterIndex = citationUrl.indexOf(URL_FRAGMENT_DELIMITER);
        return fragmentDelimiterIndex < 0 ? "" : citationUrl.substring(fragmentDelimiterIndex + 1);
    }

    /**
     * Preserves final anchors while retaining opaque identities for unresolved local sources.
     *
     * <p>Unresolved local paths share a redacted display URL, so their fragmentless raw paths remain
     * distinct. All resolvable sources use their final citation URLs so member and page anchors identify
     * separate citations.</p>
     */
    private static String citationIdentityFor(String rawUrl, String citationUrl) {
        String trimmedRawUrl = rawUrl.trim();
        if (trimmedRawUrl.startsWith(FILE_URL_PREFIX)
                && DocsSourceRegistry.resolveLocalPath(trimmedRawUrl.substring(FILE_URL_PREFIX.length()))
                        .isEmpty()) {
            return fragmentlessCitationSourceUrl(trimmedRawUrl);
        }
        return citationUrl;
    }

    /**
     * Canonicalizes a citation source URL and projects its authoritative ingested Javadoc anchor.
     */
    private String refineCitationUrl(String rawUrl, String exactAnchor) {
        String normalizedUrl = DocsSourceRegistry.normalizeDocUrl(rawUrl);
        String citationUrl = normalizedUrl;
        if (!exactAnchor.isBlank()) {
            String citationSourceUrl = fragmentlessCitationSourceUrl(citationUrl);
            String canonicalCitationSourceUrl =
                    citationSourceUrl.startsWith("http://") || citationSourceUrl.startsWith("https://")
                            ? DocsSourceRegistry.canonicalizeHttpDocUrl(citationSourceUrl)
                            : citationSourceUrl;
            return canonicalCitationSourceUrl + URL_FRAGMENT_DELIMITER + exactAnchor;
        }
        if (citationUrl.startsWith("http://") || citationUrl.startsWith("https://")) {
            return DocsSourceRegistry.canonicalizeHttpDocUrl(citationUrl);
        }
        return citationUrl;
    }

    private String trimmedCitationSnippet(String sourceText) {
        String snippetText = Optional.ofNullable(sourceText).orElse("");
        if (snippetText.length() <= CITATION_SNIPPET_MAX_LENGTH) {
            return snippetText;
        }
        return snippetText.substring(0, CITATION_SNIPPET_MAX_LENGTH) + "…";
    }

    private String safeMetadataValueForLogging(Map<String, ?> metadata, String key) {
        if (metadata == null || metadata.isEmpty()) {
            return "";
        }
        Object metadataValue = metadata.get(key);
        if (metadataValue == null) {
            return "";
        }
        try {
            String metadataText = String.valueOf(metadataValue);
            return QdrantPayloadFieldSchema.URL_FIELD.equals(key)
                    ? DocsSourceRegistry.normalizeDocUrl(metadataText)
                    : metadataText;
        } catch (RuntimeException _) {
            return "[unprintable:" + metadataValue.getClass().getSimpleName() + "]";
        }
    }
}
