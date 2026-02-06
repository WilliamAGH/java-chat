package com.williamcallahan.javachat.cli;

import java.util.List;

/**
 * Defines the documentation sets available to the {@link DocumentProcessor} CLI.
 *
 * <p>This catalog keeps the CLI entrypoint smaller and makes it easy to add or remove doc sets
 * without touching ingestion orchestration code.</p>
 */
final class DocumentationSetCatalog {

    private static final String DOCSET_PDF_BOOKS_NAME = "PDF Books";
    private static final String DOCSET_PDF_BOOKS_PATH = "books";
    private static final String DOCSET_JAVA_24_COMPLETE_NAME = "Java 24 Complete API";
    private static final String DOCSET_JAVA_24_COMPLETE_PATH = "java/java24-complete";
    private static final String DOCSET_JAVA_25_COMPLETE_NAME = "Java 25 Documentation";
    private static final String DOCSET_JAVA_25_COMPLETE_PATH = "java/java25-complete";
    private static final String DOCSET_JAVA_25_RELEASE_NOTES_NAME = "Java 25 Release Notes Issues";
    private static final String DOCSET_JAVA_25_RELEASE_NOTES_PATH = "oracle/javase";
    private static final String DOCSET_IBM_JAVA_25_ARTICLE_NAME = "IBM Java 25 Overview";
    private static final String DOCSET_IBM_JAVA_25_ARTICLE_PATH = "ibm/articles";
    private static final String DOCSET_JETBRAINS_JAVA_25_BLOG_NAME = "JetBrains Java 25 Blog";
    private static final String DOCSET_JETBRAINS_JAVA_25_BLOG_PATH = "jetbrains/idea/2025/09";
    private static final String DOCSET_SPRING_BOOT_COMPLETE_NAME = "Spring Boot Complete";
    private static final String DOCSET_SPRING_BOOT_COMPLETE_PATH = "spring-boot-complete";
    private static final String DOCSET_SPRING_FRAMEWORK_COMPLETE_NAME = "Spring Framework Complete";
    private static final String DOCSET_SPRING_FRAMEWORK_COMPLETE_PATH = "spring-framework-complete";
    private static final String DOCSET_SPRING_AI_COMPLETE_NAME = "Spring AI Complete";
    private static final String DOCSET_SPRING_AI_COMPLETE_PATH = "spring-ai-complete";

    private static final String DOCSET_JAVA_24_QUICK_NAME = "Java 24 Quick";
    private static final String DOCSET_JAVA_24_QUICK_PATH = "java24";
    private static final String DOCSET_JAVA_25_QUICK_NAME = "Java 25 Quick";
    private static final String DOCSET_JAVA_25_QUICK_PATH = "java25";
    private static final String DOCSET_SPRING_BOOT_QUICK_NAME = "Spring Boot Quick";
    private static final String DOCSET_SPRING_BOOT_QUICK_PATH = "spring-boot";
    private static final String DOCSET_SPRING_FRAMEWORK_QUICK_NAME = "Spring Framework Quick";
    private static final String DOCSET_SPRING_FRAMEWORK_QUICK_PATH = "spring-framework";
    private static final String DOCSET_SPRING_AI_QUICK_NAME = "Spring AI Quick";
    private static final String DOCSET_SPRING_AI_QUICK_PATH = "spring-ai";

    private static final List<DocumentationSet> BASE_DOCUMENTATION_SETS = List.of(
            new DocumentationSet(DOCSET_PDF_BOOKS_NAME, DOCSET_PDF_BOOKS_PATH),
            new DocumentationSet(DOCSET_JAVA_24_COMPLETE_NAME, DOCSET_JAVA_24_COMPLETE_PATH),
            new DocumentationSet(DOCSET_JAVA_25_COMPLETE_NAME, DOCSET_JAVA_25_COMPLETE_PATH),
            new DocumentationSet(DOCSET_JAVA_25_RELEASE_NOTES_NAME, DOCSET_JAVA_25_RELEASE_NOTES_PATH),
            new DocumentationSet(DOCSET_IBM_JAVA_25_ARTICLE_NAME, DOCSET_IBM_JAVA_25_ARTICLE_PATH),
            new DocumentationSet(DOCSET_JETBRAINS_JAVA_25_BLOG_NAME, DOCSET_JETBRAINS_JAVA_25_BLOG_PATH),
            new DocumentationSet(DOCSET_SPRING_BOOT_COMPLETE_NAME, DOCSET_SPRING_BOOT_COMPLETE_PATH),
            new DocumentationSet(DOCSET_SPRING_FRAMEWORK_COMPLETE_NAME, DOCSET_SPRING_FRAMEWORK_COMPLETE_PATH),
            new DocumentationSet(DOCSET_SPRING_AI_COMPLETE_NAME, DOCSET_SPRING_AI_COMPLETE_PATH));

    private static final List<DocumentationSet> QUICK_DOCUMENTATION_SETS = List.of(
            new DocumentationSet(DOCSET_JAVA_24_QUICK_NAME, DOCSET_JAVA_24_QUICK_PATH),
            new DocumentationSet(DOCSET_JAVA_25_QUICK_NAME, DOCSET_JAVA_25_QUICK_PATH),
            new DocumentationSet(DOCSET_SPRING_BOOT_QUICK_NAME, DOCSET_SPRING_BOOT_QUICK_PATH),
            new DocumentationSet(DOCSET_SPRING_FRAMEWORK_QUICK_NAME, DOCSET_SPRING_FRAMEWORK_QUICK_PATH),
            new DocumentationSet(DOCSET_SPRING_AI_QUICK_NAME, DOCSET_SPRING_AI_QUICK_PATH));

    private static final List<DocumentationSet> ALL_DOCUMENTATION_SETS = java.util.stream.Stream.concat(
                    BASE_DOCUMENTATION_SETS.stream(), QUICK_DOCUMENTATION_SETS.stream())
            .toList();

    private DocumentationSetCatalog() {}

    static List<DocumentationSet> baseSets() {
        return BASE_DOCUMENTATION_SETS;
    }

    static List<DocumentationSet> quickSets() {
        return QUICK_DOCUMENTATION_SETS;
    }

    static List<DocumentationSet> allSets() {
        return ALL_DOCUMENTATION_SETS;
    }
}
