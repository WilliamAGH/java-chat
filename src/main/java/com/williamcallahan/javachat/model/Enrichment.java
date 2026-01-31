package com.williamcallahan.javachat.model;

import java.util.List;

/**
 * Enrichment metadata extracted from assistant responses.
 */
public class Enrichment {
    private String packageName;
    private String jdkVersion;
    private String resource;
    private String resourceVersion;
    private List<String> hints = List.of();
    private List<String> reminders = List.of();
    private List<String> background = List.of();

    /**
     * Returns the Java package name associated with the enrichment.
     *
     * @return Java package name
     */
    public String getPackageName() {
        return packageName;
    }

    /**
     * Sets the Java package name associated with the enrichment.
     *
     * @param packageName Java package name
     */
    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }

    /**
     * Returns the JDK version tied to the enrichment.
     *
     * @return JDK version
     */
    public String getJdkVersion() {
        return jdkVersion;
    }

    /**
     * Sets the JDK version tied to the enrichment.
     *
     * @param jdkVersion JDK version
     */
    public void setJdkVersion(String jdkVersion) {
        this.jdkVersion = jdkVersion;
    }

    /**
     * Returns the enrichment resource identifier.
     *
     * @return resource identifier
     */
    public String getResource() {
        return resource;
    }

    /**
     * Sets the enrichment resource identifier.
     *
     * @param resource resource identifier
     */
    public void setResource(String resource) {
        this.resource = resource;
    }

    /**
     * Returns the version for the enrichment resource.
     *
     * @return resource version
     */
    public String getResourceVersion() {
        return resourceVersion;
    }

    /**
     * Sets the version for the enrichment resource.
     *
     * @param resourceVersion resource version
     */
    public void setResourceVersion(String resourceVersion) {
        this.resourceVersion = resourceVersion;
    }

    /**
     * Returns curated hints extracted from the response.
     *
     * @return hint list
     */
    public List<String> getHints() {
        return List.copyOf(hints);
    }

    /**
     * Sets curated hints extracted from the response.
     *
     * @param hints hint list
     */
    public void setHints(List<String> hints) {
        this.hints = hints == null ? List.of() : List.copyOf(hints);
    }

    /**
     * Returns reminder snippets extracted from the response.
     *
     * @return reminder list
     */
    public List<String> getReminders() {
        return List.copyOf(reminders);
    }

    /**
     * Sets reminder snippets extracted from the response.
     *
     * @param reminders reminder list
     */
    public void setReminders(List<String> reminders) {
        this.reminders = reminders == null ? List.of() : List.copyOf(reminders);
    }

    /**
     * Returns background context extracted from the response.
     *
     * @return background list
     */
    public List<String> getBackground() {
        return List.copyOf(background);
    }

    /**
     * Sets background context extracted from the response.
     *
     * @param background background list
     */
    public void setBackground(List<String> background) {
        this.background = background == null ? List.of() : List.copyOf(background);
    }

    /**
     * Returns a sanitized copy of this enrichment with all string lists trimmed and filtered.
     * Empty or null entries are removed, and remaining entries are trimmed of whitespace.
     *
     * @return sanitized enrichment instance (never null)
     */
    public Enrichment sanitized() {
        Enrichment result = new Enrichment();
        result.setPackageName(this.packageName);
        result.setJdkVersion(this.jdkVersion);
        result.setResource(this.resource);
        result.setResourceVersion(this.resourceVersion);
        result.setHints(trimFilter(this.hints));
        result.setReminders(trimFilter(this.reminders));
        result.setBackground(trimFilter(this.background));
        return result;
    }

    /**
     * Creates an empty enrichment with all lists initialized to empty.
     *
     * @return empty enrichment instance
     */
    public static Enrichment empty() {
        Enrichment empty = new Enrichment();
        empty.setHints(List.of());
        empty.setReminders(List.of());
        empty.setBackground(List.of());
        return empty;
    }

    /**
     * Trims and filters a list of strings, removing null/empty entries.
     */
    private static List<String> trimFilter(List<String> entries) {
        if (entries == null) {
            return List.of();
        }
        return entries.stream()
                .map(entry -> entry == null ? "" : entry.trim())
                .filter(entry -> !entry.isEmpty())
                .toList();
    }
}
