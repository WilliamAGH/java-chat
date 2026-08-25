package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.williamcallahan.javachat.config.AppProperties;
import com.williamcallahan.javachat.config.QdrantCollectionNames;
import com.williamcallahan.javachat.config.QdrantConnectionProperties;
import com.williamcallahan.javachat.config.QdrantProperties;
import com.williamcallahan.javachat.config.QdrantRestConnection;
import com.williamcallahan.javachat.service.ingestion.IngestionStorageServices;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

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

    @Test
    void reportsMissingCollisionSiblingThroughBoundedCitationFilter(@TempDir Path parsedDirectory) throws IOException {
        String canonicalUrl = "https://github.com/FasterXML/jackson-databind/blob/main/ObjectMapper.java";
        String siblingUrl = canonicalUrl + "?java-chat-mirror=nested";
        String canonicalHash = "a".repeat(FULL_CHUNK_HASH_LENGTH);
        String siblingHash = "b".repeat(FULL_CHUNK_HASH_LENGTH);
        LocalStoreService localStoreService = mock(LocalStoreService.class);
        when(localStoreService.getParsedDir()).thenReturn(parsedDirectory);
        when(localStoreService.toSafeName(canonicalUrl)).thenReturn("canonical");
        when(localStoreService.toSafeName(siblingUrl)).thenReturn("sibling");
        Files.writeString(parsedDirectory.resolve("canonical_0_" + canonicalHash + ".txt"), "canonical");
        Files.writeString(parsedDirectory.resolve("sibling_0_" + siblingHash + ".txt"), "sibling");
        FileIngestionMarkerStore fileIngestionMarkerStore = mock(FileIngestionMarkerStore.class);
        when(fileIngestionMarkerStore.storageUrlsForCanonicalCitation(canonicalUrl))
                .thenReturn(Set.of(canonicalUrl, siblingUrl));
        IngestionStorageServices ingestionStorageServices = mock(IngestionStorageServices.class);
        when(ingestionStorageServices.localStore()).thenReturn(localStoreService);
        when(ingestionStorageServices.fileMarkers()).thenReturn(fileIngestionMarkerStore);
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
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer qdrantServer =
                MockRestServiceServer.bindTo(restTemplate).build();
        for (String collectionName : List.of("books", "docs", "articles", "pdfs")) {
            String points = collectionName.equals("docs")
                    ? "[{\"payload\":{\"url\":\"" + canonicalUrl + "\",\"hash\":\"" + canonicalHash + "\"}}]"
                    : "[]";
            qdrantServer
                    .expect(
                            once(),
                            requestTo(qdrantRestConnection.restBaseUrl() + "/collections/" + collectionName
                                    + "/points/scroll"))
                    .andExpect(content().json("""
                            {"filter":{"should":[{"key":"url"},{"key":"citationUrl"}]}}
                            """, JsonCompareMode.LENIENT))
                    .andRespond(withSuccess(
                            "{\"result\":{\"points\":" + points + ",\"next_page_offset\":null}}",
                            MediaType.APPLICATION_JSON));
        }
        AuditService auditService =
                new AuditService(ingestionStorageServices, restTemplate, qdrantRestConnection, appProperties);

        var auditReport = auditService.auditByUrl(canonicalUrl);

        assertFalse(auditReport.ok());
        assertEquals(2, auditReport.parsedCount());
        assertEquals(1, auditReport.qdrantCount());
        assertEquals(List.of(siblingHash), auditReport.missingHashes());
        qdrantServer.verify();
    }

    private static AuditService auditService(Path parsedDirectory) {
        LocalStoreService localStoreService = mock(LocalStoreService.class);
        when(localStoreService.toSafeName("https://docs.example.com/api/foo.html"))
                .thenReturn(SAFE_SOURCE_NAME);
        when(localStoreService.getParsedDir()).thenReturn(parsedDirectory);
        FileIngestionMarkerStore fileIngestionMarkerStore = mock(FileIngestionMarkerStore.class);
        try {
            when(fileIngestionMarkerStore.storageUrlsForCanonicalCitation("https://docs.example.com/api/foo.html"))
                    .thenReturn(Set.of());
        } catch (IOException markerReadFailure) {
            throw new AssertionError(markerReadFailure);
        }
        IngestionStorageServices ingestionStorageServices = mock(IngestionStorageServices.class);
        when(ingestionStorageServices.localStore()).thenReturn(localStoreService);
        when(ingestionStorageServices.fileMarkers()).thenReturn(fileIngestionMarkerStore);
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
        return new AuditService(
                ingestionStorageServices, new RestTemplateBuilder(), qdrantRestConnection, appProperties);
    }
}
