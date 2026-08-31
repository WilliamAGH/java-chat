package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.application.search.JavaApiMethodSelector;
import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.ModelConfiguration;
import com.williamcallahan.javachat.config.RetrievalAugmentationConfig;
import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.util.QueryVersionExtractor;
import java.util.ArrayList;
import java.util.Comparator;
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
public class RetrievalService {

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
    private static final String CURRENT_JAVA_API_DOCUMENTATION_DOC_SET =
            DocsSourceRegistry.javaApiDocumentationSources().stream()
                    .max(Comparator.comparingInt(source -> Integer.parseInt(source.javaRelease())))
                    .map(DocsSourceRegistry.JavaApiDocumentationSource::relativeMirrorPath)
                    .orElseThrow();

    private final HybridSearchService hybridSearchService;
    private final AppProperties appProperties;
    private final RerankerService rerankerService;
    private final DocumentFactory documentFactory;

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
            if (queryVersionEvidence.evidenceVersions().isEmpty()
                    && !retrievalConstraint.docVersions().isEmpty()) {
                return new CitationOutcome(List.of(), 0);
            }
            RetrievalConstraint scopedConstraint = new RetrievalConstraint(
                    queryVersionEvidence.evidenceVersions(),
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
                    queryVersionEvidence.evidenceVersions(),
                    stageDeadlineNanos);
            List<Document> orderedCitationCandidates = CitationCandidateRanker.selectPromptContextForCitationQuery(
                    query, CitationCandidateRanker.orderForCitationQuery(query, citationSearchDocuments));
            CitationOutcome candidateCitationOutcome = toCitations(retainRequestedVersionCoverage(
                    orderedCitationCandidates,
                    orderedCitationCandidates,
                    queryVersionEvidence.evidenceVersions(),
                    citationLimit));
            return new CitationOutcome(
                    candidateCitationOutcome.citations().stream()
                            .limit(citationLimit)
                            .toList(),
                    candidateCitationOutcome.failedConversionCount());
        }
        List<String> parsedVersions = QueryVersionExtractor.extractVersionNumbers(query);
        RetrievalConstraint scopedRetrievalConstraint =
                defaultCurrentJavaApiScope(query, retrievalConstraint, parsedVersions);
        List<String> requestedVersions = applicableRequestedVersions(scopedRetrievalConstraint, parsedVersions);
        RetrievalConstraint combinedRetrievalConstraint = scopedRetrievalConstraint.withDocVersions(requestedVersions);
        List<Document> citationSearchDocuments = searchCitationCandidates(
                query, citationCandidateLimit, combinedRetrievalConstraint, requestedVersions, stageDeadlineNanos);
        List<Document> orderedCitationCandidates = CitationCandidateRanker.selectPromptContextForCitationQuery(
                query, CitationCandidateRanker.orderForCitationQuery(query, citationSearchDocuments));
        List<Document> limitedCitationCandidates = retainRequestedVersionCoverage(
                orderedCitationCandidates, orderedCitationCandidates, requestedVersions, citationLimit);
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
            RetrievalConstraint retrievalConstraint = new RetrievalConstraint(
                    queryVersionEvidence.evidenceVersions(),
                    "official",
                    queryVersionEvidence.docType(),
                    queryVersionEvidence.sourceName(),
                    queryVersionEvidence.docSets());
            return retrieveOutcome(
                    query,
                    retrievalConstraint,
                    queryVersionEvidence.requestedJavaVersions(),
                    queryVersionEvidence.evidenceVersions(),
                    NO_PROGRESS_LISTENER,
                    retrievalStageDeadlineNanos());
        }
        List<String> parsedVersions = QueryVersionExtractor.extractVersionNumbers(query);
        List<String> evidenceVersions = evidenceVersionsFor(parsedVersions);
        RetrievalConstraint retrievalConstraint = officialDocumentationConstraint.withDocVersions(evidenceVersions);
        return retrieveOutcome(
                query,
                retrievalConstraint,
                parsedVersions,
                evidenceVersions,
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
            if (queryVersionEvidence.evidenceVersions().isEmpty()
                    && !retrievalConstraint.docVersions().isEmpty()) {
                return new RetrievalOutcome(List.of(), List.of());
            }
            RetrievalConstraint scopedConstraint = new RetrievalConstraint(
                    queryVersionEvidence.evidenceVersions(),
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
                    queryVersionEvidence.evidenceVersions(),
                    progressListener,
                    stageDeadlineNanos);
        }
        List<String> parsedVersions = QueryVersionExtractor.extractVersionNumbers(query);
        RetrievalConstraint scopedRetrievalConstraint =
                defaultCurrentJavaApiScope(query, retrievalConstraint, parsedVersions);
        List<String> evidenceVersions = applicableRequestedVersions(scopedRetrievalConstraint, parsedVersions);
        RetrievalConstraint combinedRetrievalConstraint = scopedRetrievalConstraint.withDocVersions(evidenceVersions);
        return retrieveOutcome(
                query,
                combinedRetrievalConstraint,
                parsedVersions,
                evidenceVersions,
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
            List<String> evidenceVersions,
            Consumer<RetrievalNotice> progressListener,
            long stageDeadlineNanos) {
        progressListener.accept(new RetrievalNotice(RETRIEVAL_SEARCH_STATUS_SUMMARY, RETRIEVAL_SEARCH_STATUS_DETAILS));
        CandidateRetrieval candidateRetrieval =
                retrieveCandidates(query, retrievalConstraint, requestedVersions, evidenceVersions, stageDeadlineNanos);

        int returnDocumentLimit = appProperties.getRag().getSearchReturnK();
        List<Document> promptDocuments;
        if (requiresJavaMemberEvidence(query, retrievalConstraint)) {
            List<Document> javaMemberDocuments =
                    CitationCandidateRanker.selectPromptContextForCitationQuery(query, candidateRetrieval.documents());
            promptDocuments = evidenceVersions.isEmpty()
                    ? javaMemberDocuments.stream().limit(returnDocumentLimit).toList()
                    : retainRequestedVersionCoverage(
                            javaMemberDocuments, javaMemberDocuments, evidenceVersions, returnDocumentLimit);
        } else {
            progressListener.accept(
                    new RetrievalNotice(RETRIEVAL_RERANK_STATUS_SUMMARY, RETRIEVAL_RERANK_STATUS_DETAILS));
            List<Document> reranked = rerankerService.rerank(
                    query, candidateRetrieval.documents(), returnDocumentLimit, stageDeadlineNanos);
            requireRemainingStageBudget(stageDeadlineNanos);
            promptDocuments = retainRequestedVersionCoverage(
                    reranked, candidateRetrieval.documents(), evidenceVersions, returnDocumentLimit);
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
            List<String> evidenceVersions,
            long stageDeadlineNanos) {
        String boostedQuery = QueryVersionExtractor.boostQueryWithVersionContext(query, requestedVersions);
        int baseTopK = Math.max(1, appProperties.getRag().getSearchTopK());
        List<Document> retrievedDocuments = new ArrayList<>();
        List<RetrievalNotice> retrievalNotices = new ArrayList<>();
        if (requiresJavaMemberEvidence(query, retrievalConstraint)) {
            List<Document> citationCandidates = searchCitationCandidates(
                    query, baseTopK, retrievalConstraint, evidenceVersions, stageDeadlineNanos);
            List<Document> javaMemberDocuments = CitationCandidateRanker.selectPromptContextForCitationQuery(
                    query, CitationCandidateRanker.orderForCitationQuery(query, citationCandidates));
            return new CandidateRetrieval(
                    deduplicateByVersionAndContentHashThenHashlessCanonicalUrl(javaMemberDocuments), List.of());
        }
        if (evidenceVersions.isEmpty()) {
            appendSearchOutcome(
                    hybridSearchService.searchOutcome(boostedQuery, baseTopK, retrievalConstraint, stageDeadlineNanos),
                    retrievedDocuments,
                    retrievalNotices);
        } else {
            List<RetrievalConstraint> versionConstraints = evidenceVersions.stream()
                    .map(evidenceVersion -> retrievalConstraint.withDocVersions(List.of(evidenceVersion)))
                    .toList();
            List<HybridSearchService.SearchOutcome> versionSearchOutcomes =
                    hybridSearchService.searchOutcomes(boostedQuery, baseTopK, versionConstraints, stageDeadlineNanos);
            for (int versionIndex = 0; versionIndex < evidenceVersions.size(); versionIndex++) {
                HybridSearchService.SearchOutcome versionSearchOutcome = versionSearchOutcomes.get(versionIndex);
                appendSearchOutcome(versionSearchOutcome, retrievedDocuments, retrievalNotices);
            }
        }
        List<Document> deduplicatedCandidates =
                deduplicateByVersionAndContentHashThenHashlessCanonicalUrl(retrievedDocuments);
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
     * Uses the current Java API documentation source for a generic broad official-doc request.
     *
     * <p>Historical Java API mirrors contain near-duplicate API pages and dominate the broad
     * documentation corpus. Generic requests therefore use the newest Java API source while
     * retaining every non-Java documentation set. Explicit release requests, caller-owned release
     * filters, and exact-overload lookups retain their full source scope for historical evidence.</p>
     */
    private static RetrievalConstraint defaultCurrentJavaApiScope(
            String query, RetrievalConstraint retrievalConstraint, List<String> parsedVersions) {
        boolean hasBroadOfficialJavaApiScope = "official".equals(retrievalConstraint.sourceKind())
                && retrievalConstraint.docSet().containsAll(JAVA_API_DOCUMENTATION_DOC_SETS);
        boolean preservesHistoricalJavaApiEvidence = !parsedVersions.isEmpty()
                || !retrievalConstraint.docVersions().isEmpty()
                || requiresExactJavaOverloadEvidence(query, retrievalConstraint);
        if (!hasBroadOfficialJavaApiScope || preservesHistoricalJavaApiEvidence) {
            return retrievalConstraint;
        }
        List<String> currentJavaApiDocSets = retrievalConstraint.docSet().stream()
                .filter(docSet -> !JAVA_API_DOCUMENTATION_DOC_SETS.contains(docSet)
                        || CURRENT_JAVA_API_DOCUMENTATION_DOC_SET.equals(docSet))
                .toList();
        return retrievalConstraint.withDocSetScope(currentJavaApiDocSets);
    }

    /**
     * Retrieve documents with custom limits for token-constrained models.
     */
    public RetrievalOutcome retrieveWithLimitOutcome(String query, int maxDocuments, int maxTokensPerDocument) {
        List<String> coverageVersions =
                queryVersionEvidence(query, RetrievalConstraint.none()).evidenceVersions();
        return limitRetrievalOutcome(
                retrieveOutcome(query),
                maxDocuments,
                maxTokensPerDocument,
                coverageVersions.isEmpty()
                        ? evidenceVersionsFor(QueryVersionExtractor.extractVersionNumbers(query))
                        : coverageVersions);
    }

    /**
     * Retrieves constrained documents while capping document count and per-document token budget.
     *
     * @param query retrieval query
     * @param maxDocuments maximum number of documents to retain
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
     * @param maxDocuments maximum number of documents to retain
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
     * @param maxDocuments maximum number of documents to retain
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
        List<String> coverageVersions =
                queryVersionEvidence(query, retrievalConstraint).evidenceVersions();
        return limitRetrievalOutcome(
                retrieveOutcome(query, retrievalConstraint, progressListener, stageDeadlineNanos),
                maxDocuments,
                maxTokensPerDocument,
                coverageVersions.isEmpty()
                        ? applicableRequestedVersions(
                                retrievalConstraint, QueryVersionExtractor.extractVersionNumbers(query))
                        : coverageVersions);
    }

    private RetrievalOutcome limitRetrievalOutcome(
            RetrievalOutcome outcome, int maxDocuments, int maxTokensPerDocument, List<String> requestedVersions) {
        List<Document> documents = outcome.documents();
        if (documents.isEmpty()) {
            return outcome;
        }
        int finalDocumentLimit = Math.max(1, maxDocuments);
        List<Document> coveredDocuments = requestedVersions.isEmpty()
                ? documents.stream().limit(finalDocumentLimit).toList()
                : retainRequestedVersionCoverage(documents, documents, requestedVersions, finalDocumentLimit);
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

    private List<Document> deduplicateByVersionAndContentHashThenHashlessCanonicalUrl(List<Document> documents) {
        if (documents.isEmpty()) {
            return documents;
        }
        Set<String> retainedVersionedContentHashes = new HashSet<>();
        Set<String> retainedHashlessCanonicalUrls = new HashSet<>();
        List<Document> deduplicatedDocuments = new ArrayList<>(documents.size());
        int unidentifiedDocumentCount = 0;
        for (Document document : documents) {
            String contentHash = stringMetadataValue(document.getMetadata(), QdrantPayloadFieldSchema.HASH_FIELD);
            if (!contentHash.isBlank()) {
                String documentVersion = documentVersion(document);
                if (!retainedVersionedContentHashes.add(documentVersion + "\u0000" + contentHash)) {
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
                    if (!retainedHashlessCanonicalUrls.add(canonicalDocumentUrl)) {
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
            List<String> requestedVersions,
            long stageDeadlineNanos) {
        if (requestedVersions.isEmpty()) {
            return hybridSearchService
                    .searchDocumentationCitationsOutcome(
                            query, citationCandidateLimit, retrievalConstraint, stageDeadlineNanos)
                    .documents();
        }
        List<RetrievalConstraint> versionConstraints = requestedVersions.stream()
                .map(requestedVersion -> retrievalConstraint.withDocVersions(List.of(requestedVersion)))
                .toList();
        List<HybridSearchService.SearchOutcome> versionCitationOutcomes =
                hybridSearchService.searchDocumentationCitationsOutcomes(
                        query, citationCandidateLimit, versionConstraints, stageDeadlineNanos);
        List<Document> citationCandidates = new ArrayList<>();
        for (int versionIndex = 0; versionIndex < requestedVersions.size(); versionIndex++) {
            List<Document> versionCitationCandidates =
                    versionCitationOutcomes.get(versionIndex).documents();
            citationCandidates.addAll(versionCitationCandidates);
        }
        return deduplicateByVersionAndContentHashThenHashlessCanonicalUrl(citationCandidates);
    }

    private static List<Document> retainRequestedVersionCoverage(
            List<Document> orderedDocuments,
            List<Document> candidateDocuments,
            List<String> requestedVersions,
            int documentLimit) {
        if (requestedVersions.isEmpty()) {
            return List.copyOf(orderedDocuments);
        }
        List<Document> coveredDocuments = new ArrayList<>(
                orderedDocuments.stream().limit(Math.max(0, documentLimit)).toList());
        for (String requestedVersion : requestedVersions) {
            if (coveredDocuments.stream().anyMatch(document -> requestedVersion.equals(documentVersion(document)))) {
                continue;
            }
            Document requiredVersionDocument = candidateDocuments.stream()
                    .filter(document -> requestedVersion.equals(documentVersion(document)))
                    .findFirst()
                    .orElse(null);
            if (requiredVersionDocument == null) {
                continue;
            }
            if (coveredDocuments.size() < documentLimit) {
                coveredDocuments.add(requiredVersionDocument);
                continue;
            }
            int replacementIndex = findReplaceableDocumentIndex(coveredDocuments, requestedVersions);
            if (replacementIndex < 0) {
                continue;
            }
            coveredDocuments.set(replacementIndex, requiredVersionDocument);
        }
        return List.copyOf(coveredDocuments);
    }

    private static int findReplaceableDocumentIndex(List<Document> documents, List<String> requestedVersions) {
        for (int documentIndex = documents.size() - 1; documentIndex >= 0; documentIndex--) {
            String candidateVersion = documentVersion(documents.get(documentIndex));
            if (!requestedVersions.contains(candidateVersion)) {
                return documentIndex;
            }
            long representedVersionCount = documents.stream()
                    .filter(document -> candidateVersion.equals(documentVersion(document)))
                    .count();
            if (representedVersionCount > 1) {
                return documentIndex;
            }
        }
        return -1;
    }

    private static String documentVersion(Document document) {
        return stringMetadataValue(document.getMetadata(), QdrantPayloadFieldSchema.DOC_VERSION_FIELD);
    }

    private static List<String> applicableRequestedVersions(
            RetrievalConstraint retrievalConstraint, List<String> parsedVersions) {
        if (parsedVersions.isEmpty()) {
            return parsedVersions;
        }
        if (retrievalConstraint.docSet().isEmpty()) {
            return evidenceVersionsFor(parsedVersions);
        }
        Set<String> allowedJavaApiVersions = new HashSet<>();
        for (DocsSourceRegistry.JavaApiDocumentationSource javaApiSource :
                DocsSourceRegistry.javaApiDocumentationSources()) {
            if (retrievalConstraint.docSet().contains(javaApiSource.relativeMirrorPath())) {
                allowedJavaApiVersions.add(javaApiSource.javaRelease());
            }
        }
        return evidenceVersionsFor(parsedVersions).stream()
                .filter(allowedJavaApiVersions::contains)
                .toList();
    }

    private static List<String> evidenceVersionsFor(List<String> requestedVersions) {
        return DocsSourceRegistry.javaApiDocumentationSourcesForReleases(requestedVersions).stream()
                .map(DocsSourceRegistry.JavaApiDocumentationSource::javaRelease)
                .toList();
    }

    private static QueryVersionEvidence queryVersionEvidence(String query, RetrievalConstraint retrievalConstraint) {
        List<String> requestedJavaVersions = QueryVersionExtractor.extractVersionNumbers(query);
        List<DocsSourceRegistry.DocumentationSource> dependencyFamilySources =
                DocsSourceRegistry.versionedDocumentationEvidenceAll(query).stream()
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
        List<String> evidenceVersions = Stream.concat(
                        dependencySources.stream().map(DocsSourceRegistry.DocumentationSource::docVersion),
                        javaSources.stream().map(DocsSourceRegistry.JavaApiDocumentationSource::javaRelease))
                .distinct()
                .toList();
        List<String> resolvedDocSets = Stream.concat(
                        dependencyFamilySources.stream().map(DocsSourceRegistry.DocumentationSource::docSet),
                        javaFamilySources.stream()
                                .map(DocsSourceRegistry.JavaApiDocumentationSource::relativeMirrorPath))
                .distinct()
                .toList();
        boolean javaOnlyEvidence = dependencyFamilySources.isEmpty() && !javaFamilySources.isEmpty();
        List<String> docSets = resolvedDocSets.isEmpty()
                ? List.of()
                : (javaOnlyEvidence && !retrievalConstraint.docSet().isEmpty()
                        ? retrievalConstraint.docSet()
                        : (retrievalConstraint.docSet().isEmpty()
                                ? resolvedDocSets
                                : retrievalConstraint.docSet().stream()
                                        .filter(resolvedDocSets::contains)
                                        .toList()));
        return new QueryVersionEvidence(
                requestedJavaVersions,
                evidenceVersions,
                docSets,
                javaOnlyEvidence ? DocsSourceRegistry.JAVA_API_DOCUMENT_TYPE : "",
                javaOnlyEvidence ? "oracle" : "");
    }

    private record QueryVersionEvidence(
            List<String> requestedJavaVersions,
            List<String> evidenceVersions,
            List<String> docSets,
            String docType,
            String sourceName) {}

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
