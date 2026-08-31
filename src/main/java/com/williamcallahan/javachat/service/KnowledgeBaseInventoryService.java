package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.config.QdrantGitHubCollectionDiscovery;
import com.williamcallahan.javachat.model.KnowledgeGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Lists the ingested bodies of document knowledge across every Qdrant collection.
 *
 * <p>Individual ingestion runs leave no stored identifier, so the durable grouping is what
 * ingestion writes on every chunk: the documentation-set provenance token for the core
 * collections, and one collection per repository for GitHub ingestion. Faceting those fields
 * makes the inventory reflect what is actually retrievable.</p>
 */
@Service
public class KnowledgeBaseInventoryService {
    private static final String GITHUB_COLLECTION_KIND = "GITHUB";

    private final HybridVectorService hybridVectorService;
    private final Optional<QdrantGitHubCollectionDiscovery> gitHubCollectionDiscovery;

    /**
     * Wires the vector-store read owner and the GitHub collection inventory.
     *
     * @param hybridVectorService Qdrant read/write owner providing collection names and faceting
     * @param gitHubCollectionDiscovery discovered GitHub collections; absent in the test profile
     */
    public KnowledgeBaseInventoryService(
            HybridVectorService hybridVectorService,
            Optional<QdrantGitHubCollectionDiscovery> gitHubCollectionDiscovery) {
        this.hybridVectorService = Objects.requireNonNull(hybridVectorService, "hybridVectorService");
        this.gitHubCollectionDiscovery = Objects.requireNonNull(gitHubCollectionDiscovery, "gitHubCollectionDiscovery");
    }

    /**
     * Lists every ingested document group with its chunk count.
     *
     * @return groups ordered by collection kind (books, docs, articles, PDFs) then GitHub
     *     collections by name; groups within a collection are ordered by group name
     */
    public List<KnowledgeGroup> listKnowledgeGroups() {
        List<KnowledgeGroup> knowledgeGroups = new ArrayList<>();
        for (QdrantCollectionKind collectionKind : QdrantCollectionKind.values()) {
            String collectionName = hybridVectorService.resolveCollectionName(collectionKind);
            for (PayloadValueCount docSetCount :
                    hybridVectorService.facetPayloadValues(collectionName, QdrantPayloadFieldSchema.DOC_SET_FIELD)) {
                knowledgeGroups.add(new KnowledgeGroup(
                        collectionName, collectionKind.name(), docSetCount.payloadValue(), docSetCount.pointCount()));
            }
        }
        List<String> discoveredGitHubCollections = gitHubCollectionDiscovery
                .map(QdrantGitHubCollectionDiscovery::getDiscoveredCollections)
                .orElse(List.of());
        for (String gitHubCollectionName :
                discoveredGitHubCollections.stream().sorted().toList()) {
            for (PayloadValueCount repositoryCount : hybridVectorService.facetPayloadValues(
                    gitHubCollectionName, QdrantPayloadFieldSchema.REPO_URL_FIELD)) {
                knowledgeGroups.add(new KnowledgeGroup(
                        gitHubCollectionName,
                        GITHUB_COLLECTION_KIND,
                        repositoryCount.payloadValue(),
                        repositoryCount.pointCount()));
            }
        }
        return List.copyOf(knowledgeGroups);
    }
}
