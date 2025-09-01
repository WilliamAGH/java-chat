package com.williamcallahan.javachat.cli;

import com.williamcallahan.javachat.service.DocsIngestionService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

@SpringBootApplication
@ComponentScan(basePackages = "com.williamcallahan.javachat")
public class DocumentProcessor {
    private static final Logger log = LoggerFactory.getLogger(DocumentProcessor.class);
    
    @Autowired
    private DocsIngestionService ingestionService;
    
    public static void main(String[] args) {
        SpringApplication.run(DocumentProcessor.class, args);
    }
    
    @Bean
    public CommandLineRunner processDocuments() {
        return args -> {
            String docsDir = System.getenv("DOCS_DIR");
            if (docsDir == null) {
                docsDir = "data/docs";
            }
            
            log.info("===============================================");
            log.info("Starting Document Processing with Deduplication");
            log.info("===============================================");
            log.info("Documentation directory: {}", docsDir);
            log.info("Qdrant Collection: {}", System.getenv("QDRANT_COLLECTION"));
            log.info("Deduplication: ENABLED (using content hashes)");
            log.info("");
            
            // Documentation directories in priority order
            String[][] docSets = {
                // Complete documentation (preferred)
                {"Java 24 Complete API", "java/java24-complete"},
                {"Java 25 Complete API", "java/java25-complete"},
                {"Java 25 EA Complete API", "java/java25-ea-complete"},
                {"Spring Boot Complete", "spring-boot-complete"},
                {"Spring Framework Complete", "spring-framework-complete"},
                {"Spring AI Complete", "spring-ai-complete"},
                // Quick/partial documentation (fallback)
                {"Java 24 Quick", "java24"},
                {"Java 25 Quick", "java25"},
                {"Spring Boot Quick", "spring-boot"},
                {"Spring Framework Quick", "spring-framework"},
                {"Spring AI Quick", "spring-ai"}
            };
            
            AtomicInteger totalProcessed = new AtomicInteger(0);
            AtomicInteger totalDuplicates = new AtomicInteger(0);
            
            for (String[] docSet : docSets) {
                String name = docSet[0];
                String dir = docSet[1];
                String fullPath = docsDir + "/" + dir;
                Path dirPath = Paths.get(fullPath);
                
                if (Files.exists(dirPath) && Files.isDirectory(dirPath)) {
                    // Count HTML files
                    long fileCount = 0;
                    try (Stream<Path> paths = Files.walk(dirPath)) {
                        fileCount = paths
                            .filter(p -> !Files.isDirectory(p))
                            .filter(p -> {
                                String fileName = p.getFileName().toString().toLowerCase();
                                return fileName.endsWith(".html") || fileName.endsWith(".htm");
                            })
                            .count();
                    }
                    
                    if (fileCount > 0) {
                        log.info("-----------------------------------------------");
                        log.info("Processing: {}", name);
                        log.info("Path: {}", fullPath);
                        log.info("Files to process: {}", fileCount);
                        
                        long startTime = System.currentTimeMillis();
                        
                        try {
                            // Process with maximum file limit
                            int processed = ingestionService.ingestLocalDirectory(fullPath, Integer.MAX_VALUE);
                            
                            long duration = System.currentTimeMillis() - startTime;
                            double rate = processed > 0 ? (processed * 1000.0 / duration) : 0;
                            
                            log.info("✓ Processed {} files in {:.2f}s ({:.1f} files/sec)", 
                                    processed, duration / 1000.0, rate);
                            
                            // Track duplicates (files found but not processed due to hash match)
                            long duplicates = fileCount - processed;
                            if (duplicates > 0) {
                                log.info("  Skipped {} duplicate files (already in Qdrant)", duplicates);
                                totalDuplicates.addAndGet((int) duplicates);
                            }
                            
                            totalProcessed.addAndGet(processed);
                            
                        } catch (Exception e) {
                            log.error("✗ Error processing {}: {}", name, e.getMessage());
                            log.debug("Stack trace:", e);
                        }
                    } else {
                        log.debug("Skipping {} (no HTML files found)", name);
                    }
                } else {
                    log.debug("Skipping {} (directory not found)", name);
                }
            }
            
            // Final summary
            log.info("");
            log.info("===============================================");
            log.info("DOCUMENT PROCESSING COMPLETE");
            log.info("===============================================");
            log.info("Total new documents processed: {}", totalProcessed.get());
            log.info("Total duplicates skipped: {}", totalDuplicates.get());
            log.info("");
            log.info("Documents have been indexed in Qdrant with automatic deduplication.");
            log.info("Each document chunk is identified by a SHA-256 hash of its content.");
            log.info("");
            log.info("Next steps:");
            log.info("1. Verify in Qdrant Dashboard: http://{}:{}/dashboard", 
                    System.getenv("QDRANT_HOST") != null ? System.getenv("QDRANT_HOST") : "localhost",
                    System.getenv("QDRANT_PORT") != null ? System.getenv("QDRANT_PORT") : "8086");
            String portStr = System.getenv("PORT") != null ? System.getenv("PORT") : "8085";
            log.info("2. Test retrieval: curl http://localhost:{}/api/search?query='Java streams'", portStr);
            log.info("3. Start chat: mvn spring-boot:run");
            log.info("===============================================");
        };
    }
}