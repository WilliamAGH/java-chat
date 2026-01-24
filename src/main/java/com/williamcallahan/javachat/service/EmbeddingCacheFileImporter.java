package com.williamcallahan.javachat.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputFilter;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static java.io.ObjectInputFilter.Status;

final class EmbeddingCacheFileImporter {
    private final ObjectMapper cacheMapper;

    EmbeddingCacheFileImporter(ObjectMapper cacheMapper) {
        this.cacheMapper = Objects.requireNonNull(cacheMapper, "cacheMapper");
    }

    List<EmbeddingCacheEntry> read(BufferedInputStream bufferedInputStream) throws IOException {
        CacheFileFormat cacheFileFormat = detectCacheFileFormat(bufferedInputStream);
        return switch (cacheFileFormat) {
            case JSON -> readJsonCache(bufferedInputStream);
            case LEGACY_JAVA_SERIALIZED -> readLegacyJavaSerializedCache(bufferedInputStream);
        };
    }

    private enum CacheFileFormat {
        JSON,
        LEGACY_JAVA_SERIALIZED
    }

    private CacheFileFormat detectCacheFileFormat(BufferedInputStream bufferedInputStream) throws IOException {
        bufferedInputStream.mark(512);
        byte[] header = bufferedInputStream.readNBytes(64);
        bufferedInputStream.reset();

        if (header.length >= 2 && (header[0] & 0xFF) == 0xAC && (header[1] & 0xFF) == 0xED) {
            return CacheFileFormat.LEGACY_JAVA_SERIALIZED;
        }

        for (byte headerByte : header) {
            if (!Character.isWhitespace((char) (headerByte & 0xFF))) {
                return CacheFileFormat.JSON;
            }
        }
        throw new IOException("Unrecognized cache format; file is empty or has an unexpected header");
    }

    private List<EmbeddingCacheEntry> readJsonCache(InputStream jsonStream) throws IOException {
        return cacheMapper.readValue(jsonStream, new TypeReference<List<EmbeddingCacheEntry>>() {});
    }

    private static final ObjectInputFilter LEGACY_CACHE_INPUT_FILTER = info -> {
        if (info.depth() > 80) {
            return Status.REJECTED;
        }
        if (info.references() > 20_000_000) {
            return Status.REJECTED;
        }
        if (info.arrayLength() >= 0 && info.arrayLength() > 50_000_000) {
            return Status.REJECTED;
        }

        Class<?> serializedClass = info.serialClass();
        if (serializedClass == null) {
            return Status.UNDECIDED;
        }

        if (serializedClass.isArray()) {
            Class<?> componentType = serializedClass.getComponentType();
            if (componentType.isPrimitive()) {
                return Status.ALLOWED;
            }
            // Arrays are safe to allow because the element classes are still filtered individually.
            return Status.ALLOWED;
        }

        String className = serializedClass.getName();
        if (Objects.equals(className, EmbeddingCacheService.CachedEmbedding.class.getName())
            || Objects.equals(className, "java.util.ArrayList")
            || Objects.equals(className, "java.util.HashMap")
            || Objects.equals(className, "java.util.LinkedHashMap")
            || Objects.equals(className, "java.util.Collections$UnmodifiableMap")
            || Objects.equals(className, "java.lang.String")
            || Objects.equals(className, "java.lang.Integer")
            || Objects.equals(className, "java.lang.Long")
            || Objects.equals(className, "java.lang.Double")
            || Objects.equals(className, "java.lang.Float")
            || Objects.equals(className, "java.lang.Boolean")
            || Objects.equals(className, "java.time.LocalDateTime")
            || Objects.equals(className, "java.time.Ser")) {
            return Status.ALLOWED;
        }

        return Status.REJECTED;
    };

    private List<EmbeddingCacheEntry> readLegacyJavaSerializedCache(InputStream legacyStream) throws IOException {
        try (ObjectInputStream objectInputStream = new ObjectInputStream(legacyStream)) {
            objectInputStream.setObjectInputFilter(LEGACY_CACHE_INPUT_FILTER);

            Object deserialized = objectInputStream.readObject();
            if (!(deserialized instanceof List<?> legacyList)) {
                throw new IOException("Unexpected legacy cache format; expected a List but got: "
                    + deserialized.getClass().getName());
            }

            List<EmbeddingCacheEntry> converted = new ArrayList<>(legacyList.size());
            for (Object legacyEntry : legacyList) {
                if (!(legacyEntry instanceof EmbeddingCacheService.CachedEmbedding cachedEmbedding)) {
                    throw new IOException("Unexpected legacy cache entry type: "
                        + (legacyEntry == null ? "null" : legacyEntry.getClass().getName()));
                }
                EmbeddingCacheEntry convertedEntry = new EmbeddingCacheEntry(
                    cachedEmbedding.id == null || cachedEmbedding.id.isBlank()
                        ? UUID.randomUUID().toString()
                        : cachedEmbedding.id,
                    cachedEmbedding.content,
                    cachedEmbedding.embedding,
                    EmbeddingCacheMetadata.fromLegacyMetadataMap(cachedEmbedding.metadata)
                );
                convertedEntry.setCreatedAt(cachedEmbedding.createdAt);
                convertedEntry.setUploaded(cachedEmbedding.uploaded);
                converted.add(convertedEntry);
            }
            return converted;
        } catch (ClassNotFoundException exception) {
            throw new IOException("Legacy cache contains a class not present on the classpath", exception);
        }
    }
}
