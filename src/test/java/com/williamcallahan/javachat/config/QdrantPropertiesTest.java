package com.williamcallahan.javachat.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Verifies Qdrant configuration ownership and defensive collection-name snapshots.
 */
class QdrantPropertiesTest {
    private static final String INITIAL_BOOK_COLLECTION = "books-initial";
    private static final String REPLACEMENT_BOOK_COLLECTION = "books-replacement";
    private static final String DUPLICATE_COLLECTION_NAME = "shared-collection";

    @Test
    void setCollectionsDoesNotRetainCallerOwnedConfiguration() {
        QdrantProperties qdrantProperties = new QdrantProperties();
        QdrantCollectionNames callerOwnedCollectionNames = new QdrantCollectionNames();
        callerOwnedCollectionNames.setBooks(INITIAL_BOOK_COLLECTION);

        qdrantProperties.setCollections(callerOwnedCollectionNames);
        callerOwnedCollectionNames.setBooks(REPLACEMENT_BOOK_COLLECTION);

        assertEquals(INITIAL_BOOK_COLLECTION, qdrantProperties.getCollections().getBooks());
    }

    @Test
    void getCollectionsReturnsAnIsolatedSnapshot() {
        QdrantProperties qdrantProperties = new QdrantProperties();
        QdrantCollectionNames returnedCollectionNames = qdrantProperties.getCollections();
        String configuredBookCollection = returnedCollectionNames.getBooks();
        returnedCollectionNames.setBooks(REPLACEMENT_BOOK_COLLECTION);

        assertEquals(configuredBookCollection, qdrantProperties.getCollections().getBooks());
    }

    @Test
    void validateConfigurationRejectsDuplicateCollectionNames() {
        QdrantProperties qdrantProperties = new QdrantProperties();
        QdrantCollectionNames duplicateCollectionNames = new QdrantCollectionNames();
        duplicateCollectionNames.setBooks(DUPLICATE_COLLECTION_NAME);
        duplicateCollectionNames.setDocs(DUPLICATE_COLLECTION_NAME);
        qdrantProperties.setCollections(duplicateCollectionNames);

        assertThrows(IllegalArgumentException.class, qdrantProperties::validateConfiguration);
    }
}
