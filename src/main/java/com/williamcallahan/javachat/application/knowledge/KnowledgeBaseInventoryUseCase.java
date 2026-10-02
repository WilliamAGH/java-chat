package com.williamcallahan.javachat.application.knowledge;

import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.QdrantGitHubCollectionDiscovery;
import com.williamcallahan.javachat.domain.knowledge.KnowledgeGroup;
import com.williamcallahan.javachat.domain.knowledge.KnowledgeInventory;
import com.williamcallahan.javachat.service.HybridVectorService;
import com.williamcallahan.javachat.service.PayloadValueCount;
import com.williamcallahan.javachat.service.QdrantCollectionKind;
import com.williamcallahan.javachat.service.QdrantPayloadFieldSchema;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.springframework.stereotype.Service;

/** Builds the complete retrievable inventory from current Qdrant collection state. */
@Service
public final class KnowledgeBaseInventoryUseCase {
    private static final int MAX_CONCURRENT_SOURCE_METADATA_READS = 8;

    private final HybridVectorService hybridVectorService;
    private final Optional<QdrantGitHubCollectionDiscovery> gitHubCollectionDiscovery;

    /** Wires the Qdrant facet and dynamic collection owners used by the complete inventory read. */
    public KnowledgeBaseInventoryUseCase(
            HybridVectorService hybridVectorService,
            Optional<QdrantGitHubCollectionDiscovery> gitHubCollectionDiscovery) {
        this.hybridVectorService = Objects.requireNonNull(hybridVectorService, "hybridVectorService");
        this.gitHubCollectionDiscovery = Objects.requireNonNull(gitHubCollectionDiscovery, "gitHubCollectionDiscovery");
    }

    /** Returns the current inventory or fails instead of returning an incomplete projection. */
    public KnowledgeInventory listKnowledgeInventory() {
        try (ExecutorService inventoryExecutor = Executors.newFixedThreadPool(
                MAX_CONCURRENT_SOURCE_METADATA_READS, Thread.ofVirtual().factory())) {
            List<KnowledgeGroup> knowledgeGroups = new ArrayList<>();
            for (QdrantCollectionKind collectionKind : QdrantCollectionKind.values()) {
                String collectionName = hybridVectorService.resolveCollectionName(collectionKind);
                KnowledgeGroup.Kind groupKind =
                        switch (collectionKind) {
                            case BOOKS -> KnowledgeGroup.Kind.BOOKS;
                            case DOCS -> KnowledgeGroup.Kind.DOCS;
                            case ARTICLES -> KnowledgeGroup.Kind.ARTICLES;
                            case PDFS -> KnowledgeGroup.Kind.PDFS;
                        };
                appendGroups(
                        knowledgeGroups,
                        collectionName,
                        groupKind,
                        QdrantPayloadFieldSchema.DOC_SET_FIELD,
                        inventoryExecutor);
            }
            QdrantGitHubCollectionDiscovery discovery = gitHubCollectionDiscovery.orElseThrow(
                    () -> new IllegalStateException("GitHub collection discovery is unavailable"));
            List<String> gitHubCollections = discovery.refreshDiscoveredCollections();
            for (String gitHubCollection : gitHubCollections.stream().sorted().toList()) {
                appendGroups(
                        knowledgeGroups,
                        gitHubCollection,
                        KnowledgeGroup.Kind.GITHUB,
                        QdrantPayloadFieldSchema.REPO_URL_FIELD,
                        inventoryExecutor);
            }
            return new KnowledgeInventory(knowledgeGroups);
        } catch (IllegalStateException inventoryFailure) {
            throw new KnowledgeInventoryUnavailableException(inventoryFailure);
        }
    }

    private void appendGroups(
            List<KnowledgeGroup> knowledgeGroups,
            String collectionName,
            KnowledgeGroup.Kind groupKind,
            String payloadField,
            ExecutorService inventoryExecutor) {
        List<PayloadValueCount> groupCounts = hybridVectorService.facetPayloadValues(collectionName, payloadField);
        List<Future<KnowledgeGroup>> sourceMetadataReads = groupCounts.stream()
                .map(payloadCount -> inventoryExecutor.submit(
                        () -> knowledgeGroup(collectionName, groupKind, payloadField, payloadCount)))
                .toList();
        for (Future<KnowledgeGroup> sourceMetadataRead : sourceMetadataReads) {
            try {
                knowledgeGroups.add(sourceMetadataRead.get());
            } catch (InterruptedException | ExecutionException metadataFailure) {
                sourceMetadataReads.forEach(remainingRead -> remainingRead.cancel(true));
                if (metadataFailure instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                Throwable cause =
                        metadataFailure instanceof ExecutionException ? metadataFailure.getCause() : metadataFailure;
                if (cause instanceof IllegalStateException inventoryFailure) {
                    throw inventoryFailure;
                }
                throw new IllegalStateException("Knowledge source metadata read failed", cause);
            }
        }
    }

    private KnowledgeGroup knowledgeGroup(
            String collectionName, KnowledgeGroup.Kind groupKind, String payloadField, PayloadValueCount payloadCount) {
        String groupName = payloadCount.payloadValue();
        List<String> canonicalUrls =
                switch (groupKind) {
                    case GITHUB -> List.of(groupName);
                    case BOOKS -> List.of(DocsSourceRegistry.PUBLIC_PDFS_BASE);
                    case DOCS, ARTICLES, PDFS -> {
                        List<String> citationBases = DocsSourceRegistry.citationBasesForDocSet(groupName);
                        if (citationBases.isEmpty()) {
                            throw new IllegalStateException(
                                    "No canonical documentation URL is registered for " + groupName);
                        }
                        yield citationBases;
                    }
                };
        String versionField = groupKind == KnowledgeGroup.Kind.GITHUB
                ? QdrantPayloadFieldSchema.COMMIT_HASH_FIELD
                : QdrantPayloadFieldSchema.DOC_VERSION_FIELD;
        List<String> ingestedVersions =
                hybridVectorService.facetPayloadValues(collectionName, versionField, payloadField, groupName).stream()
                        .map(PayloadValueCount::payloadValue)
                        .filter(version -> !version.isBlank())
                        .toList();
        return new KnowledgeGroup(
                collectionName, groupKind, groupName, canonicalUrls, ingestedVersions, payloadCount.pointCount());
    }
}
