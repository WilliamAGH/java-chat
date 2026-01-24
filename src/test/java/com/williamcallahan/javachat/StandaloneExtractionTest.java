package com.williamcallahan.javachat;

import com.williamcallahan.javachat.service.HtmlContentExtractor;
import com.williamcallahan.javachat.support.AsciiTextNormalizer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manual smoke test for HTML extraction that compares noise removal between old and new paths.
 */
public class StandaloneExtractionTest {
    
    /**
     * Runs the extraction comparison against sampled documentation files.
     *
     * @param args ignored
     * @throws IOException when reading sample files fails
     */
    public static void main(String[] args) throws IOException {
        HtmlContentExtractor extractor = new HtmlContentExtractor();
        String docsRoot = "data/docs";
        
        // Test sources
        String[][] sources = {
            {"java/java24-complete", "Java 24"},
            {"java/java25-complete", "Java 25"},
            {"java/java25-ea-complete", "Java 25 (alt mirror)"},
            {"spring-boot-complete", "Spring Boot"},
            {"spring-framework-complete", "Spring Framework"}
        };
        
        System.out.println("\n========================================");
        System.out.println("HTML EXTRACTION QUALITY CONTROL TEST");
        System.out.println("========================================\n");
        
        int grandTotalOldNoise = 0;
        int grandTotalNewNoise = 0;
        List<String> problematicFiles = new ArrayList<>();
        
        for (String[] source : sources) {
            String dir = source[0];
            String name = source[1];
            Path sourcePath = Paths.get(docsRoot, dir);
            
            if (!Files.exists(sourcePath)) {
                System.out.println("⚠ Skipping " + name + " (not found)");
                continue;
            }
            
            System.out.println("\n=== Testing " + name + " ===");
            
            // Get sample files
            List<Path> htmlFiles;
            try (Stream<Path> paths = Files.walk(sourcePath)) {
                htmlFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".html"))
                    .collect(Collectors.toList());
            }
            
            if (htmlFiles.isEmpty()) {
                System.out.println("No HTML files found");
                continue;
            }
            
            // Sample up to 5 random files
            Collections.shuffle(htmlFiles);
            int sampleSize = Math.min(5, htmlFiles.size());
            
            System.out.println("Total files: " + htmlFiles.size());
            System.out.println("Sampling: " + sampleSize + " files\n");
            
            int totalOldNoise = 0;
            int totalNewNoise = 0;
            int totalOldLength = 0;
            int totalNewLength = 0;
            
            for (int i = 0; i < sampleSize; i++) {
                Path file = htmlFiles.get(i);
                String html = Files.readString(file);
                Document doc = Jsoup.parse(html);
                
                // Old extraction
                String oldText = doc.body() != null ? doc.body().text() : "";
                
                // New extraction
                String newText = file.toString().contains("/api/") ?
                    extractor.extractJavaApiContent(doc) :
                    extractor.extractCleanContent(doc);
                
                // Count noise patterns
                String[] noisePatterns = {
                    "JavaScript is disabled",
                    "Skip navigation",
                    "Hide sidebar",
                    "Show sidebar",
                    "Report a bug",
                    "suggest an enhancement",
                    "Use is subject to license",
                    "Scripting on this page",
                    "Other versions"
                };
                
                int oldNoise = 0;
                int newNoise = 0;
                String oldTextLower = AsciiTextNormalizer.toLowerAscii(oldText);
                String newTextLower = AsciiTextNormalizer.toLowerAscii(newText);

                for (String noise : noisePatterns) {
                    String normalizedNoise = AsciiTextNormalizer.toLowerAscii(noise);
                    if (oldTextLower.contains(normalizedNoise)) oldNoise++;
                    if (newTextLower.contains(normalizedNoise)) newNoise++;
                }
                
                totalOldNoise += oldNoise;
                totalNewNoise += newNoise;
                grandTotalOldNoise += oldNoise;
                grandTotalNewNoise += newNoise;
                totalOldLength += oldText.length();
                totalNewLength += newText.length();
                
                System.out.println("File " + (i+1) + ": " + file.getFileName());
                System.out.println("  Old: " + oldText.length() + " chars, " + oldNoise + " noise patterns");
                System.out.println("  New: " + newText.length() + " chars, " + newNoise + " noise patterns");
                
                double reduction = oldText.length() > 0 ? 
                    (1.0 - (double)newText.length()/oldText.length()) * 100 : 0;
                System.out.println("  Reduction: " + 
                    String.format("%.1f%%", reduction) + 
                    " size, " + (oldNoise - newNoise) + " noise patterns removed");
                
                if (newNoise > 0) {
                    problematicFiles.add(file.toString());
                    System.out.println("  ⚠ Still contains noise patterns!");
                }
                
                // Show preview for first file
                if (i == 0) {
                    System.out.println("\n  Preview (first 400 chars):");
                    System.out.println("  OLD: " + 
                        oldText.substring(0, Math.min(400, oldText.length()))
                            .replace("\n", " ").replaceAll("\\s+", " "));
                    System.out.println("\n  NEW: " + 
                        newText.substring(0, Math.min(400, newText.length()))
                            .replace("\n", " ").replaceAll("\\s+", " "));
                }
                System.out.println();
            }
            
            // Summary for this source
            System.out.println("Summary for " + name + ":");
            System.out.println("  Total noise patterns: " + totalOldNoise + " → " + totalNewNoise + 
                " (" + (totalOldNoise - totalNewNoise) + " removed)");
            
            double avgReduction = totalOldLength > 0 ? 
                (1.0 - (double)totalNewLength/totalOldLength) * 100 : 0;
            System.out.println("  Average size reduction: " + 
                String.format("%.1f%%", avgReduction));
            
            System.out.println("  Quality improvement: " + 
                (totalNewNoise == 0 ? "✅ EXCELLENT - No noise patterns" : 
                 totalNewNoise < totalOldNoise/4 ? "✅ GOOD - Significant reduction" : 
                 "⚠ NEEDS REVIEW"));
        }
        
        System.out.println("\n========================================");
        System.out.println("OVERALL RESULTS");
        System.out.println("========================================");
        System.out.println("Total noise patterns across all samples:");
        System.out.println("  OLD METHOD: " + grandTotalOldNoise + " patterns");
        System.out.println("  NEW METHOD: " + grandTotalNewNoise + " patterns");
        System.out.println("  IMPROVEMENT: " + (grandTotalOldNoise - grandTotalNewNoise) + 
            " patterns removed (" + 
            String.format("%.1f%%", 
                grandTotalOldNoise > 0 ? 
                    ((double)(grandTotalOldNoise - grandTotalNewNoise) / grandTotalOldNoise * 100) : 0) + 
            " reduction)");
        
        if (problematicFiles.isEmpty()) {
            System.out.println("\n✅ ALL SAMPLES CLEAN - No noise patterns remaining!");
        } else {
            System.out.println("\n⚠ Files still containing noise (" + problematicFiles.size() + "):");
            problematicFiles.forEach(f -> System.out.println("  - " + f));
        }
        
        System.out.println("\n========================================");
        System.out.println("TEST COMPLETE");
        System.out.println("========================================\n");
    }
}
