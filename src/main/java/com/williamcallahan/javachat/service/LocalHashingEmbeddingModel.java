package com.williamcallahan.javachat.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal CPU-only deterministic hashing embedding to unblock retrieval without remote calls.
 * Not semantically strong; replace with DJL BGE in production.
 */
public class LocalHashingEmbeddingModel implements EmbeddingModel {
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final int BYTE_OFFSET = 128;
    private static final double BYTE_SCALE = 128.0d;

    private final int dimensions;

    /**
     * Creates a hashing-based embedding model with a fixed dimension size.
     *
     * @param dimensions embedding vector dimensions
     */
    public LocalHashingEmbeddingModel(int dimensions) {
        this.dimensions = dimensions;
    }

    /**
     * Creates embeddings for all inputs in the request.
     *
     * @param request embedding request
     * @return embedding response
     */
    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> inputs = request.getInstructions();
        List<Embedding> list = new ArrayList<>(inputs.size());
        for (int index = 0; index < inputs.size(); index++) {
            list.add(new Embedding(embed(inputs.get(index)), index));
        }
        return new EmbeddingResponse(list);
    }

    /**
     * Creates an embedding for a single input.
     *
     * @param text input text
     * @return embedding vector
     */
    @Override
    public float[] embed(String text) {
        return toFloatArray(hashToVector(text, dimensions));
    }

    /**
     * Creates embeddings for each input string.
     *
     * @param texts input texts
     * @return embedding vectors
     */
    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String textValue : texts) {
            out.add(embed(textValue));
        }
        return out;
    }

    /**
     * Creates an embedding for a document.
     *
     * @param document source document
     * @return embedding vector
     */
    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    /**
     * Returns the embedding vector dimensions.
     *
     * @return embedding dimensions
     */
    @Override
    public int dimensions() {
        return dimensions;
    }

    private double[] hashToVector(String text, int dimensions) {
        try {
            MessageDigest md = MessageDigest.getInstance(HASH_ALGORITHM);
            byte[] bytes = md.digest(text.getBytes(StandardCharsets.UTF_8));
            double[] vector = new double[dimensions];
            for (int index = 0; index < dimensions; index++) {
                int byteValue = bytes[index % bytes.length] & 0xFF;
                vector[index] = (byteValue - BYTE_OFFSET) / BYTE_SCALE;
            }
            return vector;
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("Missing hash algorithm: " + HASH_ALGORITHM, ex);
        }
    }

    private float[] toFloatArray(double[] vector) {
        float[] floats = new float[vector.length];
        for (int index = 0; index < vector.length; index++) {
            floats[index] = (float) vector[index];
        }
        return floats;
    }
}
