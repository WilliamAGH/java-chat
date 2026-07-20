package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.williamcallahan.javachat.domain.javaapi.JavadocMemberAnchor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/** Verifies document metadata serializes canonical Javadoc member identity only when present. */
class DocumentFactoryTest {

    private static final String SOURCE_URL = "https://docs.example.test/Stream.html";
    private static final String PAGE_TITLE = "Interface Stream<T>";
    private static final String PACKAGE_NAME = "java.util.stream";
    private static final String CONTENT_HASH = "0123456789abcdef";
    private static final String CHUNK_TEXT = "Stream member documentation.";
    private static final String TYPE_PAGE = "Stream.html";
    private static final JavadocMemberAnchor MEMBER_ANCHOR =
            new JavadocMemberAnchor("map(java.util.function.Function)");

    @Test
    void serializesCanonicalMemberAnchor() {
        DocumentFactory documentFactory = new DocumentFactory(new ContentHasher());
        DocumentFactory.ChunkDocumentMetadata chunkMetadata = DocumentFactory.ChunkDocumentMetadata.withAnchor(
                SOURCE_URL, PAGE_TITLE, 0, PACKAGE_NAME, CONTENT_HASH, MEMBER_ANCHOR);

        Document indexedDocument = documentFactory.createDocument(CHUNK_TEXT, chunkMetadata);

        assertEquals(
                MEMBER_ANCHOR.domIdentifier(),
                indexedDocument.getMetadata().get(QdrantPayloadFieldSchema.ANCHOR_FIELD));
        assertEquals(TYPE_PAGE, indexedDocument.getMetadata().get(QdrantPayloadFieldSchema.JAVA_API_TYPE_PAGE_FIELD));
    }

    @Test
    void omitsMemberAnchorForPageOverview() {
        DocumentFactory documentFactory = new DocumentFactory(new ContentHasher());
        DocumentFactory.ChunkDocumentMetadata chunkMetadata = DocumentFactory.ChunkDocumentMetadata.withoutAnchor(
                SOURCE_URL, PAGE_TITLE, 0, PACKAGE_NAME, CONTENT_HASH);

        Document indexedDocument = documentFactory.createDocument(CHUNK_TEXT, chunkMetadata);

        assertFalse(indexedDocument.getMetadata().containsKey(QdrantPayloadFieldSchema.ANCHOR_FIELD));
        assertFalse(indexedDocument.getMetadata().containsKey(QdrantPayloadFieldSchema.JAVA_API_TYPE_PAGE_FIELD));
    }

    @Test
    void retainsTheSourceTypePageForAnAuthoritativeConstructorAnchor() {
        DocumentFactory documentFactory = new DocumentFactory(new ContentHasher());
        DocumentFactory.ChunkDocumentMetadata chunkMetadata = DocumentFactory.ChunkDocumentMetadata.withAnchor(
                "https://docs.example.test/ArrayList.html",
                "Class ArrayList<E>",
                0,
                "java.util",
                CONTENT_HASH,
                new JavadocMemberAnchor("<init>()"));

        Document indexedDocument = documentFactory.createDocument(CHUNK_TEXT, chunkMetadata);

        assertEquals("<init>()", indexedDocument.getMetadata().get(QdrantPayloadFieldSchema.ANCHOR_FIELD));
        assertEquals(
                "ArrayList.html", indexedDocument.getMetadata().get(QdrantPayloadFieldSchema.JAVA_API_TYPE_PAGE_FIELD));
    }
}
