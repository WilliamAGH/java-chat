package com.williamcallahan.javachat.service;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

@Service
public class PdfContentExtractor {
    private static final Logger log = LoggerFactory.getLogger(PdfContentExtractor.class);
    
    /**
     * Extract text content from a PDF file.
     * 
     * @param pdfPath Path to the PDF file
     * @return Extracted text content
     * @throws IOException if the PDF cannot be read
     */
    public String extractTextFromPdf(Path pdfPath) throws IOException {
        log.info("Extracting text from PDF: {}", pdfPath.getFileName());
        
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            
            // Configure the text stripper for better extraction
            stripper.setSortByPosition(true);
            stripper.setStartPage(1);
            stripper.setEndPage(document.getNumberOfPages());
            
            String text = stripper.getText(document);
            
            log.info("Successfully extracted {} characters from {} pages", 
                text.length(), document.getNumberOfPages());
            
            return text;
        } catch (IOException e) {
            // Let caller decide how to log/handle this; avoid duplicate stack traces
            throw e;
        }
    }
    
    /**
     * Extract text from a specific page range in a PDF.
     * 
     * @param pdfPath Path to the PDF file
     * @param startPage Starting page (1-indexed)
     * @param endPage Ending page (inclusive)
     * @return Extracted text content
     * @throws IOException if the PDF cannot be read
     */
    public String extractTextFromPdfRange(Path pdfPath, int startPage, int endPage) throws IOException {
        log.info("Extracting text from PDF {} (pages {}-{})", pdfPath.getFileName(), startPage, endPage);
        
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            
            // Configure the text stripper
            stripper.setSortByPosition(true);
            stripper.setStartPage(startPage);
            stripper.setEndPage(Math.min(endPage, document.getNumberOfPages()));
            
            String text = stripper.getText(document);
            
            log.info("Successfully extracted {} characters from pages {}-{}", 
                text.length(), startPage, endPage);
            
            return text;
        } catch (IOException e) {
            // Let caller decide how to log/handle this; avoid duplicate stack traces
            throw e;
        }
    }
    
    /**
     * Get metadata about a PDF file.
     * 
     * @param pdfPath Path to the PDF file
     * @return PDF metadata as a formatted string
     * @throws IOException if the PDF cannot be read
     */
    public String getPdfMetadata(Path pdfPath) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdfPath.toFile())) {
            StringBuilder metadata = new StringBuilder();
            
            if (document.getDocumentInformation() != null) {
                var info = document.getDocumentInformation();
                
                if (info.getTitle() != null) {
                    metadata.append("Title: ").append(info.getTitle()).append("\n");
                }
                if (info.getAuthor() != null) {
                    metadata.append("Author: ").append(info.getAuthor()).append("\n");
                }
                if (info.getSubject() != null) {
                    metadata.append("Subject: ").append(info.getSubject()).append("\n");
                }
            }
            
            metadata.append("Pages: ").append(document.getNumberOfPages()).append("\n");
            
            return metadata.toString();
        }
    }
}