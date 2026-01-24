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

/**
 * Searches locally parsed documents using simple keyword scoring as a fallback when vector retrieval is unavailable.
 */
@Service
public class LocalSearchService {
    private static final Logger log = LoggerFactory.getLogger(LocalSearchService.class);

    /** Maximum number of files to scan to prevent unbounded directory traversal */
    private static final int MAX_FILES_TO_SCAN = 5000;

    /** Minimum content length divisor to prevent score inflation on tiny documents */
    private static final int MIN_CONTENT_LENGTH_FOR_SCORING = 50;

    private final Path parsedDir;

    /**
     * Creates a local search service rooted at the parsed document directory.
     */
    public LocalSearchService(@Value("${app.docs.parsed-dir}") String parsedDir) {
        this.parsedDir = Path.of(parsedDir);
    }

    /**
     * Search local parsed documents using keyword matching.
     *
     * @param query the search query
     * @param topK maximum number of hits to return
     * @return SearchOutcome containing hits and status information
     */
    public SearchOutcome search(String query, int topK) {
        Objects.requireNonNull(query, "query must not be null");
        if (!Files.isDirectory(parsedDir)) {
            log.warn("Local search directory does not exist");
            return SearchOutcome.directoryNotFound(parsedDir.toString());
        }

        String[] terms = normalize(query).split("\\s+");
        Map<Path, Double> scores = new HashMap<>();

        try (var files = Files.walk(parsedDir)) {
            List<Path> textFiles = files.filter(pathCandidate -> pathCandidate.toString().endsWith(".txt"))
                .limit(MAX_FILES_TO_SCAN)
                .collect(Collectors.toList());

            log.debug("Local search scanning {} files", textFiles.size());

            for (Path textFile : textFiles) {
                try {
                    String content = Files.readString(textFile, StandardCharsets.UTF_8);
                    String normalizedContent = normalize(content);
                    double score = 0;
                    for (String term : terms) {
                        if (term.isBlank()) continue;
                        score += count(normalizedContent, term);
                    }
                    if (score > 0) {
                        scores.put(
                            textFile,
                            score / Math.max(MIN_CONTENT_LENGTH_FOR_SCORING, normalizedContent.length())
                        );
                    }
                } catch (IOException fileReadError) {
                    log.debug("Skipping unreadable file (exception type: {})",
                        fileReadError.getClass().getSimpleName());
                }
            }

            List<SearchHit> searchHits = scores.entrySet().stream()
                .sorted(Map.Entry.<Path, Double>comparingByValue().reversed())
                .limit(topK)
                .map(scoreEntry -> toSearchHit(scoreEntry.getKey(), scoreEntry.getValue()))
                .flatMap(Optional::stream)
                .collect(Collectors.toList());

            log.info("Local search found {} hits", searchHits.size());
            return SearchOutcome.success(searchHits);

        } catch (IOException walkError) {
            log.error("Local search failed to walk directory (exception type: {})",
                walkError.getClass().getSimpleName());
            return SearchOutcome.ioError(walkError.getMessage());
        }
    }

    private Optional<SearchHit> toSearchHit(Path textPath, double score) {
        try {
            String text = Files.readString(textPath, StandardCharsets.UTF_8);
            Path fileNamePath = textPath.getFileName();
            if (fileNamePath == null) {
                log.warn("Skipping chunk with null filename (root path): {}", textPath);
                return Optional.empty();
            }
            String fileName = fileNamePath.toString();
            // Filename pattern: safeUrl_index_hash.txt
            // Defensive: handle files without underscore delimiter
            int underscoreIdx = fileName.indexOf("_");
            String safeName = underscoreIdx > 0
                ? fileName.substring(0, underscoreIdx)
                : fileName.replace(".txt", "");
            String url = fromSafeName(safeName);
            return Optional.of(new SearchHit(url, text, score));
        } catch (IOException readError) {
            log.warn("Failed to read result file (exception type: {})",
                readError.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private String normalize(String inputText) {
        return inputText.toLowerCase(Locale.ROOT);
    }

    private int count(String haystack, String needleText) {
        int matchCount = 0;
        int searchIndex = 0;
        while ((searchIndex = haystack.indexOf(needleText, searchIndex)) >= 0) {
            matchCount++;
            searchIndex += needleText.length();
        }
        return matchCount;
    }

    private String fromSafeName(String safeName) {
        return safeName.replace('_', '/');
    }

    /**
     * Represents a single search hit with URL, text content, and relevance score.
     */
    public static final class SearchHit {
        private final String url;
        private final String text;
        private final double score;

        /**
         * Creates a local search hit with its source URL, raw text, and relevance score.
         */
        public SearchHit(String url, String text, double score) {
            this.url = url;
            this.text = text;
            this.score = score;
        }

        /**
         * Returns the source URL for the hit.
         */
        public String url() {
            return url;
        }

        /**
         * Returns the raw text content for the hit.
         */
        public String text() {
            return text;
        }

        /**
         * Returns the relevance score for the hit.
         */
        public double score() {
            return score;
        }
    }

    /**
     * Outcome of a local search operation, distinguishing between success, empty hits, and failures.
     */
    public static class SearchOutcome {
        private final List<SearchHit> searchHits;
        private final Status status;
        private final Optional<String> errorMessage;

        /**
         * Signals the outcome state of a local search, separate from hit payload.
         */
        public enum Status {
            SUCCESS,
            DIRECTORY_NOT_FOUND,
            IO_ERROR
        }

        private SearchOutcome(List<SearchHit> searchHits, Status status, Optional<String> errorMessage) {
            this.searchHits = List.copyOf(Objects.requireNonNull(searchHits, "searchHits must not be null"));
            this.status = Objects.requireNonNull(status, "status must not be null");
            this.errorMessage = Objects.requireNonNull(errorMessage, "errorMessage must not be null");
        }

        /**
         * Builds a successful search outcome with the provided hits.
         */
        public static SearchOutcome success(List<SearchHit> hits) {
            return new SearchOutcome(hits, Status.SUCCESS, Optional.empty());
        }

        /**
         * Builds a failure outcome for a missing parsed document directory.
         */
        public static SearchOutcome directoryNotFound(String path) {
            return new SearchOutcome(List.of(), Status.DIRECTORY_NOT_FOUND,
                Optional.of("Search directory not found: " + path));
        }

        /**
         * Builds a failure outcome for an I/O error during traversal or reads.
         */
        public static SearchOutcome ioError(String message) {
            return new SearchOutcome(List.of(), Status.IO_ERROR, Optional.of(message));
        }

        /**
         * Returns the search hits, empty when no matches were found.
         */
        public List<SearchHit> hits() {
            return List.copyOf(searchHits);
        }

        /**
         * Returns the outcome status for the search operation.
         */
        public Status status() {
            return status;
        }

        /**
         * Returns the error message if present, empty Optional for success cases.
         *
         * @return error message wrapped in Optional, never null
         */
        public Optional<String> errorMessage() {
            return errorMessage;
        }

        /**
         * Returns true when the search finished successfully (hits may still be empty).
         */
        public boolean isSuccess() {
            return status == Status.SUCCESS;
        }

        /**
         * Returns true when the search failed to run, such as due to I/O errors or missing directories.
         */
        public boolean isFailed() {
            return status != Status.SUCCESS;
        }
    }
}
