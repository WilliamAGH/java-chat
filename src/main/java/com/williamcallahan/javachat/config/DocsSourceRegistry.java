package com.williamcallahan.javachat.config;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * Central registry for documentation source URL mappings.
 *
 * Provides a single place to define how locally mirrored paths map back to
 * authoritative remote URLs for citations and ingestion.
 *
 * Source of truth for remote base URLs is src/main/resources/docs-sources.properties.
 * Scripts also source the same file to avoid DRY.
 */
public final class DocsSourceRegistry {

    private DocsSourceRegistry() {}

    private static final String DEFAULT_JAVA24 = "https://docs.oracle.com/en/java/javase/24/docs/api/";
    private static final String DEFAULT_JAVA25_EA = "https://download.java.net/java/early_access/jdk25/docs/api/";

    private static final Properties PROPS = new Properties();
    static {
        try (InputStream in = DocsSourceRegistry.class.getResourceAsStream("/docs-sources.properties")) {
            if (in != null) {
                PROPS.load(in);
            }
        } catch (Exception ignored) {
            // Fallbacks below will be used
        }
    }

    private static String propOrEnv(String key, String def) {
        String v = System.getProperty(key);
        if (v == null) v = System.getenv(key);
        if (v == null) v = PROPS.getProperty(key);
        return Objects.requireNonNullElse(v, def);
    }

    public static final String JAVA24_API_BASE = propOrEnv("JAVA24_API_BASE", DEFAULT_JAVA24);
    public static final String JAVA25_EA_API_BASE = propOrEnv("JAVA25_EA_API_BASE", DEFAULT_JAVA25_EA);
    
    // Spring ecosystem base URLs
    public static final String SPRING_BOOT_API_BASE = propOrEnv("SPRING_BOOT_API_BASE", "https://docs.spring.io/spring-boot/docs/current/api/");
    public static final String SPRING_FRAMEWORK_API_BASE = propOrEnv("SPRING_FRAMEWORK_API_BASE", "https://docs.spring.io/spring-framework/docs/current/javadoc-api/");
    public static final String SPRING_AI_API_BASE = propOrEnv("SPRING_AI_API_BASE", "https://docs.spring.io/spring-ai/reference/1.0/api/");
    
    public static final String BOOKS_LOCAL_PREFIX = "/data/docs/books/";
    public static final String PUBLIC_PDFS_BASE = "/pdfs/";

    // Known embedded hosts that can be reconstructed directly
    private static final String[] EMBEDDED_HOST_MARKERS = new String[] {
            "docs.oracle.com/",
            "download.java.net/",
            "docs.spring.io/"
    };

    // Ordered mappings: first match wins
    private static final Map<String, String> LOCAL_PREFIX_TO_REMOTE_BASE = new LinkedHashMap<>();
    static {
        // Java SE 24 API (Oracle)
        LOCAL_PREFIX_TO_REMOTE_BASE.put("/data/docs/java24/", JAVA24_API_BASE);
        LOCAL_PREFIX_TO_REMOTE_BASE.put("/data/docs/java/java24/", JAVA24_API_BASE);
        LOCAL_PREFIX_TO_REMOTE_BASE.put("/data/docs/java/java24-complete/", JAVA24_API_BASE);

        // Java 25 Early Access API (download.java.net)
        LOCAL_PREFIX_TO_REMOTE_BASE.put("/data/docs/java25/", JAVA25_EA_API_BASE);
        LOCAL_PREFIX_TO_REMOTE_BASE.put("/data/docs/java/java25/", JAVA25_EA_API_BASE);
        LOCAL_PREFIX_TO_REMOTE_BASE.put("/data/docs/java/java25-ea-complete/", JAVA25_EA_API_BASE);
        // In case a GA mirror is stored under this name locally before GA, still point to EA docs
        LOCAL_PREFIX_TO_REMOTE_BASE.put("/data/docs/java/java25-complete/", JAVA25_EA_API_BASE);
        
        // Spring Boot API documentation - map to base URL without /api/ since local structure includes it
        LOCAL_PREFIX_TO_REMOTE_BASE.put("/data/docs/spring-boot/", "https://docs.spring.io/spring-boot/docs/current/");
        LOCAL_PREFIX_TO_REMOTE_BASE.put("/data/docs/spring-boot-complete/", "https://docs.spring.io/spring-boot/docs/current/");
        
        // Spring Framework API documentation - map to base URL without /javadoc-api/ since local structure includes it
        LOCAL_PREFIX_TO_REMOTE_BASE.put("/data/docs/spring-framework/", "https://docs.spring.io/spring-framework/docs/current/");
        LOCAL_PREFIX_TO_REMOTE_BASE.put("/data/docs/spring-framework-complete/", "https://docs.spring.io/spring-framework/docs/current/");
        
        // Spring AI API documentation
        LOCAL_PREFIX_TO_REMOTE_BASE.put("/data/docs/spring-ai/", SPRING_AI_API_BASE);
        LOCAL_PREFIX_TO_REMOTE_BASE.put("/data/docs/spring-ai-complete/", SPRING_AI_API_BASE);
    }

