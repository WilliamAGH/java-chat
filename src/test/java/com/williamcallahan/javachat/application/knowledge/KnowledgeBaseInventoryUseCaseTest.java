package com.williamcallahan.javachat.application.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.config.QdrantGitHubCollectionDiscovery;
import com.williamcallahan.javachat.domain.knowledge.KnowledgeGroup;
import com.williamcallahan.javachat.domain.knowledge.KnowledgeInventory;
import com.williamcallahan.javachat.service.HybridVectorService;
import com.williamcallahan.javachat.service.PayloadValueCount;
import com.williamcallahan.javachat.service.QdrantCollectionKind;
import com.williamcallahan.javachat.service.QdrantPayloadFieldSchema;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Verifies current Qdrant groups compose one complete, deterministic inventory. */
class KnowledgeBaseInventoryUseCaseTest {
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
        for (String collection : List.of(BOOKS_COLLECTION, DOCS_COLLECTION, ARTICLES_COLLECTION, PDFS_COLLECTION)) {
            when(hybridVectorService.facetPayloadValues(collection, QdrantPayloadFieldSchema.DOC_SET_FIELD))
                    .thenReturn(List.of());
        }
    }

    @Test
    void composesCurrentGroupsAndAuthoritativeTotal() {
        when(hybridVectorService.facetPayloadValues(DOCS_COLLECTION, QdrantPayloadFieldSchema.DOC_SET_FIELD))
                .thenReturn(List.of(new PayloadValueCount("oracle/javase/25/api", 9)));
        when(hybridVectorService.facetPayloadValues(BOOKS_COLLECTION, QdrantPayloadFieldSchema.DOC_SET_FIELD))
                .thenReturn(List.of(new PayloadValueCount("books/effective-java", 12)));
        when(hybridVectorService.facetPayloadValues(PDFS_COLLECTION, QdrantPayloadFieldSchema.DOC_SET_FIELD))
                .thenReturn(List.of(new PayloadValueCount("specs/jls-25", 4)));
        when(gitHubCollectionDiscovery.refreshDiscoveredCollections()).thenReturn(List.of(GITHUB_COLLECTION));
        when(hybridVectorService.facetPayloadValues(GITHUB_COLLECTION, QdrantPayloadFieldSchema.REPO_URL_FIELD))
                .thenReturn(List.of(new PayloadValueCount("https://github.com/acme/widgets", 6)));

        KnowledgeInventory inventory = new KnowledgeBaseInventoryUseCase(
                        hybridVectorService, Optional.of(gitHubCollectionDiscovery))
                .listKnowledgeInventory();

        assertEquals(31, inventory.totalChunks());
        assertEquals(
                List.of(
                        new KnowledgeGroup(BOOKS_COLLECTION, KnowledgeGroup.Kind.BOOKS, "books/effective-java", 12),
                        new KnowledgeGroup(DOCS_COLLECTION, KnowledgeGroup.Kind.DOCS, "oracle/javase/25/api", 9),
                        new KnowledgeGroup(PDFS_COLLECTION, KnowledgeGroup.Kind.PDFS, "specs/jls-25", 4),
                        new KnowledgeGroup(
                                GITHUB_COLLECTION, KnowledgeGroup.Kind.GITHUB, "https://github.com/acme/widgets", 6)),
                inventory.groups());
    }

    @Test
    void translatesFacetFailureToInventoryUnavailable() {
        when(hybridVectorService.facetPayloadValues(BOOKS_COLLECTION, QdrantPayloadFieldSchema.DOC_SET_FIELD))
                .thenThrow(new IllegalStateException("Qdrant operation failed"));

        KnowledgeBaseInventoryUseCase inventoryUseCase =
                new KnowledgeBaseInventoryUseCase(hybridVectorService, Optional.of(gitHubCollectionDiscovery));

        assertThrows(KnowledgeInventoryUnavailableException.class, inventoryUseCase::listKnowledgeInventory);
    }

    @Test
    void rejectsInventoryWhenGitHubDiscoveryIsUnavailable() {
        KnowledgeBaseInventoryUseCase inventoryUseCase =
                new KnowledgeBaseInventoryUseCase(hybridVectorService, Optional.empty());

        assertThrows(KnowledgeInventoryUnavailableException.class, inventoryUseCase::listKnowledgeInventory);
    }
}
