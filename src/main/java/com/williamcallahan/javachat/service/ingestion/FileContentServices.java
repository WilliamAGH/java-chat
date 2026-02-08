package com.williamcallahan.javachat.service.ingestion;

import com.williamcallahan.javachat.service.FileOperationsService;
import com.williamcallahan.javachat.service.HtmlContentExtractor;
import com.williamcallahan.javachat.service.PdfContentExtractor;
import org.springframework.stereotype.Service;

/**
 * Groups file content extraction and validation dependencies for ingestion processors.
 *
 * <p>Bundles the services responsible for reading, parsing, and validating file content
 * (HTML and PDF) so ingestion processors accept a single cohesive dependency instead of
 * six individual constructor parameters.</p>
 *
 * @param htmlExtractor HTML content extractor
 * @param pdfExtractor PDF content extractor
 * @param fileOps file IO helper
 * @param titleExtractor PDF title extraction
 * @param contentGuard validates extracted HTML prior to indexing
 * @param quarantine moves invalid content aside for inspection
 */
@Service
public record FileContentServices(
        HtmlContentExtractor htmlExtractor,
        PdfContentExtractor pdfExtractor,
        FileOperationsService fileOps,
        PdfTitleExtractor titleExtractor,
        HtmlContentGuard contentGuard,
        IngestionQuarantineService quarantine) {}
