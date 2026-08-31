package com.williamcallahan.javachat.application.knowledge;

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
import org.springframework.stereotype.Service;

/** Builds the complete retrievable inventory from current Qdrant collection state. */
@Service
public final class KnowledgeBaseInventoryUseCase {
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
        try {
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
                appendGroups(knowledgeGroups, collectionName, groupKind, QdrantPayloadFieldSchema.DOC_SET_FIELD);
            }
            QdrantGitHubCollectionDiscovery discovery = gitHubCollectionDiscovery.orElseThrow(
                    () -> new IllegalStateException("GitHub collection discovery is unavailable"));
            List<String> gitHubCollections = discovery.refreshDiscoveredCollections();
            for (String gitHubCollection : gitHubCollections.stream().sorted().toList()) {
                appendGroups(
                        knowledgeGroups,
                        gitHubCollection,
                        KnowledgeGroup.Kind.GITHUB,
                        QdrantPayloadFieldSchema.REPO_URL_FIELD);
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
            String payloadField) {
        for (PayloadValueCount payloadCount : hybridVectorService.facetPayloadValues(collectionName, payloadField)) {
            knowledgeGroups.add(new KnowledgeGroup(
                    collectionName, groupKind, payloadCount.payloadValue(), payloadCount.pointCount()));
        }
    }
}
