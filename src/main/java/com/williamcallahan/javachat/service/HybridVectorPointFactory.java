package com.williamcallahan.javachat.service;

import static io.qdrant.client.PointIdFactory.id;
import static io.qdrant.client.VectorFactory.vector;
import static io.qdrant.client.VectorsFactory.namedVectors;

import io.qdrant.client.ValueFactory;
import io.qdrant.client.grpc.JsonWithInt.Value;
import io.qdrant.client.grpc.Points.PointStruct;
import io.qdrant.client.grpc.Points.Vector;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.ai.document.Document;

final class HybridVectorPointFactory {

    /**
     * Bundles the dense and sparse vector data for a single document point.
     *
     * @param denseVector dense embedding from the embedding model
     * @param sparseVector sparse BM25-style vector from lexical encoder
     * @param denseVectorName Qdrant named vector key for dense embeddings
     * @param sparseVectorName Qdrant named vector key for sparse tokens
     */
    record HybridVectorSet(
            float[] denseVector,
            LexicalSparseVectorEncoder.SparseVector sparseVector,
            String denseVectorName,
            String sparseVectorName) {
        HybridVectorSet {
            Objects.requireNonNull(denseVector, "denseVector");
            Objects.requireNonNull(sparseVector, "sparseVector");
            Objects.requireNonNull(denseVectorName, "denseVectorName");
            Objects.requireNonNull(sparseVectorName, "sparseVectorName");
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (other == null || getClass() != other.getClass()) return false;
            HybridVectorSet that = (HybridVectorSet) other;
            return java.util.Arrays.equals(denseVector, that.denseVector)
                    && java.util.Objects.equals(sparseVector, that.sparseVector)
                    && java.util.Objects.equals(denseVectorName, that.denseVectorName)
                    && java.util.Objects.equals(sparseVectorName, that.sparseVectorName);
        }

        @Override
        public int hashCode() {
            int result = java.util.Objects.hash(sparseVector, denseVectorName, sparseVectorName);
            result = 31 * result + java.util.Arrays.hashCode(denseVector);
            return result;
        }

        @Override
        public String toString() {
            return "HybridVectorSet{" + "denseVector="
                    + java.util.Arrays.toString(denseVector) + ", sparseVector="
                    + sparseVector + ", denseVectorName='"
                    + denseVectorName + '\'' + ", sparseVectorName='"
                    + sparseVectorName + '\'' + '}';
        }
    }

    private HybridVectorPointFactory() {}

    static PointStruct buildPoint(String pointId, HybridVectorSet vectorSet, Document document) {
        Map<String, Vector> namedVectorMap = new LinkedHashMap<>();
        float[] denseVectorValues = Objects.requireNonNull(vectorSet.denseVector(), "denseVector");
        Vector denseVector = vector(denseVectorValues);
        namedVectorMap.put(vectorSet.denseVectorName(), denseVector);

        LexicalSparseVectorEncoder.SparseVector sparseVectorSet =
                Objects.requireNonNull(vectorSet.sparseVector(), "sparseVector");
        if (!Objects.requireNonNull(sparseVectorSet.indices(), "sparseVector.indices").isEmpty()) {
            Vector sparseVector = vector(
                    Objects.requireNonNull(sparseVectorSet.values(), "sparseVector.values"),
                    Objects.requireNonNull(sparseVectorSet.integerIndices(), "sparseVector.integerIndices"));
            namedVectorMap.put(vectorSet.sparseVectorName(), sparseVector);
        }

        Map<String, Value> qdrantPayload = buildPayload(document);

        UUID pointUuid = Objects.requireNonNull(UUID.fromString(pointId), "pointId");
        var pointIdValue = id(pointUuid);
        return PointStruct.newBuilder()
                .setId(pointIdValue)
                .setVectors(namedVectors(namedVectorMap))
                .putAllPayload(qdrantPayload)
                .build();
    }

    private static Map<String, Value> buildPayload(Document document) {
        LinkedHashMap<String, Value> qdrantPayload = new LinkedHashMap<>();
        String documentText = document.getText();
        qdrantPayload.put(
                QdrantPayloadFieldSchema.DOC_CONTENT_FIELD,
                ValueFactory.value(documentText == null ? "" : documentText));

        Map<String, ?> metadata = document.getMetadata();
        for (String fieldName : QdrantPayloadFieldSchema.ALL_METADATA_FIELDS) {
            toQdrantValue(metadata.get(fieldName))
                    .ifPresent(qdrantFieldValue -> qdrantPayload.put(fieldName, qdrantFieldValue));
        }

        return qdrantPayload;
    }

    private static Optional<Value> toQdrantValue(Object metadataValue) {
        if (metadataValue == null) {
            return Optional.empty();
        }
        if (metadataValue instanceof String stringValue) {
            if (stringValue.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(ValueFactory.value(stringValue));
        }
        if (metadataValue instanceof Integer integerValue) {
            return Optional.of(ValueFactory.value(integerValue.longValue()));
        }
        if (metadataValue instanceof Long longValue) {
            return Optional.of(ValueFactory.value(longValue));
        }
        if (metadataValue instanceof Double doubleValue) {
            return Optional.of(ValueFactory.value(doubleValue));
        }
        if (metadataValue instanceof Float floatValue) {
            return Optional.of(ValueFactory.value(floatValue.doubleValue()));
        }
        if (metadataValue instanceof Boolean booleanValue) {
            return Optional.of(ValueFactory.value(booleanValue));
        }
        if (metadataValue instanceof Number numberValue) {
            return Optional.of(ValueFactory.value(numberValue.doubleValue()));
        }
        String metadataText = Objects.requireNonNull(String.valueOf(metadataValue), "metadataValue");
        return Optional.of(ValueFactory.value(metadataText));
    }

    static String resolvePointId(Document document) {
        String documentId = Objects.requireNonNull(document.getId(), "doc.id");
        if (!documentId.isBlank()) {
            return documentId;
        }
        return UUID.randomUUID().toString();
    }
}
