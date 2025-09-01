package com.williamcallahan.javachat.model;

import java.util.List;

public class Enrichment {
    private String packageName;
    private String jdkVersion;
    private String resource;
    private String resourceVersion;
    private List<String> hints;
    private List<String> reminders;
    private List<String> background;

    public String getPackageName() { return packageName; }
    public void setPackageName(String packageName) { this.packageName = packageName; }
    public String getJdkVersion() { return jdkVersion; }
    public void setJdkVersion(String jdkVersion) { this.jdkVersion = jdkVersion; }
    public String getResource() { return resource; }
    public void setResource(String resource) { this.resource = resource; }
    public String getResourceVersion() { return resourceVersion; }
    public void setResourceVersion(String resourceVersion) { this.resourceVersion = resourceVersion; }
    public List<String> getHints() { return hints; }
    public void setHints(List<String> hints) { this.hints = hints; }
    public List<String> getReminders() { return reminders; }
    public void setReminders(List<String> reminders) { this.reminders = reminders; }
    public List<String> getBackground() { return background; }
    public void setBackground(List<String> background) { this.background = background; }
}





