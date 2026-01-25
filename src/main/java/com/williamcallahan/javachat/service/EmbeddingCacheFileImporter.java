package com.williamcallahan.javachat.service;

import static java.io.ObjectInputFilter.Status;

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

/**
 * Reads embedding cache files in JSON or legacy serialized formats.
 */
final class EmbeddingCacheFileImporter {
    /** Bytes reserved for mark/reset during format detection. */
    private static final int FORMAT_DETECTION_MARK_LIMIT = 512;

    /** Bytes read from file header for format detection. */
    private static final int FORMAT_DETECTION_HEADER_SIZE = 64;

    /** First magic byte identifying Java serialized stream (STREAM_MAGIC high byte). */
    private static final int JAVA_SERIAL_MAGIC_BYTE_1 = 0xAC;

    /** Second magic byte identifying Java serialized stream (STREAM_MAGIC low byte). */
    private static final int JAVA_SERIAL_MAGIC_BYTE_2 = 0xED;

    /** Maximum allowed object graph depth to prevent stack overflow attacks. */
    private static final int DESERIALIZATION_MAX_DEPTH = 80;

    /** Maximum allowed reference count to prevent memory exhaustion attacks. */
    private static final long DESERIALIZATION_MAX_REFERENCES = 20_000_000L;

    /** Maximum allowed array length to prevent memory exhaustion attacks. */
    private static final long DESERIALIZATION_MAX_ARRAY_LENGTH = 50_000_000L;

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

    /**
     * Represents supported cache file serialization formats.
     */
    private enum CacheFileFormat {
        JSON,
        LEGACY_JAVA_SERIALIZED
    }

    private CacheFileFormat detectCacheFileFormat(BufferedInputStream bufferedInputStream) throws IOException {
        bufferedInputStream.mark(FORMAT_DETECTION_MARK_LIMIT);
        byte[] header = bufferedInputStream.readNBytes(FORMAT_DETECTION_HEADER_SIZE);
        bufferedInputStream.reset();

        if (header.length >= 2
                && (header[0] & 0xFF) == JAVA_SERIAL_MAGIC_BYTE_1
                && (header[1] & 0xFF) == JAVA_SERIAL_MAGIC_BYTE_2) {
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

    private static final ObjectInputFilter LEGACY_CACHE_INPUT_FILTER = filterContext -> {
        if (filterContext.depth() > DESERIALIZATION_MAX_DEPTH) {
            return Status.REJECTED;
        }
        if (filterContext.references() > DESERIALIZATION_MAX_REFERENCES) {
            return Status.REJECTED;
        }
        if (filterContext.arrayLength() >= 0 && filterContext.arrayLength() > DESERIALIZATION_MAX_ARRAY_LENGTH) {
            return Status.REJECTED;
        }

        Class<?> serializedClass = filterContext.serialClass();
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
            if (deserialized == null) {
                throw new IOException("Unexpected legacy cache format; deserialized payload is null");
            }
            if (!(deserialized instanceof List<?> legacyList)) {
                throw new IOException("Unexpected legacy cache format; expected a List but got: "
                        + deserialized.getClass().getName());
            }

            List<EmbeddingCacheEntry> converted = new ArrayList<>(legacyList.size());
            for (Object legacyEntry : legacyList) {
                if (!(legacyEntry instanceof EmbeddingCacheService.CachedEmbedding cachedEmbedding)) {
                    throw new IOException("Unexpected legacy cache entry type: "
                            + (legacyEntry == null
                                    ? "null"
                                    : legacyEntry.getClass().getName()));
                }
                EmbeddingCacheEntry convertedEntry = new EmbeddingCacheEntry(
                        cachedEmbedding.id == null || cachedEmbedding.id.isBlank()
                                ? UUID.randomUUID().toString()
                                : cachedEmbedding.id,
                        cachedEmbedding.content,
                        cachedEmbedding.embedding,
                        EmbeddingCacheMetadata.fromLegacyMetadataMap(cachedEmbedding.metadata));
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
