package com.williamcallahan.javachat.support;

import com.williamcallahan.javachat.model.Citation;
import com.williamcallahan.javachat.service.LocalStoreService;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Enhances PDF citations with page number anchors based on chunk position heuristics.
 *
 * <p>For PDFs where chunk ordering correlates with page ordering (like Think Java),
 * this service estimates the page number from the chunk index and total chunk count,
 * then adds a #page=N anchor to the citation URL.</p>
 */
@Component
public class PdfCitationEnhancer {
    private static final Logger logger = LoggerFactory.getLogger(PdfCitationEnhancer.class);

    private final Function<String, String> safeNameResolver;
    private final Supplier<Path> parsedDirSupplier;

    /** Cached page count for the Think Java PDF to avoid repeated I/O. */
    private volatile Integer cachedThinkJavaPdfPages = null;

    /** Classpath location of the Think Java PDF. */
    private static final String THINK_JAVA_PDF_CLASSPATH = "public/pdfs/Think Java - 2nd Edition Book.pdf";

    /** Filename pattern used to identify Think Java PDF URLs. */
    private static final String THINK_JAVA_PDF_FILENAME = "think java";

    /** URL-encoded variant of Think Java filename. */
    private static final String THINK_JAVA_PDF_FILENAME_ENCODED = "think%20java";

    /**
     * Creates an enhancer that estimates PDF page anchors using locally stored chunk metadata.
     *
     * @param localStore service that provides access to parsed chunk storage
     */
    public PdfCitationEnhancer(LocalStoreService localStore) {
        LocalStoreService requiredLocalStore = Objects.requireNonNull(localStore, "localStore");
        this.safeNameResolver = requiredLocalStore::toSafeName;
        this.parsedDirSupplier = requiredLocalStore::getParsedDir;
    }

    /**
     * Enhances PDF citations with estimated page anchors.
     *
     * <p>For each citation pointing to a PDF, this method attempts to calculate
     * the page number based on the document's chunk index and the total number
     * of chunks for that PDF. The citation URL is updated with a #page=N anchor.</p>
     *
     * @param docs the retrieved documents with chunk metadata
     * @param citations the citations to enhance (must be same size as docs)
     * @return the enhanced citations list (same list, mutated)
     * @throws UncheckedIOException when the PDF or chunk listing cannot be read
     */
    public List<Citation> enhanceWithPageAnchors(List<Document> docs, List<Citation> citations) {
        if (docs.size() != citations.size()) {
            logger.warn(
                    "Skipping PDF anchor enhancement because docs/citations sizes differ (docs={}, citations={})",
                    docs.size(),
                    citations.size());
            return citations;
        }

        int thinkJavaPages = getThinkJavaPdfPages();

        for (int docIndex = 0; docIndex < docs.size(); docIndex++) {
            Document document = docs.get(docIndex);
            Citation citation = citations.get(docIndex);
            String url = citation.getUrl();

            if (url == null || !url.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
                continue;
            }

            // Only apply page estimation to the Think Java PDF where we know the page count
            if (!isThinkJavaPdf(url)) {
                continue;
            }

            int chunkIndex = parseChunkIndex(document);
            if (chunkIndex < 0) {
                continue;
            }

            int totalChunks = countChunksForUrl(url);
            if (thinkJavaPages > 0 && totalChunks > 0) {
                int page = estimatePage(chunkIndex, totalChunks, thinkJavaPages);
                String withAnchor = url.contains("#page=") ? url : url + "#page=" + page;
                citation.setUrl(withAnchor);
                citation.setAnchor("page=" + page);
            }
        }
        return citations;
    }

    /**
     * Provides the total page count for the Think Java PDF.
     *
     * <p>The result is cached after the first load to avoid repeated I/O.</p>
     *
     * @return page count
     * @throws UncheckedIOException if the PDF cannot be loaded
     */
    public int getThinkJavaPdfPages() {
        if (cachedThinkJavaPdfPages != null) {
            return cachedThinkJavaPdfPages;
        }

        synchronized (this) {
            if (cachedThinkJavaPdfPages != null) {
                return cachedThinkJavaPdfPages;
            }

            try {
                ClassPathResource pdfResource = new ClassPathResource(THINK_JAVA_PDF_CLASSPATH);
                try (InputStream pdfStream = pdfResource.getInputStream();
                        PDDocument document = Loader.loadPDF(pdfStream.readAllBytes())) {
                    cachedThinkJavaPdfPages = document.getNumberOfPages();
                }
            } catch (IOException ioException) {
                throw new UncheckedIOException("Failed to load Think Java PDF for pagination", ioException);
            }
            return cachedThinkJavaPdfPages;
        }
    }

    /**
     * Counts the total number of chunks stored for a given URL.
     *
     * @param url the source URL
     * @return count of chunk files, or 0 if the directory is unavailable
     * @throws UncheckedIOException if listing files fails
     */
    int countChunksForUrl(String url) {
        try {
            String safe = safeNameResolver.apply(url);
            Path dir = parsedDirSupplier.get();
            if (dir == null) {
                return 0;
            }

            try (var stream = Files.list(dir)) {
                return (int) stream.filter(path -> {
                            Path fileNamePath = path.getFileName();
                            if (fileNamePath == null) {
                                return false;
                            }
                            String fileName = fileNamePath.toString();
                            return fileName.startsWith(safe + "_") && fileName.endsWith(".txt");
                        })
                        .count();
            }
        } catch (IOException ioException) {
            throw new UncheckedIOException("Unable to count local chunks for URL: " + url, ioException);
        }
    }

    /**
     * Parses the chunk index from document metadata.
     *
     * @param document the document with metadata
     * @return chunk index or -1 if not available
     */
    private int parseChunkIndex(Document document) {
        Object chunkIndexMetadata = document.getMetadata().get("chunkIndex");
        if (chunkIndexMetadata == null) {
            return -1;
        }

        try {
            return Integer.parseInt(String.valueOf(chunkIndexMetadata));
        } catch (NumberFormatException parseException) {
            logger.debug(
                    "Failed to parse chunkIndex from metadata: {}",
                    sanitizeForLogText(String.valueOf(chunkIndexMetadata)));
            return -1;
        }
    }

    /**
     * Estimates the page number based on chunk position within the document.
     *
     * @param chunkIndex zero-based chunk index
     * @param totalChunks total number of chunks
     * @param totalPages total number of pages in the PDF
     * @return estimated page number (1-based, clamped to valid range)
     */
    private int estimatePage(int chunkIndex, int totalChunks, int totalPages) {
        double position = (chunkIndex + 1.0) / totalChunks;
        int page = (int) Math.round(position * totalPages);
        return Math.max(1, Math.min(totalPages, page));
    }

    private static String sanitizeForLogText(String rawText) {
        if (rawText == null) {
            return "";
        }
        return rawText.replace("\r", "\\r").replace("\n", "\\n");
    }

    /**
     * Checks if the URL refers to the Think Java PDF.
     *
     * @param url the citation URL
     * @return true if the URL appears to be the Think Java PDF
     */
    private static boolean isThinkJavaPdf(String url) {
        String normalized = url.toLowerCase(Locale.ROOT);
        return normalized.contains(THINK_JAVA_PDF_FILENAME) || normalized.contains(THINK_JAVA_PDF_FILENAME_ENCODED);
    }
}
