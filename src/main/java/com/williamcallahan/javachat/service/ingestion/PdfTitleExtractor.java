package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import java.util.Objects;
import org.springframework.stereotype.Service;

/**
 * Extracts a reasonable title for PDF ingestion metadata.
 *
 * <p>PDFBox metadata is not structured uniformly across sources, so this is intentionally
 * heuristic and safe.</p>
 */
@Service
public class PdfTitleExtractor {
    private static final String TITLE_KEY = "title:";

    /**
     * Extracts a title from PDF metadata text or falls back to a filename-derived title.
     *
     * @param metadata raw metadata text
     * @param fileName file name for fallback (may include extension)
     * @return extracted title
     */
    public String extractTitle(String metadata, String fileName) {
        Objects.requireNonNull(fileName, "fileName");

        if (metadata != null && !metadata.isBlank()) {
            String normalizedMetadata = metadata.replace("\r\n", "\n");
            String lowerMetadata = AsciiTextNormalizer.toLowerAscii(normalizedMetadata);
            int titleKeyIndex = lowerMetadata.indexOf(TITLE_KEY);
            if (titleKeyIndex >= 0) {
                int valueStart = titleKeyIndex + TITLE_KEY.length();
                int valueEnd = normalizedMetadata.indexOf('\n', valueStart);
                if (valueEnd == -1) {
                    valueEnd = normalizedMetadata.length();
                }
                String extracted =
                        normalizedMetadata.substring(valueStart, valueEnd).trim();
                if (!extracted.isBlank()) {
                    return extracted;
                }
            }
        }

        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex > 0) {
            return fileName.substring(0, dotIndex);
        }
        return fileName;
    }
}
