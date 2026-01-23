package com.williamcallahan.javachat.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class LocalSearchService {
    private static final Logger log = LoggerFactory.getLogger(LocalSearchService.class);

    /** Maximum number of files to scan to prevent unbounded directory traversal */
    private static final int MAX_FILES_TO_SCAN = 5000;

    /** Minimum content length divisor to prevent score inflation on tiny documents */
    private static final int MIN_CONTENT_LENGTH_FOR_SCORING = 50;

    private final Path parsedDir;

    public LocalSearchService(@Value("${app.docs.parsed-dir}") String parsedDir) {
        this.parsedDir = Paths.get(parsedDir);
    }

    /**
     * Search local parsed documents using keyword matching.
     *
     * @param query the search query
     * @param topK maximum number of results to return
     * @return SearchOutcome containing results and status information
     */
    public SearchOutcome search(String query, int topK) {
        if (!Files.isDirectory(parsedDir)) {
            log.warn("Local search directory does not exist: {}", parsedDir);
            return SearchOutcome.directoryNotFound(parsedDir.toString());
        }

        String[] terms = normalize(query).split("\\s+");
        Map<Path, Double> scores = new HashMap<>();

        try (var files = Files.walk(parsedDir)) {
            List<Path> txts = files.filter(p -> p.toString().endsWith(".txt"))
                .limit(MAX_FILES_TO_SCAN)
                .collect(Collectors.toList());

            log.debug("Local search scanning {} files for query: {}", txts.size(), query);

            for (Path p : txts) {
                try {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    String norm = normalize(content);
                    double score = 0;
                    for (String t : terms) {
                        if (t.isBlank()) continue;
                        score += count(norm, t);
                    }
                    if (score > 0) scores.put(p, score / Math.max(MIN_CONTENT_LENGTH_FOR_SCORING, norm.length()));
                } catch (IOException fileReadError) {
                    log.debug("Skipping unreadable file {}: {}", p, fileReadError.getMessage());
                }
            }

            List<Result> results = scores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(e -> toResult(e.getKey(), e.getValue()))
                .flatMap(Optional::stream)
                .collect(Collectors.toList());

            log.info("Local search found {} results for query: {}", results.size(), query);
            return SearchOutcome.success(results);

        } catch (IOException walkError) {
            log.error("Local search failed to walk directory {}: {}", parsedDir, walkError.getMessage());
            return SearchOutcome.ioError(walkError.getMessage());
        }
    }

    private Optional<Result> toResult(Path p, double score) {
        try {
            String text = Files.readString(p, StandardCharsets.UTF_8);
            String file = p.getFileName().toString();
            // Filename pattern: safeUrl_index_hash.txt
            String url = fromSafeName(file.substring(0, file.indexOf("_"))); // best-effort
            return Optional.of(new Result(url, text, score));
        } catch (IOException readError) {
            log.warn("Failed to read result file {}: {}", p, readError.getMessage());
            return Optional.empty();
        }
    }

    private String normalize(String s) {
        return s.toLowerCase(Locale.ROOT);
    }

    private int count(String hay, String needle) {
        int c = 0;
        int idx = 0;
        while ((idx = hay.indexOf(needle, idx)) >= 0) {
            c++;
            idx += needle.length();
        }
        return c;
    }

    private String fromSafeName(String s) {
        return s.replace('_', '/');
    }

    /**
     * Represents a single search result with URL, text content, and relevance score.
     */
    public static class Result {
        public final String url;
        public final String text;
        public final double score;

        public Result(String url, String text, double score) {
            this.url = url;
            this.text = text;
            this.score = score;
        }
    }

    /**
     * Outcome of a local search operation, distinguishing between success, empty results, and failures.
     */
    public static class SearchOutcome {
        private final List<Result> results;
        private final Status status;
        private final String errorMessage;

        public enum Status {
            SUCCESS,
            DIRECTORY_NOT_FOUND,
            IO_ERROR
        }

        private SearchOutcome(List<Result> results, Status status, String errorMessage) {
            this.results = results;
            this.status = status;
            this.errorMessage = errorMessage;
        }

        public static SearchOutcome success(List<Result> results) {
            return new SearchOutcome(results, Status.SUCCESS, null);
        }

        public static SearchOutcome directoryNotFound(String path) {
            return new SearchOutcome(List.of(), Status.DIRECTORY_NOT_FOUND,
                "Search directory not found: " + path);
        }

        public static SearchOutcome ioError(String message) {
            return new SearchOutcome(List.of(), Status.IO_ERROR, message);
        }

        public List<Result> results() {
            return results;
        }

        public Status status() {
            return status;
        }

        public String errorMessage() {
            return errorMessage;
        }

        public boolean isSuccess() {
            return status == Status.SUCCESS;
        }

        public boolean isFailed() {
            return status != Status.SUCCESS;
        }
    }
}





