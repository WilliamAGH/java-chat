package com.williamcallahan.javachat.service;

import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.ScoredPoint;
import java.util.Map;
import java.util.Optional;
import org.springframework.ai.document.Document;

/**
 * Maps Qdrant scored points into Spring AI documents with typed metadata extraction.
 *
 * <p>Hybrid retrieval consumes Qdrant payload values from several collections and must
 * preserve only known metadata keys used by ranking, citations, and UI rendering.</p>
 */
final class QdrantScoredPointDocumentMapper {
    private static final String PAYLOAD_DOC_CONTENT = QdrantPayloadFieldSchema.DOC_CONTENT_FIELD;
    private static final String METADATA_KEY_SCORE = "score";
    private static final String METADATA_KEY_COLLECTION = "collection";

    private QdrantScoredPointDocumentMapper() {}

    /**
     * Converts one scored point into a document enriched with source metadata.
     *
     * @param scoredPoint Qdrant search result point
     * @param pointId unique identifier for deduplicated ranking
     * @param fusedScore reciprocal-rank-fusion score
     * @param sourceCollection collection name that produced the point
     * @return mapped document for downstream reranking and citation building
     */
    static Document toDocument(ScoredPoint scoredPoint, String pointId, double fusedScore, String sourceCollection) {
        Document document = Document.builder()
                .id(pointId)
                .text(extractPayloadString(scoredPoint.getPayloadMap(), PAYLOAD_DOC_CONTENT))
                .build();

        // Keep metadata explicit and typed; do not attempt to round-trip arbitrary payloads.
        applyKnownMetadata(scoredPoint.getPayloadMap(), document);
        document.getMetadata().put(METADATA_KEY_SCORE, fusedScore);
        document.getMetadata().put(METADATA_KEY_COLLECTION, sourceCollection);
        return document;
    }

    private static void applyKnownMetadata(Map<String, Value> payload, Document target) {
        if (payload == null || payload.isEmpty()) {
            return;
        }
        for (String stringField : QdrantPayloadFieldSchema.STRING_METADATA_FIELDS) {
            putIfPresentString(payload, target, stringField);
        }
        for (String integerField : QdrantPayloadFieldSchema.INTEGER_METADATA_FIELDS) {
            putIfPresentInteger(payload, target, integerField);
        }
    }

    private static void putIfPresentString(Map<String, Value> payload, Document target, String key) {
        String stringFieldValue = extractPayloadString(payload, key);
        if (!stringFieldValue.isBlank()) {
            target.getMetadata().put(key, stringFieldValue);
        }
    }

    private static void putIfPresentInteger(Map<String, Value> payload, Document target, String key) {
        extractPayloadInteger(payload, key)
                .ifPresent(integerFieldValue -> target.getMetadata().put(key, integerFieldValue));
    }

    private static String extractPayloadString(Map<String, Value> payload, String key) {
        if (payload == null || payload.isEmpty() || key == null || key.isBlank()) {
            return "";
        }
        Value payloadValue = payload.get(key);
        if (payloadValue == null) {
            return "";
        }
        if (payloadValue.getKindCase() == Value.KindCase.STRING_VALUE) {
            return payloadValue.getStringValue();
        }
        return fromValue(payloadValue).map(String::valueOf).orElse("");
    }

    private static Optional<Integer> extractPayloadInteger(Map<String, Value> payload, String key) {
        if (payload == null || payload.isEmpty() || key == null || key.isBlank()) {
            return Optional.empty();
        }
        Value payloadValue = payload.get(key);
        if (payloadValue == null) {
            return Optional.empty();
        }
        if (payloadValue.getKindCase() == Value.KindCase.INTEGER_VALUE) {
            long integerValue = payloadValue.getIntegerValue();
            if (integerValue > Integer.MAX_VALUE) {
                return Optional.of(Integer.MAX_VALUE);
            }
            if (integerValue < Integer.MIN_VALUE) {
                return Optional.of(Integer.MIN_VALUE);
            }
            return Optional.of((int) integerValue);
        }
        Optional<Object> maybePayloadValue = fromValue(payloadValue);
        return maybePayloadValue
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::intValue);
    }

    private static Optional<Object> fromValue(Value payloadValue) {
        if (payloadValue == null) {
            return Optional.empty();
        }
        return switch (payloadValue.getKindCase()) {
            case STRING_VALUE -> Optional.of(payloadValue.getStringValue());
            case INTEGER_VALUE -> Optional.of(payloadValue.getIntegerValue());
            case DOUBLE_VALUE -> Optional.of(payloadValue.getDoubleValue());
            case BOOL_VALUE -> Optional.of(payloadValue.getBoolValue());
            case NULL_VALUE -> Optional.empty();
            default -> Optional.empty();
        };
    }
}
