package com.williamcallahan.javachat.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuration properties for port selection when running tests.
 */
@Component
@ConfigurationProperties(prefix = "app.ports")
public class TestPortConfig {
    private boolean killOnConflict = false;
    private String range = "18085-18090";

    /**
     * Indicates whether to terminate processes that occupy a requested port.
     *
     * @return true when conflict resolution should kill existing processes
     */
    public boolean isKillOnConflict() {
        return killOnConflict;
    }

    /**
     * Sets whether to terminate processes that occupy a requested port.
     *
     * @param killOnConflict flag to enable killing conflicting processes
     */
    public void setKillOnConflict(boolean killOnConflict) {
        this.killOnConflict = killOnConflict;
    }

    /**
     * Returns the configured port range.
     *
     * @return configured port range string
     */
    public String getRange() {
        return range;
    }

    /**
     * Updates the configured port range.
     *
     * @param range port range string
     */
    public void setRange(String range) {
        this.range = range;
    }
}
