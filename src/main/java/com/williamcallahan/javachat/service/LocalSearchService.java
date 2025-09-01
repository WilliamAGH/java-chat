package com.williamcallahan.javachat.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class LocalSearchService {
    private final Path parsedDir;

    public LocalSearchService(@Value("${app.docs.parsed-dir}") String parsedDir) {
        this.parsedDir = Paths.get(parsedDir);
    }

    public List<Result> search(String query, int topK) {
        if (!Files.isDirectory(parsedDir)) return List.of();
        String[] terms = normalize(query).split("\\s+");
        Map<Path, Double> scores = new HashMap<>();
        try {
            try (var files = Files.walk(parsedDir)) {
                List<Path> txts = files.filter(p -> p.toString().endsWith(".txt")).limit(5000).collect(Collectors.toList());
                for (Path p : txts) {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    String norm = normalize(content);
                    double score = 0;
                    for (String t : terms) {
                        if (t.isBlank()) continue;
                        score += count(norm, t);
                    }
                    if (score > 0) scores.put(p, score / Math.max(50, norm.length()));
                }
            }
        } catch (IOException ignored) {}
        return scores.entrySet().stream()
                .sorted((a,b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(e -> toResult(e.getKey(), e.getValue()))
                .collect(Collectors.toList());
    }

    private Result toResult(Path p, double score) {
        try {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            String file = p.getFileName().toString();
            // Filename pattern: safeUrl_index_hash.txt
            String url = fromSafeName(file.substring(0, file.indexOf("_"))); // best-effort
            return new Result(url, text, score);
        } catch (IOException e) {
            return new Result("", "", 0);
        }
    }

    private String normalize(String s) { return s.toLowerCase(Locale.ROOT); }
    private int count(String hay, String needle) {
        int c=0, idx=0; while ((idx = hay.indexOf(needle, idx)) >= 0) { c++; idx += needle.length(); } return c;
    }
    private String fromSafeName(String s) { return s.replace('_', '/'); }

    public static class Result {
        public final String url;
        public final String text;
        public final double score;
        public Result(String url, String text, double score) { this.url=url; this.text=text; this.score=score; }
    }
}





