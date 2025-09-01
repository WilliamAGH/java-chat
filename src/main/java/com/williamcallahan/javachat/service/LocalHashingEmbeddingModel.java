package com.williamcallahan.javachat.service;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal CPU-only deterministic hashing embedding to unblock retrieval without remote calls.
 * Not semantically strong; replace with DJL BGE in production.
 */
public class LocalHashingEmbeddingModel implements EmbeddingModel {
    private final int dim;

    public LocalHashingEmbeddingModel(int dim) { this.dim = dim; }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> inputs = request.getInstructions();
        List<Embedding> list = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            list.add(new Embedding(embed(inputs.get(i)), i));
        }
        return new EmbeddingResponse(list);
    }

    @Override
    public float[] embed(String text) {
        return toFloatArray(hashToVector(text, dim));
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> out = new ArrayList<>(texts.size());
        for (String t : texts) out.add(embed(t));
        return out;
    }

    @Override
    public float[] embed(Document document) {
        return embed(document.getText());
    }

    @Override
    public int dimensions() { return dim; }

    private double[] hashToVector(String text, int d) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(text.getBytes(StandardCharsets.UTF_8));
            double[] v = new double[d];
            for (int i = 0; i < d; i++) {
                v[i] = ((bytes[i % bytes.length] & 0xFF) - 128) / 128.0;
            }
            return v;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    private float[] toFloatArray(double[] v) {
        float[] f = new float[v.length];
        for (int i = 0; i < v.length; i++) f[i] = (float) v[i];
        return f;
    }
}