    /**
     * If the given local filesystem-like path contains an embedded known host,
     * reconstruct an HTTPS URL to that embedded path.
     */
    public static String reconstructFromEmbeddedHost(String localPath) {
        String p = localPath.replace('\\', '/');
        for (String marker : EMBEDDED_HOST_MARKERS) {
            int idx = p.indexOf(marker);
            if (idx >= 0) {
                String url = "https://" + p.substring(idx);
                
                // Fix Spring URLs using proper string parsing
                if (url.startsWith("https://docs.spring.io/")) {
                    url = normalizeSpringUrl(url);
                }
                
                return url;
            }
        }
        return null;
    }
    
    /**
     * Normalize Spring documentation URLs to their correct canonical form.
     * Handles various malformed scraped URL patterns.
     */
    private static String normalizeSpringUrl(String url) {
        // Parse the URL path after docs.spring.io/
        String prefix = "https://docs.spring.io/";
        if (!url.startsWith(prefix)) {
            return url;
        }
        
        String path = url.substring(prefix.length());
        String[] parts = path.split("/");
        
        if (parts.length < 2) {
            return url;
        }
        
        String project = parts[0]; // e.g., "spring-framework" or "spring-boot"
        
        // Handle Spring Framework URLs
        if (project.equals("spring-framework")) {
            // Case 1: /spring-framework/docs/current/reference/6.1-SNAPSHOT/...
            // Should be: /spring-framework/reference/current/...
            if (parts.length > 4 && parts[1].equals("docs") && parts[2].equals("current") && parts[3].equals("reference")) {
                // Skip docs/current/reference/VERSION and rebuild as reference/current/...
                StringBuilder newPath = new StringBuilder(prefix);
                newPath.append("spring-framework/reference/current");
                for (int i = 5; i < parts.length; i++) {
                    newPath.append("/").append(parts[i]);
                }
                return newPath.toString();
            }
            
            // Case 2: /spring-framework/reference/7.0-SNAPSHOT/...
            // Should be: /spring-framework/reference/current/...
            if (parts.length > 2 && parts[1].equals("reference") && isVersionString(parts[2])) {
                StringBuilder newPath = new StringBuilder(prefix);
                newPath.append("spring-framework/reference/current");
                for (int i = 3; i < parts.length; i++) {
                    newPath.append("/").append(parts[i]);
                }
                return newPath.toString();
            }
            
            // Case 3: /spring-framework/docs/current/api/current/javadoc-api/...
            // Should be: /spring-framework/docs/current/javadoc-api/...
            if (parts.length > 5 && parts[1].equals("docs") && parts[2].equals("current") 
                && parts[3].equals("api") && parts[4].equals("current") && parts[5].equals("javadoc-api")) {
                StringBuilder newPath = new StringBuilder(prefix);
                newPath.append("spring-framework/docs/current/javadoc-api");
                for (int i = 6; i < parts.length; i++) {
                    newPath.append("/").append(parts[i]);
                }
                return newPath.toString();
            }
        }
        
        // Handle Spring Boot URLs
        if (project.equals("spring-boot")) {
            // Case 1: /spring-boot/docs/current/reference/VERSION/...
            // Should be: /spring-boot/reference/current/...
            if (parts.length > 4 && parts[1].equals("docs") && parts[2].equals("current") && parts[3].equals("reference")) {
                StringBuilder newPath = new StringBuilder(prefix);
                newPath.append("spring-boot/reference/current");
                for (int i = 5; i < parts.length; i++) {
                    newPath.append("/").append(parts[i]);
                }
                return newPath.toString();
            }
            
            // Case 2: /spring-boot/reference/VERSION/...
            // Should be: /spring-boot/reference/current/...
            if (parts.length > 2 && parts[1].equals("reference") && isVersionString(parts[2])) {
                StringBuilder newPath = new StringBuilder(prefix);
                newPath.append("spring-boot/reference/current");
                for (int i = 3; i < parts.length; i++) {
                    newPath.append("/").append(parts[i]);
                }
                return newPath.toString();
            }
        }
        
        return url;
    }
    
