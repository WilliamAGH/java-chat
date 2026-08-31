package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.config.QdrantGitHubCollectionDiscovery;
import com.williamcallahan.javachat.model.KnowledgeGroup;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies the knowledge-base inventory composes core documentation-set groups and
 * discovered GitHub repository groups in a deterministic order.
 */
class KnowledgeBaseInventoryServiceTest {
    private static final String DOCS_COLLECTION = "java-chat-qwen3-embedding-4b-2560-docs";
    private static final String BOOKS_COLLECTION = "java-chat-qwen3-embedding-4b-2560-books";
    private static final String ARTICLES_COLLECTION = "java-chat-qwen3-embedding-4b-2560-articles";
    private static final String PDFS_COLLECTION = "java-chat-qwen3-embedding-4b-2560-pdfs";
    private static final String GITHUB_COLLECTION = "github-qwen3-embedding-4b-2560-acme-widgets";

    private HybridVectorService hybridVectorService;
    private QdrantGitHubCollectionDiscovery gitHubCollectionDiscovery;

    @BeforeEach
    void setUp() {
        hybridVectorService = mock(HybridVectorService.class);
        gitHubCollectionDiscovery = mock(QdrantGitHubCollectionDiscovery.class);
        when(hybridVectorService.resolveCollectionName(QdrantCollectionKind.BOOKS))
                .thenReturn(BOOKS_COLLECTION);
        when(hybridVectorService.resolveCollectionName(QdrantCollectionKind.DOCS))
                .thenReturn(DOCS_COLLECTION);
        when(hybridVectorService.resolveCollectionName(QdrantCollectionKind.ARTICLES))
                .thenReturn(ARTICLES_COLLECTION);
        when(hybridVectorService.resolveCollectionName(QdrantCollectionKind.PDFS))
                .thenReturn(PDFS_COLLECTION);
        when(hybridVectorService.facetPayloadValues(BOOKS_COLLECTION, QdrantPayloadFieldSchema.DOC_SET_FIELD))
                .thenReturn(List.of());
        when(hybridVectorService.facetPayloadValues(DOCS_COLLECTION, QdrantPayloadFieldSchema.DOC_SET_FIELD))
                .thenReturn(List.of());
        when(hybridVectorService.facetPayloadValues(ARTICLES_COLLECTION, QdrantPayloadFieldSchema.DOC_SET_FIELD))
                .thenReturn(List.of());
        when(hybridVectorService.facetPayloadValues(PDFS_COLLECTION, QdrantPayloadFieldSchema.DOC_SET_FIELD))
                .thenReturn(List.of());
    }

    @Test
    void composesDocSetGroupsAndGitHubRepositories() {
        when(hybridVectorService.facetPayloadValues(DOCS_COLLECTION, QdrantPayloadFieldSchema.DOC_SET_FIELD))
                .thenReturn(List.of(
                        new PayloadValueCount("jetbrains/idea/2025/09", 3),
                        new PayloadValueCount("oracle/javase/25/api", 9)));
        when(hybridVectorService.facetPayloadValues(BOOKS_COLLECTION, QdrantPayloadFieldSchema.DOC_SET_FIELD))
                .thenReturn(List.of(new PayloadValueCount("books/effective-java", 12)));
        when(hybridVectorService.facetPayloadValues(ARTICLES_COLLECTION, QdrantPayloadFieldSchema.DOC_SET_FIELD))
                .thenReturn(List.of());
        when(hybridVectorService.facetPayloadValues(PDFS_COLLECTION, QdrantPayloadFieldSchema.DOC_SET_FIELD))
                .thenReturn(List.of(new PayloadValueCount("specs/jls-25", 4)));
        when(gitHubCollectionDiscovery.getDiscoveredCollections()).thenReturn(List.of(GITHUB_COLLECTION));
        when(hybridVectorService.facetPayloadValues(GITHUB_COLLECTION, QdrantPayloadFieldSchema.REPO_URL_FIELD))
                .thenReturn(List.of(new PayloadValueCount("https://github.com/acme/widgets", 6)));

        KnowledgeBaseInventoryService inventoryService =
                new KnowledgeBaseInventoryService(hybridVectorService, Optional.of(gitHubCollectionDiscovery));

        assertEquals(
                List.of(
                        new KnowledgeGroup(BOOKS_COLLECTION, "BOOKS", "books/effective-java", 12),
                        new KnowledgeGroup(DOCS_COLLECTION, "DOCS", "jetbrains/idea/2025/09", 3),
                        new KnowledgeGroup(DOCS_COLLECTION, "DOCS", "oracle/javase/25/api", 9),
                        new KnowledgeGroup(PDFS_COLLECTION, "PDFS", "specs/jls-25", 4),
                        new KnowledgeGroup(GITHUB_COLLECTION, "GITHUB", "https://github.com/acme/widgets", 6)),
                inventoryService.listKnowledgeGroups());
    }

    @Test
    void toleratesAbsentGitHubDiscoveryBean() {
        KnowledgeBaseInventoryService inventoryService =
                new KnowledgeBaseInventoryService(hybridVectorService, Optional.empty());

        assertTrue(inventoryService.listKnowledgeGroups().isEmpty());
    }

    @Test
    void propagatesFacetFailuresInsteadOfSkippingCollections() {
        when(hybridVectorService.facetPayloadValues(BOOKS_COLLECTION, QdrantPayloadFieldSchema.DOC_SET_FIELD))
                .thenThrow(new IllegalStateException("Qdrant operation failed"));

        KnowledgeBaseInventoryService inventoryService =
                new KnowledgeBaseInventoryService(hybridVectorService, Optional.of(gitHubCollectionDiscovery));

        assertThrows(IllegalStateException.class, inventoryService::listKnowledgeGroups);
    }
}
