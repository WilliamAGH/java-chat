package com.williamcallahan.javachat.service;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.DocsSourceRegistry;
import com.williamcallahan.javachat.config.QdrantGitHubCollectionDiscovery;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Resolves the Qdrant collections that own each retrieval constraint.
 */
@Component
final class QdrantCollectionScopeResolver {
    private final AppProperties appProperties;
    private final Optional<QdrantGitHubCollectionDiscovery> gitHubCollectionDiscovery;

    QdrantCollectionScopeResolver(
            AppProperties appProperties, Optional<QdrantGitHubCollectionDiscovery> gitHubCollectionDiscovery) {
        this.appProperties = Objects.requireNonNull(appProperties, "appProperties");
        this.gitHubCollectionDiscovery = Objects.requireNonNull(gitHubCollectionDiscovery, "gitHubCollectionDiscovery");
    }

    List<String> collectionNamesFor(RetrievalConstraint retrievalConstraint) {
        if (!isCanonicalOfficialDocumentationScope(retrievalConstraint)) {
            return allCollectionNames();
        }
        return officialDocumentCollectionNames();
    }

    List<String> officialDocumentCollectionNames() {
        var collectionNames = appProperties.getQdrant().getCollections();
        return List.of(documentationCollectionName(), collectionNames.getPdfs());
    }

    String documentationCollectionName() {
        return appProperties.getQdrant().getCollections().getDocs();
    }

    private List<String> allCollectionNames() {
        List<String> coreCollectionNames =
                appProperties.getQdrant().getCollections().all();
        List<String> gitHubCollectionNames = gitHubCollectionDiscovery
                .map(QdrantGitHubCollectionDiscovery::getDiscoveredCollections)
                .orElse(List.of());
        if (gitHubCollectionNames.isEmpty()) {
            return coreCollectionNames;
        }
        List<String> combinedCollectionNames =
                new ArrayList<>(coreCollectionNames.size() + gitHubCollectionNames.size());
        combinedCollectionNames.addAll(coreCollectionNames);
        combinedCollectionNames.addAll(gitHubCollectionNames);
        return List.copyOf(combinedCollectionNames);
    }

    private static boolean isCanonicalOfficialDocumentationScope(RetrievalConstraint retrievalConstraint) {
        List<String> officialDocumentationSourceIdentities = DocsSourceRegistry.officialDocumentationSourceIdentities();
        return "official".equals(retrievalConstraint.sourceKind())
                && !retrievalConstraint.docSet().isEmpty()
                && officialDocumentationSourceIdentities.containsAll(retrievalConstraint.docSet());
    }
}
