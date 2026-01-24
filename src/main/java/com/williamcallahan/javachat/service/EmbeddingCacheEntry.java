package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.ai.document.Document;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
final class EmbeddingCacheEntry {
    private String id;
    private String content;
    private float[] embedding;
    private EmbeddingCacheMetadata metadata;
    private LocalDateTime createdAt;
    private boolean uploaded;

    EmbeddingCacheEntry() {}

    EmbeddingCacheEntry(String id, String content, float[] embedding, EmbeddingCacheMetadata metadata) {
        this.id = id;
        this.content = content;
        this.embedding = embedding == null ? null : java.util.Arrays.copyOf(embedding, embedding.length);
        this.metadata = metadata == null ? EmbeddingCacheMetadata.empty() : metadata;
        this.createdAt = LocalDateTime.now();
        this.uploaded = false;
    }

    String getId() {
        return id;
    }

    void setId(String id) {
        this.id = id;
    }

    String getContent() {
        return content;
    }

    void setContent(String content) {
        this.content = content;
    }

    float[] getEmbedding() {
        return embedding == null ? null : java.util.Arrays.copyOf(embedding, embedding.length);
    }

    void setEmbedding(float[] embedding) {
        this.embedding = embedding == null ? null : java.util.Arrays.copyOf(embedding, embedding.length);
    }

    EmbeddingCacheMetadata getMetadata() {
        return metadata == null ? EmbeddingCacheMetadata.empty() : metadata;
    }

    void setMetadata(EmbeddingCacheMetadata metadata) {
        this.metadata = metadata == null ? EmbeddingCacheMetadata.empty() : metadata;
    }

    LocalDateTime getCreatedAt() {
        return createdAt;
    }

    void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    boolean isUploaded() {
        return uploaded;
    }

    void setUploaded(boolean uploaded) {
        this.uploaded = uploaded;
    }

    Document toDocument() {
        Document document = new Document(content == null ? "" : content);
        getMetadata().applyTo(document);
        return document;
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
record EmbeddingCacheMetadata(
    @JsonProperty("url") String url,
    @JsonProperty("title") String title,
    @JsonProperty("chunkIndex") Integer chunkIndex,
    @JsonProperty("package") String packageName,
    @JsonProperty("hash") String hash,
    @JsonProperty("pageStart") Integer pageStart,
    @JsonProperty("pageEnd") Integer pageEnd,
    @JsonProperty("retrievalSource") String retrievalSource,
    @JsonProperty("fallbackReason") String fallbackReason
) {
    private static final EmbeddingCacheMetadata EMPTY = new EmbeddingCacheMetadata(
        null, null, null, null, null, null, null, null, null
    );

    static EmbeddingCacheMetadata empty() {
        return EMPTY;
    }

    static EmbeddingCacheMetadata fromDocument(Document sourceDocument) {
        Objects.requireNonNull(sourceDocument, "sourceDocument");
        Map<String, ?> springMetadata = sourceDocument.getMetadata();
        if (springMetadata == null || springMetadata.isEmpty()) {
            return empty();
        }

        String url = stringOrEmpty(springMetadata, "url");
        String title = stringOrEmpty(springMetadata, "title");
        Integer chunkIndex = coerceInteger(springMetadata.get("chunkIndex"));
        String packageName = stringOrEmpty(springMetadata, "package");
        String hash = stringOrEmpty(springMetadata, "hash");
        Integer pageStart = coerceInteger(springMetadata.get("pageStart"));
        Integer pageEnd = coerceInteger(springMetadata.get("pageEnd"));
        String retrievalSource = stringOrEmpty(springMetadata, "retrievalSource");
        String fallbackReason = stringOrEmpty(springMetadata, "fallbackReason");

        return new EmbeddingCacheMetadata(
            blankToNull(url),
            blankToNull(title),
            chunkIndex,
            blankToNull(packageName),
            blankToNull(hash),
            pageStart,
            pageEnd,
            blankToNull(retrievalSource),
            blankToNull(fallbackReason)
        );
    }

    void applyTo(Document document) {
        Objects.requireNonNull(document, "document");
        if (url != null && !url.isBlank()) {
            document.getMetadata().put("url", url);
        }
        if (title != null && !title.isBlank()) {
            document.getMetadata().put("title", title);
        }
        if (chunkIndex != null) {
            document.getMetadata().put("chunkIndex", chunkIndex);
        }
        if (packageName != null && !packageName.isBlank()) {
            document.getMetadata().put("package", packageName);
        }
        if (hash != null && !hash.isBlank()) {
            document.getMetadata().put("hash", hash);
        }
        if (pageStart != null) {
            document.getMetadata().put("pageStart", pageStart);
        }
        if (pageEnd != null) {
            document.getMetadata().put("pageEnd", pageEnd);
        }
        if (retrievalSource != null && !retrievalSource.isBlank()) {
            document.getMetadata().put("retrievalSource", retrievalSource);
        }
        if (fallbackReason != null && !fallbackReason.isBlank()) {
            document.getMetadata().put("fallbackReason", fallbackReason);
        }
    }

    private static String stringOrEmpty(Map<String, ?> map, String key) {
        Object metadataField = map.get(key);
        return metadataField != null ? String.valueOf(metadataField) : "";
    }

    private static Integer coerceInteger(Object rawInput) {
        if (rawInput instanceof Number number) {
            return number.intValue();
        }
        if (rawInput instanceof String string && !string.isBlank()) {
            try {
                return Integer.parseInt(string.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String blankToNull(String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
