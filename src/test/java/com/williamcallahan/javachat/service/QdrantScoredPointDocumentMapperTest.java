package com.williamcallahan.javachat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.Points.ScoredPoint;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

/** Verifies exact Java API lookup metadata survives a Qdrant payload-to-document read. */
class QdrantScoredPointDocumentMapperTest {

    @Test
    void restoresTheTypePageAndAuthoritativeAnchor() {
        ScoredPoint scoredPoint = ScoredPoint.newBuilder()
                .putPayload(QdrantPayloadFieldSchema.DOC_CONTENT_FIELD, ValueFactory.value("List.of overload"))
                .putPayload(QdrantPayloadFieldSchema.JAVA_API_TYPE_PAGE_FIELD, ValueFactory.value("List.html"))
                .putPayload(QdrantPayloadFieldSchema.ANCHOR_FIELD, ValueFactory.value("of(E,E)"))
                .build();

        Document mappedDocument = QdrantScoredPointDocumentMapper.toDocument(scoredPoint, "point-id", 0.5, "java-docs");

        assertEquals("List.html", mappedDocument.getMetadata().get(QdrantPayloadFieldSchema.JAVA_API_TYPE_PAGE_FIELD));
        assertEquals("of(E,E)", mappedDocument.getMetadata().get(QdrantPayloadFieldSchema.ANCHOR_FIELD));
    }
}
