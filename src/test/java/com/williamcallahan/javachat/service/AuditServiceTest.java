package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.QdrantCollectionNames;
import com.williamcallahan.javachat.config.QdrantConnectionProperties;
import com.williamcallahan.javachat.config.QdrantProperties;
import com.williamcallahan.javachat.config.QdrantRestConnection;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.client.RestTemplateBuilder;

/** Verifies audit discovery recognizes current and legacy parsed chunk names. */
class AuditServiceTest {

    private static final String SAFE_SOURCE_NAME = "https___docs_example_com_api_foo_html";
    private static final int FULL_CHUNK_HASH_LENGTH = 64;
    private static final int LEGACY_CHUNK_HASH_PREFIX_LENGTH = 12;
    private static final int UNSUPPORTED_CHUNK_HASH_LENGTH = 13;

    @Test
    void usesFullPersistedChunkIdentityWithoutRehashingBody(@TempDir Path parsedDirectory) throws IOException {
        String fullHash = "a".repeat(FULL_CHUNK_HASH_LENGTH);
        Path parsedChunk = parsedDirectory.resolve(SAFE_SOURCE_NAME + "_0_" + fullHash + ".txt");
        Files.writeString(parsedChunk, "anchor-aware member text");
        AuditService auditService = auditService(parsedDirectory);

        Set<String> expectedHashes = auditService.getExpectedHashes("https://docs.example.com/api/foo.html");

        assertEquals(Set.of(fullHash), expectedHashes);
    }

    @Test
    void rejectsLegacyTruncatedChunkIdentity(@TempDir Path parsedDirectory) throws IOException {
        String legacyHashPrefix = "b".repeat(LEGACY_CHUNK_HASH_PREFIX_LENGTH);
        Files.writeString(
                parsedDirectory.resolve(SAFE_SOURCE_NAME + "_1_" + legacyHashPrefix + ".txt"),
                "member text whose anchor is no longer recoverable");
        AuditService auditService = auditService(parsedDirectory);

        IllegalStateException legacyIdentityFailure = assertThrows(
                IllegalStateException.class,
                () -> auditService.getExpectedHashes("https://docs.example.com/api/foo.html"));

        assertTrue(legacyIdentityFailure.getMessage().contains("re-ingest"));
    }

    @Test
    void recognizesFullContentHashChunkName() {
        String fullHash = "a".repeat(FULL_CHUNK_HASH_LENGTH);

        assertTrue(AuditService.parsedChunkPattern(SAFE_SOURCE_NAME)
                .matcher(SAFE_SOURCE_NAME + "_0_" + fullHash + ".txt")
                .matches());
    }

    @Test
    void recognizesLegacyHashPrefixChunkName() {
        String legacyHashPrefix = "b".repeat(LEGACY_CHUNK_HASH_PREFIX_LENGTH);

        assertTrue(AuditService.parsedChunkPattern(SAFE_SOURCE_NAME)
                .matcher(SAFE_SOURCE_NAME + "_1_" + legacyHashPrefix + ".txt")
                .matches());
    }

    @Test
    void rejectsUnsupportedHashLength() {
        String malformedHash = "c".repeat(UNSUPPORTED_CHUNK_HASH_LENGTH);

        assertFalse(AuditService.parsedChunkPattern(SAFE_SOURCE_NAME)
                .matcher(SAFE_SOURCE_NAME + "_2_" + malformedHash + ".txt")
                .matches());
    }

    private static AuditService auditService(Path parsedDirectory) {
        LocalStoreService localStoreService = mock(LocalStoreService.class);
        when(localStoreService.toSafeName("https://docs.example.com/api/foo.html"))
                .thenReturn(SAFE_SOURCE_NAME);
        when(localStoreService.getParsedDir()).thenReturn(parsedDirectory);
        AppProperties appProperties = mock(AppProperties.class);
        QdrantProperties qdrantProperties = mock(QdrantProperties.class);
        QdrantCollectionNames collectionNames = mock(QdrantCollectionNames.class);
        when(appProperties.getQdrant()).thenReturn(qdrantProperties);
        when(qdrantProperties.getCollections()).thenReturn(collectionNames);
        when(collectionNames.getBooks()).thenReturn("books");
        when(collectionNames.getDocs()).thenReturn("docs");
        when(collectionNames.getArticles()).thenReturn("articles");
        when(collectionNames.getPdfs()).thenReturn("pdfs");
        QdrantRestConnection qdrantRestConnection =
                new QdrantRestConnection(new QdrantConnectionProperties("localhost", 6334, false, ""));
        return new AuditService(localStoreService, new RestTemplateBuilder(), qdrantRestConnection, appProperties);
    }
}
