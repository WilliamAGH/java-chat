package com.williamcallahan.javachat.domain.ingestion;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps source file extensions to programming languages and classifies files
 * for GitHub repository ingestion.
 *
 * <p>Determines whether a file is indexable, which language it belongs to,
 * and whether it should be classified as source code, documentation, or configuration.
 * Only files with recognized extensions are indexed; all others (binaries, images,
 * archives) are excluded.</p>
 */
public final class SourceFileLanguage {

    private static final String DOC_TYPE_SOURCE_CODE = "source-code";
    private static final String DOC_TYPE_DOCUMENTATION = "documentation";
    private static final String DOC_TYPE_CONFIGURATION = "configuration";

    private static final Map<String, String> EXTENSION_TO_LANGUAGE = Map.ofEntries(
            Map.entry(".java", "java"),
            Map.entry(".kt", "kotlin"),
            Map.entry(".kts", "kotlin"),
            Map.entry(".xml", "xml"),
            Map.entry(".yml", "yaml"),
            Map.entry(".yaml", "yaml"),
            Map.entry(".json", "json"),
            Map.entry(".toml", "toml"),
            Map.entry(".gradle", "groovy"),
            Map.entry(".properties", "properties"),
            Map.entry(".md", "markdown"),
            Map.entry(".txt", "text"),
            Map.entry(".rst", "restructuredtext"),
            Map.entry(".sh", "shell"),
            Map.entry(".bash", "shell"),
            Map.entry(".py", "python"),
            Map.entry(".js", "javascript"),
            Map.entry(".ts", "typescript"),
            Map.entry(".tsx", "typescript"),
            Map.entry(".jsx", "javascript"),
            Map.entry(".go", "go"),
            Map.entry(".rs", "rust"),
            Map.entry(".sql", "sql"),
            Map.entry(".html", "html"),
            Map.entry(".htm", "html"),
            Map.entry(".css", "css"),
            Map.entry(".scss", "css"),
            Map.entry(".svelte", "svelte"),
            Map.entry(".vue", "vue"),
            Map.entry(".c", "c"),
            Map.entry(".h", "c"),
            Map.entry(".cpp", "cpp"),
            Map.entry(".hpp", "cpp"),
            Map.entry(".rb", "ruby"),
            Map.entry(".swift", "swift"),
            Map.entry(".scala", "scala"),
            Map.entry(".clj", "clojure"),
            Map.entry(".r", "r"),
            Map.entry(".lua", "lua"),
            Map.entry(".php", "php"));

    /** Exact filenames (case-insensitive) that are indexable without a recognized extension. */
    private static final Map<String, String> EXACT_FILENAME_TO_LANGUAGE = Map.of(
            "makefile", "make",
            "dockerfile", "docker",
            "rakefile", "ruby",
            "gemfile", "ruby",
            "cmakelists.txt", "cmake");

    private static final Set<String> DOCUMENTATION_EXTENSIONS = Set.of(".md", ".txt", ".rst", ".html", ".htm");

    private static final Set<String> CONFIGURATION_EXTENSIONS =
            Set.of(".xml", ".yml", ".yaml", ".json", ".toml", ".gradle", ".kts", ".properties", ".css", ".scss");

    private SourceFileLanguage() {
        // static utility — not instantiable
    }

    /**
     * Resolves the programming language for a file name based on its extension.
     *
     * @param fileName file name (not path), e.g. "ChatController.java"
     * @return language identifier (e.g. "java") or empty string if unrecognized
     */
    public static String fromFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);

        String exactMatch = EXACT_FILENAME_TO_LANGUAGE.get(lowerName);
        if (exactMatch != null) {
            return exactMatch;
        }

        // gradle.kts must be checked before .kts alone
        if (lowerName.endsWith(".gradle.kts")) {
            return "kotlin";
        }

        int dotIndex = lowerName.lastIndexOf('.');
        if (dotIndex < 0) {
            return "";
        }
        String extension = lowerName.substring(dotIndex);
        String language = EXTENSION_TO_LANGUAGE.get(extension);
        return language != null ? language : "";
    }

    /**
     * Returns true when a file should be indexed based on its name.
     *
     * @param fileName file name (not path)
     * @return true when the file has a recognized indexable extension or name
     */
    public static boolean isIndexableFile(String fileName) {
        return !fromFileName(fileName).isEmpty();
    }

    /**
     * Classifies a file as source code, documentation, or configuration.
     *
     * @param fileName file name (not path)
     * @return one of "source-code", "documentation", or "configuration"
     */
    public static String classifyDocType(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return DOC_TYPE_SOURCE_CODE;
        }
        String lowerName = fileName.toLowerCase(Locale.ROOT);

        if (EXACT_FILENAME_TO_LANGUAGE.containsKey(lowerName)) {
            return DOC_TYPE_CONFIGURATION;
        }

        int dotIndex = lowerName.lastIndexOf('.');
        if (dotIndex < 0) {
            return DOC_TYPE_SOURCE_CODE;
        }
        String extension = lowerName.substring(dotIndex);

        if (DOCUMENTATION_EXTENSIONS.contains(extension)) {
            return DOC_TYPE_DOCUMENTATION;
        }
        if (CONFIGURATION_EXTENSIONS.contains(extension)) {
            return DOC_TYPE_CONFIGURATION;
        }
        return DOC_TYPE_SOURCE_CODE;
    }
}