    /**
     * Check if a string looks like a version (e.g., "6.1-SNAPSHOT", "7.0", "3.2.1")
     */
    private static boolean isVersionString(String s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        // Check for patterns like X.Y, X.Y.Z, X.Y-SNAPSHOT
        return s.contains(".") && (Character.isDigit(s.charAt(0)) || s.charAt(0) == 'v');
    }

    /**
     * Map a local mirrored path to its authoritative remote base URL. Returns null if not matched.
     */
    public static String mapLocalPrefixToRemote(String localPath) {
        String p = localPath.replace('\\', '/');
        for (Map.Entry<String, String> e : LOCAL_PREFIX_TO_REMOTE_BASE.entrySet()) {
            String prefix = e.getKey();
            if (p.contains(prefix)) {
                String rel = p.substring(p.indexOf(prefix) + prefix.length());
                
                // Special handling for Spring Framework paths
                if (prefix.contains("spring-framework")) {
                    // Handle various scraped path structures
                    if (rel.startsWith("docs/current/api/current/javadoc-api/")) {
                        // Fix double "current" in path: docs/current/api/current/javadoc-api/ -> javadoc-api/
                        rel = rel.substring("docs/current/api/current/".length());
                    } else if (rel.startsWith("api/current/javadoc-api/")) {
                        // Remove api/current/ prefix: api/current/javadoc-api/ -> javadoc-api/
                        rel = rel.substring("api/current/".length());
                    } else if (rel.startsWith("docs/current/javadoc-api/")) {
                        // Remove docs/current/ prefix: docs/current/javadoc-api/ -> javadoc-api/
                        rel = rel.substring("docs/current/".length());
                    }
                }
                
                // Special handling for Spring Boot paths
                if (prefix.contains("spring-boot")) {
                    // Handle various scraped path structures
                    if (rel.startsWith("docs/current/api/")) {
                        // Remove docs/current/ prefix: docs/current/api/ -> api/
                        rel = rel.substring("docs/current/".length());
                    }
                }
                
                return joinBaseAndRel(e.getValue(), rel);
            }
        }
        return null;
    }

    private static String joinBaseAndRel(String base, String relRaw) {
        if (base == null) return null;
        String b = base;
        String rel = relRaw == null ? "" : relRaw;

        // Normalize slashes
        b = b.replaceAll("/+$", "/");
        rel = rel.replace('\\', '/');
        while (rel.startsWith("/")) rel = rel.substring(1);

        // Avoid duplicate 'docs/api' or 'api' in path
        if (b.endsWith("/docs/api") || b.endsWith("/docs/api/")) {
            if (rel.startsWith("docs/api/")) {
                rel = rel.substring("docs/api/".length());
            } else if (rel.startsWith("api/")) {
                rel = rel.substring("api/".length());
            }
        } else if (b.endsWith("/api") || b.endsWith("/api/")) {
            if (rel.startsWith("api/")) {
                rel = rel.substring("api/".length());
            }
        }

        // Final join
        if (!b.endsWith("/")) b = b + "/";
        return b + rel;
    }

    /**
     * Map a local book PDF under data/docs/books to a server-hosted public PDF path (/pdfs/...).
     * Returns null if the local path is not a recognized book PDF.
     */
    public static String mapBookLocalToPublic(String localPath) {
        if (localPath == null) return null;
        String p = localPath.replace('\\', '/');
        if (!p.toLowerCase().endsWith(".pdf")) return null;
        int idx = p.indexOf(BOOKS_LOCAL_PREFIX);
        if (idx >= 0) {
            String fileName = p.substring(idx + BOOKS_LOCAL_PREFIX.length());
            // Only map the basename to avoid subfolder leakage
            int slash = fileName.lastIndexOf('/') ;
            if (slash >= 0) fileName = fileName.substring(slash + 1);
            return PUBLIC_PDFS_BASE + fileName;
        }
        return null;
    }
}
