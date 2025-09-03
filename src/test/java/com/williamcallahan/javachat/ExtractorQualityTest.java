package com.williamcallahan.javachat;

import com.williamcallahan.javachat.service.HtmlContentExtractor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.annotation.Bean;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

// NOTE: This is a manual utility runner, not part of test boot configuration.
// IMPORTANT: Do NOT annotate with @SpringBootApplication here to avoid
// multiple @SpringBootConfiguration conflicts during @SpringBootTest.
@Configuration
@Profile("manual")
public class ExtractorQualityTest {
    
    public static void main(String[] args) {
        SpringApplication.run(ExtractorQualityTest.class, args);
    }
    
    @Bean
    CommandLineRunner testExtraction(HtmlContentExtractor extractor) {
        return args -> {
            String docsRoot = "data/docs";
            
            // Test sources
            String[][] sources = {
                {"java/java24-complete", "Java 24"},
                {"java/java25-complete", "Java 25"},
                {"java/java25-ea-complete", "Java 25 EA"},
                {"spring-boot-complete", "Spring Boot"},
                {"spring-framework-complete", "Spring Framework"}
            };
            
            System.out.println("\n========================================");
            System.out.println("HTML EXTRACTION QUALITY CONTROL TEST");
            System.out.println("========================================\n");
            
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
                        "Scripting on this page"
                    };
                    
                    int oldNoise = 0;
                    int newNoise = 0;
                    
                    for (String noise : noisePatterns) {
                        if (oldText.contains(noise)) oldNoise++;
                        if (newText.contains(noise)) newNoise++;
                    }
                    
                    totalOldNoise += oldNoise;
                    totalNewNoise += newNoise;
                    totalOldLength += oldText.length();
                    totalNewLength += newText.length();
                    
                    System.out.println("File " + (i+1) + ": " + file.getFileName());
                    System.out.println("  Old: " + oldText.length() + " chars, " + oldNoise + " noise patterns");
                    System.out.println("  New: " + newText.length() + " chars, " + newNoise + " noise patterns");
                    System.out.println("  Reduction: " + 
                        String.format("%.1f%%", (1.0 - (double)newText.length()/oldText.length()) * 100) + 
                        " size, " + (oldNoise - newNoise) + " noise patterns removed");
                    
                    // Show preview of improvement
                    if (i == 0) {
                        System.out.println("\n  Preview (first 300 chars):");
                        System.out.println("  OLD: " + oldText.substring(0, Math.min(300, oldText.length())).replace("\n", " "));
                        System.out.println("  NEW: " + newText.substring(0, Math.min(300, newText.length())).replace("\n", " "));
                    }
                    System.out.println();
                }
                
                // Summary for this source
                System.out.println("Summary for " + name + ":");
                System.out.println("  Total noise patterns: " + totalOldNoise + " → " + totalNewNoise + 
                    " (" + (totalOldNoise - totalNewNoise) + " removed)");
                System.out.println("  Average size reduction: " + 
                    String.format("%.1f%%", (1.0 - (double)totalNewLength/totalOldLength) * 100));
                System.out.println("  Quality improvement: " + 
                    (totalNewNoise == 0 ? "✅ EXCELLENT - No noise patterns" : 
                     totalNewNoise < totalOldNoise/4 ? "✅ GOOD - Significant reduction" : 
                     "⚠ NEEDS REVIEW"));
            }
            
            System.out.println("\n========================================");
            System.out.println("TEST COMPLETE");
            System.out.println("========================================\n");
            
            System.exit(0);
        };
    }
}
